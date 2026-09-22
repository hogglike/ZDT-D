# Build 20 candidate — per-app DNS / hotspot compatibility

Base branch: `per-app-dns-stage2-test`  
Base commit: `71ddaeb7622d35ead165a6cbd449ef43243855b6`

## Source-level cause

The DNS profile was still registered in netd as a secure, full-route VPN:

- selected application UIDs were attached to the synthetic VPN network;
- `0.0.0.0/0` was installed through `zdt_dnsN`;
- selected UIDs were also blocked from IPv6;
- OxygenOS and tethering helpers could therefore treat the DNS-only overlay as
  another complete VPN/upstream candidate.

That shape was unnecessary. Per-app DNS only needs the resolver address and the
profile-local `/30` route. Android netd supports VPN fallthrough when the VPN
table does not contain a matching route.

Build 19 also recorded ZDT-D ownership of `net.ipv4.ip_forward` even when the
value was already enabled by Android tethering or another tool.

## Fix

Only profiles owned by `dnsprofiles` now use this policy:

- bypassable netd VPN network (`secure=false`);
- profile-local `/30` route only;
- no `0.0.0.0/0` route through `zdt_dnsN`;
- no selected-UID IPv6 block;
- the existing UID -> netId -> Android resolver -> local DoH listener path is
  preserved.

All real traffic VPN owners keep the previous secure/full-route/IPv6 policy.

IPv4 forwarding ownership is now claimed only when ZDT-D actually changes the
kernel value from disabled to enabled. An already-enabled value remains
externally owned.

## UI

The Tools-list DNS Profiles entry is now a full feature card. It loads the
existing config/status APIs and shows:

- profile count;
- active profile count;
- selected app count;
- Working / Needs restart / Error state;
- styling and iconography matching adjacent tool cards.

Raw validation JSON in the editor is collapsed behind “technical data”.

## Automated checks in this source package

- Rust policy tests verify that DNS profiles are bypassable, have no default
  route policy and do not request IPv6 blocking.
- Rust policy tests verify that existing traffic VPN owners keep their prior
  full-tunnel behavior.
- IPv4-forward ownership has a pure decision test.
- `scripts/test_variant/prepare_test_variant.py` completes without leaving
  production identifiers or creating `ZDT-D-Test-Test` paths.
- `scripts/test_variant/verify_dns_hotspot_compat.sh` passes shell syntax
  validation and performs read-only checks on the phone.

The local Work container does not include Cargo, Gradle or the Android SDK, so a
Release compile must still be run by `.github/workflows/build.yml` before phone
installation.

## Phone validation

1. Build the isolated test branch with `.github/workflows/build.yml` in Release
   mode.
2. Install the APK and the **PLAIN** ReSukiSU module ZIP over build 19.
3. Reboot, start ZDT-D Test and confirm Chrome, Grok and Gemini still use their
   assigned DNS profile.
4. Copy `scripts/test_variant/verify_dns_hotspot_compat.sh` to Downloads and run:

   ```sh
   su -c 'sh /sdcard/Download/verify_dns_hotspot_compat.sh'
   ```

   The critical result is `PASS: DNS overlay owns no IPv4 default route`.
5. Enable the normal Android Wi-Fi hotspot and connect one client. The client
   should use the normal phone upstream, not the per-app DNS profile.
6. Enable a real VPN and test sharing it with VPN Hotspot.
7. If the system hotspot still switches off, capture before/after evidence with
   `collect_hotspot_debug.sh`; the remaining failure will then be in the vendor
   SoftAP path rather than the DNS profile route shape.
