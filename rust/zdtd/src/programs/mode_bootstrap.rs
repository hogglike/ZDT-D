//! Resolve proxy endpoints before starting the root Android Go process.
//! Android has no DNS listener on ::1:53; never confuse it with netd's resolver.
use serde_json::{json, Value};
use std::{collections::BTreeMap, net::{IpAddr, Ipv4Addr}};

pub fn resolve_outbounds(outbounds: &mut [Value], mut resolve: impl FnMut(&str) -> Vec<String>) -> Vec<Value> {
    let mut cache = BTreeMap::<String, Option<String>>::new();
    let mut report = Vec::new();
    for outbound in outbounds {
        let Some(server) = outbound.get("server").and_then(Value::as_str).map(str::trim).map(str::to_owned) else { continue };
        if server.is_empty() || server.parse::<IpAddr>().is_ok() { continue; }
        let host = server.trim_end_matches('.').to_string();
        let ip = cache.entry(host.clone()).or_insert_with(|| resolve(&host).into_iter().find(|ip| ip.parse::<Ipv4Addr>().is_ok())).clone();
        if let Some(ip) = &ip {
            // TLS otherwise derives SNI from `server`; keep the hostname after
            // replacing only the dial address. Explicit Reality SNI stays intact.
            if let Some(tls) = outbound.get_mut("tls").filter(|v| v.is_object()) {
                if tls.get("server_name").and_then(Value::as_str).unwrap_or("").is_empty() {
                    tls["server_name"] = json!(host);
                }
            }
            outbound["server"] = json!(ip);
        }
        // A failed lookup remains a domain for the explicit core UDP resolver.
        // It must not permanently mark the node unsupported or block other nodes.
        report.push(json!({"key":outbound.get("tag"),"host":host,"ip":ip,
            "error":if ip.is_some(){""}else{"Android lookup failed; core UDP bootstrap will retry"}}));
    }
    report
}
