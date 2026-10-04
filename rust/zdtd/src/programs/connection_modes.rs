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
pub struct CheckTarget { pub url: String, pub enabled: bool, pub expected_status: u16 }
impl Default for CheckTarget { fn default() -> Self { Self {url:String::new(),enabled:true,expected_status:0} } }
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
    pub preferred_key: String,
    pub check_enabled: bool,
    pub check_sites: Vec<CheckTarget>,
    pub min_success: usize,
    pub timeout_seconds: u64,
}
impl Default for ModeSettings {
    fn default() -> Self { Self { apps: vec![], app_policy: "selected".into(), node_keys: vec![],
        name_filters: vec![], subscription_ids: vec![], selected_key: String::new(), last_key: String::new(), preferred_key: String::new(), check_enabled:true,
        check_sites:["https://www.gstatic.com/generate_204","https://cp.cloudflare.com/generate_204","https://www.microsoft.com/"].iter().map(|url|CheckTarget{url:(*url).into(),..CheckTarget::default()}).collect(), min_success:2,timeout_seconds:8 } }
}
#[derive(Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Config { pub active: String, pub modes: BTreeMap<String, ModeSettings>, pub latency_url:String, pub latency_timeout_seconds:u64 }
impl Default for Config {
    fn default() -> Self {
        let mut modes = BTreeMap::new();
        for name in MODES { modes.insert(name.into(), ModeSettings::default()); }
        let white = modes.get_mut("white").unwrap();
        white.app_policy = "all".into(); white.name_filters = vec!["LTE".into()];
        Self { active: String::new(), modes, latency_url:"https://www.gstatic.com/generate_204".into(),latency_timeout_seconds:8 }
    }
}
static CONFIG_LOCK: Mutex<()> = Mutex::new(());
static REJECTED_NODES: Mutex<BTreeSet<String>> = Mutex::new(BTreeSet::new());
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
    validate_url(&c.latency_url)?;
    if !(2..=30).contains(&c.latency_timeout_seconds) { bail!("Таймаут задержки: 2–30 секунд"); }
    for (mode, v) in &c.modes {
        if !MODES.contains(&mode.as_str()) || !["all", "except_dns", "blacklist", "selected"].contains(&v.app_policy.as_str()) { bail!("Invalid mode/app policy"); }
        if v.check_sites.len()>12 || !(2..=30).contains(&v.timeout_seconds) { bail!("Проверка: максимум 12 сайтов; таймаут 2–30 секунд"); }
        let count=v.check_sites.iter().filter(|site|site.enabled).count();
        if v.check_enabled && (v.min_success==0 || v.min_success>count) { bail!("Количество успешных ответов должно быть от 1 до числа включённых сайтов"); }
        for site in &v.check_sites { validate_url(&site.url)?; if site.expected_status!=0 && !(200..=599).contains(&site.expected_status) { bail!("HTTP-код: 0 (авто) или 200–599"); } }
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
        let transport=n.definition.get("transport").and_then(|v|v.get("type")).and_then(Value::as_str).unwrap_or(&n.transport);
        let supported_transport=["", "tcp", "ws", "grpc", "http", "httpupgrade"].contains(&transport);
        let rejected=REJECTED_NODES.lock().unwrap_or_else(|e|e.into_inner()).contains(&key);
        let mut outbound = if !rejected && supported_transport && (n.targets.iter().any(|t|t=="sing-box") || n.protocol == "hysteria2") {
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
fn cleanup_routes() -> Result<()> {
    let nat_result=iptables_tproxy::cleanup_connection_mode_nat();
    let route_result=iptables_tproxy::cleanup_scope(&root().join("uids.txt"), PORT, ProtoChoice::TcpUdp, None, &options());
    nat_result?; route_result
}
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
fn render_core(all:&[Node]) -> Result<Value> {
    let mut outbounds:Vec<Value>=all.iter().filter_map(|n|n.outbound.clone()).collect();
    if outbounds.is_empty() { bail!("Нет поддерживаемых серверов. Обнови подписку; XHTTP пока не поддерживается этим ядром"); }
    let tags:Vec<String>=outbounds.iter().filter_map(|v|v["tag"].as_str().map(str::to_string)).collect();
    for tag in ["MODE", "TEST"] { outbounds.push(json!({"type":"selector","tag":tag,"outbounds":tags,"default":tags[0],"interrupt_exist_connections":true})); }
    let mut cfg:Value=serde_json::from_str(include_str!("mode_core.json"))?;
    cfg["outbounds"]=json!(outbounds);
    cfg["experimental"]["clash_api"]["secret"]=json!(crate::settings::read_or_create_token()?);
    Ok(cfg)
}
fn check_core(all:&[Node],path:&Path) -> Result<bool> {
    write_private(path,&render_core(all)?.to_string())?;
    let (code,diagnostic)=shell::run_timeout(BIN,&["check","-c",path.to_str().context("config path")?],Capture::Both,Duration::from_secs(8))?;
    if code!=0 { let _=write_private(&root().join("sing-box.log"),&diagnostic); }
    Ok(code==0)
}
fn start_core(all: &mut [Node]) -> Result<Core> {
    // Fingerprint subscription definitions, not temporary resolved addresses.
    // Use the same Android endpoint resolver as stock sing-box profiles.
    let fingerprint=core_fingerprint(all);
    let mut outbounds:Vec<Value>=all.iter().filter_map(|n|n.outbound.clone()).collect();
    let bootstrap=super::mode_bootstrap::resolve_outbounds(&mut outbounds,crate::android_dns::resolve_ipv4_all);
    let mut resolved=outbounds.into_iter();
    for n in all.iter_mut().filter(|n|n.outbound.is_some()) {n.outbound=resolved.next();}
    write_private(&root().join("bootstrap.json"),&serde_json::to_string(&bootstrap)?)?;
    let path=root().join("config.runtime.json");
    if !check_core(all,&path)? {
        // A malformed or obsolete node must not disable every other server.
        let validation=root().join("validation.runtime.json");
        for n in all.iter_mut().filter(|n|n.outbound.is_some()) {
            if !check_core(std::slice::from_ref(n),&validation)? {
                REJECTED_NODES.lock().unwrap_or_else(|e|e.into_inner()).insert(n.key.clone());
                n.outbound=None;
            }
        }
        if !check_core(all,&path)? { bail!("Ядро отклонило конфигурацию режимов. См. журнал режимов"); }
    }
    let log=fs::File::create(root().join("sing-box.log"))?;
    log.set_permissions(fs::Permissions::from_mode(0o600))?;
    let child=Command::new(BIN).args(["run","-c"]).arg(&path).stdin(Stdio::null()).stdout(Stdio::from(log.try_clone()?)).stderr(Stdio::from(log)).spawn()?;
    let mut core=Core{child,fingerprint};
    write_private(&root().join("core.pid"),&core.child.id().to_string())?;
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
fn validate_url(raw:&str) -> Result<()> {
    let url=reqwest::Url::parse(raw).context("Укажи полный адрес https://…")?;
    if url.scheme()!="https" || url.host_str().is_none() || !url.username().is_empty() || url.password().is_some() || raw.len()>2048 { bail!("Нужен HTTPS-адрес без логина и пароля"); }
    Ok(())
}
#[derive(Serialize)]
struct ProbeResult { url:String, ok:bool, http_status:Option<u16>, ms:Option<u64>, error:String }
fn probe(site:&CheckTarget, timeout:u64) -> ProbeResult {
    let start=Instant::now();
    let mut result=ProbeResult{url:site.url.clone(),ok:false,http_status:None,ms:None,error:String::new()};
    let attempt=(||->Result<()> {
        let client=Client::builder().no_proxy().proxy(Proxy::all(format!("http://127.0.0.1:{TEST_PORT}"))?)
            .redirect(reqwest::redirect::Policy::none()).connect_timeout(Duration::from_secs(timeout.min(8))).timeout(Duration::from_secs(timeout))
            .pool_max_idle_per_host(0).build()?;
        let mut response=client.get(&site.url).header("cache-control","no-cache").send()?;
        let code=response.status().as_u16();result.http_status=Some(code);
        result.ok=mode_policy::http_ok(code,site.expected_status);
        if !result.ok { bail!("HTTP {code}"); }
        let mut bytes=[0u8;1];response.read(&mut bytes)?;
        Ok(())
    })();
    result.ms=Some(start.elapsed().as_millis().max(1) as u64);
    if let Err(e)=attempt {result.ok=false;result.error=format!("{e:#}").chars().take(600).collect();}
    result
}
struct ProbeReport { delay:Option<u64>, results:Vec<ProbeResult> }
fn test_node(g:u64,n:&Node,settings:&ModeSettings) -> Result<ProbeReport> {
    select("TEST",&n.key)?;
    let mut results=Vec::new();
    for site in settings.check_sites.iter().filter(|s|s.enabled) {
        if !current(g) { bail!("Отменено"); }
        results.push(probe(site,settings.timeout_seconds));
    }
    let delays:Vec<u64>=results.iter().filter(|r|r.ok).filter_map(|r|r.ms).collect();
    let delay=if mode_policy::checks_pass(settings.check_enabled,delays.len(),settings.min_success) && !delays.is_empty() {Some(delays.iter().sum::<u64>()/delays.len() as u64)}else{None};
    Ok(ProbeReport{delay,results})
}
fn node_delay(n:&Node,c:&Config) -> (Option<u64>,String) {
    let attempt=(||->Result<u64> {
        // TEST is isolated from MODE. Use the same validated HTTPS transport as
        // mode checks: Clash API's delay handler loses the certificate context.
        select("TEST",&n.key)?;
        let start=Instant::now();
        let client=Client::builder().no_proxy().proxy(Proxy::all(format!("http://127.0.0.1:{TEST_PORT}"))?)
            .redirect(reqwest::redirect::Policy::none()).connect_timeout(Duration::from_secs(c.latency_timeout_seconds.min(8)))
            .timeout(Duration::from_secs(c.latency_timeout_seconds)).pool_max_idle_per_host(0).build()?;
        let response=client.head(&c.latency_url).header("cache-control","no-cache").send()?;
        let code=response.status().as_u16();
        if !mode_policy::http_ok(code,0) {bail!("HTTP {code} на {}",c.latency_url);}
        Ok(start.elapsed().as_millis().max(1) as u64)
    })();
    match attempt {Ok(ms)=>(Some(ms),String::new()),Err(e)=>(None,format!("{e:#}").chars().take(600).collect())}
}
fn safe_core_log(all:&[Node]) -> String {
    fn redact(value:&Value,text:&mut String) {
        if let Some(obj)=value.as_object() {for (k,v) in obj {
            if ["uuid","password","private_key","public_key","short_id","username"].contains(&k.as_str()) {if let Some(secret)=v.as_str(){if !secret.is_empty(){*text=text.replace(secret,"<скрыто>");}}}
            redact(v,text);
        }} else if let Some(a)=value.as_array(){for v in a {redact(v,text);}}
    }
    let raw=fs::read_to_string(root().join("sing-box.log")).unwrap_or_default();
    let mut text=raw.lines().rev().take(30).collect::<Vec<_>>().into_iter().rev().collect::<Vec<_>>().join("\n");
    for n in all {if let Some(v)=&n.outbound{redact(v,&mut text);}}
    if let Ok(token)=crate::settings::read_or_create_token(){if !token.is_empty(){text=text.replace(&token,"<скрыто>");}}
    text.chars().take(8000).collect()
}
pub fn diagnostics() -> Result<Value> {
    let bootstrap:Value=fs::read_to_string(root().join("bootstrap.json")).ok().and_then(|s|serde_json::from_str(&s).ok()).unwrap_or(json!([]));
    Ok(json!({"ok":true,"version":"4.2.0-mod28","status":status(),"bootstrap":bootstrap,"network_identity":physical_network(),"dns_profiles":super::dnsprofiles::runtime_status(),"core_log":safe_core_log(&nodes()?)}))
}
pub fn choose_server(mode:&str,key:&str) -> Result<()> {
    if !MODES.contains(&mode) {bail!("Unknown mode");}
    if !nodes()?.iter().any(|n|n.key==key && n.outbound.is_some()){bail!("Сервер удалён или не поддерживается");}
    let _guard=CONFIG_LOCK.lock().unwrap_or_else(|e|e.into_inner());
    let mut c=config()?;let settings=c.modes.get_mut(mode).context("Mode settings missing")?;
    if mode=="white" {settings.preferred_key=key.into();if !settings.node_keys.iter().any(|v|v==key){settings.node_keys.insert(0,key.into());}}
    else {settings.selected_key=key.into();}
    // Saving a picker selection does not activate it before Wi-Fi confirmation.
    save_config(&c)
}
pub fn request(mode:&str,ping:bool) -> Result<()> {
    if !mode.is_empty() && !MODES.contains(&mode) { bail!("Unknown mode"); }
    let mut queue=REQUEST.lock().unwrap_or_else(|e|e.into_inner());
    let g=GENERATION.fetch_add(1,Ordering::SeqCst)+1;
    update(g,json!({"mode":mode,"state":if mode.is_empty()&&!ping {"idle"} else {"connecting"},"message":if ping {"Проверка задержки…"} else {"Подключение…"},"reconnect_reason":"user_request","round":0,"attempt":0,"total":0,"probe_results":[],"core_log":""}));
    *queue=Some((g,mode.into(),ping));
    Ok(())
}
fn retry_if_current(g:u64,mode:&str,reason:&str) {
    let mut queue=REQUEST.lock().unwrap_or_else(|e|e.into_inner());
    if GENERATION.compare_exchange(g,g+1,Ordering::SeqCst,Ordering::SeqCst).is_err() { return; }
    update(g+1,json!({"mode":mode,"state":"connecting","message":"Восстановление подключения…","reconnect_reason":reason,"round":0,"attempt":0,"total":0,"probe_results":[],"core_log":""}));
    *queue=Some((g+1,mode.into(),false));
}
fn connect(g:u64,mode:&str,all:&[Node]) -> Result<()> {
    let settings=config()?.modes.get(mode).cloned().context("Mode settings missing")?;
    let list=candidates(&settings,all,mode=="white");
    if list.is_empty() { bail!("Нет подходящих серверов. Выбери сервер или правило имени в настройках режима"); }
    let keys:Vec<String>=list.iter().map(|n|n.key.clone()).collect();
    let preferred=if settings.preferred_key.is_empty(){&settings.last_key}else{&settings.preferred_key};
    let attempts=if mode=="white" && settings.check_enabled { mode_policy::attempts(&keys,preferred) } else if mode=="white" {mode_policy::attempts(&keys,preferred).into_iter().take(1).collect()} else {keys.clone()};
    for (i,key) in attempts.iter().enumerate() {
        if !current(g) { return Ok(()); }
        let n=list.iter().find(|n|&n.key==key).unwrap();
        update(g,json!({"server":n.name,"key":n.key,"round":i/list.len()+1,"attempt":i+1,"total":attempts.len(),"message":format!("Проверка {} · круг {}",n.name,i/list.len()+1)}));
        let report=if settings.check_enabled {Some(test_node(g,n,&settings)?)}else{None};
        let delay=report.as_ref().and_then(|r|r.delay);
        if let Some(report)=&report {update(g,json!({"probe_results":report.results,"required_success":settings.min_success,"core_log":if delay.is_none(){safe_core_log(all)}else{String::new()}}));}
        if !current(g) { return Ok(()); }
        update(g,json!({"last_probe_ok":delay.is_some()}));
        if delay.is_some() || !settings.check_enabled {
            // Only successful probes may change the traffic selector and app rules.
            select("MODE",&n.key)?;
            let _=cleanup_routes();
            let count=apply_apps(&settings)?;
            if !current(g) { let _=cleanup_routes(); return Ok(()); }
            { let _guard=CONFIG_LOCK.lock().unwrap_or_else(|e|e.into_inner());
              if !current(g) { let _=cleanup_routes(); return Ok(()); }
              let mut c=config()?;c.active=mode.into();let saved=c.modes.get_mut(mode).unwrap();saved.last_key=n.key.clone();saved.preferred_key.clear();save_config(&c)?; }
            // Close this instance's old connections: some sing-box versions do
            // not interrupt routed selector connections despite the flag.
            let _=Client::builder().no_proxy().timeout(Duration::from_secs(2)).build()?.delete(format!("http://127.0.0.1:{CONTROL_PORT}/connections")).bearer_auth(crate::settings::read_or_create_token()?).send();
            update(g,json!({"state":"connected","server":n.name,"key":n.key,"delay_ms":delay,"verified":settings.check_enabled,"app_count":count,"message":if let Some(ms)=delay{format!("{} · {} мс · приложений {}",n.name,ms,count)}else{format!("{} · применено без проверки сайтов · приложений {}",n.name,count)}}));
            return Ok(());
        }
    }
    let _=cleanup_routes();
    bail!("{}",if mode=="white" {"Два круга завершены: рабочий сервер не найден. См. результаты сайтов"} else {"Сервер не прошёл настроенную проверку. См. результаты сайтов или отключи проверку этого режима"})
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
fn physical_network() -> Option<String> {
    shell::run_timeout("ip",&["-o","-4","addr","show"],Capture::Stdout,Duration::from_secs(3)).ok()
        .filter(|(code,_)|*code==0).map(|(_,s)|mode_policy::physical_network_identity(&s))
}
pub fn start_worker(state:daemon::SharedState) {
    thread::spawn(move || {
        let _=cleanup_routes();stop_orphan();
        let mut core:Option<Core>=None;
        let mut last_check=Instant::now();let mut last_network:Option<String>=None;let mut was_running=false;
        loop {
            let running={let s=daemon::lock_state(&state);s.services_running&&!s.start_in_progress&&!s.stop_in_progress};
            if !running {
                if was_running { stop_for_service();core=None; }
                was_running=false;
                thread::sleep(Duration::from_secs(1));continue;
            }
            if !was_running {
                was_running=true;
                let g=GENERATION.load(Ordering::SeqCst);
                let pending=REQUEST.lock().unwrap_or_else(|e|e.into_inner()).is_some();
                if !pending { if let Ok(c)=config() {if !c.active.is_empty(){retry_if_current(g,&c.active,"service_started");}} }
            }
            let req=REQUEST.lock().unwrap_or_else(|e|e.into_inner()).take();
            if let Some((g,mode,ping))=req {
                let result=(||->Result<()> {
                    if mode.is_empty()&&!ping { let _=cleanup_routes();core=None;clear_active();update(g,json!({"state":"idle","message":"Выключено"}));return Ok(()); }
                    let mut all=nodes()?;
                    let rebuild=core.as_mut().map(|c|!c.alive()||c.fingerprint!=core_fingerprint(&all)).unwrap_or(true);
                    if rebuild { let _=cleanup_routes();core=None;core=Some(start_core(&mut all)?); }
                    if !current(g){if status()["state"]=="idle" {core=None;} return Ok(());}
                    if ping {
                        for n in all.iter().filter(|n|n.outbound.is_some()) {
                            if !current(g){return Ok(());}
                            let (delay,error)=node_delay(n,&config()?);
                            let mut pings=status()["pings"].clone();if !pings.is_object(){pings=json!({});}
                            pings[&n.key]=json!({"ms":delay,"time":now(),"error":error,"url":config()?.latency_url});update(g,json!({"pings":pings,"message":format!("Проверен {}",n.name)}));
                        }
                        let c=config()?;
                        if rebuild && !c.active.is_empty() {connect(g,&c.active,&all)?;} else {update(g,json!({"mode":c.active,"state":if c.active.is_empty(){"idle"}else{"connected"},"message":"Проверка задержки завершена"}));}
                    } else {connect(g,&mode,&all)?;}
                    Ok(())
                })();
                if let Err(e)=result {if current(g){let _=cleanup_routes();clear_active();update(g,json!({"state":"error","message":e.to_string(),"core_log":safe_core_log(&nodes().unwrap_or_default())}));}}
                last_check=Instant::now();last_network=physical_network();
            }
            if last_check.elapsed()>=Duration::from_secs(60) && status()["state"]=="connected" {
                last_check=Instant::now();
                let g=GENERATION.load(Ordering::SeqCst);
                let all=nodes().unwrap_or_default();let key=status()["key"].as_str().unwrap_or("").to_owned();
                let changed=core.as_ref().map(|c|c.fingerprint!=core_fingerprint(&all)).unwrap_or(true);
                let network=physical_network();
                let network_changed=match (&last_network,&network) {(Some(old),Some(new))=>old!=new,_=>false};
                if network.is_some() {last_network=network;}
                // Refresh endpoint IPs after Wi-Fi/mobile changes. Do not reuse
                // a core that still contains bootstrap addresses from the old network.
                if network_changed {if let Some(c)=core.as_mut(){c.fingerprint.clear();}}
                let mode=status()["mode"].as_str().unwrap_or("").to_owned();
                let settings=config().ok().and_then(|c|c.modes.get(&mode).cloned());
                let core_alive=core.as_mut().map(Core::alive).unwrap_or(false);
                let check_enabled=settings.as_ref().map(|s|s.check_enabled).unwrap_or(false);
                let probe_ok=if check_enabled && !changed && !network_changed && core_alive {settings.as_ref().and_then(|s|all.iter().find(|n|n.key==key).and_then(|n|test_node(g,n,s).ok().and_then(|r|r.delay))).is_some()}else{false};
                let reason=mode_policy::refresh_reason(changed,network_changed,core_alive,check_enabled,probe_ok);
                if let Some(reason)=reason {if current(g)&&!mode.is_empty(){retry_if_current(g,&mode,reason);}}
            }
            thread::sleep(Duration::from_secs(1));
        }
    });
}
