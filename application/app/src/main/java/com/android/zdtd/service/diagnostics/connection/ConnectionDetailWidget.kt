package com.android.zdtd.service.diagnostics.connection

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.R
import com.android.zdtd.service.RootConfigManager
import com.android.zdtd.service.ui.theme.NetworkAppearance
import com.android.zdtd.service.ui.theme.ZdtdTheme
import com.android.zdtd.service.ui.theme.ZdtdThemeMode

private val groups = linkedMapOf(
  "ru" to "Сайты РФ", "world" to "Зарубежные сайты", "dns" to "DNS",
  "vpn" to "VPN", "youtube" to "YouTube",
)

private fun preferences(context: Context) = context.getSharedPreferences("connection_widget_details", Context.MODE_PRIVATE)

class ConnectionDetailWidgetProvider : AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    ids.forEach { render(context, manager, it) }
    ConnectionHealthJobService.schedule(context, ConnectionHealth.preferences(context).getBoolean("automatic", false))
  }

  override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
    render(context, manager, id)
  }

  override fun onDeleted(context: Context, ids: IntArray) {
    val edit = preferences(context).edit()
    for (id in ids) edit.remove("groups_$id").remove("style_$id").remove("rows_$id").remove("details_$id")
    edit.apply()
  }

  companion object {
    fun renderAll(context: Context, manager: AppWidgetManager) {
      val provider = ComponentName(context, ConnectionDetailWidgetProvider::class.java)
      manager.getAppWidgetIds(provider).forEach { render(context, manager, it) }
    }

    fun render(context: Context, manager: AppWidgetManager, id: Int) {
      val prefs = preferences(context)
      val selected = prefs.getStringSet("groups_$id", groups.keys).orEmpty()
      val style = prefs.getString("style_$id", "glass")
      val details = prefs.getBoolean("details_$id", true)
      val rowLimit = prefs.getInt("rows_$id", 6).coerceIn(3, 8)
      val height = manager.getAppWidgetOptions(id).getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
      val rowHeight = if (details) 36 else 21
      val visibleRows = if (height > 0) minOf(rowLimit, ((height - 94) / rowHeight).coerceAtLeast(1)) else rowLimit

      val views = RemoteViews(context.packageName, R.layout.widget_connection_detail)
      ConnectionWidgetProvider.bind(context, views)
      val dark = style == "graphite"
      val text = Color.parseColor(if (dark) "#F8F2F4" else "#2B171B")
      val accent = Color.parseColor(if (dark) "#FFBDC4" else "#9E1525")
      val background = when (style) {
        "red" -> R.drawable.widget_detail_red
        "graphite" -> R.drawable.widget_detail_graphite
        else -> R.drawable.widget_detail_glass
      }
      views.setInt(R.id.detail_widget, "setBackgroundResource", background)
      listOf(R.id.detail_title, R.id.health_results, R.id.health_time).forEach { views.setTextColor(it, text) }
      listOf(R.id.health_check, R.id.detail_settings).forEach { views.setTextColor(it, accent) }
      views.setOnClickPendingIntent(R.id.detail_settings, PendingIntent.getActivity(
        context, id, Intent(context, ConnectionDetailWidgetSettingsActivity::class.java)
          .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
      ))

      val report = ConnectionHealth.read(context)
      val entries = report.optJSONArray("rows")
      val rows = (0 until (entries?.length() ?: 0)).mapNotNull { entries?.optJSONObject(it) }.filter { row ->
        val group = row.optString("group").ifBlank {
          when (row.optString("name")) {
            "Яндекс", "VK" -> "ru"
            "Google", "Cloudflare" -> "world"
            "DNS-профили" -> "dns"
            "VPN" -> "vpn"
            "YouTube (HTTPS)" -> "youtube"
            else -> ""
          }
        }
        group in selected
      }
      views.removeAllViews(R.id.detail_rows)
      for (row in rows.take(visibleRows)) {
        val line = RemoteViews(context.packageName, R.layout.widget_connection_detail_row)
        val state = row.optString("state")
        val mark = when (state) {
          "ok" -> "✓"
          "fail" -> "×"
          "off" -> "○"
          else -> "·"
        }
        line.setTextViewText(R.id.detail_row_name, "$mark  ${row.optString("name")}")
        line.setTextViewText(R.id.detail_row_result, row.optString("detail"))
        line.setTextColor(R.id.detail_row_name, if (state == "fail") accent else text)
        line.setTextColor(R.id.detail_row_result, text)
        line.setViewVisibility(R.id.detail_row_result, if (details) View.VISIBLE else View.GONE)
        views.addView(R.id.detail_rows, line)
      }
      views.setTextViewText(R.id.health_results, when {
        rows.isEmpty() && report.optLong("time") == 0L -> "Нажми «Проверить»"
        rows.isEmpty() -> "Для выбранных групп результатов нет"
        rows.size > visibleRows -> "Ещё ${rows.size - visibleRows} · открыть отчёт"
        else -> ""
      })
      views.setViewVisibility(R.id.health_results, if (rows.isEmpty() || rows.size > visibleRows) View.VISIBLE else View.GONE)
      manager.updateAppWidget(id, views)
    }
  }
}

class ConnectionDetailWidgetSettingsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
    val manager = AppWidgetManager.getInstance(this)
    val provider = ComponentName(this, ConnectionDetailWidgetProvider::class.java)
    setResult(Activity.RESULT_CANCELED)
    if (id == AppWidgetManager.INVALID_APPWIDGET_ID || manager.getAppWidgetInfo(id)?.provider != provider) {
      finish()
      return
    }
    val prefs = preferences(this)
    val theme = ZdtdThemeMode.fromStorage(RootConfigManager(this).getThemeMode())
    setContent {
      ZdtdTheme(theme) {
        var chosen by rememberSaveable { mutableStateOf(ArrayList(prefs.getStringSet("groups_$id", groups.keys).orEmpty())) }
        var style by rememberSaveable { mutableStateOf(prefs.getString("style_$id", "glass") ?: "glass") }
        var rows by rememberSaveable { mutableIntStateOf(prefs.getInt("rows_$id", 6).coerceIn(3, 8)) }
        var details by rememberSaveable { mutableStateOf(prefs.getBoolean("details_$id", true)) }
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Виджет «Связь подробно»", style = MaterialTheme.typography.headlineSmall)
            Text("Показывать", style = MaterialTheme.typography.titleMedium)
            for ((key, label) in groups) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(key in chosen, onCheckedChange = { checked ->
                  chosen = ArrayList(if (checked) chosen + key else chosen - key)
                })
                Text(label)
              }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
              Text("HTTP-код, задержка и ошибки", Modifier.weight(1f))
              Switch(details, onCheckedChange = { details = it })
            }
            Text("Максимум строк · $rows")
            Slider(rows.toFloat(), { rows = it.toInt() }, valueRange = 3f..8f, steps = 4)
            Text("Оформление", style = MaterialTheme.typography.titleMedium)
            for (appearance in listOf(NetworkAppearance.RED, NetworkAppearance.GLASS, NetworkAppearance.GRAPHITE)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(style == appearance.key, onClick = { style = appearance.key })
                Text(if (appearance == NetworkAppearance.GLASS) "Стекло" else appearance.label)
              }
            }
            Button(enabled = chosen.isNotEmpty(), onClick = {
              prefs.edit().putStringSet("groups_$id", chosen.toSet()).putString("style_$id", style)
                .putInt("rows_$id", rows).putBoolean("details_$id", details).apply()
              ConnectionDetailWidgetProvider.render(this@ConnectionDetailWidgetSettingsActivity, manager, id)
              ConnectionHealthJobService.schedule(this@ConnectionDetailWidgetSettingsActivity,
                ConnectionHealth.preferences(this@ConnectionDetailWidgetSettingsActivity).getBoolean("automatic", false))
              setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id))
              finish()
            }, modifier = Modifier.fillMaxWidth()) { Text("Сохранить") }
          }
        }
      }
    }
  }
}
