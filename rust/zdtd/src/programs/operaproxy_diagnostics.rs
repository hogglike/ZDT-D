use super::*;
use serde_json::{json, Value};
use std::sync::{atomic::{AtomicBool, Ordering}, Mutex, OnceLock};

static BUSY: AtomicBool = AtomicBool::new(false);
static REPORT: OnceLock<Mutex<Value>> = OnceLock::new();
fn report() -> &'static Mutex<Value> { REPORT.get_or_init(|| Mutex::new(json!({"running":false,"results":[]}))) }

pub fn validate_opera_args(args: &OperaArgs) -> Result<()> {
    if !matches!(args.server_selection.as_str(), "fastest" | "random" | "first") { bail!("Выбор сервера: fastest, random или first"); }
    let mut duration=args.init_retry_interval.as_str();let mut total=0.0;
    while !duration.is_empty(){
        let n=duration.bytes().take_while(|c| c.is_ascii_digit()||*c==b'.').count();
        let value=duration[..n].parse::<f64>().map_err(|_|anyhow::anyhow!("Интервал: например 3s, 1m или 1m30s"))?;
        let tail=&duration[n..];
        let (unit,factor)=[("ns",1.0),("us",1e3),("µs",1e3),("ms",1e6),("s",1e9),("m",6e10),("h",3.6e12)].into_iter()
            .find(|(unit,_)|tail.starts_with(unit)).ok_or_else(||anyhow::anyhow!("Интервал: укажи единицу ns/us/ms/s/m/h"))?;
        total+=value*factor;duration=&tail[unit.len()..];
    }
    if !total.is_finite()||total<1.0||total>i64::MAX as f64{bail!("Интервал должен быть положительным и допустимым для Go Duration");}
    if args.verbosity > 60 { bail!("Уровень логов: число от 0 до 60"); }
    if args.server_selection_dl_limit > i64::MAX as u64 { bail!("Лимит теста должен помещаться в положительное 64-битное число (0 — без ограничения)"); }
    let url = reqwest::Url::parse(&args.server_selection_test_url).map_err(|_| anyhow::anyhow!("Неверный адрес проверки"))?;
    if !matches!(url.scheme(),"http"|"https") || url.host_str().is_none() || !url.username().is_empty() || url.password().is_some() { bail!("Адрес проверки: HTTP/HTTPS без логина и пароля"); }
    if args.api_user_agent.contains(['\r','\n','\0']) || args.api_user_agent.len()>4096 { bail!("User-Agent содержит перенос строки или слишком длинный"); }
    let tokens = split_api_proxy_tokens(&args.api_proxy);
    if tokens.len()>1000 || args.api_proxy.len()>API_PROXY_LIST_MAX_BYTES { bail!("API-прокси: максимум 1000 записей и 1 МБ"); }
    for (index, token) in tokens.iter().enumerate() {
        if !is_api_proxy_list_file_path(token) && !is_api_proxy_list_url(token) && parse_api_proxy_candidate(token).is_none() {
            bail!("API-прокси: неверная строка {}. Используй http://логин:пароль@адрес:порт или импорт TXT в разделе Прокси",index+1);
        }
    }
    Ok(())
}
fn valid_sni(sni: &str) -> bool {
    !sni.is_empty() && sni.len()<=253 && sni.is_ascii() && sni.parse::<std::net::IpAddr>().is_err() &&
        sni.split('.').all(|part| !part.is_empty() && part.len()<=63 && !part.starts_with('-') && !part.ends_with('-') && part.bytes().all(|c| c.is_ascii_alphanumeric() || c==b'-'))
}
pub fn validate_sni_content(raw: &str) -> Result<()> {
    let list: Vec<OperaSniEntry> = serde_json::from_str(raw).map_err(|_|anyhow::anyhow!("Неверный JSON списка SNI"))?;
    if list.len()>64 { bail!("Максимум 64 записи SNI"); }
    for (i, entry) in list.iter().enumerate() {
        if !valid_sni(&entry.sni) { bail!("SNI {}: нужен домен без http://, порта, логина и пароля. Прокси Webshare добавляются в API Прокси",i+1); }
        let mode = opera_upstream_mode(entry);
        if mode==OperaUpstreamMode::Custom && !is_valid_custom_upstream_proxy(entry.custom_proxy.as_deref().unwrap_or("")) { bail!("SNI {}: неверный адрес внешнего прокси",i+1); }
        if mode==OperaUpstreamMode::Auto && (entry.custom_proxies.is_empty() || entry.custom_proxies.len()>1000 || entry.custom_proxies.iter().any(|v|!is_valid_custom_upstream_proxy(v))) { bail!("SNI {}: неверный список внешних прокси",i+1); }
        if let Some(address)=&entry.override_proxy_address {
            if !address.is_empty() && parse_host_port(address).is_none() { bail!("SNI {}: адрес сервера Opera должен иметь формат адрес:порт",i+1); }
        }
    }
    Ok(())
}
fn wait_child(child: &mut std::process::Child, limit: Duration) -> Result<bool> {
    let start=Instant::now();
    loop {
        if let Some(status)=child.try_wait()? { return Ok(status.success()); }
        if start.elapsed()>=limit { let _=child.kill(); let _=child.wait(); bail!("Проверка превысила таймаут"); }
        std::thread::sleep(Duration::from_millis(25));
    }
}
pub fn validate_byedpi_args(raw: &str) -> Result<()> {
    if raw.len()>16*1024 || raw.contains('\0') { bail!("Аргументы ByeDPI слишком длинные или содержат NUL"); }
    let mut quote=None;let mut escaped=false;
    for c in raw.chars(){
        if escaped{escaped=false;continue;}
        if c=='\\'&&quote!=Some('\''){escaped=true;continue;}
        if let Some(q)=quote{if c==q{quote=None;}}
        else if c=='\''||c=='"'{quote=Some(c);}
    }
    if quote.is_some(){bail!("ByeDPI: незакрытая кавычка в аргументах");}
    let args=normalize_config_args(raw);
    if args.iter().any(|v| matches!(v.as_str(),"-h"|"--help"|"-v"|"--version")) { bail!("Удали --help/--version из аргументов запуска"); }
    let bin=find_bin("byedpi")?;
    // The patched parser exits before listener, daemonization and pid files.
    let mut child=Command::new(bin).env("ZDT_CHECK_ARGS","1").args(&args)
        .stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn()?;
    if !wait_child(&mut child,Duration::from_secs(2))? { bail!("ByeDPI отклонил аргументы: неизвестный флаг, неверное значение или отсутствующий файл"); }
    let mut marker=String::new();if let Some(mut out)=child.stdout.take(){out.read_to_string(&mut marker)?;}
    if !marker.contains("ZDT_ARGS_OK") { bail!("Проверка аргументов требует обновлённый модуль mod38"); }
    Ok(())
}
pub(super) fn prepare_api_proxy_file(args: &OperaArgs, log: &Path) -> Result<Option<PathBuf>> {
    if args.api_proxy.trim().is_empty(){ return Ok(None); }
    let candidates=collect_api_proxy_candidates(&args.api_proxy, log);
    if candidates.is_empty(){bail!("Не удалось загрузить или разобрать список API-прокси. Проверь источник списка");}
    let path=Path::new(OPERA_ROOT).join("config/zdt_api_candidates.txt");
    use std::os::unix::fs::OpenOptionsExt;
    let mut file=OpenOptions::new().create(true).truncate(true).write(true).mode(0o600).open(&path)?;
    file.write_all(candidates.iter().map(|v|v.original.as_str()).collect::<Vec<_>>().join("\n").as_bytes())?;
    Ok(Some(path))
}
pub fn diagnostic_status() -> Value {
    let mut value=report().lock().unwrap_or_else(|e|e.into_inner()).clone();
    if let Some(results)=value.get_mut("results").and_then(Value::as_array_mut) {
        for row in results {
            if let Some(index)=row.get("index").and_then(Value::as_u64) {
                if let Ok(raw)=fs::read_to_string(Path::new(OPERA_ROOT).join(format!("log/zdt_diagnose_{}.json",index))) {
                    if let Ok(data)=serde_json::from_str::<Value>(&raw){row["diagnosis"]=data;}
                }
            }
        }
    }
    let mut runtime=Vec::new();
    if let Ok(entries)=read_sni_entries(Path::new(SNI_JSON)) {
        for (index,entry) in entries.iter().enumerate() {
            let path=Path::new(OPERA_ROOT).join(format!("log/zdt_diagnostic_state_{}.json",index));
            if let Some(mut data)=fs::read_to_string(path).ok().and_then(|v|serde_json::from_str::<Value>(&v).ok()) {
                data["sni"]=json!(entry.sni);
                let pid=data.get("pid").and_then(Value::as_i64).unwrap_or(0);
                data["process_alive"]=json!(pid>0&&pid<=i32::MAX as i64&&unsafe{libc::kill(pid as i32,0)}==0);
                if let Ok(cfg)=read_json::<PortJson>(Path::new(PORT_JSON)){
                    let address=format!("127.0.0.1:{}",cfg.opera_start_port.saturating_add(index as u16));
                    let listening=address.parse::<std::net::SocketAddr>().ok().and_then(|a|TcpStream::connect_timeout(&a,Duration::from_millis(50)).ok()).is_some();
                    if let Some(steps)=data.get_mut("steps").and_then(Value::as_array_mut){
                        if let Some(step)=steps.iter_mut().find(|v|v.get("stage").and_then(Value::as_str)==Some("local_socks")){
                            step["state"]=json!(if listening{"ready"}else{"error"});
                            step["message"]=json!(if listening{"Локальный порт отвечает"}else{"Локальный SOCKS-порт не отвечает; проверь этап API и запуск процесса"});
                        }
                    }
                }
                runtime.push(data);
            }
        }
    }
    value["runtime"]=json!(runtime);value
}
struct BusyGuard;
impl Drop for BusyGuard { fn drop(&mut self){BUSY.store(false,Ordering::Release);report().lock().unwrap_or_else(|e|e.into_inner())["running"]=json!(false);} }
struct ChildGuard(std::process::Child);
impl Drop for ChildGuard {fn drop(&mut self){let _=self.0.kill();let _=self.0.wait();}}
pub fn start_diagnostics() -> Result<()> {
    if BUSY.compare_exchange(false,true,Ordering::AcqRel,Ordering::Acquire).is_err(){bail!("Проверка уже выполняется");}
    let guard=BusyGuard;
    let raw=fs::read_to_string(SNI_JSON)?;validate_sni_content(&raw)?;
    let entries=read_sni_entries(Path::new(SNI_JSON))?;
    if entries.is_empty(){bail!("Сначала добавь SNI");}
    if entries.len()>20{bail!("Одна проверка поддерживает до 20 SNI");}
    let args=read_opera_args();validate_opera_args(&args)?;
    let bin=find_bin("opera-proxy")?;
    let bootstrap=build_bootstrap_dns_list()?;
    let country=read_server_region(Path::new(SERVER_TXT));
    let log_dir=Path::new(OPERA_ROOT).join("log");fs::create_dir_all(&log_dir)?;
    // Clear old results before exposing the next job; immutable settings snapshot.
    for i in 0..entries.len(){let _=fs::remove_file(log_dir.join(format!("zdt_diagnose_{}.json",i)));}
    *report().lock().unwrap_or_else(|e|e.into_inner())=json!({"running":true,"started_at":std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH)?.as_secs(),"results":[]});
    std::thread::spawn(move||{
        let _guard=guard;
        let result=(||->Result<()> {
            let api_file=prepare_api_proxy_file(&args,&log_dir.join("api_proxy_check.log"))?;
            let mut bye:Option<ChildGuard>=None;let mut bye_port=0;
            if entries.iter().any(|e|opera_upstream_mode(e)==OperaUpstreamMode::ByeDpi){
                let raw=fs::read_to_string(BYEDPI_START_TXT)?;validate_byedpi_args(&raw)?;
                let reservation=std::net::TcpListener::bind("127.0.0.1:0")?;bye_port=reservation.local_addr()?.port();drop(reservation);
                let child=Command::new(find_bin("byedpi")?).args(["-i","127.0.0.1","-p",&bye_port.to_string(),"-x","0"])
                    .args(normalize_config_args(&raw)).stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).spawn()?;
                bye=Some(ChildGuard(child));
                std::thread::sleep(Duration::from_millis(250));
                if bye.as_mut().unwrap().0.try_wait()?.is_some(){bail!("Диагностический ByeDPI не запустился. Проверь аргументы и порт");}
            }
            for (index,entry) in entries.iter().enumerate(){
                let output=log_dir.join(format!("zdt_diagnose_{}.json",index));
                report().lock().unwrap_or_else(|e|e.into_inner())["results"].as_array_mut().unwrap().push(json!({"index":index,"sni":entry.sni,"state":"checking"}));
                let mut cmd=Command::new(&bin);
                cmd.args(["-zdt-diagnose"]).arg(&output).arg("-cafile").arg(OPERA_CAFILE)
                    .arg("-fake-SNI").arg(&entry.sni).arg("-bootstrap-dns").arg(&bootstrap)
                    .arg("-api-user-agent").arg(&args.api_user_agent).arg("-country").arg(&country)
                    .arg("-server-selection-test-url").arg(&args.server_selection_test_url).arg("-api-proxy-parallel").arg("5");
                let auto=opera_upstream_mode(entry)==OperaUpstreamMode::Auto;
                if auto{
                    let path=log_dir.join(format!("zdt_diagnose_pool_{}.json",index));
                    use std::os::unix::fs::OpenOptionsExt;
                    let mut file=OpenOptions::new().create(true).truncate(true).write(true).mode(0o600).open(&path)?;
                    file.write_all(&serde_json::to_vec(&entry.custom_proxies)?)?;cmd.arg("-zdt-proxy-list").arg(path);
                }else{
                    if let Some(upstream)=opera_upstream_proxy(entry,bye_port)?{cmd.arg("-proxy").arg(upstream);}
                    if let Some(path)=&api_file{cmd.arg("-api-proxy-file").arg(path);}
                }
                if let Some(address)=&entry.override_proxy_address{if !address.is_empty(){cmd.arg("-override-proxy-address").arg(address);}}
                let mut child=ChildGuard(cmd.stdin(Stdio::null()).stdout(Stdio::null()).stderr(Stdio::null()).spawn()?);
                let success=wait_child(&mut child.0,Duration::from_secs(100)).unwrap_or(false);
                report().lock().unwrap_or_else(|e|e.into_inner())["results"][index]["state"]=json!(if success{"ready"}else{"error"});
                if !output.is_file(){fs::write(&output,serde_json::to_vec(&json!({"state":"error","steps":[{"stage":"config","state":"error","message":"Процесс проверки не завершился. Обнови модуль вместе с APK или проверь аргументы"}]}))?)?;}
            }
            drop(bye);Ok(())
        })();
        if let Err(e)=result{report().lock().unwrap_or_else(|e|e.into_inner())["error"]=json!(e.to_string());}
    });
    Ok(())
}

#[cfg(test)] mod tests {
 use super::*;
 #[test] fn rejects_proxy_as_sni_and_invalid_args(){
  assert!(valid_sni("m.vk.com"));assert!(!valid_sni("http://user:pass@1.2.3.4:80"));assert!(!valid_sni("1.2.3.4"));
  let mut args=OperaArgs::default();assert!(validate_opera_args(&args).is_ok());
  args.init_retry_interval="three".into();assert!(validate_opera_args(&args).is_err());args.init_retry_interval="1m30s".into();assert!(validate_opera_args(&args).is_ok());
  args.verbosity=999;assert!(validate_opera_args(&args).is_err());args.verbosity=50;
  args.api_proxy="secret:80:login:password".into();let e=validate_opera_args(&args).unwrap_err().to_string();assert!(!e.contains("password"));
 }
}
