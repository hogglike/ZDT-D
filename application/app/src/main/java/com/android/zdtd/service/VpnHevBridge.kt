package com.android.zdtd.service

/** HEV has one native instance per process, even when two VpnServices exist. */
internal object VpnHevBridge {
  internal class Lease internal constructor(val owner: String)
  private val lock = Any()
  private var active: Lease? = null
  private val clazz by lazy { Class.forName("hev.htproxy.TProxyService") }
  private val startMethod by lazy { clazz.getMethod("TProxyStartService", String::class.java, java.lang.Integer.TYPE) }
  private val stopMethod by lazy { clazz.getMethod("TProxyStopService") }
  private val runningMethod by lazy { clazz.getMethod("TProxyIsRunning") }

  fun reserve(owner: String): Lease = synchronized(lock) {
    check(active == null) { "Останови другой VPN-режим ZDT-D перед запуском DNS часов" }
    Lease(owner).also { active = it }
  }

  fun owns(lease: Lease): Boolean = synchronized(lock) { active === lease }

  fun release(lease: Lease) = synchronized(lock) {
    if (active === lease) active = null
  }

  fun run(lease: Lease, config: String, fd: Int): Boolean {
    check(owns(lease)) { "HEV session was revoked" }
    try {
      return startMethod.invoke(null, config, fd) as? Boolean ?: false
    } finally {
      release(lease)
    }
  }

  fun stop(lease: Lease): Boolean = synchronized(lock) {
    if (active !== lease) false else stopMethod.invoke(null) as? Boolean ?: false
  }

  fun isRunning(lease: Lease): Boolean = synchronized(lock) {
    active === lease && (runningMethod.invoke(null) as? Boolean) == true
  }
}
