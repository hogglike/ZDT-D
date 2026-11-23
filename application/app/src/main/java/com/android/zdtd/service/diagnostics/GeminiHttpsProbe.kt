package com.android.zdtd.service.diagnostics

import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLException

private val GeminiHosts = listOf(
  "gemini.google.com",
  "geminiweb-pa.clients6.google.com",
  "alkalimakersuite-pa.clients6.google.com",
  "robinfrontend-pa.googleapis.com",
)

internal suspend fun checkGeminiHttps(report: JSONObject) = coroutineScope {
  val filter = report.optJSONObject("gemini_filter") ?: return@coroutineScope
  val port = filter.optInt("proxy_port")
  if (port !in 19500..19515) return@coroutineScope
  val expected = filter.optJSONObject("hosts") ?: return@coroutineScope
  val client = OkHttpClient.Builder()
    .proxy(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .callTimeout(6, TimeUnit.SECONDS)
    .build()
  val rows = GeminiHosts.map { host ->
    async(Dispatchers.IO) {
      val row = JSONObject().put("host", host)
      val addresses = expected.optJSONArray(host)
      if (addresses == null || addresses.length() == 0) {
        return@async row.put("state", "no_rule")
      }
      row.put("listed_addresses", addresses)
      val start = SystemClock.elapsedRealtime()
      try {
        val request = Request.Builder().url("https://$host/").header("Accept-Language", "en").build()
        client.newCall(request).execute().use { response ->
          row.put("code", response.code).put("state", "http")
          val location = response.header("Location")?.let { response.request.url.resolve(it) }
          location?.host?.let { row.put("redirect_host", it) }
          if (host == "gemini.google.com") {
            val body = response.peekBody(96L * 1024L).string()
            if (
              body.contains("isn&#39;t currently supported in your country", ignoreCase = true) ||
              body.contains("isn't currently supported in your country", ignoreCase = true)
            ) row.put("state", "region_message")
          }
        }
      } catch (e: IOException) {
        if (!row.has("code")) row.put("state", when (e) {
          is SSLException -> "tls_error"
          is SocketTimeoutException -> "timeout"
          is java.io.InterruptedIOException -> "timeout"
          else -> "connect_error"
        }).put("error_type", e.javaClass.simpleName)
      }
      row.put("elapsed_ms", SystemClock.elapsedRealtime() - start)
    }
  }.awaitAll()
  report.put("gemini_https", JSONArray(rows))
  client.connectionPool.evictAll()
  Unit
}

internal fun geminiHttpsResult(row: JSONObject): String {
  val label = when (row.optString("state")) {
    "no_rule" -> "В списке нет адреса"
    "tls_error" -> "Ошибка TLS"
    "timeout" -> "Таймаут HTTPS"
    "connect_error" -> "Ошибка соединения"
    "region_message" -> "Сервер показал ограничение по региону"
    "http" -> "HTTP ${row.optInt("code")}"
    else -> "Результат недоступен"
  }
  val toHost = row.optString("redirect_host").takeIf { it.isNotBlank() && it != row.optString("host") }
  return listOfNotNull(
    label,
    toHost?.let { "переход на $it" },
    row.optLong("elapsed_ms").takeIf { row.has("elapsed_ms") }?.let { "$it мс" },
  ).joinToString(" · ")
}
