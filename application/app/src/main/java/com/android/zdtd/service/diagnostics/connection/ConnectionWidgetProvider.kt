package com.android.zdtd.service.diagnostics.connection

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.android.zdtd.service.R
import com.android.zdtd.service.modes.ModeActionService
import com.android.zdtd.service.modes.ModeClient
import com.android.zdtd.service.modes.ModeStatusReceiver
import java.text.DateFormat
import java.util.Date

class ConnectionWidgetProvider : AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    render(context)
    ConnectionHealthJobService.schedule(context, ConnectionHealth.preferences(context).getBoolean("automatic", false))
    context.sendBroadcast(Intent(context, ModeStatusReceiver::class.java))
  }

  companion object {
    private val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT

    fun render(context: Context) {
      val manager = AppWidgetManager.getInstance(context)
      val ids = manager.getAppWidgetIds(ComponentName(context, ConnectionWidgetProvider::class.java))
      if (ids.isEmpty()) return
      ids.forEach { id ->
        val views = RemoteViews(context.packageName, R.layout.widget_connection_health)
        bind(context, views)
        manager.updateAppWidget(id, views)
      }
    }

    fun bind(context: Context, views: RemoteViews) {
      val report = ConnectionHealth.read(context)
      val prefs = ConnectionHealth.preferences(context)
      val rows = report.optJSONArray("rows")
      val time = report.optLong("time")
      val now = System.currentTimeMillis()
      val since = prefs.getLong("running_since", 0L)
      val running = since > 0 && now - since < 120_000
      val expired = time > 0 && now - time > 3_600_000
      val timestamp = when {
        running -> "Проверка…"
        prefs.contains("error") -> "Проверка прервана"
        time == 0L -> "Ещё не проверено"
        else -> DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time)) +
          if (expired) " · устарело" else " · ${report.optString("network")}"
      }
      fun checkState(name: String): String {
        val count = rows?.length() ?: 0
        return (0 until count).firstNotNullOfOrNull { index ->
          rows?.optJSONObject(index)?.takeIf { it.optString("name") == name }?.optString("state")
        }.orEmpty()
      }
      fun mark(state: String) = when (state) {
        "ok" -> "✓"
        "off" -> "○"
        "fail" -> "!"
        else -> "·"
      }
      val internet = listOf("Яндекс", "VK", "Google", "Cloudflare").map(::checkState)
      val internetState = when {
        "ok" in internet && "fail" in internet -> "частично"
        "ok" in internet -> "доступен"
        "fail" in internet -> "нет ответа"
        else -> "—"
      }
      val vpn = ModeClient.cached(context)
      val vpnMode = vpn.optString("mode")
      val vpnState = vpn.optString("state")
      val vpnLabel = when {
        vpnState == "connecting" -> "подключается"
        vpnState == "connected" && vpn.optBoolean("verified") -> "проверен"
        vpnState == "connected" -> "подключён"
        vpnState == "error" -> "ошибка"
        vpnState == "off" || vpnState == "idle" -> "выкл."
        else -> "—"
      }
      val vpnActive = vpnMode in ModeClient.modes && vpnState in listOf("connecting", "connected")
      views.setTextViewText(R.id.health_time, timestamp)
      views.setTextViewText(R.id.health_results, "Интернет ${if (expired) "—" else internetState}\n" +
        "DNS-профили ${if (expired) "·" else mark(checkState("DNS-профили"))}     " +
        "YouTube HTTPS ${if (expired) "·" else mark(checkState("YouTube (HTTPS)"))}\n" +
        "VPN · $vpnLabel")
      views.setTextViewText(R.id.health_check, if (running) "Проверка…" else "Проверить")
      views.setBoolean(R.id.health_check, "setEnabled", !running)
      views.setTextViewText(R.id.health_vpn, if (vpnActive) "VPN выкл." else "VPN вкл.")
      views.setOnClickPendingIntent(R.id.health_vpn, PendingIntent.getForegroundService(
        context, 2385, Intent(context, ModeActionService::class.java).putExtra("mode", if (vpnActive) "" else "normal"), FLAGS,
      ))
      views.setOnClickPendingIntent(R.id.health_check, PendingIntent.getForegroundService(
        context, 2384, Intent(context, DiagnosticService::class.java).setAction("CHECK"), FLAGS,
      ))
      views.setOnClickPendingIntent(R.id.health_root, PendingIntent.getActivity(
        context, 2383, Intent(context, ConnectionDiagnosticsActivity::class.java), FLAGS,
      ))
    }
  }
}
