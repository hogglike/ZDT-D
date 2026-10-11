package com.android.zdtd.service.watch

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.net.VpnService
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.android.zdtd.service.modes.ModeClient
import com.android.zdtd.service.BuildConfig
import com.android.zdtd.service.NonRootVpnRuntime
import com.android.zdtd.service.NonRootVpnState
import kotlinx.coroutines.*
import org.json.JSONObject

class WatchDnsActivity : ComponentActivity() {
  private val vpnPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
    if (result.resultCode == Activity.RESULT_OK) connect()
    else WatchDnsRuntime.update(WatchDnsState.STOPPED, "Разрешение VPN не предоставлено; DNS часов выключен")
  }

  private fun connect() {
    runCatching { ContextCompat.startForegroundService(this, WatchDnsVpnService.startIntent(this)) }
      .onFailure { WatchDnsRuntime.update(WatchDnsState.ERROR, it.message ?: "Не удалось запустить DNS часов") }
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme {
      val store = remember { WatchDnsStore(this@WatchDnsActivity) }
      val scope = rememberCoroutineScope()
      val state by WatchDnsRuntime.status.collectAsState()
      val active = state.state in setOf(WatchDnsState.STARTING, WatchDnsState.RUNNING, WatchDnsState.STOPPING)
      var profiles by remember { mutableStateOf<List<Pair<String, JSONObject>>>(emptyList()) }
      var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
      var selectedProfile by remember { mutableStateOf(store.profile) }
      var selectedApps by remember { mutableStateOf(store.apps) }
      var loading by remember { mutableStateOf(false) }
      var error by remember { mutableStateOf<String?>(null) }
      var showApps by remember { mutableStateOf(false) }
      var search by remember { mutableStateOf("") }
      var samsungOnly by remember { mutableStateOf(true) }
      var testing by remember { mutableStateOf(false) }
      var diagnosticMessage by remember { mutableStateOf<String?>(null) }
      var observe by remember { mutableStateOf(store.observe) }
      var observation by remember { mutableStateOf(WatchDnsObserved.journal.snapshot()) }
      LaunchedEffect(active) {
        do { observation = WatchDnsObserved.journal.snapshot(); delay(1000) } while (active)
      }

      fun reload() {
        loading = true
        error = null
        scope.launch {
          try {
            val loaded = withContext(Dispatchers.IO) {
              val document = ModeClient.api(this@WatchDnsActivity).getJsonData(WatchDnsVpnService.CONFIG)
              val entries = document.getJSONObject("profiles")
              val rows = entries.keys().asSequence().sorted().map { it to entries.getJSONObject(it) }.toList()
              @Suppress("DEPRECATION")
              val installed = packageManager.getInstalledApplications(0).filter { it.packageName != packageName }
                .map { it.packageName to packageManager.getApplicationLabel(it).toString() }
                .sortedBy { it.second.lowercase() }
              rows to installed
            }
            profiles = loaded.first
            apps = loaded.second
            if (!store.initialized) {
              selectedApps = apps.filter { WatchDnsPolicy.suggestedPackage(it.first, it.second) }.map { it.first }.toSet()
              store.apps = selectedApps
              store.initialized = true
            }
            if (selectedProfile.isBlank()) {
              selectedProfile = profiles.firstOrNull { it.second.optBoolean("enabled") }?.first.orEmpty()
              store.profile = selectedProfile
            }
            if (profiles.isEmpty()) error = "Сначала создай и включи DNS-профиль на предыдущем экране"
            else if (profiles.none { it.first == selectedProfile }) error = "Сохранённый профиль удалён. Выбери другой"
          } catch (e: Exception) { error = e.message ?: "Не удалось прочитать DNS-профили" }
          finally { loading = false }
        }
      }
      LaunchedEffect(Unit) { reload() }

      Surface(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.safeDrawingPadding().padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 16.dp)) {
          item {
            Text("DNS для часов · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.headlineSmall)
            Text("Для Galaxy Watch по Bluetooth. Android VPN перехватывает DNS выбранных приложений связи с часами. Обычные соединения идут через Wi-Fi или мобильную сеть телефона.")
          }
          item {
            Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(state.message, color = if (state.state == WatchDnsState.ERROR) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
              if (state.state in setOf(WatchDnsState.STARTING, WatchDnsState.STOPPING)) LinearProgressIndicator(Modifier.fillMaxWidth())
              Text("Включение заменяет HAPP и другой системный VPN. Остальные DNS-профили ZDT-D продолжают работать. При прямом Wi-Fi или LTE на часах этот режим их соединение не меняет.", style = MaterialTheme.typography.bodySmall)
              if (state.statsAvailable) Text("Пакеты на DNS-маршрутах: отправлено ${state.sentPackets}, получено ${state.receivedPackets}", style = MaterialTheme.typography.bodySmall)
              if (active) OutlinedButton(onClick = {
                runCatching { startService(WatchDnsVpnService.stopIntent(this@WatchDnsActivity)) }
                  .onFailure { error = it.message }
              }, enabled = state.state != WatchDnsState.STOPPING) { Text("Выключить DNS часов") }
              else Button(onClick = {
                store.profile = selectedProfile
                store.apps = selectedApps
                try {
                  check(NonRootVpnRuntime.state.value !in setOf(NonRootVpnState.STARTING, NonRootVpnState.RUNNING, NonRootVpnState.STOPPING)) {
                    "Сначала останови VPN в режиме ZDT-D без root"
                  }
                  check(selectedApps.isNotEmpty()) { "Выбери приложения для связи с часами" }
                  check(profiles.any { it.first == selectedProfile && it.second.optBoolean("enabled") }) { "Выбери включённый DNS-профиль" }
                  val permission = VpnService.prepare(this@WatchDnsActivity)
                  if (permission == null) connect() else vpnPermission.launch(permission)
                } catch (e: Exception) { error = e.message }
              }, enabled = !loading && selectedApps.isNotEmpty() && profiles.any { it.first == selectedProfile && it.second.optBoolean("enabled") }) { Text("Включить DNS часов") }
            } }
          }
          error?.let { message -> item { Text(message, color = MaterialTheme.colorScheme.error) } }
          item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Диагностика реальных DNS-запросов", modifier = Modifier.weight(1f))
              Switch(observe, { observe = it; store.observe = it }, enabled = !active)
            }
            Text("Включи до запуска DNS часов. Запись хранится в памяти и начинается отдельной кнопкой на 30 секунд. В ней имена доменов и DNS-ответы выбранных приложений; их содержимое и HTTPS-запросы не записываются.", style = MaterialTheme.typography.bodySmall)
            if (observation.available && state.state == WatchDnsState.RUNNING) {
              OutlinedButton(onClick = {
                runCatching { WatchDnsObserved.journal.start() }
                  .onSuccess {
                    observation = WatchDnsObserved.journal.snapshot()
                    diagnosticMessage = "Теперь открой Gemini на часах. Через 30 секунд скопируй отчёт кнопкой ниже."
                  }.onFailure { error = it.message }
              }, enabled = observation.remaining == 0 && !testing) {
                Text(if (observation.remaining > 0) "Запись DNS · ${observation.remaining} с" else "Записать DNS · 30 секунд")
              }
            }
            if (observation.started > 0) Text("За окно записи: вопросов ${observation.events.count { it.response == false }}, ответов ${observation.events.count { it.response == true }}. Запись относится ко всем выбранным приложениям вместе.", style = MaterialTheme.typography.bodySmall)
            if (observation.started > 0 && observation.remaining == 0 && observation.events.isEmpty())
              Text("DNS-запросов в этом окне не было. Причиной может быть кэш, свой DNS приложения или другой путь соединения часов.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {
              testing = true
              diagnosticMessage = "Проверка DNS по UDP и TCP…"
              scope.launch {
                try {
                  val report = withContext(Dispatchers.IO) { WatchDnsDiagnostics.collect(this@WatchDnsActivity) }
                  getSystemService(ClipboardManager::class.java).setPrimaryClip(
                    ClipData.newPlainText("ZDT-D · DNS часов", report.toString(2)))
                  diagnosticMessage = "Отчёт скопирован. В нём тестовые ответы профиля, счётчики пакетов и запись реальных DNS-запросов, если она была запущена."
                } catch (e: Exception) { diagnosticMessage = e.message ?: "Не удалось собрать отчёт" }
                finally { testing = false }
              }
            }, enabled = !testing && !loading && profiles.any { it.first == selectedProfile && it.second.optBoolean("enabled") }) {
              Text(if (testing) "Проверка DNS…" else "Проверить DNS и скопировать отчёт")
            }
            if (testing) LinearProgressIndicator(Modifier.fillMaxWidth())
            diagnosticMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            Text("Сначала открой Gemini на часах, подожди 10–15 секунд и собери отчёт. Пакеты считаются на DNS-маршрутах VPN; тестовые запросы приложения в этот счётчик не входят. Отчёт не содержит ссылку подписки или токен DNS.", style = MaterialTheme.typography.bodySmall)
          }
          item { Text("DNS-профиль", style = MaterialTheme.typography.titleMedium) }
          items(profiles, key = { it.first }) { (id, profile) ->
            val enabled = profile.optBoolean("enabled")
            Row(Modifier.fillMaxWidth().clickable(enabled = !active && enabled) {
              selectedProfile = id; store.profile = id; error = null
            }.padding(vertical = 4.dp)) {
              RadioButton(selectedProfile == id, onClick = null, enabled = !active && enabled)
              Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(profile.optString("display_name", id))
                Text(if (enabled) "Используется существующий профиль ZDT-D" else "Профиль выключен", style = MaterialTheme.typography.bodySmall)
              }
            }
          }
          item { OutlinedButton(onClick = { reload() }, enabled = !loading) { Text("Обновить профили") } }
          item {
            HorizontalDivider()
            Text("Приложения связи с часами", style = MaterialTheme.typography.titleMedium)
            Text("Начни с Galaxy Wearable и Galaxy Watch7 Manager, которые работали в HAPP. Если этого недостаточно, добавь Google, Gemini и Сервисы Google Play, отключив фильтр Samsung в списке. Добавление приложений само по себе не гарантирует работу часов.", style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = { showApps = true }, enabled = !active && apps.isNotEmpty()) {
              Text("Выбрать приложения · ${selectedApps.size}")
            }
            apps.filter { it.first in selectedApps }.forEach { Text(it.second, style = MaterialTheme.typography.bodySmall) }
            selectedApps.filter { key -> apps.none { it.first == key } }.forEach { Text("Удалено с телефона: $it", color = MaterialTheme.colorScheme.error) }
          }
          item {
            Text("После изменения самого DNS-профиля перезапусти ZDT-D и снова включи этот режим. Перед подключением проверяется реальный ответ example.com через профиль.", style = MaterialTheme.typography.bodySmall)
            Text("Работа через HAPP подтверждает работу VPN-сервера. Сможет ли выбранный DNS обеспечить работу Gemini на часах, проверяем отдельно.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { finish() }) { Text("Назад") }
          }
        }
      }
      if (showApps) AlertDialog(onDismissRequest = { showApps = false }, title = { Text("Приложения для часов") }, text = {
        Column {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Только Samsung")
            Switch(samsungOnly, { samsungOnly = it })
          }
          OutlinedTextField(search, { search = it }, label = { Text("Название или пакет") }, singleLine = true)
          LazyColumn(Modifier.heightIn(max = 360.dp)) {
            items(apps.filter { (!samsungOnly || it.first.startsWith("com.samsung.") || it.first in selectedApps) &&
              (it.first.contains(search, true) || it.second.contains(search, true)) }, key = { it.first }) { (key, name) ->
              Row(Modifier.fillMaxWidth().clickable {
                selectedApps = if (key in selectedApps) selectedApps - key else selectedApps + key
                store.apps = selectedApps
              }.padding(vertical = 4.dp)) {
                Checkbox(key in selectedApps, onCheckedChange = null)
                Column(Modifier.padding(start = 8.dp)) { Text(name); Text(key, style = MaterialTheme.typography.bodySmall) }
              }
            }
          }
        }
      }, confirmButton = { TextButton(onClick = { showApps = false }) { Text("Готово") } })
    } }
  }
}
