# DNS Profiles Stage 2 — real per-app DoH runtime

Base: `hogglike/ZDT-D` / `ecef15d36c4fb1dcccf3c57abda12a71cf45828a`.

## What is implemented

This stage replaces the draft-only DNS Profiles backend with an actual runtime.
Each enabled DNS profile gets:

- one deterministic Android `netId` from the reserved `28200..28999` block;
- one deterministic sing-box TUN (`zdt_dnsN`);
- one isolated `/30` from `10.253.240.0/26`;
- the profile DNS address at the second host in that `/30`;
- a sing-box TUN rule that hijacks port 53 and resolves it through the profile DoH URL;
- a DIRECT outbound for all non-DNS traffic;
- Android package -> UID resolution performed on-device;
- UID binding through the existing `vpn_netd` builder;
- IPv6 protection through the existing `vpn_netd` selected-UID chain.

Unselected applications are not attached to the profile network and keep the
normal Android Wi-Fi/mobile DNS path.

## Current scope

- DoH only, one upstream per profile.
- Up to 16 DNS profiles.
- Primary Android user regular app UIDs only (`10000..19999`).
- One app can belong to only one DNS profile.
- The ZDT-D controller app cannot be selected.
- Custom DoH HTTPS port/path/query are preserved.
- The first bootstrap IPv4 address is used by the current runtime; the remaining
  values stay in the schema for the next fallback stage.
- Changes are persisted immediately but applied on the next normal ZDT-D restart.
- Application-owned DoH/Secure DNS is outside Android resolver routing and is not
  overridden by this feature.

## Required settings for Stage 2

Global ZDT-D DNSCrypt must be OFF while per-app DNS profiles are enabled. The
current global DNSCrypt mode installs device-wide DNS redirection rules and would
intercept the profile's synthetic resolver address.

Android Private DNS strict/hostname mode must also be disabled. Automatic mode
is allowed; strict mode is rejected before profile startup.

## Runtime startup

The DNS profile starter is integrated as a ninth `VpnNetdProfile` provider. It
runs after existing VPN engines so existing VPN profiles keep precedence if a UID
claim conflicts. Before binding UIDs, the starter:

1. validates the stored profile document;
2. resolves enabled package UIDs on the phone;
3. stops stale DNS-profile-owned sing-box processes;
4. generates a sing-box TUN configuration;
5. runs `sing-box check`;
6. starts sing-box and waits for the TUN;
7. sends a DNS probe to the synthetic resolver address, which must traverse the
   TUN and succeed through the configured DoH endpoint;
8. returns a `VpnNetdProfile` to the common netd builder.

For `owner_program == "dnsprofiles"`, netd DNS setup is mandatory rather than a
warning-only operation. If `resolver setnetdns/setifdns` fails, that profile is
not bound to its apps.

## UI

The DNS Profiles screen now has a real enable switch. It shows the persisted
state plus runtime state (`process_running`, `netd_applied`, TUN and resolver
address) from `/api/programs/dnsprofiles/status`.

The validation endpoint remains side-effect free and returns a preview of the
future `netId`, TUN, CIDR and DNS address.

## First phone test

1. Turn off ZDT-D global DNSCrypt.
2. Android Private DNS: Off (recommended for the first test).
3. Create `xbox` profile:
   - DoH: `https://xbox-dns.ru/dns-query`
   - bootstrap: `1.1.1.1, 9.9.9.9`
   - select Chrome and Gemini only.
4. Enable the profile and save.
5. Stop and start ZDT-D normally.
6. Open DNS Profiles and confirm the card says `Работает`.
7. Verify Chrome/Gemini use the expected Xbox-DNS behavior while YouTube or
   another unselected browser remains on the system resolver.
8. If it fails, collect:
   - `/data/adb/modules/ZDT-D/working_folder/log/zdtd.log`
   - `/data/adb/modules/ZDT-D/working_folder/dnsprofiles/runtime/xbox/sing-box.log`
   - `/data/adb/modules/ZDT-D/working_folder/vpn_netd/last_ndc.out`
   - `/data/adb/modules/ZDT-D/working_folder/vpn_netd/ndc_history.log`

## Not implemented yet

- multiple upstreams in one profile;
- fallback / fastest / round-robin / health-check policies;
- Wi-Fi/mobile/SSID overrides;
- hot-apply without ZDT-D restart;
- importing Windows zapret `alt.bat` into nfqws/nfqws2 profiles.

Those belong to the next stages after this per-app DNS runtime passes the phone test.
