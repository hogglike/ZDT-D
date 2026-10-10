package com.android.zdtd.service

import org.junit.Assert.*
import org.junit.Test

class VpnHevBridgeTest {
  @Test fun competingServicesCannotReserveOneNativeTun() {
    val lease = VpnHevBridge.reserve("watch")
    try {
      try { VpnHevBridge.reserve("nonroot"); fail("A second service acquired HEV") }
      catch (_: IllegalStateException) { }
      assertTrue(VpnHevBridge.owns(lease))
    } finally { VpnHevBridge.release(lease) }
  }

  @Test fun staleCleanupCannotReleaseNewService() {
    val previous = VpnHevBridge.reserve("watch")
    VpnHevBridge.release(previous)
    val current = VpnHevBridge.reserve("nonroot")
    try {
      VpnHevBridge.release(previous)
      assertFalse(VpnHevBridge.owns(previous))
      assertTrue(VpnHevBridge.owns(current))
    } finally { VpnHevBridge.release(current) }
  }
}
