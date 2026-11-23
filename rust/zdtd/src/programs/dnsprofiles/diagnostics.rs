use super::{
    build_plan, build_singbox_config, dns_query_for, global_dnscrypt_enabled, parse_endpoint,
    pid_matches_plan, pid_matches_tun2socks, private_dns_mode, read_pid, ProfileDocument,
    RuntimePlan, ROOT, SINGBOX_BIN,
};
use anyhow::{bail, Context, Result};
use serde_json::{json, Value};
use std::{
    fs::{self, DirBuilder, File, OpenOptions},
    io::{self, Read, Seek, SeekFrom},
    net::{Ipv4Addr, SocketAddrV4, TcpListener, TcpStream, UdpSocket},
    os::unix::fs::DirBuilderExt,
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::Mutex,
    thread,
    time::{Duration, Instant, SystemTime, UNIX_EPOCH},
};

const DEFAULT_DOMAIN: &str = "example.com";
const LOCAL_TIMEOUT: Duration = Duration::from_secs(3);
static LATEST: Mutex<Option<Job>> = Mutex::new(None);

struct Job {
    profile: String,
    ticket: u64,
    result: Option<Value>,
}

pub fn start(profile: &str, domain: Option<&str>, document: ProfileDocument) -> Result<Value> {
    let domain = domain
        .unwrap_or(DEFAULT_DOMAIN)
        .trim()
        .trim_end_matches('.');
    if !valid_domain(domain) {
        bail!("invalid_probe_domain");
    }
    let settings = document
        .profiles
        .get(profile)
        .context("unknown_dns_profile")?;
    let names = document.profiles.keys().cloned().collect::<Vec<_>>();
    let plan = build_plan(profile, settings, &names)?;

    let mut latest = LATEST.lock().unwrap_or_else(|e| e.into_inner());
    if latest.as_ref().is_some_and(|job| job.result.is_none()) {
        return Ok(json!({"state": "busy"}));
    }
    // Keep the ticket below JavaScript's exact-integer limit for API consumers.
    let ticket = SystemTime::now().duration_since(UNIX_EPOCH)?.as_millis() as u64;
    let profile = profile.to_owned();
    let domain = domain.to_ascii_lowercase();
    let previous = latest.replace(Job {
        profile,
        ticket,
        result: None,
    });
    let suspended = document.suspend_for_tethering;
    if let Err(error) = thread::Builder::new()
        .name("dns-profile-check".into())
        .spawn(move || {
            let result = check(&plan, &domain, document.revision, suspended)
                .unwrap_or_else(|_| json!({"state": "error", "reason": "check_failed"}));
            if let Some(job) = LATEST.lock().unwrap_or_else(|e| e.into_inner()).as_mut() {
                job.result = Some(result);
            }
        })
    {
        *latest = previous;
        return Err(error.into());
    }
    Ok(json!({"state": "running", "ticket": ticket}))
}

pub fn result(profile: &str, ticket: u64) -> Value {
    let latest = LATEST.lock().unwrap_or_else(|e| e.into_inner());
    match latest
        .as_ref()
        .filter(|job| job.profile == profile && job.ticket == ticket)
    {
        Some(job) => job
            .result
            .clone()
            .unwrap_or_else(|| json!({"state": "running"})),
        None => json!({"state": "missing"}),
    }
}

fn valid_domain(name: &str) -> bool {
    name.len() <= 253
        && name.contains('.')
        && name.split('.').all(|part| {
            !part.is_empty()
                && part.len() <= 63
                && !part.starts_with('-')
                && !part.ends_with('-')
                && part.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-')
        })
}

fn parse_dns_answer(message: &[u8], id: u16) -> Value {
    if message.len() < 12 || u16::from_be_bytes([message[0], message[1]]) != id {
        return json!({"state": "invalid_response"});
    }
    let flags = u16::from_be_bytes([message[2], message[3]]);
    if flags & 0x8000 == 0 {
        return json!({"state": "invalid_response"});
    }
    let rcode = flags & 0x000f;
    let answers = u16::from_be_bytes([message[6], message[7]]);
    let state = match (flags & 0x0200, rcode, answers) {
        (0, 0, 1..) => "ok",
        (0, 0, 0) => "no_records",
        (0, 3, _) => "nxdomain",
        (0, _, _) => "dns_error",
        _ => "truncated",
    };
    json!({"state": state, "rcode": rcode, "answers": answers})
}

