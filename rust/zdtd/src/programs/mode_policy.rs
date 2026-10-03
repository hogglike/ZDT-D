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

#[cfg(test)]
mod tests {
    use super::*;
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
