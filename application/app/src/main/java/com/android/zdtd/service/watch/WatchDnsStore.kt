package com.android.zdtd.service.watch

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
  val routes: List<String> = emptyList(),
  val sentPackets: Long = 0,
  val sentBytes: Long = 0,
  val receivedPackets: Long = 0,
  val receivedBytes: Long = 0,
  val statsAvailable: Boolean = false,
)

internal object WatchDnsRuntime {
  private val mutable = MutableStateFlow(WatchDnsStatus())
  val status = mutable.asStateFlow()
  fun begin() { mutable.value = WatchDnsStatus(WatchDnsState.STARTING, "Проверка DNS-профиля…") }
  fun update(state: WatchDnsState, message: String) { mutable.update { it.copy(state = state, message = message) } }
  fun setRoutes(routes: List<String>) { mutable.update { it.copy(routes = routes) } }
  fun traffic(values: LongArray) {
    mutable.update { it.copy(sentPackets = values[0], sentBytes = values[1],
      receivedPackets = values[2], receivedBytes = values[3], statsAvailable = true) }
  }
  fun active(): Boolean = status.value.state in setOf(WatchDnsState.STARTING, WatchDnsState.RUNNING, WatchDnsState.STOPPING)
}
