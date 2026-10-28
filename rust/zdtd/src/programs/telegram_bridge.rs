//! Authenticated loopback SOCKS for Telegram. No TUN, TPROXY or DNS/app rules.
use anyhow::{bail, Context, Result};
use reqwest::{blocking::Client, Proxy};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use sha2::{Digest, Sha256};
use std::{fs, io::Read, os::unix::fs::PermissionsExt, path::PathBuf, process::{Child, Command, Stdio}, sync::{Mutex, OnceLock, atomic::{AtomicU64, Ordering}}, thread, time::{Duration, Instant}};
use crate::{daemon, shell::{self, Capture}};

const PORT:u16=19978;
const CONTROL:u16=19979;
const BIN:&str="/data/adb/modules/ZDT-D/bin/sing-box";
const PROBE:&str="https://web.telegram.org/";
static REVISION:AtomicU64=AtomicU64::new(0);
static LOCK:Mutex<()>=Mutex::new(());
static STATUS:OnceLock<Mutex<Value>>=OnceLock::new();
#[derive(Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct Config { pub enabled:bool, pub automatic:bool, pub node_keys:Vec<String>, pub selected_key:String }
impl Default for Config {fn default()->Self {Self{enabled:false,automatic:true,node_keys:vec![],selected_key:String::new()}}}
fn root()->PathBuf {crate::settings::working_root_path().join("telegram_bridge")}
fn write(name:&str,value:&str)->Result<()> {
    fs::create_dir_all(root())?;
    fs::set_permissions(root(),fs::Permissions::from_mode(0o700))?;
    let path=root().join(name);let tmp=path.with_extension("tmp");
    fs::write(&tmp,value)?;fs::set_permissions(&tmp,fs::Permissions::from_mode(0o600))?;fs::rename(tmp,path)?;Ok(())
}
fn status_lock()->&'static Mutex<Value> {STATUS.get_or_init(||Mutex::new(json!({"state":"idle","message":"Выключено"})))}
fn update(value:Value) {let mut s=status_lock().lock().unwrap_or_else(|e|e.into_inner());for(k,v)in value.as_object().into_iter().flatten(){s[k]=v.clone();}}
pub fn status()->Value {status_lock().lock().unwrap_or_else(|e|e.into_inner()).clone()}
pub fn config()->Result<Config> {let p=root().join("settings.json");if p.exists(){Ok(serde_json::from_str(&fs::read_to_string(p)?)?)}else{Ok(Config::default())}}
fn password()->Result<String> {
    let p=root().join("password");
    if p.exists(){return Ok(fs::read_to_string(p)?);}
    let mut bytes=[0u8;24];fs::File::open("/dev/urandom")?.read_exact(&mut bytes)?;
    let value=hex::encode(bytes);write("password",&value)?;Ok(value)
}
pub fn snapshot()->Result<Value> {
    let _guard=LOCK.lock().unwrap_or_else(|e|e.into_inner());
    let secret=password()?;
    let catalog=super::connection_modes::snapshot()?;
    Ok(json!({"ok":true,"config":config()?,"status":status(),"nodes":catalog["nodes"],"port":PORT,"username":"zdtd","password":secret,"probe_url":PROBE}))
}
pub fn save(mut c:Config)->Result<()> {
    let _guard=LOCK.lock().unwrap_or_else(|e|e.into_inner());
    if c.node_keys.len()>256 {bail!("Можно выбрать до 256 серверов");}
    let mut seen=std::collections::BTreeSet::new();c.node_keys.retain(|s|seen.insert(s.clone()));
    if c.enabled && !c.automatic && c.selected_key.is_empty(){bail!("Выбери сервер");}
    write("settings.json",&serde_json::to_string(&c)?)?;
    REVISION.fetch_add(1,Ordering::SeqCst);
    update(json!({"state":if c.enabled{"connecting"}else{"stopping"},"message":if c.enabled{"Ожидание подключения…"}else{"Отключение…"},"failures":[]}));Ok(())
}
pub fn retry(){REVISION.fetch_add(1,Ordering::SeqCst);}
// Explicit stop also persists user intent; service stop only suspends the worker.
pub fn stop()->Result<()> {let mut c=config()?;c.enabled=false;save(c)}

