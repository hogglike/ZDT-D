package com.android.zdtd.service.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.zdtd.service.BuildConfig
import com.android.zdtd.service.ZdtdActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.coroutines.resume

private const val DefaultDomain = "example.com"

internal fun dnsStageText(stage: JSONObject): String {
  val result = when (stage.optString("state")) {
    "ok" -> "Ответ получен"
    "timeout" -> "Таймаут"
    "socket_error" -> "Ошибка сети"
    "invalid_response" -> "Некорректный ответ"
    "dns_error" -> "Ошибка DNS (код ${stage.optInt("rcode")})"
    "nxdomain" -> "Домен не найден"
    "no_records" -> "Нет A-записей"
    "truncated" -> "Ответ усечён"
    "not_needed" -> if (stage.optBoolean("automatic")) "Используется DNS сети" else "Адрес задан как IP"
    "not_running" -> "Профиль не запущен"
    "start_failed" -> "Проверочный процесс не запустился"
    "start_timeout" -> "Таймаут запуска проверки"
    else -> "Не удалось проверить"
  }
  return if (stage.has("elapsed_ms")) "$result · ${stage.optLong("elapsed_ms")} мс" else result
}

private val stages = listOf(
  "bootstrap" to "Поиск адреса DNS-сервера",
  "upstream" to "Ответ DNS-сервера",
  "listener" to "Локальный DNS профиля",
  "redirect" to "Запрос через порт 53",
)

private val knownErrors = mapOf(
  "tls_certificate" to "Ошибка сертификата TLS",
  "tls_handshake" to "Ошибка рукопожатия TLS",
  "connection_refused" to "Сервер отклонил соединение",
  "network_unreachable" to "Сеть недоступна",
  "timeout" to "Истекло время ожидания",
  "http_error" to "Ошибка HTTP от DoH-сервера",
  "listener_in_use" to "Локальный порт занят",
  "sing_box_start_error" to "Ошибка запуска sing-box",
  "sing_box_error" to "Ошибка sing-box",
)

private fun eventTexts(data: JSONObject?, key: String = "events"): List<String> {
  val events = data?.optJSONArray(key) ?: return emptyList()
  return (0 until events.length()).mapNotNull { knownErrors[events.optString(it)] }
}

private fun dnsSummary(report: JSONObject): String {
  val runtime = report.optJSONObject("runtime") ?: JSONObject()
  val bootstrap = report.optJSONObject("bootstrap")?.optString("state")
  val upstream = report.optJSONObject("upstream")?.optString("state")
  val listener = report.optJSONObject("listener")?.optString("state")
  val redirect = report.optJSONObject("redirect")?.optString("state")
  return when {
    upstream != "ok" && bootstrap !in listOf("ok", "not_needed") -> "Bootstrap DNS не вернул адрес сервера"
    upstream != "ok" -> "Внешний DNS не ответил на проверку"
    report.optBoolean("suspended_for_tethering") -> "Сервер отвечает · профили на паузе"
    !report.optBoolean("enabled") -> "Сервер отвечает · профиль выключен"
    report.optBoolean("global_dnscrypt_enabled") -> "Запуску мешает глобальный DNSCrypt"
    report.optString("android_private_dns") == "hostname" -> "В Android включён строгий «Частный DNS»"
    !runtime.optBoolean("sing_box") || !runtime.optBoolean("tun2socks") -> "Сервер отвечает · профиль не запущен"
    !runtime.optBoolean("config_matches") -> "Сервер отвечает · модуль нужно перезапустить"
    listener == "ok" && redirect != "ok" -> "Локальный DNS отвечает · запрос через порт 53 не прошёл"
    listener != "ok" || redirect != "ok" -> "Запрос через профиль не прошёл"
    !runtime.optBoolean("netd") -> "Профиль не назначен приложениям в Android"
    runtime.has("default_route") && !runtime.isNull("default_route") && !runtime.optBoolean("default_route") ->
      "В таблице маршрутов не найден маршрут профиля"
    !runtime.optBoolean("uid_lookup_ok") -> "Не удалось проверить назначения приложений"
    else -> "Профиль отвечает на DNS-запросы"
  }
}

