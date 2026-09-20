# DNS Profiles — stage 1, development patch

Base: `GAME-OVER-op/ZDT-D`, commit `ecef15d36c4fb1dcccf3c57abda12a71cf45828a`.
Local branch: `copilot-polish`. License: inherited GPL-3.0.

## Status

This is the data-model/API/UI stage from the supplied specification. **It does
not implement working per-app DNS and is not an installable module release.**

The subsequent standalone one-profile runtime prototype is documented in
`DNS_PROFILES_ONE_PROFILE.md`. It is separate from this draft UI and awaits its
first end-to-end device run after the successful Binder capability gate.
The app can create, edit, delete and validate DoH profile drafts. Every saved
profile has `enabled: false`; the backend rejects `true`. The editor explains
that no resolver, TUN or netId has been started. Existing global DNSCrypt and
other routing engines are not changed.

Package name, module ID, signing and release workflows are not renamed in this
patch. Therefore this source is not a side-by-side installable branded fork.
Do not distribute an APK from it as an official ZDT-D release.

## Implemented

- Dedicated screen, accessible from the Programs list.
- Xbox DoH defaults; arbitrary HTTPS DoH URL, including custom port/path/query.
- Explicit IPv4 bootstrap list and timeout validation.
- Installed-app search, system-app filter, multiple selection and clear action.
- Package conflicts across drafts and shared UID conflicts on device.
- Primary-user regular application UIDs only (10000–19999). System services,
  secondary users and isolated/sandbox UIDs are rejected explicitly.
- Atomic, revision-checked JSON persistence, size limits and strict schema.
- A malformed existing store is reported; saving never silently resets it.
- Configuration and UID diagnostics clearly report `draft_only`, no active netId,
  no TUN and `not_started` resolver. No fabricated latency or upstream health.
- Read-only device capability report in `scripts/dnsprofiles-device-check.sh`.

## API

All endpoints use the daemon's existing authenticated API dispatcher.

| Method | Path | Result |
|---|---|---|
| GET | `/api/programs/dnsprofiles/config` | `{ok, data: ProfileDocument}` |
| PUT | `/api/programs/dnsprofiles/config` | Validate packages/UIDs, atomically replace the draft document; returns new revision |
| POST | `/api/programs/dnsprofiles/validate` | `{ok: true, valid, data? , error?}`; no writes, no DNS probes |

PUT uses the revision returned by GET. To create/update a profile, change its map
entry. To delete it, remove its entry. Stale writes fail; reload and reapply the
edit. A single daemon owns the store; no cross-process file-lock guarantee is
claimed. Existing generic PUT handling exposes an error in the daemon/app log;
the explicit validation endpoint also returns it for display in the editor.

Store: `/data/adb/modules/ZDT-D/working_folder/dnsprofiles/profiles.json`.

```json
{
  "schema_version": 1,
  "revision": 0,
  "profiles": {
    "xbox": {
      "display_name": "Xbox DNS",
      "endpoint": "https://xbox-dns.ru/dns-query",
      "bootstrap": ["1.1.1.1", "9.9.9.9"],
      "timeout_ms": 7000,
      "apps": ["com.google.android.youtube"],
      "enabled": false
    }
  }
}
```

No assignment is currently active. Removing an app from a draft only changes
the draft; it does **not** disable a pre-existing global DNSCrypt redirect.

## Why runtime is a separate required stage

Review of the pinned source found these gaps in the handoff's assumptions:

1. `vpn_netd::apply_one_profile` logs a failure from `set_dns_universal` and
   still adds UIDs. For a DNS-only profile, resolver configuration must instead
   be mandatory, with rollback before binding the applications.
2. `sync_ipv6_block` returns no result. An unavailable ip6tables/owner matcher
   or partially installed rules currently only produce warnings. The DNS runtime
   needs verified protection before reporting success.
3. `setnetdns`/`setifdns` are the only implemented resolver configuration paths.
   Their availability on the target OxygenOS has not been measured. A successful
   netd network creation is not proof of successful DNS resolver configuration.
   If these commands are unavailable, a versioned Android DNS Resolver Binder
   helper must be investigated; do not hard-code Binder transaction numbers.
