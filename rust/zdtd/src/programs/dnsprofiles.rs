//! Per-app DNS profiles.
//!
//! Each enabled profile gets its own tun2socks TUN plus a local sing-box SOCKS
//! endpoint and Android netd VPN network. Selected app UIDs are attached to
//! that network. netd points those UIDs at a synthetic DNS address inside the
//! profile /30; DNS packets traverse tun2socks into sing-box where port 53 is
//! hijacked and resolved through the configured DoH endpoint. All non-DNS
//! traffic exits through a DIRECT outbound, so this is a DNS policy layer, not
//! a remote VPN/proxy.
use anyhow::{bail, Context, Result};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs::{self, File, OpenOptions},
    io::{Read, Write},
    net::{Ipv4Addr, SocketAddrV4, TcpStream, UdpSocket},
    os::unix::{fs::OpenOptionsExt, process::CommandExt},
    path::{Path, PathBuf},
    process::{Command, Stdio},
    sync::Mutex,
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

use super::common::{stable_netid, u32_to_ipv4, wait_tun_link, NETID_DNSPROFILES};
use crate::{
    android::pkg_uid::{self, Mode as UidMode},
    shell::{self, Capture},
    vpn_netd::VpnNetdProfile,
};

pub const CONFIG_PATH: &str = "/data/adb/modules/ZDT-D/working_folder/dnsprofiles/profiles.json";
pub const MAX_BYTES: usize = 256 * 1024;
const ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/dnsprofiles";
const RUNTIME_ROOT: &str = "/data/adb/modules/ZDT-D/working_folder/dnsprofiles/runtime";
const SINGBOX_BIN: &str = "/data/adb/modules/ZDT-D/bin/sing-box";
const TUN2SOCKS_BIN: &str = "/data/adb/modules/ZDT-D/bin/tun2socks";
const PROXY_PORT_BASE: u32 = 19500;
const DNSCRYPT_ACTIVE: &str = "/data/adb/modules/ZDT-D/working_folder/dnscrypt/active.json";
const NETID_BASE: u32 = NETID_DNSPROFILES.0;
const NETID_MAX: u32 = NETID_DNSPROFILES.1;
// 10.253.240.0/26 gives sixteen /30 profiles. It is intentionally separate
// from the pools used by the other ZDT-D VPN engines.
const DNS_NET_BASE: u32 = 0x0AFD_F000;
const TUN_WAIT: Duration = Duration::from_secs(15);
const DNS_WAIT: Duration = Duration::from_secs(12);
const CHECK_TIMEOUT: Duration = Duration::from_secs(10);
static STORE_LOCK: Mutex<()> = Mutex::new(());

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct DnsProfile {
    pub display_name: String,
    pub endpoint: String,
    pub bootstrap: Vec<Ipv4Addr>,
    pub timeout_ms: u32,
    pub apps: Vec<String>,
    pub enabled: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(deny_unknown_fields)]
pub struct ProfileDocument {
    pub schema_version: u32,
    pub revision: u64,
    pub profiles: BTreeMap<String, DnsProfile>,
}

