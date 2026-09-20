# Frozen AOSP client interfaces

Unmodified stable AIDL v1 snapshots, including upstream interface hashes:

* `resolver-v1`: `platform/packages/modules/DnsResolver`, commit
  `8822f51b0b0b01b51324acba16f4676e88ffdfdc`,
  `aidl_api/dnsresolver_aidl_interface/1/`.
* `listener-v1`: `platform/packages/modules/Connectivity`, commit
  `627eb3c63e7c7cb3cb9f8ea319e4bb1676079c3a`,
  `staticlibs/netd/aidl_api/netd_event_listener_interface/1/`.

Sources: https://android.googlesource.com/platform/packages/modules/DnsResolver/
and https://android.googlesource.com/platform/packages/modules/Connectivity/.

Copyright The Android Open Source Project. These AIDL interfaces are licensed
under Apache License 2.0; see `LICENSE`. Version 1 is sufficient for cache
creation, configuration, readback and deletion. Later parcel fields receive
their service-side defaults. No transaction numbers or parcel layouts are
handwritten. The event listener is a compile-time dependency only; this tool
never registers a listener.

The generated script embeds these sources, their hashes, and license text.

The v3.1 build also derives `ResolverInfoReader` from the generated Java proxy:
native Binder services return vectors whose lengths need not match the Java
caller's preallocated arrays. Only output allocation is adapted; the request
layout, output field order, transaction constant and cleanup remain derived
from the AIDL compiler. See `../generate_info_reader.py`. Upstream AIDL snapshots
are not modified. This reader targets Android's native resolver service.
