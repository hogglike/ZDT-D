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
        val client = OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
          .connectTimeout(4, TimeUnit.SECONDS).readTimeout(4, TimeUnit.SECONDS)
          .callTimeout(8, TimeUnit.SECONDS).followRedirects(false).followSslRedirects(false)
          .apply { if (network != null) {
            socketFactory(network.socketFactory)
            dns(object : Dns { override fun lookup(hostname: String) = network.getAllByName(hostname).toList() })
          } }
          .build()
        val direct = listOf(
          "Яндекс" to "https://ya.ru/robots.txt",
          "VK" to "https://vk.com/robots.txt",
          "Google" to "https://www.gstatic.com/generate_204",
          "Cloudflare" to "https://www.cloudflare.com/cdn-cgi/trace",
          "YouTube (HTTPS)" to "https://www.youtube.com/robots.txt",
        ).map { (name, url) -> async {
          if (network == null) row(name, "fail", "Нет физической сети")
          else probe(client, name, url, if (name == "Google") 204 else 200)
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
          .put("summary", HealthRules.summary(rows.take(2).map { it.optString("state") == "ok" }, rows.drop(2).take(2).map { it.optString("state") == "ok" }))
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

  private suspend fun probe(client: OkHttpClient, name: String, url: String, expected: Int): JSONObject {
    val start = android.os.SystemClock.elapsedRealtime()
    return withTimeoutOrNull(10_000) {
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
    } ?: row(name, "fail", "Таймаут 10 с")
  }
}
