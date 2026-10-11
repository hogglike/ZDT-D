package com.android.zdtd.service

/** HEV has one native instance per process, even when two VpnServices exist. */
internal object VpnHevBridge {
  internal class Lease internal constructor(val owner: String) {
    internal var stopping = false
    internal var started = false
  }
  private val clazz by lazy { Class.forName("hev.htproxy.TProxyService") }
  private val startMethod by lazy { clazz.getMethod("TProxyStartService", String::class.java, java.lang.Integer.TYPE) }
  private val stopMethod by lazy { clazz.getMethod("TProxyStopService") }
  private val runningMethod by lazy { clazz.getMethod("TProxyIsRunning") }
  private val statsMethod by lazy { clazz.getMethod("TProxyGetStats") }

  private val sessions = Sessions(
    { config, fd -> startMethod.invoke(null, config, fd) as? Boolean ?: false },
    { stopMethod.invoke(null) as? Boolean ?: false },
    { runningMethod.invoke(null) as? Boolean ?: false },
    { statsMethod.invoke(null) as LongArray },
  )

  fun reserve(owner: String) = sessions.reserve(owner)
  fun release(lease: Lease) = sessions.release(lease)
  fun run(lease: Lease, config: String, fd: Int) = sessions.run(lease, config, fd)
  fun stop(lease: Lease) = sessions.stop(lease)
  fun isRunning(lease: Lease) = sessions.isRunning(lease)
  fun stats(lease: Lease) = sessions.stats(lease)

  /** JNI start returns immediately; keep the lease until its pthread actually exits. */
  internal class Sessions(
    private val startNative: (String, Int) -> Boolean,
    private val stopNative: () -> Boolean,
    private val runningNative: () -> Boolean,
    private val statsNative: () -> LongArray = { longArrayOf(0, 0, 0, 0) },
  ) {
    private val lock = Any()
    private var active: Lease? = null

    fun reserve(owner: String): Lease = synchronized(lock) {
      check(active == null) { "Останови другой VPN-режим ZDT-D перед запуском DNS часов" }
      Lease(owner).also { active = it }
    }

    fun release(lease: Lease) = synchronized(lock) {
      // Completion callbacks must not release a still-running native instance.
      if (active === lease && !lease.started) active = null
    }

    fun run(lease: Lease, config: String, fd: Int): Boolean {
      try {
        val started = synchronized(lock) {
          check(active === lease) { "HEV session was revoked" }
          if (lease.stopping) false else startNative(config, fd).also { lease.started = it }
        }
        if (!started) return false
        while (isRunning(lease)) Thread.sleep(50)
        return true
      } finally {
        synchronized(lock) {
          if (active === lease) {
            if (lease.started) check(stopNative()) { "HEV could not stop" }
            lease.started = false
            active = null
          }
        }
      }
    }

    fun stop(lease: Lease): Boolean = synchronized(lock) {
      if (active !== lease) false else {
        lease.stopping = true
        if (!lease.started) true else stopNative().also { if (it) lease.started = false }
      }
    }

    fun isRunning(lease: Lease): Boolean = synchronized(lock) {
      active === lease && lease.started && runningNative()
    }

    fun stats(lease: Lease): LongArray? = synchronized(lock) {
      if (active !== lease || !lease.started || !runningNative()) null
      else statsNative().takeIf { it.size == 4 && it.all { value -> value >= 0 } }?.copyOf()
    }
  }
}
