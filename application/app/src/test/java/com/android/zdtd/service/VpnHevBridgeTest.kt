package com.android.zdtd.service

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.*
import org.junit.Test

class VpnHevBridgeTest {
  @Test fun competingServicesCannotReserveOneNativeTun() {
    val sessions = VpnHevBridge.Sessions({ _, _ -> false }, { true }, { false })
    val lease = sessions.reserve("watch")
    try { refusesReserve(sessions) } finally { sessions.release(lease) }
  }

  @Test fun staleCleanupCannotReleaseOrStopNewService() {
    val sessions = VpnHevBridge.Sessions({ _, _ -> false }, { true }, { false })
    val previous = sessions.reserve("watch")
    sessions.release(previous)
    val current = sessions.reserve("nonroot")
    try {
      sessions.release(previous)
      assertFalse(sessions.stop(previous))
      refusesReserve(sessions)
    } finally { sessions.release(current) }
  }

  @Test fun asynchronousNativeStartKeepsSessionAliveUntilStopped() {
    val running = AtomicBoolean(false)
    val started = CountDownLatch(1)
    val sessions = VpnHevBridge.Sessions(
      { _, _ -> running.set(true); started.countDown(); true },
      { running.set(false); true }, { running.get() },
    )
    val lease = sessions.reserve("watch")
    var failure: Throwable? = null
    val worker = Thread { try { sessions.run(lease, "config", 1) } catch (e: Throwable) { failure = e } }
    worker.start()
    try {
      assertTrue(started.await(2, TimeUnit.SECONDS))
      worker.join(150)
      assertTrue("Returning from JNI start must not end the VPN session", worker.isAlive)
      assertTrue(sessions.isRunning(lease))
      sessions.release(lease)
      refusesReserve(sessions)
      assertTrue(sessions.stop(lease))
      worker.join(2000)
      assertFalse(worker.isAlive)
      failure?.let { throw it }
      val next = sessions.reserve("nonroot")
      sessions.release(next)
    } finally { sessions.stop(lease); worker.join(2000) }
  }

  @Test fun stopBeforeNativeStartPreventsDelayedStartup() {
    val calls = AtomicInteger(0)
    val sessions = VpnHevBridge.Sessions({ _, _ -> calls.incrementAndGet(); true }, { true }, { false })
    val lease = sessions.reserve("watch")
    assertTrue(sessions.stop(lease))
    assertFalse(sessions.run(lease, "config", 1))
    assertEquals(0, calls.get())
    val next = sessions.reserve("nonroot")
    sessions.release(next)
  }

  @Test fun rejectedNativeStartupReleasesReservation() {
    val sessions = VpnHevBridge.Sessions({ _, _ -> false }, { true }, { false })
    val lease = sessions.reserve("watch")
    assertFalse(sessions.run(lease, "config", 1))
    val next = sessions.reserve("nonroot")
    sessions.release(next)
  }

  private fun refusesReserve(sessions: VpnHevBridge.Sessions) {
    try { sessions.reserve("other"); fail("A second service acquired HEV") }
    catch (_: IllegalStateException) { }
  }
}
