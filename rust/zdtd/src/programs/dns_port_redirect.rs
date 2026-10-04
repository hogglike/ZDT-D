//! Rules are limited to this module's synthetic IPv4 DNS addresses.
//! Keeping listeners off port 53 lets Android start its wildcard dnsmasq.
pub const LISTEN_PORT_BASE: u16 = 19600;
pub const PROFILE_COUNT: u32 = 16;
pub const DNS_NET_BASE: u32 = 0x0AFD_F000;

pub fn rule_args(operation: &str, dns: &str, port: u16, protocol: &str) -> Vec<String> {
    let destination = format!("{dns}:{port}");
    ["-w", "2", "-t", "nat", operation, "OUTPUT", "-d", dns,
        "-p", protocol, "--dport", "53", "-j", "DNAT", "--to-destination", &destination]
        .iter().map(|s| s.to_string()).collect()
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn translation_is_limited_to_profile_address_and_dns_port() {
        let args = rule_args("-I", "10.253.240.1", LISTEN_PORT_BASE, "udp");
        assert!(args.windows(2).any(|p| p == ["-d", "10.253.240.1"]));
        assert!(args.windows(2).any(|p| p == ["--dport", "53"]));
        assert!(args.windows(2).any(|p| p == ["--to-destination", "10.253.240.1:19600"]));
        assert!(!args.iter().any(|a| a == "PREROUTING" || a == "FORWARD" || a == "-F"));
    }
    #[test]
    fn removal_matches_the_installed_rule() {
        let mut installed = rule_args("-I", "10.253.240.61", 19615, "tcp");
        installed[4] = "-D".into();
        assert_eq!(installed, rule_args("-D", "10.253.240.61", 19615, "tcp"));
    }
}
