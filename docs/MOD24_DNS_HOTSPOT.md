# ZDT-D 4.2.0-mod24: DNS profiles and system hotspot

On OnePlus CPH2691 / Android 16 the supplied complete mod23 report records five
system hotspot activation attempts. Each creates wlan2, then fails to set DNS
forwarders with Remote I/O error 121, reports interface error 5 and tears down
the hotspot. The user confirmed that suspending DNS profiles and restarting
ZDT-D makes the system hotspot work under the same conditions.

This confirms involvement of the DNS profile runtime, but the report does not
contain the underlying dnsmasq exit reason. The previous runtime bound both
UDP and TCP port 53 on a TUN address. A specific TCP listener blocks a subsequent
wildcard listener on that port. Android's tethering DNS proxy uses dnsmasq.

## Change

- Preserve profile addresses, TUN/tun2socks, per-UID netd networks, DNS resolver
  configuration and the full-route policy required by OxygenOS.
- Move sing-box profile DNS listeners to ports 19600..19615.
- Translate only locally generated IPv4 UDP/TCP traffic to the exact profile
  DNS address, port 53, to that same address's high port using OUTPUT DNAT.
- Match the DNS inbound tag when applying sing-box hijack-dns, since its actual
  listening port is now high. Retain the existing port 53 routing rule.
- Check installation before the real DoH readiness probe. Startup errors remove
  the exact profile rules. Restart, suspension and stop remove all known owned
  rules, including rules belonging to deleted profiles.
- Do not flush foreign chains, change PREROUTING/FORWARD, alter ip_forward,
  rewrite system DNS, restart netd or kill system dnsmasq.
- Keep the manual suspension switch as a fallback.

## Verification

`test_dns_port_redirect.py` compiles the actual Rust argument builder and runs
its unit tests. In a disposable Linux network namespace it reproduces the old
UDP/TCP bind collision, verifies high-port listeners can coexist with a wildcard
port 53 server, sends real UDP and TCP DNS requests to two distinct profiles,
checks unrelated DNS delivery, and verifies cleanup preserves a foreign rule.
The test is required by build.yml before release build jobs start.

This host test checks kernel socket binding and packet translation. It does
not emulate OxygenOS, Android netd, the cellular modem or the public DoH service.
The real phone must still confirm hotspot activation, client internet access,
and selected applications' DNS with suspension disabled.

## Installation check

Install the matching 4.2.0-mod24 APK and its module update. Disable the manual
DNS suspension switch and restart ZDT-D (a reboot after module update also
clears existing connections). Run the extended DNS test, enable the system
hotspot, connect a second device and verify internet access. Open selected AI
apps on the phone and run the DNS test again. Record another hotspot report if
any step fails. Version mod23 remains the known fallback with DNS suspension.
