package com.android.zdtd.service.watch

import org.junit.Assert.*
import org.junit.Test

class WatchDnsPolicyTest {
  @Test(expected = IllegalArgumentException::class)
  fun missingCompanionNeverTurnsIntoDeviceWideVpn() {
    WatchDnsPolicy.allowedPackages(setOf("removed.plugin"), setOf("phone.app"), "zdt.app")
  }

  @Test(expected = IllegalArgumentException::class)
  fun hostCannotRouteItsOwnProxySocketsIntoTun() {
    WatchDnsPolicy.allowedPackages(setOf("zdt.app"), setOf("zdt.app"), "zdt.app")
  }

  @Test fun onlyRequestedInstalledAppsAreIncluded() {
    assertEquals(setOf("companion"), WatchDnsPolicy.allowedPackages(
      setOf("companion", "removed", "zdt.app"), setOf("companion", "gemini", "zdt.app"), "zdt.app"))
  }

  @Test(expected = IllegalStateException::class)
  fun oldModuleWithoutProxyPortCannotClaimConnection() { WatchDnsPolicy.requireProfile(true, 0) }

  @Test(expected = IllegalStateException::class)
  fun stoppedProfileCannotKeepVpnRunning() { WatchDnsPolicy.requireProfile(false, 28000) }

  @Test fun suggestionsDoNotIncludeUnrelatedSamsungApps() {
    assertTrue(WatchDnsPolicy.suggestedPackage("com.samsung.android.app.watchmanager", "Galaxy Wearable"))
    assertTrue(WatchDnsPolicy.suggestedPackage("com.samsung.android.watch7manager", "Galaxy Watch7 Manager"))
    assertFalse(WatchDnsPolicy.suggestedPackage("com.samsung.android.health", "Samsung Health"))
    assertFalse(WatchDnsPolicy.suggestedPackage("unrelated.app", "Watch 7"))
  }
}
