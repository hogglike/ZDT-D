# One-profile runtime prototype 0.2.1

This is the next device gate after the successful resolver Binder v3.1 report.
It is a standalone root launcher, not a packaged module/APK release. The draft
UI from stage 1 remains draft-only; this launcher does not set `enabled: true`.
No boot service or automatic startup is installed.

## What it runs

* Installed ZDT-D `bin/sing-box` 1.13.x, with a checked temporary configuration.
* One newly owned DNS cache and secure netd VPN network, ID 28200.
* DIRECT TUN `zdt_dns_one`, IPv4 `10.253.241.1/30`; resolver address `.2`.
* DNS port 53 traffic arriving at this TUN is handled by sing-box and sent to
  `https://xbox-dns.ru/dns-query`, with certificate verification enabled.
* Endpoint bootstrap uses `1.1.1.1` independently of the profile. There is no
  automatic fallback to system DNS for profile queries.
* All non-DNS traffic in the TUN uses a DIRECT outbound, with no remote VPN or
  proxy configured. Its sockets originate under the root forwarder UID, which
  is never included in the selected apps' UID ranges.
* The primary-user, non-shared UIDs of `com.android.chrome` and
  `com.google.android.apps.bard` (Gemini) are bound to the same profile. YouTube
  is the unbound control. All three UIDs are resolved afresh, not hardcoded.
* IPv6 from the selected UIDs, except loopback, is rejected while the profile is
  active. Owner matching is used only for IPv6 protection, not DNS attribution.

The test is conservative: global DNSCrypt must be disabled in ZDT-D, Private DNS
must be off. DNAT/REDIRECT/TPROXY/NFQUEUE rules reachable from OUTPUT or
POSTROUTING (including nested jumps/gotos, separately per table and IP family)
cause startup to refuse. Rules in unused chains or reachable only from
PREROUTING/FORWARD no longer cause refusal. This is conservative reachability,
not full match evaluation: UID/port conditions and preceding RETURN/ACCEPT rules
are not evaluated. Actual conflicts include the table and rule in the report;
firewall command errors are reported separately, with their exit code and output.
Other VPN UID bindings covering Chrome, Gemini or YouTube also cause refusal.
The launcher intentionally does not edit foreign rules or restore snapshots
over live changes. DNSCrypt configuration is
never rewritten. Re-enable the desired global mode manually after testing.

## Automated checks

1. Under all three app UIDs, the profile-only name `zdtd-profile.invalid` must not
   resolve to `192.0.2.123`; public DNS and HTTPS to example.com must work.
2. After creating the network, exact Binder DNS configuration readback is
   required before UID binding. A query explicitly using the new network must
   resolve the marker and `gemini.google.com` through the profile resolver.
3. IPv6 protection is installed and verified, then the Chrome and Gemini UIDs are bound.
4. In separate fresh processes running with the respective real app UID, the
   marker must resolve through Chrome and Gemini assignments and remain absent for
   YouTube. Public DNS and certificate-verified HTTPS must also work.
5. The launcher holds the profile for five minutes after READY. It monitors the
   forwarder, watchdog, redirect conflicts, IPv6 rules/hook, and both selected UIDs.
6. The end-of-test DNS/HTTPS checks repeat. Cleanup detaches the UIDs, destroys
   its owned network/cache, removes only its own IPv6 chain/hook, and terminates
   its own forwarder. Resolver readback must be empty. The selected UIDs are then
   checked against ordinary system DNS/HTTPS again.

The UID checks run with the root tool's SELinux context after dropping Linux
UID/GID; they do not launch or instrument Chrome/Gemini/YouTube. A passing result is
evidence about system resolver/routing selection for those UIDs. It is not proof
that an app's private DoH implementation, isolated subprocess or explicitly
bound network follows the same path. Open the real apps after READY. Close
Chrome and Gemini fully before starting so their existing sockets and app DNS caches do not
obscure the comparison. For this test, disable Chrome's own Secure DNS in Chrome settings so it uses
the Android resolver. Only the installed Gemini package is selected; Google
app / Play services UIDs are not silently included. If a Gemini component uses
a different package or isolated UID, the app-level test must identify that
before expanding the selection. The launcher does not promise regional/account
access merely because the DNS/HTTPS probes pass.

## Ownership and recovery

