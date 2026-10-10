package com.android.zdtd.service.watch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.android.zdtd.service.R
import com.android.zdtd.service.NonRootVpnRuntime
import com.android.zdtd.service.NonRootVpnState
import com.android.zdtd.service.VpnHevBridge
import com.android.zdtd.service.modes.ModeClient
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/** App-scoped Android VPN -> existing DNS profile SOCKS -> DoH, other traffic direct. */
class WatchDnsVpnService : VpnService() {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val lifecycle = Mutex()
  private var job: Job? = null
  private var nativeJob: Job? = null
  private var tun: ParcelFileDescriptor? = null
  private var lease: VpnHevBridge.Lease? = null
  @Volatile private var stopping = false
  @Volatile private var nativeError: String? = null

  override fun onCreate() {
    super.onCreate()
    if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java)
      .createNotificationChannel(NotificationChannel(CHANNEL, "DNS для часов", NotificationManager.IMPORTANCE_LOW))
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      START -> start()
      STOP -> stop("DNS часов выключен")
      else -> stopSelf(startId)
    }
    return START_NOT_STICKY
  }

  private fun start() {
    if (WatchDnsRuntime.active()) return
    stopping = false
    WatchDnsRuntime.update(WatchDnsState.STARTING, "Проверка DNS-профиля…")
    val open = PendingIntent.getActivity(this, 47032, Intent(this, WatchDnsActivity::class.java),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val off = PendingIntent.getService(this, 47033, stopIntent(this),
      PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_qs_tile)
      .setContentTitle("ZDT-D · DNS для часов").setContentText("Для выбранных приложений связи с часами")
      .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
      .addAction(0, "Выключить", off).build()
    val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
    ServiceCompat.startForeground(this, 47032, notification, type)
    job = scope.launch {
      try {
        check(NonRootVpnRuntime.state.value !in setOf(NonRootVpnState.STARTING, NonRootVpnState.RUNNING, NonRootVpnState.STOPPING)) {
          "Сначала останови VPN в режиме ZDT-D без root"
        }
        val store = WatchDnsStore(this@WatchDnsVpnService)
        val id = store.profile
        check(id.isNotBlank()) { "Выбери DNS-профиль" }
        @Suppress("DEPRECATION")
        val installed = store.apps.filterTo(linkedSetOf()) {
          runCatching { packageManager.getApplicationInfo(it, 0) }.isSuccess
        }
        val apps = WatchDnsPolicy.allowedPackages(store.apps, installed, packageName)
        val api = ModeClient.api(this@WatchDnsVpnService)
        val config = api.getJsonData(CONFIG)
        val profile = config.optJSONObject("profiles")?.optJSONObject(id)
          ?: error("Выбранный DNS-профиль удалён")
        val fingerprint = fingerprint(profile)
        val name = profile.optString("display_name", id)
        val port = readyPort(api.getJsonData(STATUS), id)
        WatchDnsProbe.check(port, (profile.optInt("timeout_ms", 8000) + 1000).coerceIn(3000, 31000))
        currentCoroutineContext().ensureActive()
        lifecycle.withLock {
          check(!stopping) { "Запуск отменён" }
          check(VpnService.prepare(this@WatchDnsVpnService) == null) { "Разрешение Android VPN отозвано" }
          val session = VpnHevBridge.reserve("watch-dns")
          lease = session
          val builder = Builder().setSession("ZDT-D · DNS часов").setMtu(1500)
            .addAddress("198.19.255.1", 32).addAddress("fd90:7a64:7464::1", 128)
            .addRoute("0.0.0.0", 0).addRoute("::", 0).addDnsServer("198.19.255.2")
          if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
          // Never establish without a non-empty allowlist: it would capture all apps.
          apps.forEach { builder.addAllowedApplication(it) }
          val descriptor = builder.establish() ?: error("Не удалось создать Android VPN")
          tun = descriptor
          val configFile = writeHevConfig(port)
          nativeError = null
          nativeJob = scope.launch {
            runCatching { VpnHevBridge.run(session, configFile.absolutePath, descriptor.fd) }
              .onFailure { nativeError = it.message ?: "Мост Android VPN не запустился" }
          }.also { native -> native.invokeOnCompletion { VpnHevBridge.release(session) } }
          withTimeout(5000) {
            while (!VpnHevBridge.isRunning(session)) {
              check(nativeJob?.isCompleted != true) { nativeError ?: "Мост Android VPN не запустился" }
              delay(50)
            }
          }
        }
        WatchDnsRuntime.update(WatchDnsState.RUNNING, "$name · приложений ${apps.size}\nDNS-профиль ответил. Проверь Gemini на часах по Bluetooth")
        var unavailable = 0
        while (isActive) {
          delay(5000)
          val session = lease ?: error("Мост Android VPN остановлен")
          check(VpnHevBridge.isRunning(session)) { "Мост Android VPN остановился" }
          val snapshot = runCatching {
            val latest = api.getJsonData(CONFIG).optJSONObject("profiles")?.optJSONObject(id)
              ?: error("DNS-профиль удалён")
            check(latest.optBoolean("enabled") && fingerprint(latest) == fingerprint) {
              "DNS-профиль изменён. Перезапусти ZDT-D и включи DNS часов снова"
            }
            check(readyPort(api.getJsonData(STATUS), id) == port) { "DNS-профиль перезапущен. Включи DNS часов снова" }
          }
          if (snapshot.isSuccess) unavailable = 0 else {
            unavailable++
            check(unavailable < 3) { snapshot.exceptionOrNull()?.message ?: "Нет связи с DNS-профилем" }
          }
        }
      } catch (e: CancellationException) { throw e }
      catch (e: Exception) {
        if (!stopping) WatchDnsRuntime.update(WatchDnsState.ERROR, e.message ?: "Ошибка DNS часов")
        lifecycle.withLock { teardown() }
        ServiceCompat.stopForeground(this@WatchDnsVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
      }
    }
  }

  private fun stop(message: String) {
    if (stopping) return
    stopping = true
    job?.cancel()
    WatchDnsRuntime.update(WatchDnsState.STOPPING, "Отключение DNS часов…")
    scope.launch {
      lifecycle.withLock { teardown() }
      WatchDnsRuntime.update(WatchDnsState.STOPPED, message)
      ServiceCompat.stopForeground(this@WatchDnsVpnService, ServiceCompat.STOP_FOREGROUND_REMOVE)
      stopSelf()
    }
  }

  private suspend fun teardown() = withContext(NonCancellable) {
    val session = lease
    val native = nativeJob
    if (session != null) {
      if (native == null) VpnHevBridge.release(session)
      else {
        // A stop may arrive just before the native worker enters JNI.
        withTimeoutOrNull(2500) {
          while (!native.isCompleted) {
            runCatching { VpnHevBridge.stop(session) }
            delay(50)
          }
        }
      }
    }
    runCatching { tun?.close() }
    tun = null
    lease = null
    nativeJob = null
  }

  override fun onRevoke() { stop("Android отключил DNS часов: выбран другой VPN"); super.onRevoke() }

  override fun onDestroy() {
    stopping = true
    job?.cancel()
    lease?.let { runCatching { VpnHevBridge.stop(it) }; if (nativeJob == null) VpnHevBridge.release(it) }
    runCatching { tun?.close() }
    scope.cancel()
    if (WatchDnsRuntime.status.value.state != WatchDnsState.ERROR)
      WatchDnsRuntime.update(WatchDnsState.STOPPED, "DNS часов выключен")
    super.onDestroy()
  }

  private fun writeHevConfig(port: Int): File = File(filesDir, "watch_dns_vpn/hev.yml").apply {
    parentFile?.mkdirs()
    // No mapdns: the existing profile answers real UDP/TCP DNS, including the
    // resolver addresses specified by Wear OS instead of Android's DNS hint.
    writeText("""
      tunnel:
        name: tun-watch-dns
        mtu: 1500
        multi-queue: false
        ipv4: 198.19.255.1
        ipv6: 'fd90:7a64:7464::1'
        icmp: 'off'
      socks5:
        address: 127.0.0.1
        port: $port
        udp: 'udp'
      misc:
        task-stack-size: 86016
        tcp-buffer-size: 65536
        connect-timeout: 10000
        tcp-read-write-timeout: 300000
        log-level: warn
        limit-nofile: 65535
    """.trimIndent() + "\n")
  }

  companion object {
    private const val CHANNEL = "watch_dns_vpn"
    private const val START = "com.android.zdtd.service.WATCH_DNS_START"
    private const val STOP = "com.android.zdtd.service.WATCH_DNS_STOP"
    internal const val CONFIG = "/api/programs/dnsprofiles/config"
    internal const val STATUS = "/api/programs/dnsprofiles/status"
    fun startIntent(context: Context) = Intent(context, WatchDnsVpnService::class.java).setAction(START)
    fun stopIntent(context: Context) = Intent(context, WatchDnsVpnService::class.java).setAction(STOP)
    private fun fingerprint(profile: JSONObject) = listOf(profile.optString("endpoint"),
      profile.optJSONArray("bootstrap").toString(), profile.optInt("timeout_ms").toString()).joinToString("|")
    private fun readyPort(status: JSONObject, id: String): Int {
      check(!status.optBoolean("suspended_for_tethering")) { "DNS-профили приостановлены переключателем режима раздачи" }
      val profile = status.optJSONObject("profiles")?.optJSONObject(id) ?: JSONObject()
      return WatchDnsPolicy.requireProfile(profile.optBoolean("enabled") && profile.optBoolean("process_running") &&
        profile.optBoolean("config_current"), profile.optInt("proxy_port"))
    }
  }
}
