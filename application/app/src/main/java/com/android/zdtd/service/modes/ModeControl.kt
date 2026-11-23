package com.android.zdtd.service.modes

import android.app.*
import android.appwidget.AppWidgetManager
import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.*
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.android.zdtd.service.R
import com.android.zdtd.service.RootConfigManager
import com.android.zdtd.service.api.ApiClient
import com.android.zdtd.service.api.ApiModels
import com.android.zdtd.service.diagnostics.connection.ConnectionWidgetProvider
import kotlinx.coroutines.*
import org.json.JSONObject

object ModeClient {
  val modes = listOf("white", "normal", "normal2", "browser")
  fun api(context: Context): ApiClient {
    val root = RootConfigManager(context.applicationContext)
    return ApiClient(rootManager = root, baseUrlProvider = { "http://127.0.0.1:1006" }, tokenProvider = { root.readApiToken() })
  }
  private fun prefs(context: Context) = context.getSharedPreferences("connection_modes", Context.MODE_PRIVATE)
  fun cached(context: Context): JSONObject = runCatching { JSONObject(prefs(context).getString("status", "{}")!!) }.getOrDefault(JSONObject())
  fun cache(context: Context, status: JSONObject) {
    val old = cached(context)
    prefs(context).edit().putString("status", status.toString()).apply()
    if (old.optString("mode") != status.optString("mode") ||
      old.optString("state") != status.optString("state") ||
      old.optBoolean("verified") != status.optBoolean("verified") ||
      old.optString("server") != status.optString("server") ||
      old.optString("vpn_health") != status.optString("vpn_health") ||
      old.optJSONObject("mode_summary")?.toString() != status.optJSONObject("mode_summary")?.toString()
    ) {
      ConnectionWidgetProvider.render(context)
    }
  }
  fun refresh(context: Context): JSONObject {
    val status = api(context).getJsonData("/api/connection-modes/status").optJSONObject("status") ?: error("Нет связи со службой ZDT-D")
    cache(context, status)
    return status
  }
  fun failure(context: Context, mode: String, message: String) {
    cache(context, cached(context).put("mode", mode).put("state", "error").put("message", message))
  }
  fun pickServer(context: Context, mode: String) {
    context.startActivity(Intent(context, ModeServerPickerActivity::class.java).putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
  fun switch(context: Context, mode: String) {
    val cm = context.getSystemService(ConnectivityManager::class.java)
    val wifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    try {
      if (mode == "white" && wifi) context.startActivity(confirmation(context, mode))
      else ContextCompat.startForegroundService(context, Intent(context, ModeActionService::class.java).putExtra("mode", mode))
    } catch (e: Exception) { failure(context, mode, "Не удалось запустить: ${e.message}") }
  }
  fun confirmation(context: Context, mode: String) = Intent(context, ModeSwitchActivity::class.java)
    .putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
  fun maintenance(context: Context, refresh: Boolean, subscriptionOnly: Boolean = false) {
    try { ContextCompat.startForegroundService(context, Intent(context, ModeActionService::class.java).putExtra(if (refresh) "refresh_servers" else "ping", true).putExtra("subscription_only", subscriptionOnly)) }
    catch (e: Exception) { maintenanceFailure(context, e.message.orEmpty()) }
  }
  fun maintenanceFailure(context: Context, message: String) {
    cache(context, cached(context).put("maintenance_state", "error").put("maintenance_message", message))
  }
}

/** All three entry points share Wi-Fi confirmation; cancel never sends a command. */
class ModeSwitchActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val mode = intent.getStringExtra("mode").orEmpty()
    if (mode.isNotEmpty() && mode !in ModeClient.modes) { finish(); return }
    val summary = ModeClient.cached(this).optJSONObject("mode_summary")?.optJSONObject(mode)
    if (intent.getBooleanExtra("widget", false) && summary?.optBoolean("ready") != true) {
      ModeClient.pickServer(this, mode)
      finish()
      return
    }
    val cm = getSystemService(ConnectivityManager::class.java)
    val wifi = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    if (mode == "white" && wifi) {
      AlertDialog.Builder(this).setTitle("Wi-Fi подключён")
        .setMessage("Точно включить «Белый» режим? Выбранные приложения будут подключены через сервер подписки.")
        .setPositiveButton("Подключить") { _, _ -> start(mode) }
        .setNegativeButton("Отмена") { _, _ -> finish() }.setOnCancelListener { finish() }.show()
    } else start(mode)
  }
  private fun start(mode: String) {
    try { ContextCompat.startForegroundService(this, Intent(this, ModeActionService::class.java).putExtra("mode", mode)) }
    catch (e: Exception) { ModeClient.failure(this, mode, "Не удалось запустить: ${e.message}") }
    finish()
  }
}

