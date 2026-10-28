package com.android.zdtd.service.ui

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.diagnostics.nfqws.*
import com.android.zdtd.service.api.ApiModels
import com.android.zdtd.service.modes.ModeClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

@Composable
fun ZapretAutoCard(program: String, serviceRunning: Boolean, onApply: (String, String) -> Unit) {
  val context = LocalContext.current
  val prefs = remember { context.getSharedPreferences("zapret_auto", Context.MODE_PRIVATE) }
  val scope = rememberCoroutineScope()
  val report by ZapretAutoReport.state.collectAsState()
  var urls by remember { mutableStateOf(prefs.getString("urls", "https://www.youtube.com/\nhttps://discord.com/").orEmpty()) }
  var limit by remember { mutableStateOf(prefs.getInt("limit", 12)) }
  var message by remember { mutableStateOf("") }
  var details by remember { mutableStateOf(false) }
  var restoring by remember { mutableStateOf(false) }
  var backup by remember { mutableStateOf(prefs.getString("backup", "")) }
  LaunchedEffect(Unit) { ZapretAutoReport.restore(context) }
  val running = report.optBoolean("running")
  Card(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("Автоподбор Zapret", style = MaterialTheme.typography.titleLarge)
      Text("Дважды проверяет каждый HTTPS-адрес через настоящие правила выбранной стратегии. Основной ZDT-D и другие VPN должны быть выключены. Настройки профилей до применения не меняются.")
      OutlinedTextField(urls, { urls = it }, Modifier.fillMaxWidth(), enabled = !running, label = { Text("Что должно открываться · 1–4 HTTPS-адреса") }, minLines = 2)
      Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (count in listOf(12, 30, 100)) FilterChip(limit == count, { limit = count }, enabled = !running, label = { Text(if (count == 100) "Все (до 100)" else "$count стратегий") })
      }
      Text("Проверяются первые варианты из установленного набора. Успех означает ответы HTTP 2xx/3xx в обеих попытках; видео, QUIC и звонки проверь отдельно. Максимальная сессия — 30 минут.", style = MaterialTheme.typography.bodySmall)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
          prefs.edit().putString("urls", urls).putInt("limit", limit).apply()
          runCatching { ZapretAutoService.start(context, program, urls.lines().map { it.trim() }.filter { it.isNotBlank() }.distinct(), limit) }.onFailure { message = it.message.orEmpty() }
        }, enabled = !serviceRunning && !running && ZapretTestOwner.owner.get() == null) { Text("Подобрать автоматически") }
        if (running) OutlinedButton(onClick = { ZapretAutoService.stop(context) }) { Text("Стоп") }
      }
      if (serviceRunning) Text("Останови ZDT-D кнопкой питания, затем запусти подбор.")
      if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
      if (report.has("started")) Text("${report.optString("network")} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(report.optLong("started")))}", style = MaterialTheme.typography.bodySmall)
      Text(report.optString("message", "Проверка ещё не запускалась"))
      val baseline = report.optJSONArray("baseline") ?: JSONArray()
      if (baseline.length() > 0) Text("Без стратегии: ${(0 until baseline.length()).count { baseline.getJSONObject(it).optBoolean("ok") }}/${baseline.length()} успешных запросов", style = MaterialTheme.typography.bodySmall)
      val results = report.optJSONArray("results") ?: JSONArray()
      val sorted = (0 until results.length()).map { results.getJSONObject(it) }.sortedWith(compareBy<JSONObject> { !it.optBoolean("passed") }.thenBy { it.optLong("elapsed_ms", Long.MAX_VALUE) })
      sorted.filter { it.optBoolean("passed") }.forEach { row ->
        OutlinedButton(onClick = { onApply(report.optString("program"), row.optString("strategy")); backup = prefs.getString("backup", "") }, enabled = !running && !serviceRunning && ZapretTestOwner.owner.get() == null, modifier = Modifier.fillMaxWidth()) {
          Text("✓ ${row.optString("strategy")} · ${row.optLong("elapsed_ms")} мс суммарно")
        }
      }
      if (sorted.isNotEmpty()) TextButton(onClick = { details = !details }) { Text(if (details) "Скрыть подробности" else "Результаты всех попыток") }
      if (details) sorted.forEach { row ->
        Text(row.optString("strategy"), style = MaterialTheme.typography.titleSmall)
        if (row.has("error")) Text(row.optString("error"), color = MaterialTheme.colorScheme.error)
        val tests = row.optJSONArray("tests") ?: JSONArray()
        for (i in 0 until tests.length()) { val t = tests.getJSONObject(i); Text("${if (t.optBoolean("ok")) "✓" else "×"} ${t.optString("url")} · ${t.optInt("attempt")} · ${t.optLong("elapsed_ms")} мс ${t.optString("error")}", style = MaterialTheme.typography.bodySmall) }
      }
      OutlinedButton(onClick = {
        backup = prefs.getString("backup", "")
        if (backup.isNullOrBlank()) { message = "Резервной копии пока нет: она появится после применения стратегии"; return@OutlinedButton }
        scope.launch {
          restoring = true
          try {
            val saved = JSONObject(backup!!)
            withContext(Dispatchers.IO) {
              val api = ModeClient.api(context)
              val status = api.getStatus() ?: error("Нет связи со службой")
              check(!ApiModels.isServiceOn(status) && !status.startInProgress && !status.stopInProgress && ZapretTestOwner.owner.get() == null) { "Сначала останови ZDT-D и тестер" }
              check(api.putTextContent(saved.getString("path"), saved.getString("content"))) { "Не удалось восстановить" }
            }
            message = "Предыдущие аргументы восстановлены. Запусти ZDT-D для применения"
          } catch(e: Exception) { message = e.message.orEmpty() }
          finally { restoring = false }
        }
      }, enabled = !running && !serviceRunning && !restoring) { Text("Вернуть аргументы до последнего применения") }
      if (message.isNotBlank()) Text(message)
    }
  }
}
