package com.android.zdtd.service.diagnostics.connection

import android.app.PendingIntent
import android.appwidget.*
import android.content.*
import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.RemoteViews
import com.android.zdtd.service.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConnectionWidgetProvider : AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    render(context)
    ConnectionHealthJobService.schedule(context, ConnectionHealth.preferences(context).getBoolean("automatic", false))
  }
  companion object {
    fun render(context: Context) {
      val manager = AppWidgetManager.getInstance(context)
      val report = ConnectionHealth.read(context)
      val rows = report.optJSONArray("rows")
      val time = report.optLong("time")
      val age = System.currentTimeMillis() - time
      val since = ConnectionHealth.preferences(context).getLong("running_since", 0)
      val running = since > 0 && System.currentTimeMillis() - since < 120_000
      val state = if (running) "Проверка…" else if (time == 0L) "Ещё не проверено" else
        SimpleDateFormat("dd.MM HH:mm", Locale.getDefault()).format(Date(time)) + if (age > 60 * 60 * 1000) " · устарело" else ""
      val text = if (rows == null) "Opera · DNS · YouTube · интернет" else (0 until rows.length()).joinToString("\n") {
        val r = rows.getJSONObject(it)
        val icon = when (r.optString("state")) { "ok" -> "✓"; "off" -> "○"; else -> "!" }
        "$icon ${r.optString("name")}"
      }
      val open = PendingIntent.getActivity(context, 2383, Intent(context, ConnectionDiagnosticsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      val check = PendingIntent.getForegroundService(context, 2384, Intent(context, DiagnosticService::class.java).setAction("CHECK"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
      manager.getAppWidgetIds(ComponentName(context, ConnectionWidgetProvider::class.java)).forEach { id ->
        val views = RemoteViews(context.packageName, R.layout.widget_connection_health)
        views.setTextViewText(R.id.health_time, state)
        val colored = SpannableString(text)
        if (rows != null) {
          var offset = 0
          text.split("\n").forEachIndexed { index, line ->
            val color = if (age > 60 * 60 * 1000) Color.GRAY else when (rows.getJSONObject(index).optString("state")) {
              "ok" -> Color.rgb(40, 116, 59)
              "off" -> Color.GRAY
              else -> Color.rgb(173, 24, 48)
            }
            colored.setSpan(ForegroundColorSpan(color), offset, offset + line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            offset += line.length + 1
          }
        }
        views.setTextViewText(R.id.health_results, colored)
        views.setOnClickPendingIntent(R.id.health_check, check)
        views.setOnClickPendingIntent(R.id.health_root, open)
        manager.updateAppWidget(id, views)
      }
    }
  }
}