impl Default for ProfileDocument {
    fn default() -> Self {
        Self {
            schema_version: 1,
            revision: 0,
            profiles: BTreeMap::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize)]
pub struct RuntimeProfileStatus {
    pub enabled: bool,
    pub netid: u32,
    pub tun: String,
    pub dns: String,
    pub process_running: bool,
    pub netd_applied: bool,
    pub pid: Option<i32>,
    pub log_path: String,
}

#[derive(Debug, Clone, Serialize)]
pub struct RuntimeStatus {
    pub runtime_available: bool,
    pub global_dnscrypt_enabled: bool,
    pub private_dns_mode: String,
    pub restart_required_after_edit: bool,
    pub profiles: BTreeMap<String, RuntimeProfileStatus>,
}

#[derive(Debug, Clone)]
struct RuntimePlan {
    id: String,
    profile: DnsProfile,
    netid: u32,
    tun: String,
    tun_address: String,
    cidr: String,
    dns: String,
    proxy_port: u16,
    root: PathBuf,
    config: PathBuf,
    log: PathBuf,
    pid: PathBuf,
    tun2socks_log: PathBuf,
    tun2socks_pid: PathBuf,
    app_in: PathBuf,
    app_out: PathBuf,
}

fn valid_id(id: &str) -> bool {
    !id.is_empty()
        && id.len() <= 48
        && id
            .bytes()
            .all(|c| c.is_ascii_alphanumeric() || c == b'_' || c == b'-')
}

fn valid_package(package: &str) -> bool {
    package.len() <= 255
        && package.contains('.')
        && package.split('.').all(|part| {
            !part.is_empty()
                && part.as_bytes()[0].is_ascii_alphabetic()
                && part.bytes().all(|c| c.is_ascii_alphanumeric() || c == b'_')
        })
}

pub fn validate(document: &ProfileDocument) -> Result<()> {
    if document.schema_version != 1 {
        bail!("unsupported_schema_version");
    }
    if document.profiles.len() > 16 {
        bail!("too_many_profiles: maximum 16 profiles");
    }
    let mut assigned = BTreeMap::<&str, &str>::new();
    for (id, profile) in &document.profiles {
        if !valid_id(id) {
            bail!("invalid_profile_id: {id}");
        }
        let name = profile.display_name.trim();
        if name.is_empty() || name.chars().count() > 80 || name.chars().any(char::is_control) {
            bail!("invalid_display_name: {id}");
        }
        if profile.endpoint.len() > 2048 || profile.endpoint.chars().any(char::is_whitespace) {
            bail!("invalid_doh_endpoint: {id}");
        }
        let url = reqwest::Url::parse(&profile.endpoint).context("invalid_doh_endpoint")?;
        if url.scheme() != "https"
            || url.host_str().is_none()
            || !url.username().is_empty()
            || url.password().is_some()
            || url.fragment().is_some()
        {
            bail!("invalid_doh_endpoint: {id}: HTTPS without credentials or fragment required");
        }
        if url.port() == Some(0) {
            bail!("invalid_doh_port: {id}");
        }
        if !(250..=30000).contains(&profile.timeout_ms) {
            bail!("invalid_timeout: {id}");
        }
        if profile.bootstrap.is_empty() || profile.bootstrap.len() > 8 {
            bail!("invalid_bootstrap: {id}: specify 1..8 IPv4 addresses");
        }
        let mut bootstrap = BTreeSet::new();
        for ip in &profile.bootstrap {
            if ip.is_unspecified()
                || ip.is_loopback()
                || ip.is_multicast()
                || ip.is_broadcast()
                || !bootstrap.insert(*ip)
            {
                bail!("invalid_bootstrap: {id}: {ip}");
            }
        }
        if profile.apps.len() > 512 {
            bail!("too_many_apps: {id}");
        }
        if profile.enabled && profile.apps.is_empty() {
            bail!("enabled_profile_has_no_apps: {id}");
        }
        for package in &profile.apps {
            if !valid_package(package) {
                bail!("invalid_package: {package}");
            }
            if package == "com.android.zdtd.service" {
                bail!("controller_app_excluded: {package}");
            }
            if let Some(other) = assigned.insert(package, id) {
                bail!("package_conflict: {package}: {other}, {id}");
            }
        }
    }
    Ok(())
}

/// The UID map must come from the device, never from a client-supplied UID.
/// Unresolved packages are errors, not silently treated as unassigned.
pub fn validate_uids(document: &ProfileDocument, uids: &BTreeMap<String, u32>) -> Result<()> {
    validate(document)?;
    let mut owners = BTreeMap::<u32, &str>::new();
    for (id, profile) in &document.profiles {
        for package in &profile.apps {
            let uid = *uids
                .get(package)
                .with_context(|| format!("unresolved_package: {package}"))?;
            // Current runtime intentionally supports normal apps of Android user 0.
            if !(10000..=19999).contains(&uid) {
                bail!("unsupported_uid: {package}: {uid}; primary-user application UID required");
            }
            if let Some(other) = owners.insert(uid, id) {
                if other != id {
                    bail!("shared_uid_conflict: UID {uid}: {other}, {id}");
                }
            }
        }
    }
    Ok(())
}

pub fn packages(document: &ProfileDocument) -> Vec<String> {
    document
        .profiles
        .values()
        .flat_map(|p| p.apps.iter().cloned())
        .collect()
}

fn enabled_packages(document: &ProfileDocument) -> Vec<String> {
    document
        .profiles
        .values()
        .filter(|p| p.enabled)
        .flat_map(|p| p.apps.iter().cloned())
        .collect()
}

fn read_unlocked(path: &Path) -> Result<ProfileDocument> {
    let file = match File::open(path) {
        Ok(file) => file,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(ProfileDocument::default()),
        Err(e) => return Err(e).context("read DNS profile store"),
    };
    let mut bytes = Vec::new();
    file.take((MAX_BYTES + 1) as u64).read_to_end(&mut bytes)?;
    if bytes.len() > MAX_BYTES {
        bail!("dns_profile_store_too_large");
    }
    let document =
        serde_json::from_slice(&bytes).context("invalid DNS profile store; not overwritten")?;
    validate(&document)?;
    Ok(document)
}

pub fn load(path: &Path) -> Result<ProfileDocument> {
    let _guard = STORE_LOCK
        .lock()
        .map_err(|_| anyhow::anyhow!("dns_profile_store_lock"))?;
    read_unlocked(path)
}

/// Replace the document with compare-and-swap semantics. Runtime changes are
/// applied on the next normal ZDT-D stop/start cycle.
pub fn save(path: &Path, mut document: ProfileDocument) -> Result<ProfileDocument> {
    validate(&document)?;
    let _guard = STORE_LOCK
        .lock()
        .map_err(|_| anyhow::anyhow!("dns_profile_store_lock"))?;
    let current = read_unlocked(path)?;
    if document.revision != current.revision {
        bail!("revision_conflict: reload profiles before saving");
    }
    document.revision = current
        .revision
        .checked_add(1)
        .context("revision_overflow")?;
    let bytes = serde_json::to_vec_pretty(&document)?;
    if bytes.len() > MAX_BYTES {
        bail!("dns_profile_store_too_large");
    }
    let parent = path.parent().context("missing store parent")?;
    fs::create_dir_all(parent)?;
    let temporary = path.with_extension("json.tmp");
    let result = (|| -> Result<()> {
        let mut file = OpenOptions::new()
            .write(true)
            .create(true)
            .truncate(true)
            .mode(0o600)
            .custom_flags(libc::O_NOFOLLOW)
            .open(&temporary)?;
        file.write_all(&bytes)?;
        file.sync_all()?;
        fs::rename(&temporary, path)?;
        File::open(parent)?.sync_all()?;
        Ok(())
    })();
    if result.is_err() {
        let _ = fs::remove_file(&temporary);
    }
    result?;
    Ok(document)
}

pub fn has_enabled_profiles() -> bool {
    load(Path::new(CONFIG_PATH))
        .map(|d| d.profiles.values().any(|p| p.enabled && !p.apps.is_empty()))
        .unwrap_or(false)
}

fn global_dnscrypt_enabled() -> bool {
    let Ok(raw) = fs::read_to_string(DNSCRYPT_ACTIVE) else {
        return false;
    };
    let Ok(value) = serde_json::from_str::<Value>(&raw) else {
        return false;
    };
    value.get("enabled").and_then(Value::as_bool).unwrap_or(false)
}

fn private_dns_mode() -> String {
    shell::run_timeout(
        "settings",
        &["get", "global", "private_dns_mode"],
        Capture::Stdout,
        Duration::from_secs(2),
    )
    .ok()
    .and_then(|(code, out)| if code == 0 { Some(out.trim().to_string()) } else { None })
    .filter(|s| !s.is_empty() && s != "null")
    .unwrap_or_else(|| "unknown".to_string())
}

fn ensure_runtime_prerequisites() -> Result<()> {
    if !Path::new(SINGBOX_BIN).is_file() {
        bail!("dnsprofiles: sing-box binary missing: {SINGBOX_BIN}");
    }
    if global_dnscrypt_enabled() {
        bail!("dnsprofiles: global DNSCrypt is enabled; disable it before starting per-app DNS profiles");
    }
    let pdns = private_dns_mode();
    if pdns.eq_ignore_ascii_case("hostname") {
        bail!("dnsprofiles: Android Private DNS strict mode is enabled; set Private DNS to Off or Automatic before start");
    }
    Ok(())
}

pub fn validate_start_plan() -> Result<()> {
    let document = load(Path::new(CONFIG_PATH))?;
    if !document.profiles.values().any(|p| p.enabled) {
        return Ok(());
    }
    ensure_runtime_prerequisites()?;
    let all_names = document.profiles.keys().cloned().collect::<Vec<_>>();
    for (id, profile) in &document.profiles {
        if !profile.enabled {
            continue;
        }
        let _ = build_plan(id, profile, &all_names)?;
    }
    Ok(())
}

fn runtime_root(id: &str) -> PathBuf {
    Path::new(RUNTIME_ROOT).join(id)
}

fn build_plan(id: &str, profile: &DnsProfile, all_names: &[String]) -> Result<RuntimePlan> {
    let netid = stable_netid(NETID_BASE, NETID_MAX, all_names, id)?;
    let index = netid - NETID_BASE;
    let tun = format!("zdt_dns{index}");
    if tun.len() > 15 {
        bail!("dnsprofiles: generated TUN name is too long: {tun}");
    }
    let offset = index
        .checked_mul(4)
        .context("dnsprofiles CIDR overflow")?;
    let network = DNS_NET_BASE
        .checked_add(offset)
        .context("dnsprofiles CIDR overflow")?;
    let host = network.checked_add(1).context("dnsprofiles CIDR overflow")?;
    let dns = network.checked_add(2).context("dnsprofiles CIDR overflow")?;
    let proxy_port_u32 = PROXY_PORT_BASE
        .checked_add(index)
        .context("dnsprofiles proxy port overflow")?;
    let proxy_port = u16::try_from(proxy_port_u32)
        .context("dnsprofiles proxy port out of range")?;
    let root = runtime_root(id);
    Ok(RuntimePlan {
        id: id.to_string(),
        profile: profile.clone(),
        netid,
        tun,
        tun_address: format!("{}/30", u32_to_ipv4(host)),
        cidr: format!("{}/30", u32_to_ipv4(network)),
        dns: u32_to_ipv4(dns),
        proxy_port,
        config: root.join("config.json"),
        log: root.join("sing-box.log"),
        pid: root.join("sing-box.pid"),
        tun2socks_log: root.join("tun2socks.log"),
        tun2socks_pid: root.join("tun2socks.pid"),
        app_in: root.join("app/uid/user_program"),
        app_out: root.join("app/out/user_program"),
        root,
    })
}

fn doh_parts(profile: &DnsProfile) -> Result<(String, u16, String, bool)> {
    let url = reqwest::Url::parse(&profile.endpoint).context("invalid_doh_endpoint")?;
    let host = url
        .host_str()
        .map(str::to_string)
        .context("invalid_doh_endpoint: host missing")?;
    let port = url.port_or_known_default().unwrap_or(443);
    let mut path = if url.path().is_empty() { "/".to_string() } else { url.path().to_string() };
    if let Some(query) = url.query() {
        path.push('?');
        path.push_str(query);
    }
    let host_is_ip = host.parse::<std::net::IpAddr>().is_ok();
    Ok((host, port, path, host_is_ip))
}

fn build_singbox_config(plan: &RuntimePlan) -> Result<Value> {
    let (host, port, path, host_is_ip) = doh_parts(&plan.profile)?;
    let bootstrap = plan
        .profile
        .bootstrap
        .first()
        .context("dnsprofiles: missing bootstrap DNS")?
        .to_string();

    let mut tls = serde_json::Map::new();
    tls.insert("enabled".to_string(), Value::Bool(true));
    if !host_is_ip {
        tls.insert("server_name".to_string(), Value::String(host.clone()));
    }

    Ok(json!({
        "log": {"level": "info", "timestamp": true},
        "dns": {
            "servers": [
                {"type": "udp", "tag": "bootstrap", "server": bootstrap},
                {
                    "type": "https", "tag": "profile-doh", "server": host,
                    "server_port": port, "path": path,
                    "domain_resolver": {"server": "bootstrap", "strategy": "ipv4_only"},
                    "tls": Value::Object(tls),
                    "connect_timeout": format!("{}ms", plan.profile.timeout_ms)
                }
            ],
            "final": "profile-doh",
            "strategy": "ipv4_only",
            "independent_cache": true
        },
        "inbounds": [{
            "type": "mixed", "tag": "dns-profile-mixed",
            "listen": "127.0.0.1", "listen_port": plan.proxy_port
        }],
        "outbounds": [{"type": "direct", "tag": "direct"}],
        "route": {
            "auto_detect_interface": true,
            "default_domain_resolver": {"server": "profile-doh", "strategy": "ipv4_only"},
            "rules": [{"port": [53], "action": "hijack-dns"}],
            "final": "direct"
        }
    }))
}

fn write_atomic(path: &Path, bytes: &[u8]) -> Result<()> {
    if let Some(parent) = path.parent() {
        fs::create_dir_all(parent).with_context(|| format!("mkdir {}", parent.display()))?;
    }
    let tmp = path.with_extension("tmp");
    fs::write(&tmp, bytes).with_context(|| format!("write {}", tmp.display()))?;
    fs::rename(&tmp, path).with_context(|| format!("rename {} -> {}", tmp.display(), path.display()))?;
    Ok(())
}

fn prepare_plan_files(plan: &RuntimePlan) -> Result<()> {
    fs::create_dir_all(&plan.root).with_context(|| format!("mkdir {}", plan.root.display()))?;
    let config = serde_json::to_vec_pretty(&build_singbox_config(plan)?)?;
    write_atomic(&plan.config, &config)?;
    let mut apps = plan.profile.apps.clone();
    apps.sort();
    apps.dedup();
    let app_text = if apps.is_empty() {
        String::new()
    } else {
        format!("{}\n", apps.join("\n"))
    };
    write_atomic(&plan.app_in, app_text.as_bytes())?;
    if let Some(parent) = plan.app_out.parent() {
        fs::create_dir_all(parent)?;
    }
    Ok(())
}

fn singbox_check(plan: &RuntimePlan) -> Result<()> {
    let cfg = plan.config.to_str().context("non-utf8 dnsprofiles config path")?;
    let (code, out) = shell::run_timeout(
        SINGBOX_BIN,
        &["check", "-c", cfg],
        Capture::Both,
        CHECK_TIMEOUT,
    )
    .with_context(|| format!("dnsprofiles: sing-box check {}", plan.config.display()))?;
    if code != 0 {
        let _ = fs::write(
            &plan.log,
            format!("sing-box check failed rc={code}\n{}\n", out.trim()),
        );
        bail!("dnsprofiles: sing-box check failed for {}: {}", plan.id, out.trim());
    }
    Ok(())
}

fn read_pid(path: &Path) -> Option<i32> {
    fs::read_to_string(path).ok()?.trim().parse::<i32>().ok().filter(|p| *p > 1)
}

fn pid_matches_plan(pid: i32, plan: &RuntimePlan) -> bool {
    let path = PathBuf::from("/proc").join(pid.to_string()).join("cmdline");
    let Ok(raw) = fs::read(path) else { return false; };
    let text = String::from_utf8_lossy(&raw).replace('\0', " ");
    text.contains("sing-box") && text.contains(&plan.config.display().to_string())
}

fn pid_matches_tun2socks(pid: i32, plan: &RuntimePlan) -> bool {
    let path = PathBuf::from("/proc").join(pid.to_string()).join("cmdline");
    let Ok(raw) = fs::read(path) else { return false; };
    let text = String::from_utf8_lossy(&raw).replace('\0', " ");
    text.contains("tun2socks")
        && text.contains(&format!("tun://{}", plan.tun))
        && text.contains(&format!("socks5://127.0.0.1:{}", plan.proxy_port))
}

fn stop_pid_file(path: &Path, matches: impl Fn(i32) -> bool) {
    let Some(pid) = read_pid(path) else {
        let _ = fs::remove_file(path);
        return;
    };
    if !matches(pid) {
        let _ = fs::remove_file(path);
        return;
    }
    unsafe {
        let _ = libc::kill(pid, libc::SIGTERM);
    }
    let deadline = Instant::now() + Duration::from_secs(2);
    while Instant::now() < deadline {
        if !PathBuf::from("/proc").join(pid.to_string()).exists() {
            break;
        }
        thread::sleep(Duration::from_millis(100));
    }
    if PathBuf::from("/proc").join(pid.to_string()).exists() {
        unsafe {
            let _ = libc::kill(pid, libc::SIGKILL);
        }
    }
    let _ = fs::remove_file(path);
}

fn wait_tcp_ready(port: u16, timeout: Duration) -> Result<()> {
    let deadline = Instant::now() + timeout;
    let addr = SocketAddrV4::new(Ipv4Addr::LOCALHOST, port);
    while Instant::now() < deadline {
        if TcpStream::connect_timeout(&addr.into(), Duration::from_millis(400)).is_ok() {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(150));
    }
    bail!("dnsprofiles: local sing-box proxy port {port} did not become ready")
}

fn spawn_tun2socks(plan: &RuntimePlan) -> Result<i32> {
    if let Some(pid) = read_pid(&plan.tun2socks_pid) {
        if pid_matches_tun2socks(pid, plan) {
            return Ok(pid);
        }
        let _ = fs::remove_file(&plan.tun2socks_pid);
    }
    if !Path::new(TUN2SOCKS_BIN).is_file() {
        bail!("dnsprofiles: tun2socks binary missing: {TUN2SOCKS_BIN}");
    }
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&plan.tun2socks_log)
        .with_context(|| format!("open {}", plan.tun2socks_log.display()))?;
    let logf_err = logf.try_clone()?;
    let proxy = format!("socks5://127.0.0.1:{}", plan.proxy_port);
    let mut cmd = Command::new(TUN2SOCKS_BIN);
    cmd.arg("-device")
        .arg(format!("tun://{}", plan.tun))
        .arg("-proxy")
        .arg(&proxy)
        .arg("-loglevel")
        .arg("info")
        .current_dir(&plan.root)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));
    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }
    let child = cmd
        .spawn()
        .with_context(|| format!("dnsprofiles: spawn tun2socks for {}", plan.id))?;
    let pid = i32::try_from(child.id()).unwrap_or(i32::MAX);
    write_atomic(&plan.tun2socks_pid, format!("{pid}\n").as_bytes())?;
    Ok(pid)
}

