package com.android.zdtd.service.watch

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.android.zdtd.service.BuildConfig
import com.android.zdtd.service.modes.ModeClient
import org.json.JSONArray
import org.json.JSONObject

/** Deliberately omits the DoH endpoint, its query/token, and API credentials. */
internal object WatchDnsDiagnostics {
  fun collect(context: Context): JSONObject {
    val store = WatchDnsStore(context)
    val state = WatchDnsRuntime.status.value
    val report = JSONObject().put("version", BuildConfig.VERSION_NAME).put("time_ms", System.currentTimeMillis())
      .put("profile_id", store.profile).put("apps", JSONArray(store.apps.sorted()))
      .put("vpn_state", state.state.name).put("vpn_message", state.message)
      .put("routing", "dns_host_routes; ordinary traffic uses underlying network")
      .put("dns_routes", JSONArray(state.routes))
      // HEV counts IP packets on the DNS routes, not decoded DNS questions.
      // These are sampled BEFORE synthetic probes, which bypass the TUN.
      .put("dns_route_traffic", JSONObject().put("available", state.statsAvailable)
        .put("sent_packets", state.sentPackets).put("received_packets", state.receivedPackets)
        .put("sent_bytes", state.sentBytes).put("received_bytes", state.receivedBytes))
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
    report.put("phone_network", when {
      caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "wifi"
      caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "cellular"
      caps == null -> "none"
      else -> "other"
    })
    try {
      val api = ModeClient.api(context)
      val status = api.getJsonData(WatchDnsVpnService.STATUS)
      report.put("private_dns_mode", status.optString("private_dns_mode"))
      report.put("suspended_for_tethering", status.optBoolean("suspended_for_tethering"))
      val profile = status.optJSONObject("profiles")?.optJSONObject(store.profile) ?: JSONObject()
      report.put("profile_running", profile.optBoolean("process_running"))
      report.put("profile_config_current", profile.optBoolean("config_current"))
      val port = WatchDnsPolicy.requireProfile(profile.optBoolean("enabled") &&
        profile.optBoolean("process_running") && profile.optBoolean("config_current"), profile.optInt("proxy_port"))
      val config = api.getJsonData(WatchDnsVpnService.CONFIG).optJSONObject("profiles")?.optJSONObject(store.profile)
      val timeout = ((config?.optInt("timeout_ms", 8000) ?: 8000) + 1000).coerceIn(3000, 31000)
      val results = JSONArray()
      listOf(Triple("example.com", 1, false), Triple("example.com", 1, true),
        Triple("gemini.google.com", 1, false), Triple("generativelanguage.googleapis.com", 1, false),
        Triple("www.google.com", 1, false), Triple("gemini.google.com", 28, false)).forEach { (name, type, tcp) ->
        val result = JSONObject().put("name", name).put("type", if (type == 1) "A" else "AAAA")
          .put("transport", if (tcp) "tcp" else "udp")
        val start = System.nanoTime()
        try {
          val answer = WatchDnsProbe.query(port, timeout, name, type, tcp)
          result.put("rcode", answer.code).put("answer_records", answer.records)
            .put("addresses", JSONArray(answer.addresses)).put("dns_answer_ok", answer.code == 0)
          if (answer.code == 0 && answer.records == 0) result.put("note", "No records; AAAA may be empty with IPv4-only DNS")
        } catch (e: Exception) { result.put("dns_answer_ok", false).put("error", e.message?.take(400)) }
        result.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000)
        results.put(result)
      }
      report.put("dns_tests", results)
    } catch (e: Exception) { report.put("profile_error", e.message?.take(400)) }
    return report
  }
}