4. A local TUN forwarder originates new sockets under its own UID. Combining it
   with the current per-app NFQUEUE rules needs a deliberate mark/queue strategy
   and tests; simultaneous DNS + Zapret assignment is not proven by the existing
UI conflict handling.
5. A private netId does not override app-owned DoH or explicit network binding.
   Chrome's Secure DNS must be accounted for in isolation tests. Test Android's
   system resolver independently of whether YouTube opens successfully.

These observations do not prove failure on a particular phone. They define what
the device test and subsequent implementation must establish.

## Next implementation gate

### First target-device report (2026-09-20)

The user ran v1 while the original global Xbox DoH configuration was active.
The report confirms Android API 36 / Android 16, root UID 0, SELinux Enforcing,
the presence of `/dev/tun`, `netd`, `dnsresolver`, sing-box 1.13.14 and
dnscrypt-proxy 2.1.16. `ndc resolver` returns `500 0 Command not recognized`
despite an OS exit code of zero. The legacy resolver path must not be treated
as supported on this device.

The missing `ZDT_VPN_NETD_V6` chain does not demonstrate missing kernel IPv6
support: the current mode is global DNS, not our per-app runtime. Owner matcher
help is available, but installation of actual rules has not been tested.

The UID and settings checks failed with `Failed transaction (2147483646)`.
Their cause remains unconfirmed. V1 inherited the Termux environment and sent
Binder shell command output directly to the redirected report file. V2 removes
these variables by using system binaries, a clean PATH, `/dev/null` input and
pipe-backed stdout/stderr. It also compares the existing upstream shell-user
fallback. The host transport regression test verifies actual child descriptor
types and preservation of a nonzero exit code.

`scripts/dnsprofiles/build_probe.py` builds a standalone `dnscheck-v2.sh` using
an Android SDK and Java 17. Its embedded, source-included DEX only checks the
platform resolver Stub/parcel types, Binder descriptor and stable AIDL version
metadata. It does not invoke setters, read query history or create a network.
No hidden-API exemptions or SELinux changes are applied. Source compilation
and shell syntax passed; Android execution and setter permissions remain
unverified by v2 itself.

Build example:

```sh
python scripts/dnsprofiles/build_probe.py \
  --android-jar /path/to/sdk/platforms/android-36/android.jar \
  --d8-jar /path/to/sdk/build-tools/36.0.0/lib/d8.jar \
  --output /path/to/dnscheck-v2.sh
python scripts/dnsprofiles/test_probe_transport.py
```

### V2 device result and V3 Binder cache test

The returned v2 report confirms root, SDK 36, SELinux Enforcing, Private DNS
`off`, YouTube UID `10453` and Chrome UID `10253`. Both root and shell-user
package/settings reads succeeded. It does not isolate which v2 transport or
environment change resolved the v1 failure.

DNS Resolver Binder is alive with descriptor `android.net.IDnsResolver`.
`app_process` cannot load the platform `IDnsResolver` or `ResolverParamsParcel`
Java classes. This is a client classpath failure, not evidence that the service
lacks these operations. V3 bundles Java generated from unmodified AOSP stable
AIDL v1 snapshots; provenance and licenses are in `scripts/dnsprofiles/aidl`.

V3 tests metadata, creates a resolver cache, writes `127.0.0.1` as a marker,
reads back the server and all six parameters, deletes the cache in `finally`,
and reads back an empty server list. It sends no DNS requests, creates no netd
network, and binds no UID. The cache key `1000000` is outside netd's permitted
network ID range (100..65535) but accepted by AOSP's unsigned 32-bit resolver
cache map. Thus it cannot select an existing netd network. An OEM rejection of
this test key is reported without falling back to an active network ID.

AOSP `resolv_create_cache_for_net` rejects an existing cache with EEXIST while
holding its cache mutex. V3 only configures/deletes after successful creation;
it never deletes an existing or ambiguously acquired cache. Interrupts, Binder
transport failure after creation, or forced termination can leave an unused
test cache; those cases must not be reported as success. No automatic blind
cleanup of an unowned cache is provided. An OEM that changes the upstream
ownership contract needs separate investigation.

