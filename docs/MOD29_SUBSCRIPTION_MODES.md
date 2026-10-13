# ZDT-D 4.2.0-mod29

This update extends the working mod28 subscription controller and Android UI.
DNS/netd profiles, packet rules, hotspot behavior, core rendering and TLS
verification are unchanged.

## Server selection

Normal and Browser can enable `auto_enabled` and keep an ordered `node_keys`
list, optional name filters and subscription filters. White keeps its existing
automatic behavior. With checks enabled, automatic modes use the same bounded
two-pass policy and remembered successful server. Checks disabled still apply
one candidate without guessing whether the server works.

`manual_override` pins `selected_key` in any mode. The widget server picker
offers Auto when the mode has multiple servers enabled. Selecting a manual
server does not remove candidates, filters or priority. Returning to Auto
clears only the override. Auto selection requires enabled site checks. Unknown
keys, unsupported servers and unavailable Auto are rejected before saving.

Missing fields default to false, so old single-server Normal/Browser settings
continue working; White remains automatic for old configurations.

## UI

- Sticky save button; detailed connection checks and latency options collapse.
- Two app policies: selected apps or blacklist. All / Except DNS / Clear remain
  selection buttons. Legacy all/except_dns policies are converted to explicit
  app selections on UI save, respecting shared UID exclusions.
- Successful measured servers are green and first (then by delay); unmeasured
  are neutral; failed are red below them; unsupported are gray and last. User
  candidate priority is independent of display sorting. Measurements carry time.
- Search subscriptions, candidates and widget picker servers. Subscription
  editor shows name, URL and auto-update first; advanced authentication/HWID
  fields stay configured when collapsed. Server import/config buttons collapse.
- Subscription cards show the next scheduled update when available.

## Validation and phone acceptance

build.yml Release on copilot-polish runs production settings serialization /
manual-to-auto regressions, the two-pass/manual policy, Android result ordering
tests and existing real TCP/UDP, DNS coexistence and VLESS/Reality tests. It
builds ARM64/ARMv7, APK unit tests and verifies its signature.

Install both mod29 APK and PLAIN module ZIP, reboot, then configure two servers
in Normal or Browser. Check auto fallback, widget manual selection outside the
list and return to Auto. Check both application policies, saved custom sites,
Gemini DNS, hotspot and Wi-Fi/mobile transitions.
