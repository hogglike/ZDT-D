# Personal fork: DNS profiles development

Repository: `hogglike/ZDT-D`. Development branch: `copilot-polish`.

This change preserves the work completed so far. It is not a working per-app DNS
release. The Compose screen and authenticated API edit and validate draft
profiles only; the separate prototype is not integrated with module startup.

## Building on GitHub

The existing `Build ZDT-D` workflow now also runs on pull requests targeting
`main`. Manual `workflow_dispatch` still accepts `build_type=Release`. Builds of
different branches/PRs do not cancel each other. Release publishing remains
restricted to `main`; the development PR does not publish a release.

Before starting the native jobs, the workflow checks these existing signing
requirements without printing their values:

* `ZDT_KEYSTORE_BASE64`
* `ZDT_KEYSTORE_PASSWORD`
* `ZDT_KEY_ALIAS`
* `ZDT_KEY_PASSWORD`

Set them in this fork's Settings > Secrets and variables > Actions. Upstream
secrets are not supplied by the source code. Use a persistent personal signing
key; a differently signed APK cannot update the original installation in place.
The code does not generate, commit, upload, or replace any private signing key.

Successful full builds provide `zdt-apk` and `zdt-module-final` artifacts.
Compilation success alone does not validate Android resolver or app behavior.

## Current device blocker

The standalone launcher reached the baseline UID check, then failed resolving
example.com under YouTube's UID with ECONNREFUSED. Chrome opened the site
separately. The cause is unresolved. Do not remove this failure gate or describe
the DNS runtime as working until the query path and actual app behavior are
verified. Details and reproduction sources are in
`docs/DNS_PROFILES_ONE_PROFILE.md` and `scripts/dnsprofiles/`.