The v3 device report identifies resolver version 17, hash
`a2d92aab649c699b4fd87d5821b7f40b536ccdd9`. Cache creation, the configuration
setter, and cache destruction all succeeded with SELinux Enforcing. Readback
failed at the generated Java proxy's `readIntArray(stats)` with `bad array
lengths`. This confirms a client output-allocation mismatch; it does not prove
that a particular number of new statistics fields was added. The cleanup call
succeeded, but the post-cleanup readback was not reached.

V3.1 uses `generate_info_reader.py` to derive a dynamic-output reader from the
SDK-generated AIDL proxy. It keeps the compiler's transaction constant, request
encoding and reply order, replacing fixed-array reads with `createStringArray`
and `createIntArray`. The six configuration parameters and server list must
still match exactly; statistics are printed at their actual returned length.
The empty-server readback after cleanup remains mandatory. The frozen upstream
AIDL files themselves are unchanged. Report header `v3.1` distinguishes this
revision from the original `v3` script despite the same download/report names.

Local Java/AIDL/DEX compilation, shell syntax and twelve ownership/cleanup/
variable-array scenarios pass. Host tests use a fake service and do not validate
Binder IPC. The returned v3.1 device report passes: marker server and all six
parameters match, cache deletion succeeds, empty-server readback succeeds, and
the process exits 0. This closes the resolver Binder configuration gate on the
target phone. Statistics in this successful run contain seven integers; the
earlier mismatch must not be attributed to a permanent version-17 size change.
This validates resolver cache configuration, not DNS packet routing, DoH
connectivity or app isolation. Proceed to the single-profile runtime prototype;
do not repeat this capability probe without a new concrete failure.
The required GitHub Release CI gate has not run; this is a standalone diagnostic,
not a module/APK release.

```sh
python scripts/dnsprofiles/build_cache_probe.py \
  --android-jar /path/to/sdk/platforms/android-36/android.jar \
  --d8-jar /path/to/sdk/build-tools/36.0.0/lib/d8.jar \
  --aidl /path/to/sdk/build-tools/36.0.0/aidl \
  --output /path/to/dnscheck-v3.sh --test
```

On the phone, save the script directly in Download, enter `su`, then run
`/system/bin/sh /sdcard/Download/dnscheck-v3.sh`. The script writes
`/sdcard/Download/dnsprofiles-report-v3.txt` automatically. The existing global
Xbox DNS setup is not reconfigured by this test.

After the Binder gate, implement exactly one profile:

1. Allocate fixed, collision-checked netId/TUN/subnet for the first profile.
2. Start a local resolver and DIRECT forwarder with independent bootstrap;
   verify DNS answers before routing apps.
3. Require resolver registration, UID resolution and IPv6 protection to succeed.
4. Bind only selected UIDs. Reject simultaneous global DNS redirect or incompatible
   app routes without changing them silently.
5. Own every process/network/rule and clean them on failure, stop and restart.
6. Confirm system-resolver queries for YouTube and Chrome take different paths;
   repeat on Wi-Fi/mobile, handover, reboot and child-process failure.
7. Only after device validation: multiple active profiles, policies, Wi-Fi/mobile
   overrides, then BAT/ZIP import. Unknown importer arguments must remain visible.

## Build and validation

Use the accompanying validation report for commands, actual results and gaps.
Repository instructions designate `.github/workflows/build.yml`,
`build_type=Release`, branch `copilot-polish` as the required CI gate. Local Rust
checks do not replace that gate. No remote branch, Actions run or PR is implied.
Do not use `build.sh` for AI validation.

## Rollback

Before applying the patch, use a clean checkout of the base commit. Reverse with
`git apply -R --check dnsprofiles-stage1.patch` followed by
`git apply -R dnsprofiles-stage1.patch` only when the patch is still unmodified.
The stage does not create network rules or processes requiring cleanup.
Keep any profile document you want to preserve; reinstalling the original app
must follow that project's normal backup/restore process.
