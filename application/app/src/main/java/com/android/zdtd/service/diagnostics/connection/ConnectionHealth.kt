package com.android.zdtd.service.diagnostics.connection

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.android.zdtd.service.RootConfigManager
import com.android.zdtd.service.api.ApiClient
import com.android.zdtd.service.modes.ModeClient
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object ConnectionHealth {
  private val running = AtomicBoolean(false)
  fun preferences(context: Context) = context.getSharedPreferences("connection_health", Context.MODE_PRIVATE)
  fun read(context: Context): JSONObject = runCatching {
    JSONObject(preferences(context).getString("report", "{}") ?: "{}")
  }.getOrDefault(JSONObject())

  data class Target(val name: String, val url: String, val group: String, val expected: Int, val enabled: Boolean = true)
  val defaults = listOf(
    Target("Яндекс", "https://ya.ru/robots.txt", "ru", 200),
    Target("VK", "https://vk.com/robots.txt", "ru", 200),
    Target("Google", "https://www.gstatic.com/generate_204", "world", 204),
    Target("Cloudflare", "https://www.cloudflare.com/cdn-cgi/trace", "world", 200),
  )
  fun validTarget(target: Target): Boolean {
    val address = target.url.toHttpUrlOrNull() ?: return false
    return address.isHttps && address.username.isEmpty() && address.password.isEmpty() &&
      target.name.isNotBlank() && (target.expected == 0 || target.expected in 100..599)
  }
  fun targets(context: Context): List<Target> = runCatching {
    val saved = JSONArray(preferences(context).getString("targets", null) ?: return defaults)
    (0 until saved.length()).map { index ->
      val row = saved.getJSONObject(index)
      Target(row.getString("name"), row.getString("url"), row.getString("group"), row.optInt("expected"), row.optBoolean("enabled", true))
    }
  }.getOrDefault(defaults)
  fun saveTargets(context: Context, targets: List<Target>, timeout: Int) {
    val saved = JSONArray(targets.map {
      JSONObject().put("name", it.name).put("url", it.url).put("group", it.group).put("expected", it.expected).put("enabled", it.enabled)
    })
    preferences(context).edit().putString("targets", saved.toString()).putInt("target_timeout", timeout).remove("report").apply()
    ConnectionWidgetProvider.render(context)
  }

  private fun api(root: RootConfigManager) = ApiClient(root, { "http://127.0.0.1:1006" }, { root.readApiToken() })

  suspend fun check(context: Context) {
    if (!running.compareAndSet(false, true)) return
    val prefs = preferences(context)
    prefs.edit().putLong("running_since", System.currentTimeMillis()).apply()
    ConnectionWidgetProvider.render(context)
    try {
      val result = coroutineScope {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun physical(n: Network?) = n != null && cm.getNetworkCapabilities(n)?.let {
          it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
        } == true
        val network = cm.activeNetwork.takeIf { physical(it) } ?: cm.allNetworks.firstOrNull { physical(it) }
        val caps = network?.let { cm.getNetworkCapabilities(it) }
        val networkName = when {
          caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true -> "Wi-Fi"
          caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true -> "Мобильная сеть"
          network != null -> "Другая сеть"
          else -> "Нет физической сети"
        }
        val timeout = prefs.getInt("target_timeout", 4).coerceIn(2, 15).toLong()
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
          .connectTimeout(timeout, TimeUnit.SECONDS).readTimeout(timeout, TimeUnit.SECONDS)
          .callTimeout(timeout, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
          .apply { if (network != null) {
            socketFactory(network.socketFactory)
            dns(object : Dns { override fun lookup(hostname: String) = network.getAllByName(hostname).toList() })
          } }
          .build()
        val direct = (targets(context).filter { it.enabled && validTarget(it) } +
          Target("YouTube (HTTPS)", "https://www.youtube.com/robots.txt", "youtube", 200)).map { target -> async {
          val result = if (network == null) row(target.name, "fail", "Нет физической сети")
            else probe(client, target.name, target.url, target.expected.takeIf { it != 0 })
          result.put("group", target.group)
        } }
        val dns = async(Dispatchers.IO) {
          try {
            val d = api(RootConfigManager(context)).postJsonResult("/api/programs/dnsprofiles/diagnose", JSONObject())
            val data = d.optJSONObject("data") ?: d
            val count = data.optInt("enabled_profiles")
            val passed = data.optInt("passed_profiles")
            when {
              data.optBoolean("suspended_for_tethering") -> row("DNS-профили", "off", "Отключены для раздачи")
              !data.has("enabled_profiles") -> row("DNS-профили", "fail", "Неполный ответ демона")
              count == 0 -> row("DNS-профили", "off", "Нет включённых профилей")
              else -> row("DNS-профили", if (passed == count) "ok" else "fail", "Ответ DNS: $passed из $count. Проверка локальных слушателей, не каждого UID")
            }
          } catch (e: Exception) { row("DNS-профили", "fail", e.javaClass.simpleName) }
        }
        val vpn = async(Dispatchers.IO) {
          try {
            val status = ModeClient.refresh(context)
            val state = status.optString("state")
            val server = status.optString("server")
            when {
              state == "connected" && status.optString("vpn_health") == "fail" -> row("VPN", "fail", "VPN включён, сайты не ответили")
              state == "connected" && status.optString("vpn_health") == "partial" -> row("VPN", "unknown", "Частичный доступ через VPN")
              state == "connected" && status.optBoolean("verified") ->
                row("VPN", "ok", "Сайт доступен через сервер: $server")
              state == "connected" -> row("VPN", "unknown", "Подключён: $server. Проверка сайтов отключена")
              state == "connecting" -> row("VPN", "unknown", "Подключается")
              state == "error" -> row("VPN", "fail", status.optString("message", "Ошибка подключения"))
              else -> row("VPN", "off", "Выключен")
            }
          } catch (e: CancellationException) {
            throw e
          } catch (e: Exception) {
            row("VPN", "fail", "Нет связи с модулем")
          }
        }
        val rows = direct.awaitAll()
        JSONObject().put("time", System.currentTimeMillis()).put("network", networkName)
          .put("summary", HealthRules.summary(rows.filter { it.optString("group") == "ru" }.map { it.optString("state") == "ok" }, rows.filter { it.optString("group") == "world" }.map { it.optString("state") == "ok" }))
          .put("rows", JSONArray(rows + listOf(dns.await(), vpn.await())))
          .put("note", "YouTube: HTTPS с UID виджета. Воспроизведение проверяется в самом YouTube. DNS: проверяются слушатели профилей. Для проверки назначения открой нужное приложение.")
      }
      prefs.edit().putString("report", result.toString()).apply()
    } finally {
      prefs.edit().remove("running_since").apply()
      running.set(false)
      ConnectionWidgetProvider.render(context)
    }
  }

  private fun row(name: String, state: String, detail: String) = JSONObject().put("name", name).put("state", state).put("detail", detail)

  private suspend fun probe(client: OkHttpClient, name: String, url: String, expected: Int?): JSONObject {
    val start = android.os.SystemClock.elapsedRealtime()
    return withTimeoutOrNull(client.callTimeoutMillis.toLong() + 500) {
      suspendCancellableCoroutine<JSONObject> { continuation ->
        val call = client.newCall(Request.Builder().url(url).header("User-Agent", "ZDT-D-health/23").build())
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
          override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resume(row(name, "fail", e.javaClass.simpleName + ": " + (e.message ?: "").take(120)))
          }
          override fun onResponse(call: Call, response: Response) {
            response.use {
              val ms = android.os.SystemClock.elapsedRealtime() - start
              if (continuation.isActive) continuation.resume(row(name, if (HealthRules.httpOk(it.code, expected)) "ok" else "fail", "HTTP ${it.code}, $ms мс"))
            }
          }
        })
      }
    } ?: row(name, "fail", "Таймаут")
  }
}
