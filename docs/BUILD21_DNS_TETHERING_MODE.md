# Build 21 candidate — working app routing plus tethering mode

## Why Build 20 was changed

Build 20 passed the static DNS/hotspot smoke test, but Gemini, Grok and ChatGPT
could not access the network. On this OxygenOS device, a UID attached to a netd
VPN network without a default route did not reliably fall through to Android's
normal network. The selected app was therefore bound to a route-less network.

## Runtime policy

- DNS listeners remain on each `zdt_dnsN` TUN address; the old loopback alias is
  not restored.
- DNS profiles again use a secure netd VPN network, `0.0.0.0/0`, and the existing
  selected-UID IPv6 block, matching the working Build 15 behavior.
- The Build 20 `ip_forward` ownership fix remains intact.
- `suspend_for_tethering` prevents every DNS profile from starting while keeping
  all profile settings, enabled flags and app assignments.
- Changes remain restart-applied: switch tethering mode, stop/start ZDT-D, then
  enable the Android hotspot.

## Diagnostics

The DNS Profiles screen “Тест” button sends a real UDP DNS query for
`example.com` to every running profile-local listener. It also reports resolved
UIDs, sing-box/tun2socks state, netd application, default route presence and TUN
counters.

The module ZIP includes `diagnose_dns_hotspot_runtime.sh`. Run it without a
separate download:

```sh
su -c 'sh /data/adb/modules/ZDT-D-Test/diagnose_dns_hotspot_runtime.sh 75'
```

During the 75-second window, send a message in Gemini/Grok/ChatGPT and try the
system hotspot. The script writes before/after routing, tethering, firewall,
logcat, daemon logs, a five-second timeline and optional 96-byte packet captures
to `/sdcard/Download/ZDTD_dns_hotspot_<timestamp>/`.
