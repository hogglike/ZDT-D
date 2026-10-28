package com.android.zdtd.service.diagnostics.nfqws

import android.app.*
import android.content.*
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.PowerManager
import androidx.core.content.ContextCompat
import com.android.zdtd.service.R
import com.android.zdtd.service.api.ApiModels
import com.android.zdtd.service.modes.ModeClient
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.atomic.AtomicReference

/** A manual and an automated tester may never own the native test queue together. */
object ZapretTestOwner { val owner = AtomicReference<String?>(null) }
object ZapretAutoReport {
  val state = MutableStateFlow(JSONObject())
  fun restore(context: Context) {
    if (state.value.length() == 0) {
      val old = runCatching { JSONObject(context.getSharedPreferences("zapret_auto", 0).getString("report", "{}")!!) }.getOrDefault(JSONObject())
      if (old.optBoolean("running")) old.put("running", false).put("message", "Предыдущая проверка прервана; запусти заново")
      state.value = old
    }
  }
  fun publish(context: Context, report: JSONObject) {
    val copy = JSONObject(report.toString()); state.value = copy
    context.getSharedPreferences("zapret_auto", 0).edit().putString("report", copy.toString()).apply()
  }
}

class ZapretAutoService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var job: Job? = null
  private var owns = false
  private var wake: PowerManager.WakeLock? = null
  override fun onBind(intent: Intent?) = null
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (intent?.action == "stop") { job?.cancel(); return START_NOT_STICKY }
    if (job?.isActive == true) return START_NOT_STICKY
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("zapret_auto", "Автоподбор Zapret", NotificationManager.IMPORTANCE_LOW))
    val stop = PendingIntent.getService(this, 19980, Intent(this, ZapretAutoService::class.java).setAction("stop"), PendingIntent.FLAG_IMMUTABLE)
    startForeground(19980, Notification.Builder(this, "zapret_auto").setSmallIcon(R.drawable.ic_qs_tile).setContentTitle("ZDT-D · автоподбор Zapret")
      .setContentText("Проверка стратегий по HTTPS").addAction(Notification.Action.Builder(null, "Остановить", stop).build()).build())
    owns = ZapretTestOwner.owner.compareAndSet(null, "auto")
    if (!owns) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY }
    val program = intent?.getStringExtra("program").orEmpty()
    val urls = intent?.getStringArrayListExtra("urls").orEmpty()
    val limit = intent?.getIntExtra("limit", 12)?.coerceIn(1, 100) ?: 12
    job = scope.launch { withTimeout(30 * 60 * 1000L) { runSession(program, urls, limit) } }
    return START_NOT_STICKY
  }
  private suspend fun runSession(program: String, urls: List<String>, limit: Int) {
    val runner = NfqwsTesterRunner(this)
    val report = JSONObject().put("program", program).put("running", true).put("started", System.currentTimeMillis()).put("results", JSONArray())
    fun publish(message: String) { report.put("message", message); ZapretAutoReport.publish(this, report) }
    var nativeOwned = false
    try {
      require(program in listOf("nfqws", "nfqws2")) { "Неизвестное ядро" }
      require(urls.size in 1..4 && urls.all { u -> runCatching { val url = URL(u); url.protocol == "https" && url.host.isNotBlank() && url.userInfo == null && url.port in listOf(-1, 443) }.getOrDefault(false) }) { "Нужны 1–4 HTTPS-адреса без пароля, порт 443" }
      val cm = getSystemService(ConnectivityManager::class.java)
      val network = cm.activeNetwork ?: error("Нет подключения к сети")
      val caps = cm.getNetworkCapabilities(network) ?: error("Сеть недоступна")
      check(!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) { "Выключи другой VPN перед подбором" }
      val api = ModeClient.api(this)
      suspend fun environment() {
        currentCoroutineContext().ensureActive()
        check(cm.activeNetwork == network) { "Сеть сменилась. Результаты сохранены; повтори подбор в новой сети" }
        val s = api.getStatus() ?: error("Не удалось получить состояние ZDT-D")
        check(!ApiModels.isServiceOn(s) && !s.startInProgress && !s.stopInProgress) { "Для теста останови ZDT-D. Проверка не меняет работающие профили" }
      }
      environment()
      check(!runner.status().optBoolean("active")) { "Другой тест уже запущен. Останови его перед автоподбором" }
      nativeOwned = true
      runner.stopTester() // Remove a stale session only after confirming no live test or production service.
      wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$packageName:zapret-auto").also { it.acquire(30 * 60 * 1000L) }
      report.put("network", if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) "Wi-Fi" else "Мобильная сеть")
      publish("Проверка без стратегии…")
      val baseline = probeRound(urls)
      report.put("baseline", baseline)
      val strategies = runner.listStrategies(program).take(limit)
      check(strategies.isNotEmpty()) { "Нет стратегий в модуле для $program" }
      report.put("total", strategies.size)
      for ((index, file) in strategies.withIndex()) {
        environment()
        report.put("index", index + 1); publish("${index + 1}/${strategies.size}: $file")
        val row = JSONObject().put("strategy", file).put("passed", false)
        try {
          val start = runner.startStrategy(program, "/data/adb/modules/ZDT-D/strategic/strategicvar/$program/$file", guarded = true)
          check(start.optBoolean("ok")) { "Ядро не приняло стратегию" }
          delay(300)
          check(runner.status().optBoolean("active")) { "Ядро остановилось после запуска" }
          val tests = probeRound(urls)
          environment()
          check(runner.status().optBoolean("active")) { "Ядро остановилось во время проверки" }
          row.put("tests", tests).put("passed", (0 until tests.length()).all { tests.getJSONObject(it).optBoolean("ok") })
          row.put("elapsed_ms", (0 until tests.length()).sumOf { tests.getJSONObject(it).optLong("elapsed_ms") })
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { row.put("error", e.message.orEmpty().take(500)) }
        finally { withContext(NonCancellable) { runner.stopTester() } }
        report.getJSONArray("results").put(row); publish("Проверено ${index + 1} из ${strategies.size}")
      }
      val results = report.getJSONArray("results")
      val passed = (0 until results.length()).count { results.getJSONObject(it).optBoolean("passed") }
      publish(if (passed > 0) "Готово: устойчивых вариантов $passed. Выбери стратегию и профиль для применения" else "Устойчивый вариант не найден. Проверь адреса или расширь список стратегий")
    } catch (e: CancellationException) { publish("Остановлено. Завершённые результаты сохранены") }
    catch (e: Exception) { publish(e.message ?: "Ошибка автоподбора") }
    finally {
      withContext(NonCancellable) { if (nativeOwned) runCatching { runner.stopTester() } }
      report.put("running", false).put("finished", System.currentTimeMillis()); ZapretAutoReport.publish(this, report)
      if (wake?.isHeld == true) wake?.release()
      ZapretTestOwner.owner.compareAndSet("auto", null); owns = false
      stopForeground(STOP_FOREGROUND_REMOVE); stopSelf()
    }
  }
  private suspend fun probeRound(urls: List<String>): JSONArray {
    val results = JSONArray()
    repeat(2) { attempt ->
      for (url in urls) {
        currentCoroutineContext().ensureActive()
        val start = System.nanoTime(); val row = JSONObject().put("url", url).put("attempt", attempt + 1)
        var connection: HttpURLConnection? = null
        try {
          connection = URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
          connection.connectTimeout = 5000; connection.readTimeout = 5000; connection.useCaches = false; connection.instanceFollowRedirects = false
          connection.setRequestProperty("Connection", "close")
          val code = connection.responseCode
          row.put("status", code).put("ok", code in 200..399)
          if (code !in 200..399) row.put("error", "HTTP $code")
        } catch (e: Exception) { row.put("ok", false).put("error", e.javaClass.simpleName) }
        finally { connection?.disconnect() }
        row.put("elapsed_ms", (System.nanoTime() - start) / 1_000_000); results.put(row)
      }
    }
    return results
  }
  override fun onDestroy() { job?.cancel(); super.onDestroy() /* finally owns cleanup; native guard covers process death */ }
  companion object {
    fun start(context: Context, program: String, urls: List<String>, limit: Int) {
      ContextCompat.startForegroundService(context, Intent(context, ZapretAutoService::class.java).putExtra("program", program).putStringArrayListExtra("urls", ArrayList(urls)).putExtra("limit", limit))
    }
    fun stop(context: Context) { context.startService(Intent(context, ZapretAutoService::class.java).setAction("stop")) }
  }
}