fn elapsed_ms(start: Instant) -> u64 {
    start.elapsed().as_millis() as u64
}

fn udp_probe(server: SocketAddrV4, domain: &str, timeout: Duration) -> Value {
    let start = Instant::now();
    let probe = || -> io::Result<Value> {
        let socket = UdpSocket::bind((Ipv4Addr::UNSPECIFIED, 0))?;
        socket.set_read_timeout(Some(timeout))?;
        socket.set_write_timeout(Some(timeout))?;
        socket.connect(server)?;
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .subsec_nanos();
        let id = (nanos ^ (nanos >> 16)) as u16;
        socket.send(&dns_query_for(id, domain))?;
        let mut message = [0u8; 4096];
        let size = socket.recv(&mut message)?;
        Ok(parse_dns_answer(&message[..size], id))
    };
    let mut result = match probe() {
        Ok(result) => result,
        Err(error)
            if matches!(
                error.kind(),
                io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut
            ) =>
        {
            json!({"state": "timeout"})
        }
        Err(error) => json!({"state": "socket_error", "os_error": error.raw_os_error()}),
    };
    result["elapsed_ms"] = json!(elapsed_ms(start));
    result
}

struct ProbeDir(PathBuf);

impl ProbeDir {
    fn create() -> io::Result<Self> {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        let path = Path::new(ROOT).join(format!(".probe-{}-{nonce}", std::process::id()));
        DirBuilder::new().mode(0o700).create(&path)?;
        Ok(Self(path))
    }
}

impl Drop for ProbeDir {
    fn drop(&mut self) {
        let _ = fs::remove_dir_all(&self.0);
    }
}

struct ProbeChild(Child);

impl Drop for ProbeChild {
    fn drop(&mut self) {
        let _ = self.0.kill();
        let _ = self.0.wait();
    }
}

fn error_events(path: &Path) -> Vec<&'static str> {
    let read_tail = || -> io::Result<String> {
        let mut file = File::open(path)?;
        file.seek(SeekFrom::Start(
            file.metadata()?.len().saturating_sub(16_384),
        ))?;
        let mut bytes = Vec::new();
        file.take(16_384).read_to_end(&mut bytes)?;
        Ok(String::from_utf8_lossy(&bytes).into_owned())
    };
    let mut events = Vec::new();
    for line in read_tail().unwrap_or_default().lines() {
        if !line.contains("ERROR") && !line.contains("FATAL") {
            continue;
        }
        let lower = line.to_ascii_lowercase();
        let category = if lower.contains("x509") || lower.contains("certificate") {
            "tls_certificate"
        } else if lower.contains("tls:") || lower.contains("handshake") {
            "tls_handshake"
        } else if lower.contains("connection refused") {
            "connection_refused"
        } else if lower.contains("network is unreachable") || lower.contains("no route to host") {
            "network_unreachable"
        } else if lower.contains("deadline exceeded") || lower.contains("timeout") {
            "timeout"
        } else if lower.contains("status code") || lower.contains("http status") {
            "http_error"
        } else if lower.contains("address already in use") {
            "listener_in_use"
        } else if line.contains("FATAL") {
            "sing_box_start_error"
        } else {
            "sing_box_error"
        };
        if !events.contains(&category) {
            events.push(category);
        }
    }
    events
}

