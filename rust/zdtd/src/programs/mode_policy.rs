//! Pure connection-mode policy; also tested directly with rustc in CI.
pub fn matches_name(name: &str, filters: &[String]) -> bool {
    let name = name.to_lowercase();
    filters.iter().any(|filter| {
        let filter = filter.trim().to_lowercase();
        !filter.is_empty() && name.contains(&filter)
    })
}

/// Exactly two complete passes; a remembered candidate moves to the front,
/// but no candidate is dropped and duplicate selections cannot multiply tries.
pub fn attempts(candidates: &[String], last: &str) -> Vec<String> {
    let mut ordered = Vec::new();
    if candidates.iter().any(|v| v == last) { ordered.push(last.to_owned()); }
    for key in candidates {
        if !ordered.contains(key) { ordered.push(key.clone()); }
    }
    ordered.iter().chain(ordered.iter()).cloned().collect()
}

/// Manual selection pins one server without destroying the saved auto list.
pub fn automatic(mode: &str, enabled: bool, manual_override: bool) -> bool {
    (mode == "white" || enabled) && !manual_override
}
pub fn connection_attempts(keys: &[String], last: &str, automatic: bool, checks: bool) -> Vec<String> {
    if automatic && checks { attempts(keys, last) }
    else if automatic { attempts(keys, last).into_iter().take(1).collect() }
    else { keys.iter().take(1).cloned().collect() }
}

pub fn include_app(policy: &str, selected: bool, is_dns: bool) -> bool {
    match policy {
        "all" => true,
        "except_dns" => !is_dns,
        "blacklist" => !selected,
        _ => selected,
    }
}

/// A NAT ACCEPT ends this table, preserving the original TPROXY destination.
/// Matching only the unique mode mark leaves DNS and foreign scopes alone.
pub fn nat_bypass_args(operation:&str, chain:&str, mark:&str) -> Vec<String> {
    ["-t", "nat", operation, chain, "-m", "mark", "--mark", mark, "-j", "ACCEPT"]
        .iter().map(|s|s.to_string()).collect()
}

/// Only packets already routed back through loopback belong to our TPROXY
/// socket diversion. Ordinary DNS/DoH and other backend sockets must not match.
pub fn divert_match_args(mark:&str, chain:&str) -> Vec<String> {
    ["-i", "lo", "-m", "mark", "--mark", mark, "-p", "tcp", "-m", "socket", "--transparent", "-j", chain]
        .iter().map(|s|s.to_string()).collect()
}

/// DHCP lifetimes, flags and listing order are not network changes.
pub fn physical_network_identity(addresses:&str) -> String {
    let mut identities=std::collections::BTreeSet::new();
    for line in addresses.lines() {
        let fields:Vec<&str>=line.split_whitespace().collect();
        if fields.len()<4 || fields[2]!="inet" {continue;}
        let iface=fields[1].split('@').next().unwrap_or("");
        if iface=="wlan0" || ["rmnet", "ccmni", "wwan"].iter().any(|prefix|iface.starts_with(prefix)) {
            identities.insert(format!("{iface} {}",fields[3]));
        }
    }
    identities.into_iter().collect::<Vec<_>>().join("\n")
}

pub fn refresh_reason(catalog_changed:bool, network_changed:bool, core_alive:bool, check_enabled:bool, probe_ok:bool) -> Option<&'static str> {
    if !core_alive {Some("core_stopped")}
    else if catalog_changed {Some("subscription_changed")}
    else if network_changed {Some("network_changed")}
    else if check_enabled && !probe_ok {Some("site_check_failed")}
    else {None}
}