fn configure_tun_addr(plan: &RuntimePlan) -> Result<()> {
    let (code, out) = shell::run_timeout(
        "ip",
        &["addr", "replace", &plan.tun_address, "dev", &plan.tun],
        Capture::Both,
        Duration::from_secs(3),
    )
    .context("dnsprofiles: configure TUN address")?;
    if code != 0 {
        bail!("dnsprofiles: ip addr replace failed for {}: {}", plan.tun, out.trim());
    }
    let (code, out) = shell::run_timeout(
        "ip",
        &["link", "set", "dev", &plan.tun, "up"],
        Capture::Both,
        Duration::from_secs(3),
    )
    .context("dnsprofiles: bring TUN up")?;
    if code != 0 {
        bail!("dnsprofiles: ip link set up failed for {}: {}", plan.tun, out.trim());
    }
    Ok(())
}

fn stop_plan_process(plan: &RuntimePlan) {
    stop_pid_file(&plan.tun2socks_pid, |pid| pid_matches_tun2socks(pid, plan));
    stop_pid_file(&plan.pid, |pid| pid_matches_plan(pid, plan));
}

fn stop_stale_owned_processes(document: &ProfileDocument) {
    let names = document.profiles.keys().cloned().collect::<Vec<_>>();
    for (id, profile) in &document.profiles {
        if let Ok(plan) = build_plan(id, profile, &names) {
            stop_plan_process(&plan);
        }
    }
    // Also handle deleted profiles whose runtime directories still exist.
    let Ok(entries) = fs::read_dir(RUNTIME_ROOT) else { return; };
    for entry in entries.flatten() {
        let dir = entry.path();
        if !dir.is_dir() {
            continue;
        }
        let pid_path = dir.join("sing-box.pid");
        let config = dir.join("config.json");
        let Some(pid) = read_pid(&pid_path) else { continue; };
        let proc = PathBuf::from("/proc").join(pid.to_string()).join("cmdline");
        if let Ok(raw) = fs::read(proc) {
            let text = String::from_utf8_lossy(&raw).replace('\0', " ");
            if text.contains("sing-box") && text.contains(&config.display().to_string()) {
                unsafe {
                    let _ = libc::kill(pid, libc::SIGTERM);
                }
            }
        }
        let _ = fs::remove_file(pid_path);
    }
}

