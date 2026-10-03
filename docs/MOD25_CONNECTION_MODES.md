# ZDT-D 4.2.0-mod25: subscription modes

Add a standard HTTPS subscription in **Apps → Subscriptions**, enable it, then
open **Режимы: Белый · Обычный · Браузер**. This builds on the working mod24
DNS/hotspot implementation; it does not replace DNS profiles or system tethering.

- **White**: explicit candidates in saved order, followed by enabled-subscription
  nodes matching any nonempty case-insensitive name fragment. Last successful
  candidate moves to the front. Exactly two full passes at most; two of three
  TLS-verified HTTPS checks must succeed. Failure becomes red and stops retries
  until a new manual activation. A successful active mode is checked every 60s;
  subscription or physical-network changes trigger a fresh selection.
- **Normal / Browser**: independent application policies and server selections;
  no automatic selection of another server. Browser discovery is a convenience;
  inspect and save the list before activation.
- App policies: selected, all eligible application UIDs, all except DNS-assigned
  UIDs, or blacklist complement. Shared UIDs cannot be separated. Root/system
  UIDs below 10000 and the controlling application are excluded. Blacklisted
  apps retain other existing ZDT-D assignments.
- Subscription refresh uses the existing scheduler/settings. Server identity is
  derived from subscription ID and connection definition, excluding label/tag;
  a label change alone preserves manual selection. Changed endpoint/credentials
  are a new server; white-mode name rules can pick it up after refresh.
- Add the **ZDT-D · 3 режима** home-screen widget, or the three **ZDT-D** mode
  tiles through the system shade editor. White = neutral, yellow = connecting,
  green = verified connected, red = error. Tile colours are controlled by Android.
  Activating White on an active Wi-Fi network requires confirmation in all three
  entry points. Cancelling leaves the active mode unchanged.
- Latency is measured through the TEST selector using the same HTTPS checks,
  not ICMP. The separate MODE selector keeps the current traffic server while
  TEST scans. No direct fallback is included in the core. TLS checks reject
  untrusted website certificates and redirects do not count as successful checks.
- Turn off the mode from its settings screen. Global ZDT-D Stop also cancels
  in-flight operations, stops its own core, and removes only mode-owned rules.

## Compatibility and scope

Uses the existing sing-box renderer (VLESS/VMess/Trojan supported transports,
Shadowsocks/SOCKS, Hysteria2). Unsupported transports including XHTTP and this
core's legacy WireGuard outbound are explicitly unavailable. HAPP-specific
encrypted subscriptions, HWID restrictions and unsupported transports require
separate compatibility work. No provider link or credentials are exposed in the
mode catalog or Android preference cache; generated core/config files are 0600.

Selected IPv4 TCP/UDP application traffic uses scoped TPROXY and dedicated ports
19972–19974. UDP/TCP 53, loopback and existing LAN bypasses remain outside it.
DNS high-port listeners and mod24 DNAT rules remain unchanged. Android chooses
hotspot upstream; these modes do not add VPN sharing to connected hotspot clients.
TPROXY unsupported on a device produces an error, never a silent TCP-only mode.

## Validation and device acceptance

CI runs bounded-two-pass/name/app-policy tests, widget-state unit tests, real
local TLS-over-SOCKS tests of the production core template (bad proxy cannot
fall back to direct; TEST does not switch MODE; controller authentication and
TLS trust enforced), plus mod24 live UDP/TCP DNS/port-53 coexistence regressions.
Android arm32/arm64 compilation and release APK tests use build.yml Release.

Actual subscription, OEM routing and switching need phone acceptance:

1. Save one known working Normal server/app; connect and open the app.
2. Ping servers; the selected traffic server must remain unchanged.
3. White: choose two unavailable servers and clear name fragments. Confirm
   exactly four attempts / two rounds, red error, no further retries after 60s.
4. Add one working candidate: green only after successful HTTPS checks.
5. On Wi-Fi, cancel White confirmation; old mode must remain active.
6. Verify selected / except-DNS / blacklist app behavior; verify assigned DNS.
7. Switch Wi-Fi/mobile; verify refresh, then switch modes rapidly and turn off.
   A cancelled operation must not install rules or turn a button green later.
8. Enable the system hotspot with DNS and mode enabled; check phone and client
   separately. Only the phone's selected apps are routed by these modes.
