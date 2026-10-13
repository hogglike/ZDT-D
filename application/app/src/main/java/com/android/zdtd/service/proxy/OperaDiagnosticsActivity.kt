package com.android.zdtd.service.proxy

import android.os.Bundle
import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.modes.ModeClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

class OperaDiagnosticsActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Diagnostics() } }
  }
  @Composable private fun Diagnostics() {
    val scope = rememberCoroutineScope()
    var data by remember { mutableStateOf(JSONObject()) }
    var error by remember { mutableStateOf("") }
    var starting by remember { mutableStateOf(false) }
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
      while (true) {
        try {
          val value = withContext(Dispatchers.IO) { ModeClient.api(this@OperaDiagnosticsActivity).getJsonData("/api/programs/operaproxy/diagnostics") }
          if (!value.has("running")) error = "Обнови модуль вместе с APK: диагностика недоступна."
          else { data = value; loaded = true }
        } catch (_: Exception) { error = "Нет связи со службой ZDT-D." }
        delay(2000)
      }
    }
    val running = data.optBoolean("running")
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text("Диагностика Opera", style = MaterialTheme.typography.titleLarge)
          TextButton(onClick = { finish() }) { Text("Назад") }
        }
        Text("API-прокси помогает получить доступ к API Opera. SNI — домен для TLS; это не адрес Webshare. Проверка использует сохранённые настройки и не меняет маршруты приложений.", style = MaterialTheme.typography.bodySmall)
        Button(onClick = { scope.launch {
          starting = true; error = ""
          try {
            withContext(Dispatchers.IO) { ModeClient.api(this@OperaDiagnosticsActivity).postJsonData("/api/programs/operaproxy/diagnostics", JSONObject()) }
            data = withContext(Dispatchers.IO) { ModeClient.api(this@OperaDiagnosticsActivity).getJsonData("/api/programs/operaproxy/diagnostics") }
          } catch (e: Exception) { error = e.message ?: "Не удалось запустить проверку" }
          finally { starting = false }
        } }, enabled = loaded && !starting && !running, modifier = Modifier.fillMaxWidth()) {
          Text(if (running || starting) "Проверка выполняется…" else "Проверить все сохранённые SNI")
        }
        Text("До 90 секунд на запись. Проверяются API, сервер Opera и HTTPS-адрес из настроек. Контроль без подмены SNI выполняется после ошибки. Зелёный результат относится к этой сети и моменту проверки.", style = MaterialTheme.typography.bodySmall)
        if (running || starting) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error)
        if (data.optString("error").isNotBlank()) Text(data.optString("error"), color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          val results = objects(data.optJSONArray("results"))
          results.forEach { row -> item {
            ReportCard(row.optString("sni"), row.optJSONObject("diagnosis"), row.optString("state"))
          } }
          item { Text("Состояние работающей Opera", style = MaterialTheme.typography.titleMedium) }
          val runtime = objects(data.optJSONArray("runtime"))
          if (runtime.isEmpty()) item { Text("Нет отчёта текущего запуска. После обновления модуля перезапусти ZDT-D. Проверку SNI можно запускать отдельно.", style = MaterialTheme.typography.bodySmall) }
          runtime.forEach { row -> item {
            ReportCard(row.optString("sni") + if (!row.optBoolean("process_alive")) " · процесс остановлен" else "", row, row.optString("state"))
          } }
          item { Text("Если API и тест сайта проходят, а приложение не работает, проверь выбор приложений и используемую сеть. Диагностическая проверка сама по себе не подтверждает маршрутизацию приложения.", style = MaterialTheme.typography.bodySmall) }
        }
        OutlinedButton(onClick = {
          (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Opera diagnostics", data.toString(2)))
          error = "Отчёт скопирован. Логины и пароли прокси в него не включаются."
        }, enabled = loaded, modifier = Modifier.fillMaxWidth()) { Text("Скопировать отчёт") }
      }
    }
  }
  @Composable private fun ReportCard(title: String, report: JSONObject?, state: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
      Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = stateColor(state))
        if (report == null) Text(if (state == "checking") "Ожидание результата…" else "Результат недоступен")
        else {
          val steps = objects(report.optJSONArray("steps"))
          steps.forEach { step ->
            val status = step.optString("state")
            val stage = stageLabel(step.optString("stage"))
            val prefix = when (status) { "ready" -> "✓"; "error" -> "×"; else -> "…" }
            Text("$prefix $stage${if (step.optString("endpoint").isNotBlank()) " · " + step.optString("endpoint") else ""}", color = stateColor(status), style = MaterialTheme.typography.bodyMedium)
            if (status == "error") Text(step.optString("message"), style = MaterialTheme.typography.bodySmall)
          }
          val sniDifference = steps.any { control -> control.optString("stage").endsWith("control_without_sni") && control.optString("state") == "ready" && steps.any { original -> original.optString("stage") == (if (control.optString("stage").startsWith("api_")) "api" else "opera_tunnel") && original.optString("state") == "error" } }
          if (sniDifference) Text("Контроль без подмены SNI прошёл, а выбранный SNI — нет. Попробуй другую запись SNI. Результат указывает на различие в соединении с этим SNI, но не доказывает конкретную причину блокировки.", style = MaterialTheme.typography.bodySmall)
          val failures = objects(report.optJSONArray("failures"))
          if (failures.isNotEmpty()) {
            Text("Отклонённые API-прокси:", style = MaterialTheme.typography.labelMedium)
            failures.take(10).forEach { Text(it.optString("endpoint") + " · " + it.optString("message"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
          }
        }
      }
    }
  }
  @Composable private fun stateColor(state: String): Color = when (state) {
    "ready" -> Color(0xFF2E7D32)
    "error" -> MaterialTheme.colorScheme.error
    else -> MaterialTheme.colorScheme.onSurface
  }
  private fun objects(array: JSONArray?): List<JSONObject> = array?.let { (0 until it.length()).mapNotNull(it::optJSONObject) } ?: emptyList()
  private fun stageLabel(stage: String): String = when (stage) {
    "config" -> "Настройки и сертификаты"
    "api_list" -> "Список API-прокси"
    "api", "anonymous registration", "device registration" -> "Авторизация API Opera"
    "discovery", "discover" -> "Получение серверов Opera"
    "opera_tunnel" -> "Туннель Opera · TCP/TLS/CONNECT"
    "test_site" -> "Проверочный сайт"
    "api_control_without_sni" -> "Контроль API без подмены SNI"
    "tunnel_control_without_sni" -> "Контроль туннеля без подмены SNI"
    "local_socks" -> "Запуск локального SOCKS"
    "complete" -> "Итог проверки"
    else -> stage
  }
}