/// Persist attempts as well as completions: a failed refresh must not retry
/// every daemon tick. A clock correction permits one fresh attempt.
pub fn maintenance_due(enabled:bool, minutes:u64, last_attempt:u64, now:u64) -> bool {
    enabled && (15..=10080).contains(&minutes)
        && (last_attempt==0 || now<last_attempt || now-last_attempt>=minutes*60)
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test] fn refresh_schedule_is_optional_bounded_and_does_not_spin_on_failure() {
        assert!(!maintenance_due(false,60,0,4000));
        assert!(maintenance_due(true,60,0,4000));
        assert!(!maintenance_due(true,60,4000,7599));
        assert!(maintenance_due(true,60,4000,7600));
        assert!(maintenance_due(true,60,4000,3000));
        assert!(!maintenance_due(true,0,0,4000));
        assert!(!maintenance_due(true,10081,0,4000));
        assert!(maintenance_due(true,15,4000,4900));
    }
    #[test] fn manual_override_and_auto_return_keep_the_same_candidate_list() {
        let keys=vec!["lte1".into(), "lte2".into()];
        for mode in ["normal", "browser", "white"] {
            assert!(automatic(mode,true,false));
            assert!(!automatic(mode,true,true));
            assert_eq!(connection_attempts(&keys,"lte2",automatic(mode,true,false),true),["lte2","lte1","lte2","lte1"]);
            assert_eq!(connection_attempts(&keys,"lte2",automatic(mode,true,true),true),["lte1"]);
            assert_eq!(connection_attempts(&keys,"lte2",true,false),["lte2"]);
        }
        assert!(!automatic("normal",false,false));
        assert!(automatic("white",false,false)); // mod28 configuration migration
    }
    #[test] fn two_rounds_preserve_order_and_bound_attempts() {
        let v = vec!["lte1".into(), "lte2".into(), "auto".into(), "lte1".into()];
        assert_eq!(attempts(&v, "lte2"), ["lte2", "lte1", "auto", "lte2", "lte1", "auto"]);
        assert!(attempts(&[], "deleted").is_empty());
        assert_eq!(attempts(&v, "deleted").len(), 6);
    }
    #[test] fn dynamic_names_accept_renames_and_new_nodes_without_matching_everything() {
        let filters = vec![" LTE ".into()];
        assert!(matches_name("🇷🇺 LTE AUTO updated", &filters));
        assert!(matches_name("lte 3", &filters));
        assert!(!matches_name("regular 3", &filters));
        assert!(!matches_name("anything", &[" ".into()]));
    }
    #[test] fn app_lists_are_independent_of_dns_and_blacklist_is_complement() {
        assert!(include_app("all", false, true));
        assert!(!include_app("except_dns", true, true));
        assert!(include_app("except_dns", false, false));
        assert!(!include_app("blacklist", true, false));
        assert!(include_app("blacklist", false, false));
        assert!(!include_app("selected", false, false));
    }
    #[test] fn network_identity_ignores_lifetimes_flags_order_and_virtual_dns() {
        let before="12: wlan0 inet 192.168.1.20/24 brd 192.168.1.255 scope global dynamic wlan0\\ valid_lft 3600sec preferred_lft 3600sec\n14: rmnet_data0 inet 100.64.1.2/30 scope global rmnet_data0\\ valid_lft forever preferred_lft forever";
        let after="90: zdt_dns0 inet 10.253.240.1/30 scope global zdt_dns0\n14: rmnet_data0 inet 100.64.1.2/30 scope global rmnet_data0\n12: wlan0 inet 192.168.1.20/24 scope global secondary dynamic wlan0\\ valid_lft 3540sec preferred_lft 3540sec";
        assert_eq!(physical_network_identity(before),physical_network_identity(after));
        assert_ne!(physical_network_identity(before),physical_network_identity("12: wlan0 inet 192.168.1.21/24 scope global wlan0"));
        assert_ne!(physical_network_identity(before),physical_network_identity("14: rmnet_data0 inet 100.64.1.2/30 scope global rmnet_data0"));
        assert_eq!(physical_network_identity("bad line"),"");
    }
    #[test] fn disabled_checks_never_trigger_a_site_retry_but_real_changes_do() {
        assert_eq!(refresh_reason(false,false,true,false,false),None);
        assert_eq!(refresh_reason(false,false,true,true,false),Some("site_check_failed"));
        assert_eq!(refresh_reason(false,false,true,true,true),None);
        assert_eq!(refresh_reason(false,true,true,false,false),Some("network_changed"));
        assert_eq!(refresh_reason(true,false,true,false,false),Some("subscription_changed"));
        assert_eq!(refresh_reason(false,false,false,false,false),Some("core_stopped"));
    }
}

/// Optional checks do not veto manual application; latency uses its own URL.
pub fn checks_pass(enabled:bool,successes:usize,required:usize)->bool { !enabled || (required>0 && successes>=required) }
pub fn http_ok(code:u16,expected:u16)->bool {if expected==0 {(200..400).contains(&code)}else{code==expected}}
#[cfg(test)] mod check_tests {
 use super::*;
 #[test] fn optional_checks_and_threshold_are_independent() {
  assert!(checks_pass(false,0,2));assert!(!checks_pass(true,1,2));assert!(checks_pass(true,1,1));assert!(!checks_pass(true,3,0));
  assert!(http_ok(301,0));assert!(!http_ok(403,0));assert!(http_ok(403,403));assert!(!http_ok(200,204));
 }
}