fn isolated_probe(plan: &RuntimePlan, domain: &str) -> Result<Value> {
    let temp = ProbeDir::create()?;
    let config = temp.0.join("config.json");
    let log = temp.0.join("sing-box.log");
    let local = Ipv4Addr::LOCALHOST;
    let tcp = TcpListener::bind((local, 0))?;
    let port = tcp.local_addr()?.port();
    let udp = UdpSocket::bind((local, port))?;

    // Same encrypted resolver and bootstrap as the profile. A temporary
    // loopback listener keeps the test independent of Android netd and TUN.
    let mut settings = build_singbox_config(plan)?;
    let mut inbound = settings["inbounds"][1].clone();
    inbound["listen"] = json!(local);
    inbound["listen_port"] = json!(port);
    settings["inbounds"] = json!([inbound]);
    settings["log"]["level"] = json!("error");
    fs::write(&config, serde_json::to_vec(&settings)?)?;
    let output = OpenOptions::new().write(true).create_new(true).open(&log)?;
    let stderr = output.try_clone()?;
    drop((tcp, udp));
    let mut child = ProbeChild(
        Command::new(SINGBOX_BIN)
            .args(["run", "-c"])
            .arg(&config)
            .stdin(Stdio::null())
            .stdout(Stdio::from(output))
            .stderr(Stdio::from(stderr))
            .spawn()?,
    );

    let address = SocketAddrV4::new(local, port);
    let start = Instant::now();
    while start.elapsed() < Duration::from_secs(2) {
        if child.0.try_wait()?.is_some() {
            return Ok(json!({"state": "start_failed", "events": error_events(&log)}));
        }
        if TcpStream::connect_timeout(&address.into(), Duration::from_millis(100)).is_ok() {
            let timeout =
                Duration::from_millis(u64::from(plan.profile.timeout_ms.clamp(1_500, 8_000)));
            let mut result = udp_probe(address, domain, timeout);
            result["events"] = json!(error_events(&log));
            return Ok(result);
        }
        thread::sleep(Duration::from_millis(40));
    }
    Ok(json!({"state": "start_timeout", "events": error_events(&log)}))
}

fn uid_in_ranges(uid: u32, ranges: &[String]) -> bool {
    ranges.iter().any(|range| match range.split_once('-') {
        Some((start, end)) => start
            .parse::<u32>()
            .ok()
            .zip(end.parse::<u32>().ok())
            .is_some_and(|(start, end)| (start..=end).contains(&uid)),
        None => range.parse::<u32>() == Ok(uid),
    })
}

