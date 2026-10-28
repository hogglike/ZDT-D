# ZDT-D 4.2.0-mod30

## Persistent server choices

mod29 identified a mode server by hashing its full connection definition. A provider update to the address, TLS parameters or key changed that hash and left obsolete choices behind.

mod30 uses the subscription and the persisted subscription node ID. Connection revision remains a separate hash: updated nodes retain choices but require a fresh delay measurement. Node ID preservation first matches unchanged connection definitions, then the same named protocol slot. Duplicate names keep identities when reordered, and newly generated ID collisions cannot hide a server.

Legacy hashes that still match the cached definitions migrate safely. Ambiguous and genuinely unavailable old choices are removed only when all subscription caches are readable. Disabled subscriptions retain selection metadata. A failed or unreadable cache is not treated as deletion. Lost hashes from an earlier provider update cannot be reconstructed reliably; choose those servers again once and save.

## Screen

Settings and server delay results use separate tabs. A full screen server picker supports search, selected-only view and priority arrows. Name rules and connection diagnostics are collapsed. Subscription checkboxes explicitly restrict name matching only. Raw delay failures are available on demand.

## Validation

Production renderer regressions cover stable refreshed and renamed identities, duplicate-name reorder, generated ID collision, legacy migration/deduplication, authoritative deletion and incomplete cache protection. Existing manual-to-auto and per-mode policy tests remain. Kotlin tests cover stale measurement rejection. Validate build.yml Release on copilot-polish, including live TCP/UDP DNS translation and sing-box TLS/Reality selector tests.

On device: install matching module and APK, save cleaned selections, choose several servers in Normal/Browser, return from widget manual selection to Auto, refresh the subscription and confirm the same choices/order remain. Disable/re-enable a subscription, then measure delays again. Confirm DNS applications and hotspot still work with the new UI.
