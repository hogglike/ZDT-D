use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, BTreeSet},
    fs,
    io::Read,
    net::Ipv4Addr,
    path::PathBuf,
    sync::{mpsc, Mutex, OnceLock},
    time::{Duration, Instant, UNIX_EPOCH},
};

const SOURCE: &str = "https://raw.githubusercontent.com/Noktomezo/RussiaFancyLists/main/lists/hosts/smart-no-crutch.adguard.txt";
const INTERVAL: Duration = Duration::from_secs(3600);
const MAX_SOURCE: u64 = 512 * 1024;
pub(super) const CORE_DOMAIN: &str = "gemini.google.com";
const DOMAINS: &[&str] = &[
    CORE_DOMAIN,
    "bard.google.com",
    "geminiweb-pa.clients6.google.com",
    "alkalimakersuite-pa.clients6.google.com",
    "webchannel-alkalimakersuite-pa.clients6.google.com",
    "robinfrontend-pa.googleapis.com",
    "assistant-s3-pa.googleapis.com",
    "proactivebackend-pa.googleapis.com",
    "generativelanguage.googleapis.com",
];
pub(super) const PACKAGES: &[&str] = &[
    "com.google.android.apps.bard",
    "com.google.android.googlequicksearchbox",
    "com.google.android.gms",
    "com.android.vending",
];

static WORKER: OnceLock<mpsc::SyncSender<()>> = OnceLock::new();
static STATE: Mutex<(bool, Option<&'static str>)> = Mutex::new((false, None));

pub(super) fn path() -> PathBuf {
    PathBuf::from(super::ROOT).join("gemini-filter.hosts")
}

fn public_address(ip: Ipv4Addr) -> bool {
    let [a, b, c, _] = ip.octets();
    !ip.is_private()
        && !ip.is_loopback()
        && !ip.is_link_local()
        && !ip.is_documentation()
        && a != 0
        && a < 224
        && !(a == 100 && (64..=127).contains(&b))
        && !(a == 198 && (18..=19).contains(&b))
        && !(a == 192 && b == 0 && c == 0)
}

// Matches only the address rewrite format published by this list. Never
// import suffixes, regular expressions, or unrecognised destinations.
fn render_hosts(source: &str) -> Result<String> {
    let mut records = BTreeMap::<&str, BTreeSet<Ipv4Addr>>::new();
    for line in source.lines().map(str::trim) {
        let Some((domain, address)) = line
            .strip_prefix("||")
            .and_then(|rule| rule.split_once("^$dnsrewrite=NOERROR;A;"))
        else {
            continue;
        };
        if !DOMAINS.contains(&domain) {
            continue;
        }
        let ip: Ipv4Addr = address.parse().context("invalid Gemini address")?;
        if !public_address(ip) {
            bail!("non-public Gemini address");
        }
        records.entry(domain).or_default().insert(ip);
    }
    if !records.contains_key(CORE_DOMAIN) || records.len() < 2 {
        bail!("incomplete Gemini filter");
    }

    let mut hosts = String::new();
    for (domain, addresses) in records {
        for ip in addresses {
            hosts.push_str(&format!("{ip} {domain}\n"));
        }
    }
    Ok(hosts)
}

fn refresh() -> Result<()> {
    let mut response = reqwest::blocking::Client::builder()
        .https_only(true)
        .no_proxy()
        .redirect(reqwest::redirect::Policy::none())
        .timeout(Duration::from_secs(15))
        .build()?
        .get(SOURCE)
        .send()?
        .error_for_status()?;
    let mut source = String::new();
    response.by_ref().take(MAX_SOURCE + 1).read_to_string(&mut source)?;
    if source.len() as u64 > MAX_SOURCE {
        bail!("Gemini filter exceeds size limit");
    }
    let hosts = render_hosts(&source)?;
    super::write_atomic(&path(), hosts.as_bytes())
}

pub(super) fn ensure_cache() -> Result<()> {
    let file = path();
    if let Some(parent) = file.parent() {
        fs::create_dir_all(parent)?;
    }
    match fs::OpenOptions::new().write(true).create_new(true).open(file) {
        Ok(_) => Ok(()),
        Err(error) if error.kind() == std::io::ErrorKind::AlreadyExists => Ok(()),
        Err(error) => Err(error.into()),
    }
}

pub(super) fn cached_addresses(domain: &str) -> BTreeSet<Ipv4Addr> {
    if !DOMAINS.contains(&domain) {
        return BTreeSet::new();
    }
    fs::read_to_string(path()).unwrap_or_default().lines().filter_map(|line| {
        let (address, name) = line.split_once(' ')?;
        (name == domain).then(|| address.parse().ok()).flatten()
    }).collect()
}

pub(super) fn probe_hosts() -> BTreeMap<&'static str, BTreeSet<Ipv4Addr>> {
    [
        CORE_DOMAIN,
        "geminiweb-pa.clients6.google.com",
        "alkalimakersuite-pa.clients6.google.com",
        "robinfrontend-pa.googleapis.com",
    ].into_iter().map(|host| (host, cached_addresses(host))).collect()
}

