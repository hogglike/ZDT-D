# ZDT-D 4.2.0-mod28

## Report and scope

mod27 connects selected browsers, but the phone reports repeated reconnects
with site checks disabled and disruption of Gemini's existing DNS profile.
The supplied log contains an endpoint TCP timeout, not the old absent ::1 DNS.

## Fixes

- Compare sorted physical interface/address identities instead of raw `ip addr`
  text. DHCP valid/preferred lifetimes and output ordering no longer invalidate
  the mode core. A failed address command does not look like a network change.
- Keep disabled site checks disabled during periodic maintenance. Rebuild only
  for actual address changes, updated subscription definitions or a stopped core.
  Diagnostics identify the last reconnect reason and current network identity.
- Restrict the shared socket DIVERT hook to marked loopback packets and
  transparent sockets. The former unrestricted TCP socket hook could mark
  normal incoming backend/DNS/DoH traffic; it is removed on apply/cleanup.
- Include existing DNS profile process/netd status in copied diagnostics.
  This does not run probes, restart DNS profiles or alter their configuration.

Per-app DNS/netd TUNs, high-port DNS listeners, port-53 DNAT rules and hotspot
behavior are retained. This is an incremental mode routing fix, not a DNS rewrite.

## Validation

The existing build.yml Release validation includes Rust policy regressions for
DHCP lifetime changes, address changes and disabled checks, plus real TCP/UDP
packets in a disposable Linux network namespace. The latter compiles production
MARK/TPROXY rule builders, checks transparent TCP diversion and verifies that
two DNS profiles and wildcard system DNS keep replying while a mode is active
and after its cleanup. Existing UDP endpoint bootstrap, HTTPS certificate and
VLESS/Reality/Vision tests remain required.

The OxygenOS/Gemini result still requires phone acceptance: install both mod28
APK and module, reboot, keep checks disabled, run Browser for several minutes,
then use Gemini and test hotspot. If there is another failure, copied diagnostics
now distinguish reconnection triggers and DNS process/netd state.
