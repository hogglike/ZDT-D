package com.android.zdtd.service.watch

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal class WatchDnsStore(context: Context) {
  private val prefs = context.getSharedPreferences("watch_dns_vpn", Context.MODE_PRIVATE)
  var profile: String
    get() = prefs.getString("profile", "").orEmpty()
    set(value) { prefs.edit().putString("profile", value).apply() }
  var apps: Set<String>
    get() = prefs.getStringSet("apps", emptySet()).orEmpty().toSet()
    set(value) { prefs.edit().putStringSet("apps", value.toSet()).apply() }
  var initialized: Boolean
    get() = prefs.getBoolean("initialized", false)
    set(value) { prefs.edit().putBoolean("initialized", value).apply() }
}

internal enum class WatchDnsState { STOPPED, STARTING, RUNNING, STOPPING, ERROR }

internal data class WatchDnsStatus(
  val state: WatchDnsState = WatchDnsState.STOPPED,
  val message: String = "DNS часов выключен",
)

internal object WatchDnsRuntime {
  private val mutable = MutableStateFlow(WatchDnsStatus())
  val status = mutable.asStateFlow()
  fun update(state: WatchDnsState, message: String) { mutable.value = WatchDnsStatus(state, message) }
  fun active(): Boolean = status.value.state in setOf(WatchDnsState.STARTING, WatchDnsState.RUNNING, WatchDnsState.STOPPING)
}