type Node=(String,String,Value);
fn candidates(c:&Config,all:Vec<Node>)->Vec<Node> {
    let mut nodes:Vec<_>=all.into_iter().filter(|n|if c.automatic {c.node_keys.is_empty()||c.node_keys.contains(&n.0)} else {n.0==c.selected_key}).collect();
    if !c.node_keys.is_empty(){nodes.sort_by_key(|n|c.node_keys.iter().position(|k|k==&n.0).unwrap_or(usize::MAX));}nodes
}
fn layout(nodes:&[Node],password:&str,control_secret:&str)->Result<Value> {
    if nodes.is_empty(){bail!("Нет доступных серверов: выбери серверы или обнови подписку");}
    let mut cfg:Value=serde_json::from_str(include_str!("mode_core.json"))?;
    cfg["inbounds"]=json!([{"type":"mixed","tag":"telegram-local","listen":"127.0.0.1","listen_port":PORT,"users":[{"username":"zdtd","password":password}]}]);
    let mut outbounds:Vec<_>=nodes.iter().map(|n|n.2.clone()).collect();
    outbounds.push(json!({"type":"selector","tag":"TELEGRAM","outbounds":nodes.iter().map(|n|&n.0).collect::<Vec<_>>(),"default":nodes[0].0,"interrupt_exist_connections":true}));
    cfg["outbounds"]=json!(outbounds);cfg["route"]["rules"]=json!([]);cfg["route"]["final"]=json!("TELEGRAM");
    cfg["experimental"]["clash_api"]["external_controller"]=json!(format!("127.0.0.1:{CONTROL}"));
    cfg["experimental"]["clash_api"]["secret"]=json!(control_secret);Ok(cfg)
}
struct Core {child:Child}
impl Core {fn alive(&mut self)->bool {matches!(self.child.try_wait(),Ok(None))}}
impl Drop for Core {fn drop(&mut self){let _=self.child.kill();let _=self.child.wait();let _=fs::remove_file(root().join("core.pid"));}}
fn stop_orphan() {
    if let Ok(pid)=fs::read_to_string(root().join("core.pid")).unwrap_or_default().trim().parse::<i32>() {
        if pid>1 {
            let cmd=fs::read(format!("/proc/{pid}/cmdline")).unwrap_or_default();
            let args:Vec<_>=cmd.split(|c|*c==0).collect();
            let path=root().join("runtime.json");
            if args.first().copied()==Some(BIN.as_bytes()) && args.contains(&path.as_os_str().as_encoded_bytes()) {unsafe{libc::kill(pid,libc::SIGKILL);}}
        }
    }
    let _=fs::remove_file(root().join("core.pid"));
}
fn launch(nodes:&mut Vec<Node>,password:&str)->Result<Core> {
    let mut values:Vec<_>=nodes.iter().map(|n|n.2.clone()).collect();
    super::mode_bootstrap::resolve_outbounds(&mut values,crate::android_dns::resolve_ipv4_all);
    for(n,v)in nodes.iter_mut().zip(values){n.2=v;}
    fn valid(nodes:&[Node],password:&str,name:&str)->Result<bool> {
        write(name,&layout(nodes,password,&crate::settings::read_or_create_token()?)?.to_string())?;
        let(code,_)=shell::run_timeout(BIN,&["check","-c",root().join(name).to_str().context("config path")?],Capture::Both,Duration::from_secs(8))?;Ok(code==0)
    }
    if !valid(nodes,password,"runtime.json")? {
        let mut supported=vec![];
        for n in nodes.iter(){if valid(std::slice::from_ref(n),password,"validation.json")? {supported.push(n.clone());}}
        *nodes=supported;
        if !valid(nodes,password,"runtime.json")?{bail!("Ядро отклонило настройки серверов");}
    }
    let log=fs::File::create(root().join("core.log"))?;log.set_permissions(fs::Permissions::from_mode(0o600))?;
    let child=Command::new(BIN).args(["run","-c"]).arg(root().join("runtime.json")).stdin(Stdio::null()).stdout(Stdio::from(log.try_clone()?)).stderr(Stdio::from(log)).spawn()?;
    let mut core=Core{child};write("core.pid",&core.child.id().to_string())?;
    for _ in 0..40 {
        if !core.alive(){bail!("Ядро Telegram остановилось; проверь свободные порты 19978–19979");}
        if std::net::TcpStream::connect_timeout(&format!("127.0.0.1:{CONTROL}").parse()?,Duration::from_millis(100)).is_ok(){return Ok(core);}
        thread::sleep(Duration::from_millis(100));
    }bail!("Ядро Telegram не запустилось за 4 секунды")
}
fn select(key:&str)->Result<()> {
    let response=Client::builder().no_proxy().timeout(Duration::from_secs(3)).build()?.put(format!("http://127.0.0.1:{CONTROL}/proxies/TELEGRAM"))
        .bearer_auth(crate::settings::read_or_create_token()?).header("content-type","application/json").body(json!({"name":key}).to_string()).send()?;
    if !response.status().is_success(){bail!("Не удалось переключить сервер");}Ok(())
}
fn probe(password:&str)->Result<u64> {
    let start=Instant::now();
    let response=Client::builder().no_proxy().proxy(Proxy::all(format!("http://127.0.0.1:{PORT}"))?.basic_auth("zdtd",password))
        .timeout(Duration::from_secs(8)).redirect(reqwest::redirect::Policy::none()).build()?.get(PROBE).header("connection","close").send()?;
    if !(200..400).contains(&response.status().as_u16()){bail!("Проверочный сайт вернул HTTP {}",response.status().as_u16());}
    Ok(start.elapsed().as_millis() as u64)
}
fn error_label(e:&anyhow::Error)->String {
    if let Some(e)=e.downcast_ref::<reqwest::Error>() {return if e.is_timeout(){"Таймаут HTTPS-проверки"}else if e.is_connect(){"Ошибка соединения/TLS"}else{"Ошибка HTTPS-проверки"}.into();}
    // All our own errors are fixed text; core output / URLs with credentials never enter status.
    e.to_string()
}
pub fn start_worker(state:daemon::SharedState) {
    thread::spawn(move||{
        stop_orphan();
        let mut core:Option<Core>=None;let mut loaded=vec![];let mut fingerprint=String::new();
        let mut revision=u64::MAX;let mut next=Instant::now();let mut selected=String::new();let mut failures=0u8;
        loop {
            thread::sleep(Duration::from_secs(1));
            let running={let s=daemon::lock_state(&state);s.services_running&&!s.start_in_progress&&!s.stop_in_progress};
            let c=match config(){Ok(c)=>c,Err(_)=>{core=None;update(json!({"state":"error","message":"Не удалось прочитать настройки Telegram"}));continue;}};
            if !c.enabled || !running {
                core=None;selected.clear();fingerprint.clear();
                update(json!({"state":if c.enabled{"paused"}else{"idle"},"message":if c.enabled{"Приостановлено: запусти ZDT-D"}else{"Выключено"},"server":"","key":""}));continue;
            }
            let requested=REVISION.load(Ordering::SeqCst);
            if requested!=revision {revision=requested;core=None;next=Instant::now();selected.clear();failures=0;}
            if Instant::now()<next {continue;}
            next=Instant::now()+Duration::from_secs(60);
            let result=(||->Result<()>{
                let secret={let _guard=LOCK.lock().unwrap_or_else(|e|e.into_inner());password()?};
                let nodes=candidates(&c,super::connection_modes::proxy_nodes()?);
                let physical=shell::run_timeout("ip",&["-o","-4","addr","show"],Capture::Stdout,Duration::from_secs(3)).ok()
                    .filter(|(code,_)|*code==0).map(|(_,s)|super::mode_policy::physical_network_identity(&s));
                let hash=hex::encode(Sha256::digest(serde_json::to_vec(&(&nodes,physical))?));
                if hash!=fingerprint || core.as_mut().map(|c|!c.alive()).unwrap_or(true) {
                    core=None;loaded=nodes;
                    update(json!({"state":"connecting","message":"Запуск локального прокси…","failures":[]}));
                    core=Some(launch(&mut loaded,&secret)?);fingerprint=hash;selected.clear();failures=0;
                }
                if !selected.is_empty() {
                    if let Ok(ms)=probe(&secret){failures=0;update(json!({"state":"ready","message":"HTTPS Telegram доступен через сервер","delay_ms":ms}));return Ok(());}
                    failures+=1;
                    if failures<2 {update(json!({"state":"degraded","message":"Проверка не прошла; повтор через 60 секунд"}));return Ok(());}
                }
                let mut errors=vec![];
                // Keep healthy connections; only rotate after two failed checks.
                let mut order=loaded.clone();
                if c.automatic && order.len()>1 {if let Some(index)=order.iter().position(|n|n.0==selected){let count=order.len();order.rotate_left((index+1)%count);}}
                for n in &order {
                    if REVISION.load(Ordering::SeqCst)!=revision {return Ok(());}
                    {let s=daemon::lock_state(&state);if !s.services_running||s.stop_in_progress{return Ok(());}}
                    update(json!({"state":"connecting","message":format!("Проверка: {}",n.1),"server":n.1,"key":n.0}));
                    select(&n.0)?;
                    match probe(&secret) {
                        Ok(ms)=>{selected=n.0.clone();failures=0;update(json!({"state":"ready","message":"HTTPS Telegram доступен через сервер","server":n.1,"key":n.0,"delay_ms":ms,"failures":errors}));return Ok(());},
                        Err(e)=>errors.push(json!({"server":n.1,"message":error_label(&e)})),
                    }
                }
                update(json!({"failures":errors}));selected.clear();bail!("Ни один сервер не прошёл HTTPS-проверку Telegram. Повтор через 60 секунд")
            })();
            if let Err(e)=result {update(json!({"state":"error","message":error_label(&e)}));}
        }
    });
}
#[cfg(test)]
mod tests {
    use super::*;
    fn node(k:&str)->Node{(k.into(),k.into(),json!({"type":"direct","tag":k}))}
    #[test] fn telegram_core_fixture() {
        let good=("good".into(),"good".into(),json!({"type":"socks","tag":"good","server":"127.0.0.1","server_port":10000}));
        let dead=("dead".into(),"dead".into(),json!({"type":"socks","tag":"dead","server":"127.0.0.1","server_port":10001}));
        let cfg=layout(&[good,dead],"fixture-password","fixture-token").unwrap();
        assert_eq!(cfg["inbounds"].as_array().unwrap().len(),1);
        assert_eq!(cfg["inbounds"][0]["listen"],"127.0.0.1");
        assert!(!cfg["outbounds"].as_array().unwrap().iter().any(|n|n["type"]=="direct"));
        if let Ok(path)=std::env::var("ZDT_TELEGRAM_FIXTURE"){fs::write(path,cfg.to_string()).unwrap();}
    }
    #[test] fn telegram_pool_respects_manual_selection_and_deleted_nodes(){
        let mut c=Config::default();c.node_keys=vec!["b".into(),"deleted".into(),"a".into()];
        assert_eq!(candidates(&c,vec![node("a"),node("b"),node("c")]).iter().map(|n|n.0.as_str()).collect::<Vec<_>>(),vec!["b","a"]);
        c.automatic=false;c.selected_key="c".into();assert_eq!(candidates(&c,vec![node("a"),node("c")]).len(),1);
        c.selected_key="deleted".into();assert!(candidates(&c,vec![node("a")]).is_empty());
    }
}