class ModeActionService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var job: Job? = null
  override fun onBind(intent: Intent?) = null
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent == null) { stopSelf(); return START_NOT_STICKY }
    val mode = intent.getStringExtra("mode").orEmpty()
    val ping = intent.getBooleanExtra("ping", false)
    val refreshServers = intent.getBooleanExtra("refresh_servers", false)
    val maintenance = ping || refreshServers
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("modes", "Режимы подключения", NotificationManager.IMPORTANCE_LOW))
    startForeground(19972, Notification.Builder(this, "modes").setSmallIcon(R.drawable.ic_qs_tile)
      .setContentTitle("ZDT-D · режимы").setContentText(if (refreshServers) "Обновление и проверка серверов" else if (ping) "Проверка серверов" else "Подключение…").build())
    job?.cancel()
    job = scope.launch {
      try {
        if (maintenance) ModeClient.cache(this@ModeActionService, ModeClient.cached(this@ModeActionService).put("maintenance_state", "pending").put("maintenance_message", "Ожидание службы…"))
        else ModeClient.cache(this@ModeActionService, ModeClient.cached(this@ModeActionService).put("mode", mode).put("state", "connecting").put("vpn_health", "").put("message", "Ожидание службы…"))
        val api = ModeClient.api(this@ModeActionService)
        if (mode.isNotEmpty() || maintenance) {
          if (!ApiModels.isServiceOn(api.getStatus())) check(api.startService()) { "Не удалось запустить ZDT-D" }
          var started = false
          repeat(30) {
            if (!started) {
              val report = api.getStatus()
              started = report != null && ApiModels.isServiceOn(report) && !report.startInProgress && !report.stopInProgress
              if (!started) delay(1000)
            }
          }
          check(started) { "Служба не запустилась за 30 секунд" }
        }
        val result = api.postJsonResult("/api/connection-modes/${if (refreshServers) "refresh-servers" else if (ping) "ping" else "activate"}", JSONObject().put("mode", mode).put("only", intent.getBooleanExtra("subscription_only", false)))
        check(result.optBoolean("ok", false)) { "Служба не приняла команду" }
        while (isActive) {
          val status = ModeClient.refresh(this@ModeActionService)
          if (maintenance) {
            if (status.optString("maintenance_state") !in listOf("pending", "refreshing", "checking")) break
          } else if (status.optString("state") != "connecting") break
          delay(1000)
        }
      } catch (e: CancellationException) { throw e }
      catch (e: Exception) {
        if (maintenance) {
          val latest = runCatching { ModeClient.refresh(this@ModeActionService) }.getOrNull()
          if (latest?.optString("maintenance_state") !in listOf("pending", "refreshing", "checking")) ModeClient.maintenanceFailure(this@ModeActionService, e.message ?: "Ошибка обновления/проверки")
        }
        else ModeClient.failure(this@ModeActionService, mode, e.message ?: "Ошибка подключения")
      }
      finally { if (isActive) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(startId) } }
    }
    return START_NOT_STICKY
  }
  override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

/** Broadcast carries no trusted state: fetch the authenticated local API instead. */
class ModeStatusReceiver : BroadcastReceiver() {
  override fun onReceive(context: Context, intent: Intent?) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.IO).launch {
      try { ModeClient.refresh(context) }
      catch (_: Exception) { ModeClient.failure(context, ModeClient.cached(context).optString("mode"), "Нет связи со службой") }
      finally { pending.finish() }
    }
  }
}

