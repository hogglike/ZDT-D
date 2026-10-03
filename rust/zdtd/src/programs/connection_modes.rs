//! Optional subscription modes. Existing DNS/netd profiles remain authoritative
//! for DNS; the mode uses a separate sing-box selector and scoped TCP/UDP TPROXY.
use anyhow::{bail, Context, Result};
use reqwest::{blocking::Client, Proxy};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{collections::{BTreeMap, BTreeSet}, fs, io::Read, os::unix::fs::PermissionsExt,
    path::{Path, PathBuf}, process::{Child, Command, Stdio}, sync::{Mutex, OnceLock,
    atomic::{AtomicU64, Ordering}}, thread, time::{Duration, Instant, SystemTime, UNIX_EPOCH}};
use crate::{daemon, iptables::{iptables_port::{DpiTunnelOptions, ProtoChoice}, iptables_tproxy}, shell::{self, Capture}};
use super::{mihomo_subscription as subscriptions, mode_policy};

const PORT: u16 = 19972;
const TEST_PORT: u16 = 19974;
const CONTROL_PORT: u16 = 19973;
const BIN: &str = "/data/adb/modules/ZDT-D/bin/sing-box";
const MODES: [&str; 3] = ["white", "normal", "browser"];
static GENERATION: AtomicU64 = AtomicU64::new(0);
static REQUEST: Mutex<Option<(u64, String, bool)>> = Mutex::new(None);
static STATUS: OnceLock<Mutex<Value>> = OnceLock::new();
fn root() -> PathBuf { crate::settings::working_root_path().join("connection_modes") }
fn options() -> DpiTunnelOptions { DpiTunnelOptions { port_preference: 0, dpi_ports: "1-52 54-65535".into() } }
fn now() -> u64 { SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs() }
fn status_lock() -> &'static Mutex<Value> { STATUS.get_or_init(|| Mutex::new(json!({"mode":"", "state":"idle", "message":"Выключено", "pings":{}}))) }
fn write_private(path: &Path, value: &str) -> Result<()> {
    fs::create_dir_all(path.parent().context("missing parent")?)?;
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, value)?;
    fs::set_permissions(&tmp, fs::Permissions::from_mode(0o600))?;
    fs::rename(tmp, path)?;
    Ok(())
}
fn current(g: u64) -> bool { GENERATION.load(Ordering::SeqCst) == g }
fn update(g: u64, patch: Value) {
    let mut status = status_lock().lock().unwrap_or_else(|e| e.into_inner());
    if !current(g) { return; }
    let changed = patch.get("state").map(|s| s != &status["state"]).unwrap_or(false);
    for (k,v) in patch.as_object().into_iter().flatten() { status[k] = v.clone(); }
    status["updated_at"] = json!(now());
    let _ = write_private(&root().join("status.json"), &status.to_string());
    drop(status);
    if changed { thread::spawn(|| {
        let _ = shell::run_timeout("am", &["broadcast", "--user", "0", "--receiver-foreground",
            "-a", "com.android.zdtd.service.ACTION_MODES_STATUS", "-n",
            "com.android.zdtd.service/.modes.ModeStatusReceiver"], Capture::None, Duration::from_secs(4));
    }); }
}
pub fn status() -> Value { status_lock().lock().unwrap_or_else(|e| e.into_inner()).clone() }