fn spawn_plan(plan: &RuntimePlan) -> Result<i32> {
    if let Some(pid) = read_pid(&plan.pid) {
        if pid_matches_plan(pid, plan) {
            return Ok(pid);
        }
        let _ = fs::remove_file(&plan.pid);
    }

    if let Some(parent) = plan.log.parent() {
        fs::create_dir_all(parent)?;
    }
    let logf = OpenOptions::new()
        .create(true)
        .write(true)
        .truncate(true)
        .open(&plan.log)
        .with_context(|| format!("open {}", plan.log.display()))?;
    let logf_err = logf.try_clone()?;
    let mut cmd = Command::new(SINGBOX_BIN);
    cmd.arg("run")
        .arg("-c")
        .arg(&plan.config)
        .stdin(Stdio::null())
        .stdout(Stdio::from(logf))
        .stderr(Stdio::from(logf_err));
    unsafe {
        cmd.pre_exec(|| {
            let _ = libc::setsid();
            Ok(())
        });
    }
    let child = cmd
        .spawn()
        .with_context(|| format!("dnsprofiles: spawn sing-box for {}", plan.id))?;
    let pid = i32::try_from(child.id()).unwrap_or(i32::MAX);
    write_atomic(&plan.pid, format!("{pid}\n").as_bytes())?;
    Ok(pid)
}

