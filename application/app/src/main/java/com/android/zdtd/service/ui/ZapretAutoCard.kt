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
import com.android.zdtd.service.ui.theme.NetworkPanel
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

private val YoutubeTestUrls = listOf(
  "https://www.youtube.com/",
  "https://music.youtube.com/",
  "https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg",
).joinToString("\n")

@Composable
fun ZapretAutoCard(
  program: String,
  serviceRunning: Boolean,
  serviceBusy: Boolean,
  daemonOnline: Boolean,
  selectProgram: (String) -> Unit,
  onApply: (String, String) -> Unit,
) {
  val context = LocalContext.current
  val prefs = remember { context.getSharedPreferences("zapret_auto", Context.MODE_PRIVATE) }
  val scope = rememberCoroutineScope()
  val report by ZapretAutoReport.state.collectAsState()
  var urls by remember {
    val oldDefault = YoutubeTestUrls.replace("https://i.ytimg.com/vi/jNQXAC9IVRw/hqdefault.jpg", "https://i.ytimg.com/generate_204")
    val saved = prefs.getString("urls", YoutubeTestUrls).orEmpty()
    mutableStateOf(if (saved == oldDefault) YoutubeTestUrls else saved)
  }
  var limit by remember { mutableStateOf(prefs.getInt("limit", 12)) }
  var message by remember { mutableStateOf("") }
  var options by remember { mutableStateOf(false) }
  var details by remember { mutableStateOf(false) }
  var restoring by remember { mutableStateOf(false) }
  var backup by remember { mutableStateOf(prefs.getString("backup", "")) }
  LaunchedEffect(Unit) { ZapretAutoReport.restore(context) }
  val running = report.optBoolean("running")
  NetworkPanel(Modifier.fillMaxWidth(), accent = true) {
    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("Автоподбор Zapret", style = MaterialTheme.typography.titleLarge)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(program == "nfqws2", { selectProgram("nfqws2") }, enabled = !running, label = { Text("Zapret 2") })
        FilterChip(program == "nfqws", { selectProgram("nfqws") }, enabled = !running, label = { Text("Zapret 1") })
      }
      if (serviceRunning && !running) {
        Text("На время теста модуль остановится. После подбора можно сразу применить стратегию и запустить ZDT-D.", style = MaterialTheme.typography.bodySmall)
      }
      if (!daemonOnline) Text("Нет связи с модулем. Проверь root и версию установленного ZIP.", color = MaterialTheme.colorScheme.error)
      if (running) {
        OutlinedButton(onClick = { ZapretAutoService.stop(context) }) { Text("Остановить проверку") }
      } else {
        Button(onClick = {
          message = ""
          prefs.edit().putString("urls", urls).putInt("limit", limit).apply()
          runCatching {
            ZapretAutoService.start(context, program, urls.lines().map(String::trim).filter(String::isNotBlank).distinct(), limit, stopModule = serviceRunning)
          }.onFailure { message = it.message.orEmpty() }
        }, enabled = daemonOnline && !serviceBusy && ZapretTestOwner.owner.get() == null, modifier = Modifier.fillMaxWidth()) {
          Text(if (serviceRunning) "Остановить и подобрать" else "Подобрать стратегии")
        }
      }
      TextButton(onClick = { options = !options }) { Text(if (options) "Скрыть параметры теста" else "Адреса и число стратегий") }
      if (options) {
        OutlinedTextField(urls, { urls = it }, Modifier.fillMaxWidth(), enabled = !running,
          label = { Text("До 4 HTTPS-адресов") }, minLines = 2)
        TextButton(onClick = { urls = YoutubeTestUrls }, enabled = !running) { Text("Вернуть адреса YouTube и Music") }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          for (count in listOf(12, 30, 100)) FilterChip(limit == count, { limit = count }, enabled = !running,
            label = { Text(if (count == 100) "До 100" else count.toString()) })
        }
      }
      if (running) LinearProgressIndicator(Modifier.fillMaxWidth())
      if (report.has("started")) Text("${report.optString("network")} · ${java.text.DateFormat.getDateTimeInstance().format(java.util.Date(report.optLong("started")))}", style = MaterialTheme.typography.bodySmall)
      Text(report.optString("message", "Проверка ещё не запускалась"))
      val baseline = report.optJSONArray("baseline") ?: JSONArray()
      if (baseline.length() > 0) Text("Без стратегии: ${(0 until baseline.length()).count { baseline.getJSONObject(it).optBoolean("ok") }}/${baseline.length()} успешных запросов", style = MaterialTheme.typography.bodySmall)
      val results = report.optJSONArray("results") ?: JSONArray()
      val sorted = (0 until results.length()).map { results.getJSONObject(it) }.sortedWith(compareBy<JSONObject> { !it.optBoolean("passed") }.thenBy { it.optLong("elapsed_ms", Long.MAX_VALUE) })
      val passed = sorted.filter { it.optBoolean("passed") }
      val canApply = daemonOnline && !serviceBusy && !running && !serviceRunning && ZapretTestOwner.owner.get() == null
      if ((report.optJSONArray("baseline_failed_urls")?.length() ?: 0) > 0) {
        passed.firstOrNull()?.let { best ->
          Button(onClick = { onApply(report.optString("program"), best.optString("strategy")) },
            enabled = canApply, modifier = Modifier.fillMaxWidth()) {
            Text("Применить лучший по замерам")
          }
        }
      }
      (if (details) passed else passed.take(3)).forEach { row ->
        OutlinedButton(onClick = { onApply(report.optString("program"), row.optString("strategy")); backup = prefs.getString("backup", "") }, enabled = canApply, modifier = Modifier.fillMaxWidth()) {
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
      if (!prefs.getString("backup", "").isNullOrBlank()) TextButton(onClick = {
        backup = prefs.getString("backup", "")
        if (backup.isNullOrBlank()) { message = "Резервной копии пока нет: она появится после применения стратегии"; return@TextButton }
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