private fun reportText(report: JSONObject): String = buildString {
  appendLine("ZDT-D ${report.optString("app_version")} · проверка DNS-профиля")
  appendLine(report.optString("checked_at"))
  appendLine()
  appendLine(dnsSummary(report))
  appendLine("Профиль ${report.optString("profile")} · ${report.optString("protocol")} · ${report.optString("endpoint_host")}:${report.optInt("endpoint_port")}")
  appendLine("Проверяемый домен ${report.optString("domain")}")
  stages.forEach { (key, title) ->
    report.optJSONObject(key)?.let { stage ->
      appendLine("$title — ${dnsStageText(stage)}")
      eventTexts(stage).forEach { appendLine("  $it") }
    }
  }
  appendLine()
  appendLine("В JSON указаны состояния процессов, маршрута и назначения UID.")
  appendLine("Архив содержит имена пакетов приложений и домены. Пути и параметры URL DNS-серверов скрыты.")
}

private fun writeReport(output: OutputStream, report: JSONObject, zip: Boolean) {
  val json = report.toString(2).toByteArray(Charsets.UTF_8)
  if (!zip) {
    output.write(json)
    return
  }
  ZipOutputStream(output).use { archive ->
    listOf("report.txt" to reportText(report).toByteArray(Charsets.UTF_8), "dns-profile.json" to json)
      .forEach { (name, content) ->
        archive.putNextEntry(ZipEntry(name))
        archive.write(content)
        archive.closeEntry()
      }
  }
}

private suspend fun <T> awaitDnsResult(call: ((T) -> Unit) -> Unit): T =
  suspendCancellableCoroutine { work ->
    call { if (work.isActive) work.resume(it) }
  }