fn dns_query_packet(id: u16) -> Vec<u8> {
    let mut buf = Vec::with_capacity(64);
    buf.extend_from_slice(&id.to_be_bytes());
    buf.extend_from_slice(&0x0100u16.to_be_bytes());
    buf.extend_from_slice(&1u16.to_be_bytes());
    buf.extend_from_slice(&0u16.to_be_bytes());
    buf.extend_from_slice(&0u16.to_be_bytes());
    buf.extend_from_slice(&0u16.to_be_bytes());
    for label in ["example", "com"] {
        buf.push(label.len() as u8);
        buf.extend_from_slice(label.as_bytes());
    }
    buf.push(0);
    buf.extend_from_slice(&1u16.to_be_bytes());
    buf.extend_from_slice(&1u16.to_be_bytes());
    buf
}

fn dns_probe_once(ip: Ipv4Addr) -> bool {
    let Ok(socket) = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0)) else { return false; };
    let _ = socket.set_read_timeout(Some(Duration::from_secs(2)));
    let nanos = SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .unwrap_or_default()
        .subsec_nanos();
    let id = ((std::process::id() as u16) ^ (nanos as u16) ^ ((nanos >> 16) as u16)).wrapping_add(0x3157);
    let packet = dns_query_packet(id);
    if socket.send_to(&packet, SocketAddrV4::new(ip, 53)).is_err() {
        return false;
    }
    let mut buf = [0u8; 1024];
    let Ok((n, _)) = socket.recv_from(&mut buf) else { return false; };
    if n < 12 || u16::from_be_bytes([buf[0], buf[1]]) != id {
        return false;
    }
    let flags = u16::from_be_bytes([buf[2], buf[3]]);
    let rcode = flags & 0x000f;
    rcode == 0 || rcode == 3
}