fn check(plan: &RuntimePlan, domain: &str, revision: u64, suspended: bool) -> Result<Value> {
    use crate::{
        android::pkg_uid::{self, Mode},
        shell::{self, Capture},
        vpn_netd,
    };

    let url = parse_endpoint(&plan.profile.endpoint)?;
    let host = url.host_str().context("dns_endpoint_host_missing")?;
    let hostname = host.trim_matches(['[', ']']).trim_end_matches('.');
    let protocol = if url.scheme() == "tls" { "DoT" } else { "DoH" };
    let port = url
        .port()
        .unwrap_or(if protocol == "DoT" { 853 } else { 443 });

    let sing_box = read_pid(&plan.pid).is_some_and(|pid| pid_matches_plan(pid, plan));
    let tun2socks =
        read_pid(&plan.tun2socks_pid).is_some_and(|pid| pid_matches_tun2socks(pid, plan));
    let active_config = fs::read(&plan.config)
        .ok()
        .and_then(|bytes| serde_json::from_slice::<Value>(&bytes).ok());
    let config_matches = sing_box && active_config.as_ref() == Some(&build_singbox_config(plan)?);
    let snapshot = vpn_netd::read_applied_snapshot();
    let netd = snapshot.as_ref().ok().and_then(|snapshot| {
        snapshot.profiles.iter().find(|p| {
            p.owner_program == "dnsprofiles" && p.profile == plan.id && p.netid == plan.netid
        })
    });
    let uids = pkg_uid::resolve_uid_map(Mode::Default, &plan.profile.apps);
    let apps = plan
        .profile
        .apps
        .iter()
        .map(|package| {
            let uid = uids.as_ref().ok().and_then(|map| map.get(package));
            let applied = uid
                .zip(netd)
                .is_some_and(|(uid, p)| uid_in_ranges(*uid, &p.uid_ranges));
            json!({"package": package, "uid": uid, "applied": applied})
        })
        .collect::<Vec<_>>();
    let route = shell::run_timeout(
        "ip",
        &["-4", "route", "show", "table", "all"],
        Capture::Stdout,
        LOCAL_TIMEOUT,
    )
    .ok()
    .filter(|(code, _)| *code == 0)
    .map(|(_, out)| {
        out.lines().any(|line| {
            line.starts_with("default ")
                && line
                    .split_whitespace()
                    .collect::<Vec<_>>()
                    .windows(2)
                    .any(|pair| pair == ["dev", plan.tun.as_str()])
        })
    });

    let address: Ipv4Addr = plan.dns.parse()?;
    let (bootstrap, upstream, listener, redirect) = thread::scope(|scope| {
        let bootstrap = scope.spawn(|| {
            if hostname.parse::<std::net::IpAddr>().is_ok() {
                return json!({"state": "not_needed"});
            }
            let server = plan.profile.bootstrap[0];
            let mut result = udp_probe(SocketAddrV4::new(server, 53), hostname, LOCAL_TIMEOUT);
            result["server"] = json!(server);
            result
        });
        let upstream = scope.spawn(|| {
            isolated_probe(plan, domain).unwrap_or_else(|_| json!({"state": "start_failed"}))
        });
        let listener = scope.spawn(|| {
            if !sing_box {
                return json!({"state": "not_running"});
            }
            udp_probe(
                SocketAddrV4::new(address, plan.dns_listen_port),
                domain,
                LOCAL_TIMEOUT,
            )
        });
        let redirect = scope.spawn(|| {
            if !sing_box {
                return json!({"state": "not_running"});
            }
            udp_probe(SocketAddrV4::new(address, 53), domain, LOCAL_TIMEOUT)
        });
        let resolve = |worker: thread::ScopedJoinHandle<'_, Value>| {
            worker
                .join()
                .unwrap_or_else(|_| json!({"state": "check_failed"}))
        };
        (
            resolve(bootstrap),
            resolve(upstream),
            resolve(listener),
            resolve(redirect),
        )
    });

    Ok(json!({
        "schema": 1, "state": "done", "profile": plan.id, "revision": revision,
        "domain": domain, "protocol": protocol, "endpoint_host": host, "endpoint_port": port,
        "enabled": plan.profile.enabled, "suspended_for_tethering": suspended,
        "global_dnscrypt_enabled": global_dnscrypt_enabled(), "android_private_dns": private_dns_mode(),
        "bootstrap": bootstrap, "upstream": upstream, "listener": listener, "redirect": redirect,
        "gemini_filter": plan.profile.google_filter.then(|| {
            let mut filter = super::google_filter::status();
            filter["matches_domain"] = json!(super::google_filter::has_cached_rule(domain));
            filter
        }),
        "runtime": {
            "sing_box": sing_box, "tun2socks": tun2socks, "netd": netd.is_some(),
            "netd_snapshot_available": snapshot.is_ok(), "uid_lookup_ok": uids.is_ok(),
            "default_route": route, "config_matches": config_matches, "apps": apps,
            "events": error_events(&plan.log), "tun_events": error_events(&plan.tun2socks_log),
        },
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn probe_rejects_bad_names_and_unrelated_packets() {
        assert!(valid_domain("gemini.google.com"));
        for name in [
            "",
            "example",
            "https://example.com",
            "x..com",
            "-x.com",
            "x y.com",
        ] {
            assert!(!valid_domain(name), "{name}");
        }
        let response = [0x42, 0x42, 0x81, 0x80, 0, 1, 0, 2, 0, 0, 0, 0];
        assert_eq!(parse_dns_answer(&response, 0x4242)["state"], "ok");
        assert_eq!(
            parse_dns_answer(&response, 0x4243)["state"],
            "invalid_response"
        );
        assert_eq!(
            parse_dns_answer(&response[..8], 0x4242)["state"],
            "invalid_response"
        );
        let mut response = response;
        response[3] = 0x83;
        assert_eq!(parse_dns_answer(&response, 0x4242)["state"], "nxdomain");
    }

    #[test]
    fn uid_matching_uses_netd_range_boundaries() {
        let ranges = ["42".to_string(), "100-105".to_string()];
        for uid in [42, 100, 103, 105] {
            assert!(uid_in_ranges(uid, &ranges));
        }
        for uid in [41, 43, 99, 106] {
            assert!(!uid_in_ranges(uid, &ranges));
        }
    }
}