The private root-only session directory is `/data/local/tmp/zdtd-dns-one`.
Atomic directory creation prevents concurrent launches. A pre-existing resolver
cache, TUN name or IPv6 chain is not reused or deleted. Resource ownership is
journalled after successful creation; UID-binding intent is recorded before
binding, so network destruction also handles ambiguous binding responses.
netd's textual 4xx/5xx errors are failures even if the process exits 0.

An independent session watches the owner PID and start time. It recognizes
zombies as exited processes and attempts cleanup if the owner disappears. The
main loop stops after five minutes of elapsed uptime; the watcher requests
termination at ten minutes if startup or cleanup is unusually slow. A normal
stop request and INT/TERM/HUP also enter cleanup. Process start times and boot
IDs prevent cleanup from blindly signalling a reused PID or destroying a new
boot's network. A failed cleanup retains its journal for `stop` to retry.

This is not crash-proof production journalling: a hard kill precisely between
a successful external mutation and its ownership record, death of both owner
and watchdog, or ambiguous Binder/netd completion can leave resources. Do not
report cleanup success in those cases. Use `stop`; if cleanup cannot complete,
retain the report and reboot before restarting the test. A reboot removes these
temporary kernel/netd resources; this prototype has no persistent startup hook.
Do not deliberately kill processes during the first device test.

## Run on the target phone

Save `dns-one.sh` directly in Download. In ZDT-D turn off global DNSCrypt and
other active Zapret/proxy redirections for the test; keep ZDT-D installed.
Fully close Chrome and Gemini. Then in Termux:

```sh
su
/system/bin/sh /sdcard/Download/dns-one.sh
```

After READY, open Chrome and Gemini. Wait for CLEANUP_OK and TEST_COMPLETE in
Termux, then send `/sdcard/Download/dns-one-report.txt`. To stop early, run in a
second root Termux session:

```sh
/system/bin/sh /sdcard/Download/dns-one.sh stop
```

The script does not enable global DNSCrypt after finishing. Re-enable that
toggle if returning to the previous global Xbox DNS setup.

## Validation actually performed

* Java 8 bytecode/AIDL client compilation against Android SDK 36, DEX generation
  with min API 26, and shell syntax check pass.
* Official Linux sing-box 1.13.14 accepts the unchanged runtime JSON via `check`.
* Twenty host launcher scenarios using fake OS commands pass: success, occupied
  cache, existing redirect, netd create/route failure, resolver setup/DoH failure,
  IPv6 failure, first/second UID-binding failure, DNS readback failure, disappearance of the
  active IPv6 rule, manual stop, owner SIGKILL with watchdog cleanup, nested and
  IPv6 redirects, POSTROUTING queueing, firewall read/parse failures, and unrelated
  rules. These
  do not simulate real Binder/TUN/network forwarding.
* Attempting a local sing-box DNS listener in this build environment was blocked
  by `subscribe route updates: operation not permitted`; no live DNS forwarding
  result is claimed from that attempt.
* The prior Binder v3.1 device check passed. Runtime 0.2.1 was attempted on the
  target phone. After stopping the existing ZDT-D services, firewall checks and
  sing-box config validation passed. The first baseline query under YouTube UID
  10453 failed resolving example.com with EAI_NODATA / ECONNREFUSED, before any
  profile network was created. Chrome opened example.com separately; this does
  not establish whether the UID helper, per-UID policy, or resolver state caused
  the failure. Chrome UID was 10253 and Gemini UID was 10252 in that report.
  No READY or successful per-app Xbox DNS result has been obtained.
  Wi-Fi/mobile handover, actual app behavior, reboot/restart handling
  and simultaneous Zapret are not validated. Required GitHub Release CI has not
  run; no installable release is claimed.

Build:

```sh
python scripts/dnsprofiles/build_one_profile.py \
  --android-jar /path/to/sdk/platforms/android-36/android.jar \
  --d8-jar /path/to/sdk/build-tools/36.0.0/lib/d8.jar \
  --aidl /path/to/sdk/build-tools/36.0.0/aidl --output /path/to/dns-one.sh
python scripts/dnsprofiles/tests/test_one_profile.py /path/to/dns-one.sh
```

Configuration references: [sing-box HTTPS DNS](https://sing-box.sagernet.org/configuration/dns/server/https/),
[TUN](https://sing-box.sagernet.org/configuration/inbound/tun/), and
[Hosts DNS](https://sing-box.sagernet.org/configuration/dns/server/hosts/).