fn wait_dns_ready(plan: &RuntimePlan) -> Result<()> {
    let ip: Ipv4Addr = plan.dns.parse().context("dnsprofiles generated DNS is invalid")?;
    let deadline = Instant::now() + DNS_WAIT;
    while Instant::now() < deadline {
        if let Some(pid) = read_pid(&plan.pid) {
            if !pid_matches_plan(pid, plan) {
                bail!("dnsprofiles: sing-box for {} exited during DNS readiness", plan.id);
            }
        }
        if dns_probe_once(ip) {
            return Ok(());
        }
        thread::sleep(Duration::from_millis(500));
    }
    bail!("dnsprofiles: DoH readiness failed for {} via {}", plan.id, plan.profile.endpoint)
}

fn start_plan(plan: &RuntimePlan) -> Result<()> {
    prepare_plan_files(plan)?;
    singbox_check(plan)?;
    let pid = spawn_plan(plan)?;
    let start_result = (|| -> Result<()> {
        wait_tcp_ready(plan.proxy_port, TUN_WAIT)?;
        let tun_pid = spawn_tun2socks(plan)?;
        wait_tun_link(&plan.tun, TUN_WAIT)?;
        configure_tun_addr(plan)?;
        wait_dns_ready(plan)?;
        log::info!(
            "dnsprofiles: profile={} ready singbox_pid={} tun2socks_pid={} netid={} tun={} dns={} proxy_port={} endpoint={}",
            plan.id,
            pid,
            tun_pid,
            plan.netid,
            plan.tun,
            plan.dns,
            plan.proxy_port,
            plan.profile.endpoint
        );
        Ok(())
    })();
    if let Err(e) = start_result {
        stop_plan_process(plan);
        return Err(e).with_context(|| format!("dnsprofiles profile {} singbox_pid={pid}", plan.id));
    }
    Ok(())
}

pub fn start_profiles_for_netd() -> Result<Vec<VpnNetdProfile>> {
    fs::create_dir_all(ROOT)?;
    let document = load(Path::new(CONFIG_PATH))?;
    if !document.profiles.values().any(|p| p.enabled) {
        return Ok(Vec::new());
    }
    ensure_runtime_prerequisites()?;

    let pkgs = enabled_packages(&document);
    let uids = pkg_uid::resolve_uid_map(UidMode::Default, &pkgs)?;
    let enabled_only = ProfileDocument {
        schema_version: document.schema_version,
        revision: document.revision,
        profiles: document
            .profiles
            .iter()
            .filter(|(_, p)| p.enabled)
            .map(|(id, p)| (id.clone(), p.clone()))
            .collect(),
    };
    validate_uids(&enabled_only, &uids)?;

    stop_stale_owned_processes(&document);
    let all_names = document.profiles.keys().cloned().collect::<Vec<_>>();
    let mut out = Vec::new();
    let mut had_error = false;

    crate::logging::user_info("DNS-профили: запуск");
    for (id, profile) in &document.profiles {
        if !profile.enabled {
            continue;
        }
        let result = (|| -> Result<VpnNetdProfile> {
            let plan = build_plan(id, profile, &all_names)?;
            start_plan(&plan)?;
            Ok(VpnNetdProfile {
                owner_program: "dnsprofiles".to_string(),
                profile: id.clone(),
                netid: plan.netid,
                tun: plan.tun,
                cidr: plan.cidr,
                gateway: None,
                dns: vec![plan.dns],
                app_list_path: plan.app_in,
                app_out_path: plan.app_out,
                endpoint_escape_ips: Vec::new(),
            })
        })();
        match result {
            Ok(item) => out.push(item),
            Err(e) => {
                had_error = true;
                log::warn!("dnsprofiles: profile {id} failed, continuing: {e:#}");
            }
        }
    }
    if had_error {
        crate::logging::user_warn("DNS-профили: часть профилей не запущена");
    }
    if out.is_empty() {
        bail!("dnsprofiles: no enabled profile started");
    }
    Ok(out)
}

pub fn enabled_tun_claims() -> Vec<(String, String)> {
    let Ok(document) = load(Path::new(CONFIG_PATH)) else { return Vec::new(); };
    let names = document.profiles.keys().cloned().collect::<Vec<_>>();
    document
        .profiles
        .iter()
        .filter(|(_, p)| p.enabled)
        .filter_map(|(id, p)| build_plan(id, p, &names).ok().map(|plan| (format!("dnsprofiles/{id}"), plan.tun)))
        .collect()
}

pub fn enabled_cidr_claims() -> Vec<(String, String)> {
    let Ok(document) = load(Path::new(CONFIG_PATH)) else { return Vec::new(); };
    let names = document.profiles.keys().cloned().collect::<Vec<_>>();
    document
        .profiles
        .iter()
        .filter(|(_, p)| p.enabled)
        .filter_map(|(id, p)| build_plan(id, p, &names).ok().map(|plan| (format!("dnsprofiles/{id}"), plan.cidr)))
        .collect()
}