private fun validCheckDomain(input: String): Boolean {
  val domain = input.trim().trimEnd('.')
  return domain.length in 3..253 && domain.contains('.') && domain.split('.').all {
    it.length in 1..63 && !it.startsWith('-') && !it.endsWith('-') &&
      it.all { c -> c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-' }
  }
}

@Composable
internal fun DnsProfileCheckDialog(
  actions: ZdtdActions, id: String, name: String, appLabels: Map<String, String>, previous: JSONObject?,
  onResult: (JSONObject) -> Unit, onDismiss: () -> Unit,
  defaultDomain: String = DefaultDomain,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val path = "/api/programs/dnsprofiles/checks/$id"
  var domain by rememberSaveable(id) { mutableStateOf(previous?.optString("domain") ?: defaultDomain) }
  var result by remember(id) { mutableStateOf(previous) }
  var running by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var feedback by remember { mutableStateOf<String?>(null) }
  var advanced by rememberSaveable { mutableStateOf(false) }
  var pendingExport by rememberSaveable { mutableStateOf<String?>(null) }

  fun save(uri: android.net.Uri?, zip: Boolean) {
    val snapshot = pendingExport
    pendingExport = null
    if (uri == null || snapshot == null) return
    scope.launch {
      val saved = withContext(Dispatchers.IO) {
        runCatching {
          val output = checkNotNull(context.contentResolver.openOutputStream(uri, "wt"))
          output.use { writeReport(it, JSONObject(snapshot), zip) }
        }.isSuccess
      }
      feedback = if (saved) "Отчёт сохранён" else "Не удалось сохранить отчёт"
    }
  }

  val exportZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { save(it, true) }
  val exportJson = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { save(it, false) }

  fun run() {
    if (running || !validCheckDomain(domain)) return
    running = true
    error = null
    feedback = null
    val requestedDomain = domain.trim()
    scope.launch {
      try {
        val started = awaitDnsResult<JSONObject?> { actions.postJsonResult(path, JSONObject().put("domain", requestedDomain), it) }
          ?.optJSONObject("data")
        if (started?.optString("state") == "busy") {
          error = "Служба ещё проверяет другой профиль. Повтори через несколько секунд."
          return@launch
        }
        val ticket = started?.optLong("ticket") ?: 0
        if (ticket <= 0) {
          error = "Проверка недоступна. Убедись, что APK и модуль обновлены до одной версии."
          return@launch
        }
        repeat(65) {
          delay(500)
          val checked = awaitDnsResult<JSONObject?> { actions.loadJsonData("$path/$ticket", it) }
          when (checked?.optString("state")) {
            "done" -> {
              val date = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.ROOT)
              date.timeZone = TimeZone.getTimeZone("UTC")
              checked.put("checked_at", date.format(Date())).put("app_version", BuildConfig.VERSION_NAME)
              result = checked
              onResult(checked)
              return@launch
            }
            "missing", "error" -> {
              error = "Проверка прервалась. Повтори её."
              return@launch
            }
          }
        }
        error = "Служба не вернула результат вовремя."
      } finally {
        running = false
      }
    }
  }

  LaunchedEffect(id) { if (previous == null) run() }

  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text("Проверка DNS", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
          TextButton(onClick = onDismiss) { Text("Закрыть") }
        }
        val report = result
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp),
          contentPadding = PaddingValues(vertical = 8.dp)) {
          item {
            Text(name, style = MaterialTheme.typography.titleLarge)
            OutlinedTextField(domain, { domain = it }, label = { Text("Проверяемый домен") },
              singleLine = true, enabled = !running, modifier = Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              Button(onClick = ::run, enabled = !running && validCheckDomain(domain)) {
                Text(if (running) "Проверяю…" else "Проверить")
              }
              TextButton(onClick = { domain = "gemini.google.com"; run() }, enabled = !running) { Text("Проверить Gemini") }
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
          }
          if (report != null) {
            item {
              Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.primaryContainer) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  Text(dnsSummary(report), style = MaterialTheme.typography.titleMedium)
                  Text("${report.optString("protocol")} · ${report.optString("endpoint_host")} · ${report.optString("domain")}",
                    style = MaterialTheme.typography.bodySmall)
                  report.optJSONObject("gemini_filter")?.let { filter ->
                    Text(when {
                      filter.optLong("updated_at") == 0L -> "Подписка Gemini · список ещё не загружен"
                      filter.optBoolean("matches_domain") -> "Подписка Gemini · для домена есть правило"
                      else -> "Подписка Gemini · для домена используется DNS профиля"
                    }, style = MaterialTheme.typography.bodySmall)
                  }
                }
              }
            }
            items(stages, key = { it.first }) { (key, label) ->
              val stage = report.optJSONObject(key) ?: JSONObject()
              val state = stage.optString("state")
              OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                  val tested = report.optString("upstream_domain")
                  Text(
                    if (key == "upstream" && tested.isNotBlank() && tested != report.optString("domain")) "$label · $tested" else label,
                    style = MaterialTheme.typography.labelLarge,
                  )
                  Text(dnsStageText(stage), color = when (state) {
                    "ok" -> MaterialTheme.colorScheme.primary
                    "not_needed", "not_running" -> MaterialTheme.colorScheme.onSurfaceVariant
                    else -> MaterialTheme.colorScheme.error
                  })
                  stage.optString("server").takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall)
                  }
                  eventTexts(stage).forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
              }
            }
            item {
              val runtime = report.optJSONObject("runtime") ?: JSONObject()
              val apps = runtime.optJSONArray("apps")
              val assignments = (0 until (apps?.length() ?: 0)).mapNotNull { apps?.optJSONObject(it) }
              OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                  Text("Назначение в Android", style = MaterialTheme.typography.titleMedium)
                  Text("sing-box ${if (runtime.optBoolean("sing_box")) "работает" else "остановлен"} · " +
                    "туннель ${if (runtime.optBoolean("tun2socks")) "работает" else "остановлен"}")
                  Text(when {
                    !runtime.optBoolean("netd_snapshot_available") -> "Нет данных netd"
                    runtime.optBoolean("netd") -> "Профиль зарегистрирован в netd"
                    else -> "Профиль отсутствует в netd"
                  })
                  for (app in assignments) {
                    val pkg = app.optString("package")
                    val state = when {
                      app.isNull("uid") -> "UID не найден"
                      app.optBoolean("applied") -> "назначено"
                      else -> "не назначено"
                    }
                    Text("${appLabels[pkg] ?: pkg} · $state", style = MaterialTheme.typography.bodySmall)
                  }
                  eventTexts(runtime).plus(eventTexts(runtime, "tun_events")).distinct()
                    .forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                }
              }
            }
            item {
              TextButton(onClick = { advanced = !advanced }) {
                Text(if (advanced) "Скрыть JSON" else "Технические данные")
              }
              if (advanced) Text(report.toString(2), style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
          }
        }
        if (report != null) {
          HorizontalDivider()
          feedback?.let { Text(it, Modifier.padding(top = 6.dp), style = MaterialTheme.typography.bodyMedium) }
          Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
              pendingExport = report.toString()
              exportZip.launch("ZDTD-DNS-$id.zip")
            }, enabled = !running, modifier = Modifier.weight(1f)) { Text("Сохранить ZIP") }
            OutlinedButton(onClick = {
              pendingExport = report.toString()
              exportJson.launch("ZDTD-DNS-$id.json")
            }, enabled = !running, modifier = Modifier.weight(1f)) { Text("JSON") }
          }
        }
      }
    }
  }
}
