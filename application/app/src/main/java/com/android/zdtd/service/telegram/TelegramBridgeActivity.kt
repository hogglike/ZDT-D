package com.android.zdtd.service.telegram

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.api.ApiModels
import com.android.zdtd.service.modes.ModeClient
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class TelegramBridgeActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Surface { Screen() } } }
  }
  @Composable private fun Screen() {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(JSONObject()) }
    var status by remember { mutableStateOf(JSONObject()) }
    var automatic by remember { mutableStateOf(true) }
    var keys by remember { mutableStateOf(setOf<String>()) }
    var selected by remember { mutableStateOf("") }
    var search by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var dirty by remember { mutableStateOf(false) }
    suspend fun load() {
      val data = withContext(Dispatchers.IO) { ModeClient.api(this@TelegramBridgeActivity).getJsonData("/api/telegram-bridge") }
      check(data.optBoolean("ok")) { "Нет связи со службой ZDT-D" }
      snapshot = data; status = data.optJSONObject("status") ?: JSONObject()
      val c = data.optJSONObject("config") ?: JSONObject()
      automatic = c.optBoolean("automatic", true); selected = c.optString("selected_key")
      val arr = c.optJSONArray("node_keys") ?: JSONArray()
      keys = (0 until arr.length()).map { arr.getString(it) }.toSet(); dirty = false
    }
    LaunchedEffect(Unit) {
      runCatching { load() }.onFailure { message = it.message.orEmpty() }
      while (isActive) {
        delay(2000)
        runCatching { withContext(Dispatchers.IO) { ModeClient.api(this@TelegramBridgeActivity).getJsonData("/api/telegram-bridge/status") } }
          .onSuccess { status = it.optJSONObject("status") ?: status }
          .onFailure { message = "Нет связи со службой ZDT-D" }
      }
    }
    fun action(enable: Boolean) { scope.launch {
      busy = true; message = ""
      try {
        val cfg = JSONObject().put("enabled", enable).put("automatic", automatic).put("selected_key", selected).put("node_keys", JSONArray(keys.toList()))
        withContext(Dispatchers.IO) {
          val api = ModeClient.api(this@TelegramBridgeActivity)
          if (enable) {
            if (!ApiModels.isServiceOn(api.getStatus())) check(api.startService()) { "Не удалось запустить ZDT-D" }
            var started = false
            repeat(30) { if (!started) { val s = api.getStatus(); started = s != null && ApiModels.isServiceOn(s) && !s.startInProgress && !s.stopInProgress; if (!started) delay(1000) } }
            check(started) { "Запуск ZDT-D не завершён" }
          }
          if (enable) check(api.putJsonData("/api/telegram-bridge", cfg)) { "Не удалось сохранить подключение" }
          else check(api.postJsonResult("/api/telegram-bridge/stop", JSONObject()).optBoolean("ok")) { "Не удалось отключить" }
        }
        load()
      } catch(e: Exception) { if (e is CancellationException) throw e; message = e.message.orEmpty() }
      finally { busy = false }
    } }
    val nodes = snapshot.optJSONArray("nodes") ?: JSONArray()
    val all = (0 until nodes.length()).map { nodes.getJSONObject(it) }.filter { it.optBoolean("supported") && it.optBoolean("enabled", true) }
    val missing = keys - all.map { it.optString("key") }.toSet()
    fun link(): Uri = Uri.Builder().scheme("tg").authority("socks").appendQueryParameter("server", "127.0.0.1")
      .appendQueryParameter("port", snapshot.optInt("port", 19978).toString()).appendQueryParameter("user", snapshot.optString("username"))
      .appendQueryParameter("pass", snapshot.optString("password")).build()
    Column(Modifier.fillMaxSize().systemBarsPadding().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("Telegram через подписку", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        TextButton(onClick = { finish() }) { Text("Назад") }
      }
      Text("Локальный SOCKS5 для встроенной настройки Telegram. Работает отдельно от режимов VPN и Opera. Использует трафик выбранной VPN-подписки.")
      Card(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(status.optString("message", "Загрузка…"), color = if (status.optString("state") == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (status.optString("server").isNotBlank()) Text(status.optString("server"))
        Text("Проверка HTTPS: web.telegram.org. Это не проверка доставки сообщений и звонков.", style = MaterialTheme.typography.bodySmall)
      } }
      if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.error)
      if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { action(true) }, enabled = !busy && snapshot.has("config") && (automatic || selected.isNotBlank())) { Text(if (dirty) "Сохранить и подключить" else "Подключить") }
        OutlinedButton(onClick = { action(false) }, enabled = !busy && snapshot.has("config")) { Text("Отключить") }
      }
      Button(onClick = { runCatching { startActivity(Intent(Intent.ACTION_VIEW, link())) }.onFailure { message = "Telegram не найден. Скопируй настройки и добавь SOCKS5 вручную." } }, enabled = snapshot.optString("password").isNotBlank() && status.optString("state") in listOf("ready", "degraded"), modifier = Modifier.fillMaxWidth()) { Text("Открыть в Telegram") }
      Text("В Telegram подтверди добавление прокси и включи его. Если останавливаешь этот инструмент, отключи прокси и в Telegram. Для звонков проверь переключатель «Использовать для звонков» в Telegram.", style = MaterialTheme.typography.bodySmall)
      TextButton(onClick = {
        getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("Telegram SOCKS5", link().toString()))
        message = "Ссылка с паролем скопирована; она предназначена только для этого телефона"
      }, enabled = snapshot.optString("password").isNotBlank()) { Text("Скопировать настройку SOCKS5") }
      HorizontalDivider()
      Row { Switch(automatic, { automatic = it; dirty = true }, enabled = !busy); Text("Автоперебор серверов", Modifier.padding(12.dp)) }
      Text(if (automatic) "Две ошибки подряд → следующий сервер. Проверка раз в минуту. Пустой список — все поддерживаемые серверы включённых подписок, включая новые." else "Ручной выбор: сервер не меняется автоматически.", style = MaterialTheme.typography.bodySmall)
      if (missing.isNotEmpty()) {
        Text("Недоступных записей: ${missing.size}. Они пропускаются; остальные выбранные серверы сохраняются.")
        TextButton(onClick = { keys = keys - missing; dirty = true }) { Text("Убрать недоступные из выбора") }
      }
      if (automatic) TextButton(onClick = { keys = emptySet(); dirty = true }) { Text("Использовать все серверы (${all.size})") }
      OutlinedTextField(search, { search = it }, Modifier.fillMaxWidth(), label = { Text("Поиск сервера или подписки") }, singleLine = true)
      all.filter { (it.optString("name") + it.optString("subscription")).contains(search, true) }
        .sortedBy { if ((automatic && it.optString("key") in keys) || (!automatic && it.optString("key") == selected)) 0 else 1 }
        .forEach { n ->
          val key = n.optString("key"); val checked = if (automatic) key in keys else key == selected
          Row(Modifier.fillMaxWidth().clickable(enabled = !busy) { if (automatic) keys = if (checked) keys - key else keys + key else selected = key; dirty = true }) {
            if (automatic) Checkbox(checked, null) else RadioButton(checked, null)
            Column(Modifier.padding(10.dp)) { Text(n.optString("name")); Text(n.optString("subscription"), style = MaterialTheme.typography.bodySmall) }
          }
        }
      if (all.isEmpty()) Text("Нет поддерживаемых серверов. Добавь или обнови VPN-подписку в разделе «Подписки».")
      OutlinedButton(onClick = { scope.launch { if (dirty) message = "Сначала сохрани выбор кнопкой подключения" else runCatching { load() }.onFailure { message = it.message.orEmpty() } } }, enabled = !busy) { Text("Обновить список") }
      val errors = status.optJSONArray("failures") ?: JSONArray()
      if (errors.length() > 0) { Text("Последняя проверка", style = MaterialTheme.typography.titleMedium); for (i in 0 until errors.length()) { val e = errors.getJSONObject(i); Text("${e.optString("server")}: ${e.optString("message")}", style = MaterialTheme.typography.bodySmall) } }
    }
  }
}