pub fn is_running() -> bool {
    let Ok(document) = load(Path::new(CONFIG_PATH)) else { return false; };
    let names = document.profiles.keys().cloned().collect::<Vec<_>>();
    document.profiles.iter().any(|(id, p)| {
        if !p.enabled {
            return false;
        }
        let Ok(plan) = build_plan(id, p, &names) else { return false; };
        read_pid(&plan.pid).map(|pid| pid_matches_plan(pid, &plan)).unwrap_or(false)
    })
}

pub fn cleanup_runtime_metadata() {
    let Ok(entries) = fs::read_dir(RUNTIME_ROOT) else { return; };
    for entry in entries.flatten() {
        let _ = fs::remove_file(entry.path().join("sing-box.pid"));
        let _ = fs::remove_file(entry.path().join("tun2socks.pid"));
    }
}

pub fn runtime_status() -> RuntimeStatus {
    let document = load(Path::new(CONFIG_PATH)).unwrap_or_default();
    let names = document.profiles.keys().cloned().collect::<Vec<_>>();
    let applied = crate::vpn_netd::read_applied_snapshot().unwrap_or_default();
    let mut profiles = BTreeMap::new();
    for (id, profile) in &document.profiles {
        if let Ok(plan) = build_plan(id, profile, &names) {
            let pid = read_pid(&plan.pid).filter(|pid| pid_matches_plan(*pid, &plan));
            let tun2socks_running = read_pid(&plan.tun2socks_pid)
                .map(|p| pid_matches_tun2socks(p, &plan))
                .unwrap_or(false);
            let netd_applied = applied
                .profiles
                .iter()
                .any(|p| p.owner_program == "dnsprofiles" && p.profile == *id && p.netid == plan.netid);
            profiles.insert(
                id.clone(),
                RuntimeProfileStatus {
                    enabled: profile.enabled,
                    netid: plan.netid,
                    tun: plan.tun,
                    dns: plan.dns,
                    process_running: pid.is_some() && tun2socks_running,
                    netd_applied,
                    pid,
                    log_path: plan.log.display().to_string(),
                },
            );
        }
    }
    RuntimeStatus {
        runtime_available: true,
        global_dnscrypt_enabled: global_dnscrypt_enabled(),
        private_dns_mode: private_dns_mode(),
        restart_required_after_edit: true,
        profiles,
    }
}

