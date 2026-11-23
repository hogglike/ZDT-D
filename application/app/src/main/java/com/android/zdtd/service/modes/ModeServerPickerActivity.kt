package com.android.zdtd.service.modes

import com.android.zdtd.service.RootConfigManager
import com.android.zdtd.service.ui.theme.ZdtdTheme
import com.android.zdtd.service.ui.theme.ZdtdThemeMode
import android.os.Bundle
import android.content.Intent
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
    val themeMode = ZdtdThemeMode.fromStorage(RootConfigManager(this).getThemeMode())
    setContent { ZdtdTheme(themeMode) {
      val scope = rememberCoroutineScope()
      var nodes by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
      var status by remember { mutableStateOf(JSONObject()) }
      var message by remember { mutableStateOf("Загрузка серверов…") }
      var busy by remember { mutableStateOf(false) }
      var selected by remember { mutableStateOf("") }
      var autoAvailable by remember { mutableStateOf(false) }
      var autoSelected by remember { mutableStateOf(false) }
      var choice by remember { mutableStateOf("") }
      var updated by remember { mutableStateOf(0L) }
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
          choice = if (autoSelected) "auto" else selected
          updated = status.optLong("maintenance_last_completed")
          message = if (nodes.isEmpty()) "Добавь или обнови подписку" else ""
        } catch (e: Exception) { message = e.message ?: "Служба недоступна" }
      }
      LaunchedEffect(Unit) {
        while (isActive) {
          delay(750L)
          val cached = ModeClient.cached(this@ModeServerPickerActivity)
          if (cached.has("maintenance_state")) status = cached
          val completed = cached.optLong("maintenance_last_completed")
          if (completed > updated) {
            updated = completed
            runCatching {
              val snapshot = withContext(Dispatchers.IO) { ModeClient.api(this@ModeServerPickerActivity).getJsonData("/api/connection-modes") }
              val catalog = snapshot.getJSONArray("nodes")
              nodes = (0 until catalog.length()).map { catalog.getJSONObject(it) }
            }
          }
        }
      }
      fun choose(key: String, connect: Boolean) {
        busy = true
        scope.launch {
          try {
            val result = withContext(Dispatchers.IO) { ModeClient.api(this@ModeServerPickerActivity).postJsonResult("/api/connection-modes/select-server", JSONObject().put("mode", mode).put("key", key)) }
            check(result.optBoolean("ok")) { result.optString("error", "Не удалось выбрать сервер") }
            withContext(Dispatchers.IO) { runCatching { ModeClient.refresh(this@ModeServerPickerActivity) } }
            if (connect) ModeClient.switch(this@ModeServerPickerActivity, mode)
            finish()
          } catch (e: Exception) { message = "Ошибка: ${e.message}"; busy = false }
        }
      }
      Surface(Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
          Text("${ModeState.title(mode)} · выбор сервера", style = MaterialTheme.typography.headlineSmall)
          if (message.isNotBlank()) Text(message)
          val checking = status.optString("maintenance_state") in listOf("pending", "refreshing", "checking")
          if (busy || checking) LinearProgressIndicator(Modifier.fillMaxWidth())
          status.optString("maintenance_message").takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = {
              startActivity(Intent(this@ModeServerPickerActivity, ConnectionModesActivity::class.java).putExtra("mode", mode).putExtra("open_apps", true))
              finish()
            }) { Text("Приложения") }
            TextButton(enabled = !checking && !busy && nodes.any { it.optBoolean("supported") }, onClick = {
              ModeClient.maintenance(this@ModeServerPickerActivity, false)
            }) { Text("Проверить задержку") }
          }
          TextButton(enabled = !checking && !busy, onClick = {
            ModeClient.maintenance(this@ModeServerPickerActivity, true, subscriptionOnly = true)
          }) { Text("Обновить подписки") }
          if (autoAvailable) {
            Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { choice = "auto" }.padding(vertical = 8.dp)) {
              RadioButton(choice == "auto", onClick = null, enabled = !busy)
              Column(Modifier.padding(start = 8.dp)) {
                Text("Авто · сохранённый список серверов")
                Text("Последний рабочий сервер или сохранённый замер", style = MaterialTheme.typography.bodySmall)
              }
            }
          }
          OutlinedTextField(search, { search = it }, label = { Text("Поиск сервера или подписки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
          LazyColumn(Modifier.weight(1f)) {
            items(sortedModeNodes(nodes, status).filter { it.optString("name").contains(search, true) || it.optString("subscription").contains(search, true) }, key = { it.optString("key") }) { node ->
              val key = node.optString("key")
              val supported = node.optBoolean("supported")
              Row(Modifier.fillMaxWidth().clickable(enabled = supported && !busy) {
                choice = key
              }.padding(vertical = 12.dp)) {
                RadioButton(choice == key, onClick = null, enabled = supported && !busy)
                Column(Modifier.padding(start = 8.dp)) {
                  ModeNodeLabel(node, status)
                }
              }
              HorizontalDivider()
            }
          }
          val validChoice = choice == "auto" && autoAvailable || nodes.any {
            it.optString("key") == choice && it.optBoolean("supported")
          }
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled = validChoice && !busy, onClick = { choose(choice, false) }) { Text("Сохранить") }
            Button(enabled = validChoice && !busy, onClick = { choose(choice, true) }) { Text("Подключить") }
          }
        }
      }
    } }
  }
}
