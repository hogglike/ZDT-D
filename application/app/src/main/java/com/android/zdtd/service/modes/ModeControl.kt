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
import kotlinx.coroutines.*
import org.json.JSONObject

object ModeClient {
  val modes = listOf("white", "normal", "browser")
  fun api(context: Context): ApiClient {
    val root = RootConfigManager(context.applicationContext)
    return ApiClient(rootManager = root, baseUrlProvider = { "http://127.0.0.1:1006" }, tokenProvider = { root.readApiToken() })
  }
  private fun prefs(context: Context) = context.getSharedPreferences("connection_modes", Context.MODE_PRIVATE)
  fun cached(context: Context): JSONObject = runCatching { JSONObject(prefs(context).getString("status", "{}")!!) }.getOrDefault(JSONObject())
  fun cache(context: Context, status: JSONObject) {
    val old = cached(context)
    val changed = listOf("mode", "state", "server").any { old.optString(it) != status.optString(it) }
    prefs(context).edit().putString("status", status.toString()).apply()
    ModeWidgetProvider.render(context)
    if (!changed) return
    listOf(WhiteModeTile::class.java, NormalModeTile::class.java, BrowserModeTile::class.java).forEach {
      TileService.requestListeningState(context, ComponentName(context, it))
    }
  }
  fun refresh(context: Context): JSONObject {
    val status = api(context).getJsonData("/api/connection-modes/status").optJSONObject("status") ?: error("Нет связи со службой ZDT-D")
    cache(context, status)
    return status
  }
  fun failure(context: Context, mode: String, message: String) {
    cache(context, JSONObject().put("mode", mode).put("state", "error").put("message", message))
  }
  fun pickServer(context: Context, mode: String) {
    context.startActivity(Intent(context, ModeServerPickerActivity::class.java).putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
  fun switch(context: Context, mode: String) {
    context.startActivity(Intent(context, ModeSwitchActivity::class.java).putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
  }
}

/** All three entry points share Wi-Fi confirmation; cancel never sends a command. */
class ModeSwitchActivity : Activity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val mode = intent.getStringExtra("mode").orEmpty()
    if (mode.isNotEmpty() && mode !in ModeClient.modes) { finish(); return }
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
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("modes", "Режимы подключения", NotificationManager.IMPORTANCE_LOW))
    startForeground(19972, Notification.Builder(this, "modes").setSmallIcon(R.drawable.ic_qs_tile)
      .setContentTitle("ZDT-D · режимы").setContentText(if (ping) "Проверка серверов" else "Подключение…").build())
    job?.cancel()
    job = scope.launch {
      try {
        ModeClient.cache(this@ModeActionService, JSONObject().put("mode", mode).put("state", "connecting").put("message", "Ожидание службы…"))
        val api = ModeClient.api(this@ModeActionService)
        if (mode.isNotEmpty() || ping) {
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
        val result = api.postJsonResult("/api/connection-modes/${if (ping) "ping" else "activate"}", JSONObject().put("mode", mode))
        check(result.optBoolean("ok", false)) { "Служба не приняла команду" }
        while (isActive) {
          val status = ModeClient.refresh(this@ModeActionService)
          if (status.optString("state") != "connecting") break
          delay(1000)
        }
      } catch (e: CancellationException) { throw e }
      catch (e: Exception) { ModeClient.failure(this@ModeActionService, mode, e.message ?: "Ошибка подключения") }
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

class ModeWidgetProvider : android.appwidget.AppWidgetProvider() {
  override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
    render(context)
    context.sendBroadcast(Intent(context, ModeStatusReceiver::class.java))
  }
  companion object {
    fun render(context: Context) {
      val manager = AppWidgetManager.getInstance(context)
      val ids = manager.getAppWidgetIds(ComponentName(context, ModeWidgetProvider::class.java))
      val status = ModeClient.cached(context)
      for (id in ids) {
        val views = RemoteViews(context.packageName, R.layout.widget_connection_modes)
        views.setTextViewText(R.id.mode_message, status.optString("message", "Открой настройки режимов"))
        val buttons = listOf(R.id.mode_white, R.id.mode_normal, R.id.mode_browser)
        val pickers = listOf(R.id.pick_white, R.id.pick_normal, R.id.pick_browser)
        ModeClient.modes.forEachIndexed { index, mode ->
          views.setOnClickPendingIntent(pickers[index], PendingIntent.getActivity(context, 20100 + index,
            Intent(context, ModeServerPickerActivity::class.java).putExtra("mode", mode), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
          views.setInt(buttons[index], "setBackgroundColor", ModeState.color(mode, status.optString("mode"), status.optString("state")))
          views.setOnClickPendingIntent(buttons[index], PendingIntent.getActivity(context, 19972 + index,
            Intent(context, ModeSwitchActivity::class.java).putExtra("mode", mode), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        views.setOnClickPendingIntent(R.id.mode_settings, PendingIntent.getActivity(context, 19980,
          Intent(context, ConnectionModesActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        manager.updateAppWidget(id, views)
      }
    }
  }
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
    val intent = Intent(this, ModeSwitchActivity::class.java).putExtra("mode", mode).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(PendingIntent.getActivity(this, 20000 + ModeClient.modes.indexOf(mode), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
    else startActivityAndCollapse(intent)
  }
  override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
class WhiteModeTile : ConnectionModeTile("white")
class NormalModeTile : ConnectionModeTile("normal")
class BrowserModeTile : ConnectionModeTile("browser")
