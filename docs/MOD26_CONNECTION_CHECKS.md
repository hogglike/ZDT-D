# ZDT-D 4.2.0-mod26

Continue the working mod24 DNS/hotspot and mod25 subscription modes.

- Each mode has its own site-check switch, up to 12 HTTPS targets with per-site
  switches, expected HTTP code (0 accepts 200–399), required successful response
  count, and 2–30 second per-target timeout. Disabled checks apply the selected
  server and app rules without claiming verified Internet reachability. White
  with checks disabled applies its first candidate without automatic failover.
- Existing mod25 settings migrate with checks enabled and the existing three
  targets. The addresses are now visible and editable. The default automatic
  status criterion accepts legitimate website redirects; redirects are not
  followed. TLS website verification remains enabled.
- Each failed attempt reports individual URL/status/time/error, plus a bounded
  core log with proxy credentials and API token redacted. Copy diagnostic output
  from the error screen, never share the raw subscription or runtime config.
- Server latency uses the core's individual outbound URL-delay endpoint and one
  globally configurable HTTPS address/timeout. It is separate from mode checks;
  it never switches MODE. Unavailable nodes show n/d and the URL-test error.
- A widget's launcher owns long press. A "Сервер ▾" control under each mode opens
  the picker, saves the choice, and activates via the existing Wi-Fi confirmation.
  Long press on a mode in the app opens the same picker. White manual choices
  get first priority without being recorded as successful before verification.
- Reality parameters enable TLS even when a Clash provider omits a redundant
  tls flag; native TLS objects missing enabled and Reality fingerprints get
  compatible defaults. URI/native field aliases retain SNI/keys/short ID/Vision.
  The mode core uses the built-in Mozilla CA store for root Android execution.
  Unsupported XHTTP remains explicitly unavailable.

CI validates DNS/foreign-rule coexistence, bounded failover and optional health
policy, old-settings migration, actual subscription renderer output, one-URL
latency, real TLS over SOCKS, and synthetic VLESS/Reality Vision traffic using
production-rendered nodes. A wrong Reality public key must fail. Release APK
and Android arm32/arm64 builds use build.yml on copilot-polish.

Phone acceptance: update both APK and ZIP, reboot; first disable Browser's site
checks and save, choose the known working server, then test actual browsing. If
it fails, copy diagnostics. Re-enable checks with one known accessible URL and
required count 1; inspect individual results. Test widget picker, white Wi-Fi
cancellation, latency, existing DNS profiles and system hotspot. No provider link
was supplied, so CI cannot certify that provider's live node configuration.