#[derive(Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct ModeSettings {
    pub apps: Vec<String>,
    pub app_policy: String,
    pub node_keys: Vec<String>,
    pub name_filters: Vec<String>,
    pub subscription_ids: Vec<String>,
    pub selected_key: String,
    pub last_key: String,
}
impl Default for ModeSettings {
    fn default() -> Self { Self { apps: vec![], app_policy: "selected".into(), node_keys: vec![],
        name_filters: vec![], subscription_ids: vec![], selected_key: String::new(), last_key: String::new() } }
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Config { pub active: String, pub modes: BTreeMap<String, ModeSettings> }
impl Default for Config {
    fn default() -> Self {
        let mut modes = BTreeMap::new();
        for name in MODES { modes.insert(name.into(), ModeSettings::default()); }
        let white = modes.get_mut("white").unwrap();
        white.app_policy = "all".into(); white.name_filters = vec!["LTE".into()];
        Self { active: String::new(), modes }
    }
}
static CONFIG_LOCK: Mutex<()> = Mutex::new(());
pub fn config() -> Result<Config> {
    let path = root().join("config.json");
    if !path.exists() { return Ok(Config::default()); }
    serde_json::from_str(&fs::read_to_string(path)?).context("read mode settings")
}
fn save_config(c: &Config) -> Result<()> { write_private(&root().join("config.json"), &serde_json::to_string_pretty(c)?) }
pub fn save(mut c: Config) -> Result<Config> {
    let _guard = CONFIG_LOCK.lock().unwrap_or_else(|e| e.into_inner());
    for mode in MODES { c.modes.entry(mode.into()).or_default(); }
    if !c.active.is_empty() && !MODES.contains(&c.active.as_str()) { bail!("Unknown mode"); }
    for (mode, v) in &c.modes {
        if !MODES.contains(&mode.as_str()) || !["all", "except_dns", "blacklist", "selected"].contains(&v.app_policy.as_str()) { bail!("Invalid mode/app policy"); }
        if v.apps.len() > 5000 || v.node_keys.len() > 1000 || v.name_filters.len() > 32 || v.name_filters.iter().any(|s| s.len()>128) { bail!("Mode settings too large"); }
        if v.apps.iter().any(|p| p.is_empty() || p.len()>255 || !p.chars().all(|c| c.is_ascii_alphanumeric() || c=='.' || c=='_')) { bail!("Invalid package name"); }
    }
    // Runtime owns the active mode and per-mode successful server memory.
    let old = config()?; c.active = old.active.clone();
    for mode in MODES { c.modes.get_mut(mode).unwrap().last_key = old.modes.get(mode).map(|v|v.last_key.clone()).unwrap_or_default(); }
    save_config(&c)?;
    if !c.active.is_empty() { request(&c.active, false)?; }
    Ok(c)
}

#[derive(Clone)]
struct Node { key: String, name: String, subscription: String, subscription_id: String, protocol: String, outbound: Option<Value> }
fn nodes() -> Result<Vec<Node>> {
    let mut result: Vec<Node> = subscriptions::mode_nodes()?.into_iter().map(|(id, subscription, n)| {
        // A label change alone must not lose a manually selected server.
        let mut identity = n.definition.clone();
        if let Some(obj) = identity.as_object_mut() { for k in ["name", "tag", "remarks", "_zdt_share_link"] { obj.remove(k); } }
        let key = format!("n_{}", &hex::encode(Sha256::digest(format!("{id}\0{}", identity)))[..24]);
        let mut outbound = if n.targets.iter().any(|t|t=="sing-box") || n.protocol == "hysteria2" {
            subscriptions::singbox_outbound(&n).ok()
        } else { None };
        if let Some(v) = &mut outbound { v["tag"] = json!(key); }
        Node { key, name:n.name, subscription, subscription_id:id, protocol:n.protocol, outbound }
    }).collect();
    let mut used=BTreeSet::new();result.retain(|n|used.insert(n.key.clone()));
    Ok(result)
}
pub fn snapshot() -> Result<Value> {
    let catalog: Vec<Value> = nodes()?.iter().map(|n| json!({"key":n.key,"name":n.name,"subscription":n.subscription,"subscription_id":n.subscription_id,"protocol":n.protocol,"supported":n.outbound.is_some()})).collect();
    let dns = super::dnsprofiles::load(Path::new(super::dnsprofiles::CONFIG_PATH)).map(|c|super::dnsprofiles::packages(&c)).unwrap_or_default();
    Ok(json!({"ok":true,"config":config()?,"status":status(),"nodes":catalog,"dns_apps":dns}))
}
fn candidates(settings: &ModeSettings, all: &[Node], white: bool) -> Vec<Node> {
    let mut used = BTreeSet::new();
    let mut out = Vec::new();
    // Explicit choices retain the user's priority order.
    for key in &settings.node_keys {
        if let Some(n) = all.iter().find(|n| &n.key==key && n.outbound.is_some()) {
            if used.insert(n.key.clone()) { out.push(n.clone()); }
        }
    }
    if white {
        for n in all.iter().filter(|n| n.outbound.is_some()
            && (settings.subscription_ids.is_empty() || settings.subscription_ids.contains(&n.subscription_id))
            && mode_policy::matches_name(&n.name, &settings.name_filters)) {
            if used.insert(n.key.clone()) { out.push(n.clone()); }
        }
    } else {
        out.clear();
        let key = if settings.selected_key.is_empty() { &settings.last_key } else { &settings.selected_key };
        if let Some(n) = all.iter().find(|n| &n.key==key && n.outbound.is_some()) { out.push(n.clone()); }
    }
    out
}
fn cleanup_routes() -> Result<()> { iptables_tproxy::cleanup_scope(&root().join("uids.txt"), PORT, ProtoChoice::TcpUdp, None, &options()) }
fn apply_apps(settings: &ModeSettings) -> Result<usize> {
    let (rc, out) = shell::run_timeout("cmd", &["package","list","packages","-U"], Capture::Stdout, Duration::from_secs(8))?;
    if rc!=0 { bail!("Не удалось получить UID приложений"); }
    let mut packages = BTreeMap::new();
    for line in out.lines() {
        let mut fields = line.split_whitespace();
        if let (Some(pkg),Some(uid)) = (fields.next().and_then(|s|s.strip_prefix("package:")),fields.next().and_then(|s|s.strip_prefix("uid:")).and_then(|s|s.parse::<u32>().ok())) { packages.insert(pkg.to_string(),uid); }
    }
    let dns_packages = super::dnsprofiles::load(Path::new(super::dnsprofiles::CONFIG_PATH)).map(|c|super::dnsprofiles::packages(&c)).unwrap_or_default();
    let excluded: BTreeSet<u32> = packages.iter().filter(|(p,_)|p.starts_with("com.hogglike.zdtd") || p.starts_with("com.android.zdtd")).map(|(_,uid)|*uid).collect();
    // Shared UID exclusions must apply to the entire UID, never just one label.
    let dns_uids: BTreeSet<u32> = packages.iter().filter(|(p,_)|dns_packages.contains(p)).map(|(_,u)|*u).collect();
    let selected_uids: BTreeSet<u32> = packages.iter().filter(|(p,_)|settings.apps.contains(p)).map(|(_,u)|*u).collect();
    let mut uids = BTreeSet::new();
    let mut lines=Vec::new();
    for (p,uid) in packages {
        if uid%100000<10000 || excluded.contains(&uid) { continue; }
        if mode_policy::include_app(&settings.app_policy, selected_uids.contains(&uid), dns_uids.contains(&uid)) && uids.insert(uid) { lines.push(format!("{p}={uid}")); }
    }
    if lines.is_empty() { bail!("Не выбрано ни одного приложения"); }
    write_private(&root().join("uids.txt"), &lines.join("\n"))?;
    if let Err(e) = iptables_tproxy::apply_connection_mode(&root().join("uids.txt"),PORT,&options()) { let _=cleanup_routes(); return Err(e); }
    Ok(lines.len())
}

struct Core { child: Child, fingerprint: String }
impl Core {
    fn stop(&mut self) { let _=self.child.kill(); let _=self.child.wait(); }
    fn alive(&mut self) -> bool { matches!(self.child.try_wait(), Ok(None)) }
}
impl Drop for Core { fn drop(&mut self) { self.stop(); } }
fn core_fingerprint(all: &[Node]) -> String { hex::encode(Sha256::digest(serde_json::to_vec(&all.iter().filter_map(|n| n.outbound.as_ref()).collect::<Vec<_>>()).unwrap_or_default())) }
fn start_core(all: &[Node]) -> Result<Core> {
    let mut outbounds: Vec<Value> = all.iter().filter_map(|n| n.outbound.clone()).collect();
    if outbounds.is_empty() { bail!("Нет поддерживаемых серверов. Обнови подписку; XHTTP пока не поддерживается этим ядром"); }
    let tags:Vec<String> = outbounds.iter().filter_map(|v|v["tag"].as_str().map(str::to_string)).collect();
    for tag in ["MODE", "TEST"] { outbounds.push(json!({"type":"selector","tag":tag,"outbounds":tags,"default":tags[0],"interrupt_exist_connections":true})); }
    let token=crate::settings::read_or_create_token()?;
    let mut cfg:Value=serde_json::from_str(include_str!("mode_core.json"))?;
    cfg["outbounds"]=json!(outbounds);
    cfg["experimental"]["clash_api"]["secret"]=json!(token);
    let path=root().join("config.runtime.json"); write_private(&path,&cfg.to_string())?;
    let (code,check_log) = shell::run_timeout(BIN,&["check","-c",path.to_str().context("config path")?],Capture::Both,Duration::from_secs(8))?;
    if code!=0 { let _=write_private(&root().join("sing-box.log"),&check_log); bail!("Ядро отклонило конфигурацию сервера. См. журнал режимов; проверь протокол подписки"); }
    let log=fs::File::create(root().join("sing-box.log"))?;
    let child=Command::new(BIN).args(["run","-c"]).arg(&path).stdin(Stdio::null()).stdout(Stdio::from(log.try_clone()?)).stderr(Stdio::from(log)).spawn()?;
    write_private(&root().join("core.pid"),&child.id().to_string())?;
    let mut core=Core{child,fingerprint:core_fingerprint(all)};
    for _ in 0..40 {
        if !core.alive() { bail!("Ядро режимов остановилось. Возможно, занят порт 19972–19974"); }
        if std::net::TcpStream::connect_timeout(&format!("127.0.0.1:{CONTROL_PORT}").parse()?,Duration::from_millis(100)).is_ok() { return Ok(core); }
        thread::sleep(Duration::from_millis(100));
    }
    bail!("Ядро режимов не ответило за 4 секунды")
}
fn select(group:&str,key:&str) -> Result<()> {
    let client=Client::builder().no_proxy().timeout(Duration::from_secs(3)).build()?;
    let response=client.put(format!("http://127.0.0.1:{CONTROL_PORT}/proxies/{group}"))
        .bearer_auth(crate::settings::read_or_create_token()?).header("content-type","application/json")
        .body(json!({"name":key}).to_string()).send()?;
    if !response.status().is_success() { bail!("Selector rejected server"); } Ok(())
}
const CHECKS: [(&str,u16);3] = [
    ("https://www.gstatic.com/generate_204",204),
    ("https://cp.cloudflare.com/generate_204",204),
    ("https://www.microsoft.com/",200),
];
fn probe(url:&str, expected:u16) -> Option<u64> {
    // Explicit HTTP CONNECT proxy: no direct fallback, no redirects, real TLS.
    let client=Client::builder().no_proxy().proxy(Proxy::all(format!("http://127.0.0.1:{TEST_PORT}")).ok()?)
        .redirect(reqwest::redirect::Policy::none()).connect_timeout(Duration::from_secs(4)).timeout(Duration::from_secs(6))
        .pool_max_idle_per_host(0).build().ok()?;
    let start=Instant::now();
    let mut response=client.get(url).header("cache-control","no-cache").send().ok()?;
    if response.status().as_u16()!=expected { return None; }
    let mut bytes=[0u8;1]; let _=response.read(&mut bytes).ok()?;
    Some(start.elapsed().as_millis().max(1) as u64)
}
fn test_node(g:u64,n:&Node) -> Result<Option<u64>> {
    select("TEST",&n.key)?;
    let mut ok=Vec::new();
    for (url,expected) in CHECKS {
        if !current(g) { bail!("Отменено"); }
        if let Some(ms)=probe(url,expected) { ok.push(ms); }
    }
    Ok(if ok.len()>=2 { Some(ok.iter().sum::<u64>()/ok.len() as u64) } else { None })
}
pub fn request(mode:&str,ping:bool) -> Result<()> {
    if !mode.is_empty() && !MODES.contains(&mode) { bail!("Unknown mode"); }
    let mut queue=REQUEST.lock().unwrap_or_else(|e|e.into_inner());
    let g=GENERATION.fetch_add(1,Ordering::SeqCst)+1;
    update(g,json!({"mode":mode,"state":if mode.is_empty()&&!ping {"idle"} else {"connecting"},"message":if ping {"Проверка задержки…"} else {"Подключение…"},"round":0,"attempt":0,"total":0}));
    *queue=Some((g,mode.into(),ping));
    Ok(())
}
fn retry_if_current(g:u64,mode:&str) {
    let mut queue=REQUEST.lock().unwrap_or_else(|e|e.into_inner());
    if GENERATION.compare_exchange(g,g+1,Ordering::SeqCst,Ordering::SeqCst).is_err() { return; }
    update(g+1,json!({"mode":mode,"state":"connecting","message":"Повторная проверка соединения…","round":0,"attempt":0,"total":0}));
    *queue=Some((g+1,mode.into(),false));
}
fn connect(g:u64,mode:&str,all:&[Node]) -> Result<()> {
    let settings=config()?.modes.get(mode).cloned().context("Mode settings missing")?;
    let list=candidates(&settings,all,mode=="white");
    if list.is_empty() { bail!("Нет подходящих серверов. Выбери сервер или правило имени в настройках режима"); }
    let keys:Vec<String>=list.iter().map(|n|n.key.clone()).collect();
    let attempts=if mode=="white" { mode_policy::attempts(&keys,&settings.last_key) } else {keys.clone()};
    for (i,key) in attempts.iter().enumerate() {
        if !current(g) { return Ok(()); }
        let n=list.iter().find(|n|&n.key==key).unwrap();
        update(g,json!({"server":n.name,"key":n.key,"round":i/list.len()+1,"attempt":i+1,"total":attempts.len(),"message":format!("Проверка {} · круг {}",n.name,i/list.len()+1)}));
        let delay=test_node(g,n)?;
        if !current(g) { return Ok(()); }
        update(g,json!({"last_probe_ok":delay.is_some()}));
        if let Some(ms)=delay {
            // Only successful probes may change the traffic selector and app rules.
            select("MODE",&n.key)?;
            let _=cleanup_routes();
            let count=apply_apps(&settings)?;
            if !current(g) { let _=cleanup_routes(); return Ok(()); }
            { let _guard=CONFIG_LOCK.lock().unwrap_or_else(|e|e.into_inner());
              if !current(g) { let _=cleanup_routes(); return Ok(()); }
              let mut c=config()?;c.active=mode.into();c.modes.get_mut(mode).unwrap().last_key=n.key.clone();save_config(&c)?; }
            // Close this instance's old connections: some sing-box versions do
            // not interrupt routed selector connections despite the flag.
            let _=Client::builder().no_proxy().timeout(Duration::from_secs(2)).build()?.delete(format!("http://127.0.0.1:{CONTROL_PORT}/connections")).bearer_auth(crate::settings::read_or_create_token()?).send();
            update(g,json!({"state":"connected","server":n.name,"key":n.key,"delay_ms":ms,"app_count":count,"message":format!("{} · {} мс · приложений {}",n.name,ms,count)}));
            return Ok(());
        }
    }
    let _=cleanup_routes();
    bail!("{}",if mode=="white" {"Два круга завершены: рабочий сервер не найден"} else {"Выбранный сервер не прошёл проверку 2 из 3 сайтов"})
}
fn clear_active() {
    let _guard=CONFIG_LOCK.lock().unwrap_or_else(|e|e.into_inner());
    if let Ok(mut c)=config() { c.active.clear();let _=save_config(&c); }
}
/// Global Stop invalidates in-flight selection before core/routing teardown.
pub fn stop_for_service() {
    let g={
        let mut queue=REQUEST.lock().unwrap_or_else(|e|e.into_inner());
        let g=GENERATION.fetch_add(1,Ordering::SeqCst)+1; *queue=None; g
    };
    let _=cleanup_routes();clear_active();
    update(g,json!({"mode":"","state":"idle","message":"Выключено"}));
    stop_orphan();
}
fn stop_orphan() {
    if let Ok(s)=fs::read_to_string(root().join("core.pid")) {
        if let Ok(pid)=s.trim().parse::<i32>() { if pid>1 {
            if let Ok(cmd)=fs::read(format!("/proc/{pid}/cmdline")) {
                let args:Vec<&[u8]>=cmd.split(|b|*b==0).collect();
                let config=root().join("config.runtime.json");
                if args.first().map(|s|s.ends_with(b"sing-box")).unwrap_or(false) && args.iter().any(|s| *s==config.as_os_str().as_encoded_bytes()) { unsafe {libc::kill(pid,libc::SIGTERM);} }
            }
        } }
    }
}
fn physical_network() -> String {
    shell::run_timeout("ip",&["-o","-4","addr","show"],Capture::Stdout,Duration::from_secs(3)).map(|(_,s)|s.lines().filter(|l|l.contains(" wlan0 ") || l.contains(" rmnet") || l.contains(" ccmni")).collect::<Vec<_>>().join("\n")).unwrap_or_default()
}
pub fn start_worker(state:daemon::SharedState) {
    thread::spawn(move || {
        let _=cleanup_routes();stop_orphan();
        let mut core:Option<Core>=None;
        let mut last_check=Instant::now();let mut last_network=String::new();let mut was_running=false;
        loop {
            let running={let s=daemon::lock_state(&state);s.services_running&&!s.start_in_progress&&!s.stop_in_progress};
            if !running {
                if was_running { stop_for_service();core=None; }
                was_running=false;
                thread::sleep(Duration::from_secs(1));continue;
            }
            if !was_running { was_running=true;if let Ok(c)=config() {if !c.active.is_empty(){let _=request(&c.active,false);}} }
            let req=REQUEST.lock().unwrap_or_else(|e|e.into_inner()).take();
            if let Some((g,mode,ping))=req {
                let result=(||->Result<()> {
                    if mode.is_empty()&&!ping { let _=cleanup_routes();core=None;clear_active();update(g,json!({"state":"idle","message":"Выключено"}));return Ok(()); }
                    let all=nodes()?;
                    let rebuild=core.as_mut().map(|c|!c.alive()||c.fingerprint!=core_fingerprint(&all)).unwrap_or(true);
                    if rebuild { let _=cleanup_routes();core=None;core=Some(start_core(&all)?); }
                    if !current(g){return Ok(());}
                    if ping {
                        for n in all.iter().filter(|n|n.outbound.is_some()) {
                            if !current(g){return Ok(());}
                            let delay=test_node(g,n)?;
                            let mut pings=status()["pings"].clone();if !pings.is_object(){pings=json!({});}
                            pings[&n.key]=json!({"ms":delay,"time":now()});update(g,json!({"pings":pings,"message":format!("Проверен {}",n.name)}));
                        }
                        let c=config()?;
                        if rebuild && !c.active.is_empty() {connect(g,&c.active,&all)?;} else {update(g,json!({"mode":c.active,"state":if c.active.is_empty(){"idle"}else{"connected"},"message":"Проверка задержки завершена"}));}
                    } else {connect(g,&mode,&all)?;}
                    Ok(())
                })();
                if let Err(e)=result {if current(g){let _=cleanup_routes();clear_active();update(g,json!({"state":"error","message":e.to_string()}));}}
                last_check=Instant::now();last_network=physical_network();
            }
            if last_check.elapsed()>=Duration::from_secs(60) && status()["state"]=="connected" {
                last_check=Instant::now();
                let g=GENERATION.load(Ordering::SeqCst);
                let all=nodes().unwrap_or_default();let key=status()["key"].as_str().unwrap_or("").to_owned();
                let changed=core.as_ref().map(|c|c.fingerprint!=core_fingerprint(&all)).unwrap_or(true);
                let network=physical_network();let network_changed=!last_network.is_empty() && network!=last_network;last_network=network;
                let healthy=if changed||network_changed {false} else {all.iter().find(|n|n.key==key).and_then(|n|test_node(g,n).ok().flatten()).is_some()};
                if current(g)&&!healthy {let mode=status()["mode"].as_str().unwrap_or("").to_owned();if !mode.is_empty(){retry_if_current(g,&mode);}}
            }
            thread::sleep(Duration::from_secs(1));
        }
    });
}
