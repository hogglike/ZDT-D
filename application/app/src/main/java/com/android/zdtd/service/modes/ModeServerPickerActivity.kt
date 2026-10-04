package com.android.zdtd.service.modes

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.*
import org.json.JSONObject

/** Explicit widget control: launchers reserve long press for moving widgets. */
class ModeServerPickerActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    val mode = intent.getStringExtra("mode").orEmpty()
    if (mode !in ModeClient.modes) { finish(); return }
    setContent { MaterialTheme {
      val scope = rememberCoroutineScope()
      var nodes by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
      var status by remember { mutableStateOf(JSONObject()) }
      var message by remember { mutableStateOf("Загрузка серверов…") }
      var busy by remember { mutableStateOf(false) }
      var selected by remember { mutableStateOf("") }
      var autoAvailable by remember { mutableStateOf(false) }
      var autoSelected by remember { mutableStateOf(false) }
      var checksEnabled by remember { mutableStateOf(true) }
      var search by remember { mutableStateOf("") }
      LaunchedEffect(Unit) {
        try {
          val snapshot = withContext(Dispatchers.IO) { ModeClient.api(this@ModeServerPickerActivity).getJsonData("/api/connection-modes") }
          val catalog = snapshot.getJSONArray("nodes")
          nodes = (0 until catalog.length()).map { catalog.getJSONObject(it) }
          status = snapshot.getJSONObject("status")
          val settings = snapshot.getJSONObject("config").getJSONObject("modes").getJSONObject(mode)
          selected = settings.optString("selected_key").ifBlank { settings.optString("last_key") }
          autoAvailable = mode == "white" || settings.optBoolean("auto_enabled")
          autoSelected = autoAvailable && !settings.optBoolean("manual_override")
          checksEnabled = settings.optBoolean("check_enabled", true)
          message = if (nodes.isEmpty()) "Нет серверов: включи и обнови подписку" else "Нажми сервер, чтобы выбрать его и включить режим"
        } catch (e: Exception) { message = e.message ?: "Служба недоступна" }
      }
      fun choose(key: String) {
        busy = true
        scope.launch {
          try {
            val result = withContext(Dispatchers.IO) { ModeClient.api(this@ModeServerPickerActivity).postJsonResult("/api/connection-modes/select-server", JSONObject().put("mode", mode).put("key", key)) }
            check(result.optBoolean("ok")) { result.optString("error", "Не удалось выбрать сервер") }
            ModeClient.switch(this@ModeServerPickerActivity, mode)
            finish()
          } catch (e: Exception) { message = "Ошибка: ${e.message}"; busy = false }
        }
      }
      Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("${ModeState.title(mode)} · выбор сервера", style = MaterialTheme.typography.headlineSmall)
          Text(message)
          if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
          if (autoAvailable) {
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy && checksEnabled) { choose("auto") }.padding(vertical = 8.dp)) {
              RadioButton(autoSelected, onClick = null, enabled = !busy && checksEnabled)
              Column(Modifier.padding(start = 8.dp)) {
                Text("Авто · сохранённый список серверов")
                Text(if (checksEnabled) "Два круга по кандидатам; ручной выбор список не удаляет" else "Включи проверку сайтов в настройках режима", style = MaterialTheme.typography.bodySmall)
              }
            }
          }
          OutlinedTextField(search, { search = it }, label = { Text("Поиск сервера или подписки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          LazyColumn(Modifier.weight(1f)) {
            items(sortedModeNodes(nodes, status).filter { it.optString("name").contains(search, true) || it.optString("subscription").contains(search, true) }, key = { it.optString("key") }) { node ->
              val key = node.optString("key")
              val supported = node.optBoolean("supported")
              Row(Modifier.fillMaxWidth().clickable(enabled = supported && !busy) {
                choose(key)
              }.padding(vertical = 12.dp)) {
                RadioButton(!autoSelected && selected == key, onClick = null, enabled = supported && !busy)
                Column(Modifier.padding(start = 8.dp)) {
                  ModeNodeLabel(node, status)
                }
              }
              HorizontalDivider()
            }
          }
          TextButton(onClick = { finish() }) { Text("Отмена") }
        }
      }
    } }
  }
}
