# ZDT-D 4.2.0 · mod23

The startup screen no longer imposes a randomized 2–4.5 second wait. The
existing root/module handshake and daemon retry remain; the UI opens as soon
as status is available. Program settings and logs refresh independently.

Open **DNS profiles → Проверка соединения и ошибки раздачи**. The new
**ZDT-D · проверка связи** home-screen widget offers a manual check and shows
the last result and timestamp. Results older than an hour are marked stale.

Checks send HTTPS requests to Yandex, VK, Google, Cloudflare and YouTube.
Direct probes bind sockets and DNS to an available physical Android network;
they do not represent the network assignment of every selected application.
TLS verification remains enabled. Redirects/captive portals and unexpected
status codes fail. YouTube checks HTTPS access, not video playback.
Opera is explicitly tested through its configured first SOCKS listener.
The existing DNS diagnostic endpoint sends a real DNS query to each enabled
profile listener. It does not prove that every application's full path works.
Disabled/suspended profiles are displayed separately from failures.

The restriction hint requires two successful domestic probes and two failed
foreign probes. It is an inference, not a determination that the operator is
using a whitelist. Server outages, DNS and routing failures are other causes.

Automatic checks are opt-in. JobScheduler requests a 30-minute interval;
Android/OEM battery policy may delay execution. Network changes trigger a
debounced check while the diagnostic screen is open. No permanent daemon
network watcher, routing changes or exact alarms are added.

Hotspot capture runs as a user-started foreground service. Start it, then
attempt to enable the system hotspot. It reads filtered logcat events,
tethering/Wi-Fi status, interfaces, packet counters and routing snapshots.
Commands use a required timeout wrapper. It neither changes settings nor
clears logs. Capture runs for 90 seconds plus initial/final snapshots.
Stopping it saves a partial report. ZIP output is app-private and can be
saved/shared through Android's chooser. Known secret fields and MAC addresses
are redacted; network addresses remain, so review before sharing. No reports
are automatically uploaded. Missing root, unavailable timeout or OEM log
restrictions are reported; an exported ZIP is not proof of a diagnosed cause.

Per-app DNS routing, manual tethering suspension, boot scripts and upstream
binaries are preserved from mod22. Subscription import is unchanged.

Validation: host test-variant suite; Android unit tests for failure and
restriction classification; signed Release APK and module packaging through
build.yml on copilot-polish. Real hotspot reproduction and widget/OEM
background behavior require on-device verification.