pub fn preview(document: &ProfileDocument) -> Result<Value> {
    validate(document)?;
    let names = document.profiles.keys().cloned().collect::<Vec<_>>();
    let mut profiles = serde_json::Map::new();
    for (id, profile) in &document.profiles {
        let plan = build_plan(id, profile, &names)?;
        profiles.insert(
            id.clone(),
            json!({
                "enabled": profile.enabled,
                "netid": plan.netid,
                "tun": plan.tun,
                "dns": plan.dns,
                "cidr": plan.cidr,
                "endpoint": profile.endpoint,
            }),
        );
    }
    Ok(json!({
        "runtime_available": true,
        "restart_required_after_edit": true,
        "global_dnscrypt_enabled": global_dnscrypt_enabled(),
        "private_dns_mode": private_dns_mode(),
        "profiles": profiles,
        "limitations": [
            "primary_android_user_only",
            "app_owned_doh_not_overridden",
            "global_dnscrypt_must_be_disabled",
            "one_doh_upstream_per_profile",
            "changes_apply_after_zdtd_restart"
        ]
    }))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicUsize, Ordering};
    static NEXT: AtomicUsize = AtomicUsize::new(0);

    fn fixture() -> ProfileDocument {
        ProfileDocument {
            profiles: BTreeMap::from([(
                "xbox".into(),
                DnsProfile {
                    display_name: "Xbox DNS".into(),
                    endpoint: "https://xbox-dns.ru/dns-query".into(),
                    bootstrap: vec![Ipv4Addr::new(1, 1, 1, 1), Ipv4Addr::new(9, 9, 9, 9)],
                    timeout_ms: 7000,
                    apps: vec!["com.google.android.youtube".into()],
                    enabled: false,
                },
            )]),
            ..ProfileDocument::default()
        }
    }
    fn temp_store() -> std::path::PathBuf {
        std::env::temp_dir()
            .join(format!(
                "zdtd-dns-test-{}-{}",
                std::process::id(),
                NEXT.fetch_add(1, Ordering::Relaxed)
            ))
            .join("profiles.json")
    }
    #[test]
    fn accepts_xbox_doh_custom_https_port_and_enabled_runtime() {
        let mut doc = fixture();
        validate(&doc).unwrap();
        doc.profiles.get_mut("xbox").unwrap().endpoint =
            "https://dns.example:8443/custom?key=value".into();
        doc.profiles.get_mut("xbox").unwrap().enabled = true;
        validate(&doc).unwrap();
    }
    #[test]
    fn enabled_profile_requires_apps() {
        let mut doc = fixture();
        let p = doc.profiles.get_mut("xbox").unwrap();
        p.enabled = true;
        p.apps.clear();
        assert!(validate(&doc)
            .unwrap_err()
            .to_string()
            .contains("enabled_profile_has_no_apps"));
    }
    #[test]
    fn rejects_unknown_fields() {
        let mut value = serde_json::to_value(fixture()).unwrap();
        value["profiles"]["xbox"]["policy"] = "fallback".into();
        assert!(serde_json::from_value::<ProfileDocument>(value).is_err());
    }
    #[test]
    fn rejects_unsafe_or_ambiguous_urls() {
        for endpoint in [
            "http://dns.example/dns-query",
            "https://u:p@dns.example/",
            "https://dns.example/#x",
            "https://dns.example:0/",
            "https://dns.example/\n",
        ] {
            let mut doc = fixture();
            doc.profiles.get_mut("xbox").unwrap().endpoint = endpoint.into();
            assert!(validate(&doc).is_err(), "{endpoint}");
        }
    }
    #[test]
    fn rejects_paths_bad_packages_and_bootstrap() {
        let mut doc = fixture();
        let profile = doc.profiles.remove("xbox").unwrap();
        doc.profiles.insert("../escape".into(), profile);
        assert!(validate(&doc).is_err());
        for package in ["com.test;reboot", "com..test", "com.android.zdtd.service"] {
            let mut doc = fixture();
            doc.profiles.get_mut("xbox").unwrap().apps = vec![package.into()];
            assert!(validate(&doc).is_err());
        }
        for ip in ["127.0.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255"] {
            let mut doc = fixture();
            doc.profiles.get_mut("xbox").unwrap().bootstrap = vec![ip.parse().unwrap()];
            assert!(validate(&doc).is_err());
        }
    }
    #[test]
    fn rejects_package_and_shared_uid_conflicts() {
        let mut doc = fixture();
        doc.profiles
            .insert("other".into(), doc.profiles["xbox"].clone());
        assert!(validate(&doc).is_err());
        doc.profiles.get_mut("other").unwrap().apps = vec!["com.example.alias".into()];
        let mut uids = BTreeMap::from([
            ("com.google.android.youtube".into(), 10123),
            ("com.example.alias".into(), 10123),
        ]);
        assert!(validate_uids(&doc, &uids)
            .unwrap_err()
            .to_string()
            .contains("shared_uid_conflict"));
        uids.insert("com.example.alias".into(), 10124);
        validate_uids(&doc, &uids).unwrap();
        uids.remove("com.example.alias");
        assert!(validate_uids(&doc, &uids)
            .unwrap_err()
            .to_string()
            .contains("unresolved_package"));
    }
    #[test]
    fn rejects_system_and_secondary_user_uids() {
        for uid in [0, 1000, 2000, 9999, 99001, 1010123] {
            let uids = BTreeMap::from([("com.google.android.youtube".into(), uid)]);
            assert!(validate_uids(&fixture(), &uids).is_err());
        }
    }
    #[test]
    fn generated_runtime_is_deterministic_and_disjoint() {
        let mut doc = fixture();
        doc.profiles.insert(
            "other".into(),
            DnsProfile {
                apps: vec!["com.example.app".into()],
                ..doc.profiles["xbox"].clone()
            },
        );
        let names = doc.profiles.keys().cloned().collect::<Vec<_>>();
        let a = build_plan("other", &doc.profiles["other"], &names).unwrap();
        let b = build_plan("xbox", &doc.profiles["xbox"], &names).unwrap();
        assert_ne!(a.netid, b.netid);
        assert_ne!(a.tun, b.tun);
        assert_ne!(a.cidr, b.cidr);
        assert_ne!(a.dns, b.dns);
    }
    #[test]
    fn generated_singbox_config_has_tun_hijack_and_tls_doh() {
        let doc = fixture();
        let names = doc.profiles.keys().cloned().collect::<Vec<_>>();
        let plan = build_plan("xbox", &doc.profiles["xbox"], &names).unwrap();
        let cfg = build_singbox_config(&plan).unwrap();
        assert_eq!(cfg["inbounds"][0]["type"], "tun");
        assert_eq!(cfg["route"]["rules"][0]["action"], "hijack-dns");
        assert_eq!(cfg["dns"]["servers"][1]["type"], "https");
        assert_eq!(cfg["dns"]["servers"][1]["server"], "xbox-dns.ru");
        assert_eq!(cfg["dns"]["servers"][1]["tls"]["server_name"], "xbox-dns.ru");
    }
    #[test]
    fn roundtrip_delete_and_stale_writer() {
        let path = temp_store();
        let first = save(&path, fixture()).unwrap();
        assert_eq!(load(&path).unwrap(), first);
        assert_eq!(first.revision, 1);
        assert!(save(&path, fixture())
            .unwrap_err()
            .to_string()
            .contains("revision_conflict"));
        let mut next = first;
        next.profiles.clear();
        assert!(save(&path, next).unwrap().profiles.is_empty());
        fs::remove_dir_all(path.parent().unwrap()).unwrap();
    }
    #[test]
    fn never_overwrites_corrupt_store() {
        let path = temp_store();
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        fs::write(&path, b"broken").unwrap();
        assert!(save(&path, fixture()).is_err());
        assert_eq!(fs::read(&path).unwrap(), b"broken");
        fs::remove_dir_all(path.parent().unwrap()).unwrap();
    }
    #[test]
    fn refuses_symlink_temporary_file() {
        let path = temp_store();
        fs::create_dir_all(path.parent().unwrap()).unwrap();
        let target = path.with_extension("sentinel");
        fs::write(&target, b"keep").unwrap();
        std::os::unix::fs::symlink(&target, path.with_extension("json.tmp")).unwrap();
        assert!(save(&path, fixture()).is_err());
        assert_eq!(fs::read(target).unwrap(), b"keep");
        fs::remove_dir_all(path.parent().unwrap()).unwrap();
    }
}
