package com.android.zdtd.service.proxy

import com.android.zdtd.service.isValidOperaCustomProxy
import com.android.zdtd.service.maskOperaProxyForLog
import java.net.URI
import java.net.URLEncoder
import java.util.Locale

const val MAX_PROXY_TEXT_BYTES = 1024 * 1024
const val MAX_PROXY_ENTRIES = 1000

data class SavedProxy(val url: String) {
  val display: String get() = maskOperaProxyForLog(url)
  val endpoint: String get() {
    val uri = URI(url)
    return "${uri.host}:${uri.port} · ${uri.scheme.uppercase(Locale.ROOT)}"
  }
}

data class ProxyImport(val entries: List<SavedProxy>, val invalidLines: List<Int>, val duplicates: Int)

/** Parse exports locally; error messages must never contain credentials. */
fun parseProxyList(text: String, protocol: String = "http"): ProxyImport {
  require(protocol in setOf("http", "socks5")) { "Выбери HTTP или SOCKS5" }
  require(text.toByteArray(Charsets.UTF_8).size <= MAX_PROXY_TEXT_BYTES) { "Файл больше 1 МБ" }
  val entries = linkedMapOf<String, SavedProxy>()
  val invalid = mutableListOf<Int>()
  var duplicates = 0
  text.removePrefix("\uFEFF").lineSequence().forEachIndexed { index, raw ->
    val line = raw.trim()
    if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
    val url = if (line.contains("://")) line else {
      val parts = line.split(':', limit = 4)
      if (parts.size != 4 || parts[2].isEmpty() || parts[3].isEmpty()) ""
      else "$protocol://${encodeUserInfo(parts[2])}:${encodeUserInfo(parts[3])}@${parts[0]}:${parts[1]}"
    }
    val uri = runCatching { URI(url) }.getOrNull()
    val host = uri?.host.orEmpty()
    val validIpv4 = !host.matches(Regex("[0-9.]+")) ||
      (host.split('.').size == 4 && host.split('.').all { it.toIntOrNull()?.let { octet -> octet in 0..255 } == true })
    if (!isValidOperaCustomProxy(url) || !validIpv4 || line.any { it.isISOControl() }) {
      invalid.add(index + 1)
    } else {
      val normalized = uri!!.scheme.lowercase(Locale.ROOT) + url.substring(url.indexOf(':')).removeSuffix("/")
      if (entries.containsKey(normalized)) duplicates++ else entries[normalized] = SavedProxy(normalized)
      require(entries.size <= MAX_PROXY_ENTRIES) { "В списке больше $MAX_PROXY_ENTRIES серверов" }
    }
  }
  return ProxyImport(entries.values.toList(), invalid, duplicates)
}

private fun encodeUserInfo(value: String): String = URLEncoder.encode(value, "UTF-8").replace("+", "%20")
