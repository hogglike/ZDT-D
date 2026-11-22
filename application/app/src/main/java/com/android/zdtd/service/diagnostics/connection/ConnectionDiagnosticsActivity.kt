package com.android.zdtd.service.diagnostics.connection

import android.content.Intent
import android.net.*
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ConnectionDiagnosticsActivity : ComponentActivity() {
  private var networkChange: Job? = null
  private var lastNetwork: Network? = null
  private val callback = object : ConnectivityManager.NetworkCallback() {
    override fun onAvailable(network: Network) {
      if (network == lastNetwork) return
      lastNetwork = network
      networkChange?.cancel()
      networkChange = lifecycleScope.launch {
        delay(2_000)
        if (ConnectionHealth.preferences(this@ConnectionDiagnosticsActivity).getBoolean("automatic", false)) start("CHECK")
      }
    }
  }
  override fun onStart() {
    super.onStart()
    // Track the selected network rather than every available (possibly idle) one.
    getSystemService(ConnectivityManager::class.java).registerDefaultNetworkCallback(callback)
  }
  override fun onStop() {
    networkChange?.cancel()
    getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(callback)
    super.onStop()
  }
  private fun start(action: String) {
    try {
      ConnectionHealth.preferences(this).edit().remove("error").apply()
      ContextCompat.startForegroundService(this, Intent(this, DiagnosticService::class.java).setAction(action))
    } catch (e: Exception) {
      ConnectionHealth.preferences(this).edit().putString("error", "Не удалось начать: ${e.javaClass.simpleName}").apply()
    }
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent {
      MaterialTheme {
        var tick by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) { while (isActive) { delay(750); tick++ } }
        var editTargets by remember { mutableStateOf(false) }
        val prefs = ConnectionHealth.preferences(this)
        if (editTargets) CheckTargetsEditor(this) { editTargets = false }
        @Suppress("UNUSED_VARIABLE") val refresh = tick
        val report = ConnectionHealth.read(this)
        val now = System.currentTimeMillis()
        val captureSince = prefs.getLong("capture_since", 0)
        val capture = captureSince > 0 && now - captureSince < 180_000
        val runningSince = prefs.getLong("running_since", 0)
        val checking = runningSince > 0 && now - runningSince < 120_000
        val error = prefs.getString("error", null)
        val zip = File(cacheDir, "ZDTD_hotspot_report.zip")
        Surface(Modifier.fillMaxSize()) {
          Column(Modifier.safeDrawingPadding().padding(20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Проверка соединения", style = MaterialTheme.typography.headlineSmall)
            Text("Напрямую · VPN · DNS · YouTube")
            OutlinedButton(onClick = { editTargets = true }) { Text("Сайты и таймаут") }
            Button(onClick = { start("CHECK") }, enabled = !checking && !capture) { Text(if (checking) "Проверка…" else "Проверить сейчас") }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
              Switch(checked = prefs.getBoolean("automatic", false), onCheckedChange = {
                prefs.edit().putBoolean("automatic", it).apply()
                ConnectionHealthJobService.schedule(this@ConnectionDiagnosticsActivity, it)
                tick++
                if (it) start("CHECK")
              })
              Text("Автопроверка каждые 30 минут", Modifier.weight(1f))
            }
            Text("Android может задержать фоновую проверку. При смене сети проверка запускается сразу, пока этот экран открыт. Внешние сайты получат небольшие тестовые запросы.", style = MaterialTheme.typography.bodySmall)
            val time = report.optLong("time")
            if (time > 0) {
              Text("${report.optString("network")} · ${SimpleDateFormat("dd.MM HH:mm:ss", Locale.getDefault()).format(Date(time))}")
              if (now - time > 60 * 60 * 1000) Text("Результаты устарели", color = MaterialTheme.colorScheme.error)
              Text(report.optString("summary"))
              val rows = report.optJSONArray("rows")
              if (rows != null) for (i in 0 until rows.length()) {
                val row = rows.getJSONObject(i)
                val state = row.optString("state")
                Card(Modifier.fillMaxWidth()) {
                  Column(Modifier.padding(12.dp)) {
                    Text((when (state) { "ok" -> "✓ "; "off" -> "○ "; else -> "! " }) + row.optString("name"),
                      color = when (state) { "ok" -> Color(0xFF28743B); "off" -> Color.Gray; else -> MaterialTheme.colorScheme.error })
                    Text(row.optString("detail"), style = MaterialTheme.typography.bodySmall)
                  }
                }
              }
              Text(report.optString("note"), style = MaterialTheme.typography.bodySmall)
            }
            if (!error.isNullOrBlank()) Text(error, color = MaterialTheme.colorScheme.error)
            HorizontalDivider()
            Text("Ошибка включения раздачи", style = MaterialTheme.typography.titleLarge)
            Text("Нажми «Записать», затем попробуй включить системную точку доступа. Запись идёт 90 секунд и собирает ошибки SoftAP, hostapd, tethering, маршруты и счётчики интерфейсов. Нужен root.")
            Button(onClick = { start("HOTSPOT") }, enabled = !capture && !checking) {
              Text(if (capture) "Запись: ${((now - captureSince) / 1000).coerceAtMost(90)} / 90 с" else "Записать ошибку раздачи")
            }
            if (capture) OutlinedButton(onClick = { stopService(Intent(this@ConnectionDiagnosticsActivity, DiagnosticService::class.java)) }) { Text("Остановить запись") }
            OutlinedButton(onClick = {
              val uri = FileProvider.getUriForFile(this@ConnectionDiagnosticsActivity, "$packageName.fileprovider", zip)
              val send = Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
              startActivity(Intent.createChooser(send, "Сохранить или отправить отчёт"))
            }, enabled = zip.exists() && !capture) { Text("Сохранить / отправить ZIP-отчёт") }
            Text("Отчёт остаётся на телефоне. Перед отправкой проверь содержимое: в нём есть сетевые адреса. Рабочие настройки раздачи и DNS запись не меняет.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { finish() }) { Text("Назад") }
          }
        }
      }
    }
  }
}