pub(super) fn has_cached_rule(domain: &str) -> bool {
    !cached_addresses(domain).is_empty()
}

pub fn status() -> Value {
    let updated_at = fs::metadata(path())
        .and_then(|m| if m.len() == 0 { Err(std::io::ErrorKind::NotFound.into()) } else { m.modified() })
        .ok()
        .and_then(|t| t.duration_since(UNIX_EPOCH).ok())
        .map(|age| age.as_secs());
    let (updating, error) = *STATE.lock().unwrap_or_else(|e| e.into_inner());
    json!({"updating": updating, "last_error": error, "updated_at": updated_at})
}

fn enabled() -> bool {
    super::load(std::path::Path::new(super::CONFIG_PATH))
        .map(|document| document.profiles.values().any(|profile| profile.enabled && profile.google_filter))
        .unwrap_or(false)
}

pub fn request_refresh() -> Result<()> {
    if !enabled() {
        bail!("enable and save the Gemini DNS profile first");
    }
    match WORKER.get().context("Gemini filter worker unavailable")?.try_send(()) {
        Ok(()) | Err(mpsc::TrySendError::Full(())) => Ok(()),
        Err(e) => Err(e.into()),
    }
}

pub fn start_worker() {
    let (tx, rx) = mpsc::sync_channel(1);
    if WORKER.set(tx).is_err() {
        return;
    }
    std::thread::spawn(move || {
        let mut next: Option<Instant> = None;
        loop {
            let manual = match rx.recv_timeout(Duration::from_secs(20)) {
                Ok(()) => true,
                Err(mpsc::RecvTimeoutError::Timeout) => false,
                Err(mpsc::RecvTimeoutError::Disconnected) => break,
            };
            if !enabled() {
                next = None;
                continue;
            }
            if !manual && next.is_some_and(|due| Instant::now() < due) {
                continue;
            }
            *STATE.lock().unwrap_or_else(|e| e.into_inner()) = (true, None);
            let result = refresh();
            let error = if let Err(error) = result {
                log::warn!("Gemini DNS filter refresh failed: {error:#}");
                Some("Не удалось обновить список Gemini")
            } else {
                None
            };
            *STATE.lock().unwrap_or_else(|e| e.into_inner()) = (false, error);
            let delay = if error.is_some() { Duration::from_secs(300) } else { INTERVAL };
            next = Some(Instant::now() + delay);
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn restricts_rules_to_exact_gemini_names() {
        let source = "||gemini.google.com^$dnsrewrite=NOERROR;A;8.8.4.4\n
||gemini.google.com^$dnsrewrite=NOERROR;A;8.8.8.8\n
||gemini.google.com^$dnsrewrite=NOERROR;A;8.8.4.4\n
||robinfrontend-pa.googleapis.com^$dnsrewrite=NOERROR;A;9.9.9.9\n
||google.com^$dnsrewrite=NOERROR;A;1.1.1.1\n
||chatgpt.com^$dnsrewrite=NOERROR;A;1.1.1.1\n
||evilgemini.google.com^$dnsrewrite=NOERROR;A;1.1.1.1";
        assert_eq!(
            render_hosts(source).unwrap(),
            "8.8.4.4 gemini.google.com\n8.8.8.8 gemini.google.com\n9.9.9.9 robinfrontend-pa.googleapis.com\n"
        );
        assert!(render_hosts(&source.replace("9.9.9.9", "127.0.0.1")).is_err());
        assert!(render_hosts("||chatgpt.com^$dnsrewrite=NOERROR;A;1.1.1.1").is_err());
    }
}