internal fun bindVpnWidget(context: Context, views: RemoteViews, graphite: Boolean) {
  val status = ModeClient.cached(context)
  val state = status.optString("state")
  val mode = status.optString("mode")
  val health = status.optString("vpn_health")
  val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
  val active = state in listOf("connected", "connecting")
  val buttons = listOf(R.id.mode_white, R.id.mode_normal, R.id.mode_normal2, R.id.mode_browser)
  ModeClient.modes.forEachIndexed { index, target ->
    val intent = ModeClient.confirmation(context, target).putExtra("widget", true)
    views.setOnClickPendingIntent(buttons[index], PendingIntent.getActivity(context, 20200 + index, intent, flags))
    val selected = target == mode
    val connected = selected && state == "connected" && health !in listOf("checking", "partial", "fail")
    val background = when {
      connected -> R.drawable.widget_network_button_selected
      selected && (active || state == "error") -> R.drawable.widget_network_button_pending
      else -> R.drawable.widget_network_button
    }
    views.setInt(buttons[index], "setBackgroundResource", background)
    views.setTextColor(buttons[index], context.getColor(when {
      connected -> R.color.widget_network_on_accent
      selected && (active || state == "error") -> R.color.widget_network_accent
      else -> R.color.widget_network_text
    }))
  }
  val detail = when (health) {
    "checking" -> "проверка"
    "partial" -> "частичный доступ"
    "fail" -> "сайты не ответили"
    else -> ""
  }
  views.setTextViewText(R.id.mode_status, when (state) {
    "connected" -> listOf(status.optString("server"), detail).filter { it.isNotBlank() }.joinToString(" · ")
    "connecting" -> "${ModeState.title(mode)} · подключение"
    "error" -> "VPN · ошибка подключения"
    else -> "VPN выключен"
  })
  val target = mode.takeIf { it in ModeClient.modes } ?: "normal"
  val picker = Intent(context, ModeServerPickerActivity::class.java).putExtra("mode", target)
  val settings = Intent(context, ConnectionModesActivity::class.java).putExtra("mode", target)
  views.setOnClickPendingIntent(R.id.mode_settings, PendingIntent.getActivity(context, 20204, settings, flags))
  views.setOnClickPendingIntent(R.id.mode_server, PendingIntent.getActivity(context, 20206, picker, flags))
  views.setBoolean(R.id.mode_stop, "setEnabled", active)
  views.setTextColor(R.id.mode_stop,
    if (graphite) android.graphics.Color.parseColor(if (active) "#FFBDC4" else "#C5B8BE")
    else context.getColor(if (active) R.color.widget_network_accent else R.color.widget_network_muted),
  )
  val stop = Intent(context, ModeActionService::class.java).putExtra("mode", "")
  views.setOnClickPendingIntent(R.id.mode_stop, PendingIntent.getForegroundService(context, 20205, stop, flags))
}

abstract class ConnectionModeTile(private val mode: String) : TileService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  override fun onStartListening() {
    super.onStartListening()
    scope.launch {
      val s = runCatching { ModeClient.refresh(this@ConnectionModeTile) }.getOrElse { JSONObject() }
      withContext(Dispatchers.Main) {
        val state = s.optString("state")
        val selected = s.optString("mode") == mode
        qsTile?.apply {
          label = "ZDT-D · ${ModeState.title(mode)}"
          this.state = if (selected && state == "connected") Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
          if (Build.VERSION.SDK_INT >= 29) subtitle = when { selected && state == "connecting" -> "Подключение…"; selected && state == "error" -> "Ошибка"; selected && state == "connected" -> s.optString("server"); else -> "Включить" }
          updateTile()
        }
      }
    }
  }
  @Suppress("DEPRECATION")
  override fun onClick() {
    super.onClick()
    val intent = ModeClient.confirmation(this, mode)
    if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 20000 + ModeClient.modes.indexOf(mode), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    else startActivityAndCollapse(intent)
  }
  override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
class WhiteModeTile : ConnectionModeTile("white")
class NormalModeTile : ConnectionModeTile("normal")
class BrowserModeTile : ConnectionModeTile("browser")