@Composable
private fun CheckTargetsEditor(context: android.content.Context, close: () -> Unit) {
  var targets by remember { mutableStateOf(ConnectionHealth.targets(context)) }
  var timeout by remember { mutableStateOf(ConnectionHealth.preferences(context).getInt("target_timeout", 4).toString()) }
  AlertDialog(onDismissRequest = close, title = { Text("Сайты для проверки напрямую") }, text = {
    Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedTextField(timeout, { timeout = it.filter(Char::isDigit).take(2) }, label = { Text("Таймаут · 2–15 с") }, singleLine = true)
      targets.forEachIndexed { index, target ->
        fun edit(next: ConnectionHealth.Target) { targets = targets.mapIndexed { i, item -> if (i == index) next else item } }
        HorizontalDivider()
        Row {
          Checkbox(target.enabled, onCheckedChange = { edit(target.copy(enabled = it)) })
          FilterChip(selected = target.group == "ru", onClick = { edit(target.copy(group = "ru")) }, label = { Text("РФ") })
          Spacer(Modifier.width(6.dp))
          FilterChip(selected = target.group == "world", onClick = { edit(target.copy(group = "world")) }, label = { Text("Зарубежные") })
        }
        OutlinedTextField(target.name, { edit(target.copy(name = it.take(60))) }, label = { Text("Название") }, singleLine = true)
        OutlinedTextField(target.url, { edit(target.copy(url = it.trim().take(2000))) }, label = { Text("HTTPS-адрес") }, singleLine = true)
        OutlinedTextField(if (target.expected == 0) "" else target.expected.toString(),
          { edit(target.copy(expected = it.filter(Char::isDigit).take(3).toIntOrNull() ?: 0)) },
          label = { Text("HTTP-код · пусто = 2xx") }, singleLine = true)
        TextButton(onClick = { targets = targets.filterIndexed { i, _ -> i != index } }) { Text("Удалить") }
      }
      TextButton(enabled = targets.size < 12, onClick = {
        targets = targets + ConnectionHealth.Target("", "https://", "world", 0)
      }) { Text("Добавить сайт") }
      TextButton(onClick = { targets = ConnectionHealth.defaults; timeout = "4" }) { Text("Вернуть стандартные") }
    }
  }, confirmButton = {
    val seconds = timeout.toIntOrNull()
    TextButton(enabled = seconds != null && seconds in 2..15 && targets.all(ConnectionHealth::validTarget), onClick = {
      ConnectionHealth.saveTargets(context, targets, seconds ?: 4)
      close()
    }) { Text("Сохранить") }
  }, dismissButton = { TextButton(onClick = close) { Text("Отмена") } })
}
