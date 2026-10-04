# ZDT-D 4.2.0-mod27

The phone's mod26 log showed the root sing-box process trying to resolve a
subscription server hostname through `[::1]:53`, where no DNS listener exists.
Both mode traffic and the independent latency test therefore failed before
connecting to the VLESS/Reality server. One node using an IP address did pass.

Mode startup now resolves endpoint domains with the existing stock
`android_dns::resolve_ipv4_all` helper and caches repeated names within that
startup. It changes only the runtime dial address; UUID, Vision flow, Reality
keys and explicit SNI stay intact. An implicit TLS SNI is preserved as the
original hostname. Subscription definitions and stable node keys remain intact.

If Android lookup cannot resolve a node, it stays available for retry through
the core's explicit UDP bootstrap at 1.1.1.1, instead of the nonexistent local
listener. This is only endpoint bootstrap; app DNS profiles are unchanged.
An unavailable bootstrap resolver can still prevent a domain node connecting;
it does not imply a working server when site checks are disabled.

Network changes invalidate the runtime core so endpoint IPs are refreshed.
The copied diagnostics include a credential-free bootstrap host/IP report.
All existing DNS/netd, hotspot, app selection and TPROXY rules are unchanged.

Validation adds production bootstrap tests for caching, failed-node retry and
SNI/Reality preservation. The real-packet core fixture now uses SOCKS and
VLESS/Reality servers addressed by domain and requires actual UDP DNS replies
before HTTPS traffic succeeds. Dead nodes, wrong Reality keys, untrusted TLS
and TEST/MODE isolation remain covered. Phone acceptance still requires
installing both mod27 APK and module ZIP, rebooting, testing the same subscription
node, and repeating on Wi-Fi/mobile. No live subscription credentials were used.
