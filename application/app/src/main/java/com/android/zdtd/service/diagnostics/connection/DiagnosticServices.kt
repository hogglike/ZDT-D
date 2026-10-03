package com.android.zdtd.service.diagnostics.connection

import android.app.*
import android.app.job.*
import android.content.*
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.android.zdtd.service.BuildConfig
import com.android.zdtd.service.RootConfigManager
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DiagnosticService : Service() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var task: Job? = null
  override fun onBind(intent: Intent?): IBinder? = null
  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    if (task?.isActive == true) return START_NOT_STICKY
    val hotspot = intent?.action == "HOTSPOT"
    ConnectionHealth.preferences(this).edit().remove("error").apply()
    val manager = getSystemService(NotificationManager::class.java)
    manager.createNotificationChannel(NotificationChannel("connection_diagnostics", "Диагностика соединения", NotificationManager.IMPORTANCE_LOW))
    val open = PendingIntent.getActivity(this, 2381, Intent(this, ConnectionDiagnosticsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    startForeground(2381, NotificationCompat.Builder(this, "connection_diagnostics")
      .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("ZDT-D: диагностика")
      .setContentText(if (hotspot) "Запись 90 с: попробуй включить точку доступа" else "Проверка соединения")
      .setContentIntent(open).setOngoing(true).build())
    task = scope.launch {
      try {
        if (hotspot) captureHotspot() else ConnectionHealth.check(applicationContext)
      } catch (e: CancellationException) { throw e
      } catch (e: Exception) {
        ConnectionHealth.preferences(this@DiagnosticService).edit().putString("error", "${e.javaClass.simpleName}: ${e.message}").apply()
      } finally {
        stopSelf(startId)
      }
    }
    return START_NOT_STICKY
  }

  private suspend fun captureHotspot() {
    val prefs = ConnectionHealth.preferences(this)
    prefs.edit().putLong("capture_since", System.currentTimeMillis()).remove("error").apply()
    val root = RootConfigManager(this)
    val report = StringBuilder("ZDT-D ${BuildConfig.VERSION_NAME}\n${Date()}\n${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}; Android ${android.os.Build.VERSION.RELEASE}\nRead-only capture; no routing/settings changes.\n")
    fun shell(script: String): String {
      val quoted = "'" + script.replace("'", "'\\''") + "'"
      val r = root.execRoot("sh -c $quoted")
      return (r.out + r.err).joinToString("\n").take(350_000)
        .replace(Regex("(?i)(passphrase|password|psk|ssid|bssid|serial|token)\\s*[:=]\\s*[^,}\\n]+"), "$1=<redacted>")
        .replace(Regex("(?i)[0-9a-f]{2}(?::[0-9a-f]{2}){5}"), "<mac>")
    }
    // A timeout wrapper is mandatory; OEM dumpsys services can otherwise block indefinitely.
    val prefix = "command -v timeout >/dev/null 2>&1 || { echo 'ERROR: timeout unavailable'; exit 1; }; "
    fun snapshot(label: String) {
      report.append("\n=== $label ${Date()} ===\n")
      report.append(shell(prefix + "timeout 4 dumpsys tethering; timeout 4 dumpsys wifi | grep -Ei 'SoftAp|AP state|failure|fail reason|tether|hostapd' | grep -Eiv 'configuration|ssid|passphrase|password|psk'; timeout 4 ip -s link; timeout 4 ip rule; timeout 4 ip route show table all; cat /proc/sys/net/ipv4/ip_forward"))
    }
    try {
      check(shell("id -u").trim() == "0") { "Для системных журналов нужен root" }
      check(!shell(prefix + "echo TIMEOUT_OK").contains("ERROR:")) { "На устройстве не найден timeout" }
      snapshot("BEFORE")
      val start = android.os.SystemClock.elapsedRealtime()
      var last = ""
      while (currentCoroutineContext().isActive && android.os.SystemClock.elapsedRealtime() - start < 90_000) {
        val logs = shell(prefix + "timeout 4 logcat -d -t 600 -b all -v threadtime | grep -Ei 'SoftAp|hostapd|Tethering|IpServer|tether|ap.*fail|netd.*(error|fail)' | grep -Eiv 'passphrase|password|psk|ssid|bssid'")
        // Keep only new lines between snapshots; never clear the system log.
        val previous = last.lines().toSet()
        report.append("\n=== EVENTS ${Date()} ===\n")
        if (report.length < 2_000_000) report.append(logs.lines().filter { it.isNotBlank() && it !in previous }.joinToString("\n"))
        last = logs
        delay(5_000)
      }
      snapshot("AFTER")
    } catch (e: Exception) {
      report.append("\nCAPTURE ENDED: ${e.javaClass.simpleName}: ${e.message}\n")
      throw e
    } finally {
      // Keep a partial report on user cancellation as well.
      val target = File(cacheDir, "ZDTD_hotspot_report.zip")
      val temporary = File(cacheDir, "ZDTD_hotspot_report.tmp")
      try {
        ZipOutputStream(temporary.outputStream()).use { zip ->
          zip.putNextEntry(ZipEntry("hotspot.txt")); zip.write(report.toString().toByteArray()); zip.closeEntry()
          zip.putNextEntry(ZipEntry("README.txt"))
          zip.write("Отчёт содержит системные ошибки и сетевые адреса. Редактирование известных секретных полей выполнено автоматически, но перед отправкой проверь содержимое. Журналы сохраняются только на устройстве. После старта записи попробуй включить раздачу. Подключения и DNS не изменяются. Наличие отчёта не означает, что причина найдена.\n".toByteArray())
          zip.closeEntry()
        }
        check(temporary.renameTo(target)) { "Не удалось сохранить ZIP" }
        prefs.edit().putLong("capture_finished", System.currentTimeMillis()).apply()
      } finally { prefs.edit().remove("capture_since").apply() }
    }
  }

  override fun onDestroy() { scope.cancel(); super.onDestroy() }
}

/** Periodic jobs may be delayed by Android or the manufacturer's battery policy. */
class ConnectionHealthJobService : JobService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private var task: Job? = null
  override fun onStartJob(params: JobParameters): Boolean {
    if (!ConnectionHealth.preferences(this).getBoolean("automatic", false)) return false
    task = scope.launch {
      try { ConnectionHealth.check(applicationContext) }
      catch (e: CancellationException) { throw e }
      catch (e: Exception) {
        ConnectionHealth.preferences(this@ConnectionHealthJobService).edit().putString("error", e.javaClass.simpleName).apply()
      }
      finally { if (isActive) jobFinished(params, false) }
    }
    return true
  }
  override fun onStopJob(params: JobParameters): Boolean { task?.cancel(); return false }
  override fun onDestroy() { scope.cancel(); super.onDestroy() }
  companion object {
    fun schedule(context: Context, enabled: Boolean) {
      val scheduler = context.getSystemService(JobScheduler::class.java)
      if (enabled) scheduler.schedule(JobInfo.Builder(2382, ComponentName(context, ConnectionHealthJobService::class.java))
        .setPeriodic(30 * 60 * 1000L).build())
      else scheduler.cancel(2382)
    }
  }
}
