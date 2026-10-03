package com.android.zdtd.service.modes

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private data class ModeApp(val packageName: String, val label: String)

class ConnectionModesActivity : ComponentActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Screen() } }
  }
  @Composable
  private fun Screen() {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(JSONObject()) }
    var draft by remember { mutableStateOf(JSONObject()) }
    var status by remember { mutableStateOf(ModeClient.cached(this)) }
    var editMode by remember { mutableStateOf("white") }
    var message by remember { mutableStateOf("Загрузка настроек…") }
    var dirty by remember { mutableStateOf(false) }
    var appDialog by remember { mutableStateOf(false) }
    var nodeDialog by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<ModeApp>>(emptyList()) }
    fun change(field: String, value: Any) {
      val copy = JSONObject(draft.toString())
      copy.getJSONObject("modes").getJSONObject(editMode).put(field, value)
      draft = copy; dirty = true
    }
    suspend fun load(resetDraft: Boolean) {
      try {
        val value = withContext(Dispatchers.IO) { ModeClient.api(this@ConnectionModesActivity).getJsonData("/api/connection-modes") }
        check(value.has("config")) { value.optString("error", "Служба недоступна. Запусти ZDT-D; модуль и APK должны быть mod25.") }
        snapshot = value
        if (resetDraft) { draft = JSONObject(value.getJSONObject("config").toString()); dirty = false }
        status = value.getJSONObject("status")
        message = ""
      } catch (e: Exception) { message = e.message.orEmpty() }
    }
    LaunchedEffect(Unit) {
      load(true)
      apps = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        packageManager.getInstalledApplications(0).filter { it.uid % 100000 >= 10000 && !it.packageName.startsWith(packageName.substringBeforeLast('.')) }
          .map { ModeApp(it.packageName, packageManager.getApplicationLabel(it).toString()) }.sortedBy { it.label.lowercase() }
      }
      while (isActive) {
        delay(1200)
        status = withContext(Dispatchers.IO) { runCatching { ModeClient.refresh(this@ConnectionModesActivity) }.getOrElse {
          JSONObject().put("state", "error").put("mode", status.optString("mode")).put("message", "Нет связи со службой")
        } }
      }
    }
    val settings = draft.optJSONObject("modes")?.optJSONObject(editMode)
    val nodes = snapshot.optJSONArray("nodes").objects()
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Режимы подписки · mod25", style = MaterialTheme.typography.headlineSmall)
        Text("Подписку добавляй на экране «Подписки». Её существующее автообновление сохраняется. При обновлении список серверов режима обновится автоматически.")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          ModeClient.modes.forEach { mode ->
            Button(onClick = { if (dirty) message = "Сначала сохрани настройки" else ModeClient.switch(this@ConnectionModesActivity, mode) }, modifier = Modifier.weight(1f),
              colors = ButtonDefaults.buttonColors(containerColor = Color(ModeState.color(mode, status.optString("mode"), status.optString("state"))), contentColor = Color.Black)) { Text(ModeState.title(mode)) }
          }
        }
        Text(status.optString("message", "Выключено"), color = if (status.optString("state") == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (status.optInt("total") > 0 && status.optString("state") == "connecting") Text("Попытка ${status.optInt("attempt")} из ${status.optInt("total")} · круг ${status.optInt("round")}")
        OutlinedButton(onClick = { ModeClient.switch(this@ConnectionModesActivity, "") }, modifier = Modifier.fillMaxWidth()) { Text("Отключить режим") }
        HorizontalDivider()
        Text("Настройки режима", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          ModeClient.modes.forEach { mode -> FilterChip(selected = editMode == mode, onClick = { editMode = mode }, label = { Text(ModeState.title(mode)) }) }
        }
        if (settings != null) {
          val selectedApps = settings.optJSONArray("apps").strings()
          Text(if (editMode == "white") "Последний рабочий сервер — первым; затем остальные кандидаты по порядку. Максимум два полных круга, успех — ответ хотя бы от 2 из 3 зарубежных сайтов." else "Этот режим запоминает отдельный сервер и отдельный список приложений. Автоматического перебора других серверов нет.")
          val policies = listOf("selected" to "Только выбранные приложения", "all" to "Все приложения", "except_dns" to "Все, кроме приложений DNS", "blacklist" to "Чёрный список: все, кроме выбранных")
          policies.forEach { (policy, label) ->
            Row(Modifier.fillMaxWidth().clickable { change("app_policy", policy) }) {
              RadioButton(settings.optString("app_policy") == policy, onClick = { change("app_policy", policy) })
              Text(label, Modifier.padding(top = 12.dp))
            }
          }
          Text("DNS сохраняет свои профили во всех режимах. «Кроме DNS» исключает такие приложения целиком. Чёрный список исключает приложения из этого режима; их прежние назначения ZDT-D сохраняются.", style = MaterialTheme.typography.bodySmall)
          OutlinedButton(onClick = { appDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать приложения · ${selectedApps.size}") }
          if (editMode == "browser") OutlinedButton(onClick = {
            @Suppress("DEPRECATION")
            val browsers = packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE), PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.distinct()
            change("apps", JSONArray(browsers)); change("app_policy", "selected")
            message = "Найдено браузеров: ${browsers.size}. Проверь список перед сохранением."
          }) { Text("Выбрать установленные браузеры") }
          OutlinedButton(onClick = { nodeDialog = true }, modifier = Modifier.fillMaxWidth()) { Text(if (editMode == "white") "Кандидаты и порядок серверов" else "Выбрать сервер") }
          if (editMode == "white") {
            val keys = settings.optJSONArray("node_keys").strings()
            keys.forEachIndexed { index, key ->
              val node = nodes.find { it.optString("key") == key }
              Row(Modifier.fillMaxWidth()) {
                Text("${index + 1}. ${node?.optString("name") ?: "Сервер удалён из подписки"}", Modifier.weight(1f))
                TextButton(enabled = index > 0, onClick = { val reordered = keys.toMutableList(); reordered[index] = keys[index - 1]; reordered[index - 1] = key; change("node_keys", JSONArray(reordered)) }) { Text("↑") }
                TextButton(onClick = { change("node_keys", JSONArray(keys.filter { it != key })) }) { Text("×") }
              }
            }
            OutlinedTextField(value = settings.optJSONArray("name_filters").strings().joinToString("\n"), onValueChange = { change("name_filters", JSONArray(it.lines())) }, label = { Text("Правила имени · по одному на строку") }, modifier = Modifier.fillMaxWidth())
            Text("Например LTE совпадёт с LTE 1, LTE 2 и LTE AUTO независимо от регистра. Новые совпавшие серверы добавляются после ручного списка; удалённые пропускаются.", style = MaterialTheme.typography.bodySmall)
            Text("Подписки для правил имени (пустой выбор = все включённые):")
            nodes.distinctBy { it.optString("subscription_id") }.forEach { n ->
              val id = n.optString("subscription_id"); val ids = settings.optJSONArray("subscription_ids").strings()
              Row(Modifier.fillMaxWidth().clickable { change("subscription_ids", JSONArray(if (id in ids) ids - id else ids + id)) }) {
                Checkbox(id in ids, onCheckedChange = { checked -> change("subscription_ids", JSONArray(if (checked) ids + id else ids - id)) })
                Text(n.optString("subscription"), Modifier.padding(top = 12.dp))
              }
            }
          } else {
            val key = settings.optString("selected_key").ifBlank { settings.optString("last_key") }
            Text("Сервер: ${nodes.find { it.optString("key") == key }?.optString("name") ?: "не выбран"}")
          }
          Button(onClick = {
            val value = JSONObject(draft.toString())
            scope.launch {
              try {
                withContext(Dispatchers.IO) { check(ModeClient.api(this@ConnectionModesActivity).putJsonData("/api/connection-modes", value)) }
                dirty = false; load(true); message = "Сохранено"
              } catch (e: Exception) { message = "Не сохранено: ${e.message}" }
            }
          }, enabled = dirty, modifier = Modifier.fillMaxWidth()) { Text("Сохранить настройки") }
        }
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        HorizontalDivider()
        Text("Задержка серверов", style = MaterialTheme.typography.titleMedium)
        Text("Это время HTTPS-запросов через сервер, включая TLS и ответ сайта, а не ICMP-пинг. Успех требует ответа 2 из 3 сайтов; прямого обхода прокси нет.")
        OutlinedButton(onClick = {
          ContextCompat.startForegroundService(this@ConnectionModesActivity, Intent(this@ConnectionModesActivity, ModeActionService::class.java).putExtra("ping", true).putExtra("mode", status.optString("mode")))
        }, enabled = status.optString("state") != "connecting", modifier = Modifier.fillMaxWidth()) { Text("Проверить задержку всех серверов") }
        OutlinedButton(onClick = { scope.launch { load(false) } }) { Text("Обновить список и статус") }
        nodes.forEach { n ->
          val p = status.optJSONObject("pings")?.optJSONObject(n.optString("key"))
          val delay = when { !n.optBoolean("supported") -> "не поддерживается этим ядром (в т.ч. XHTTP)"; p == null -> "не проверен"; p.isNull("ms") -> "нет ответа 2 из 3 сайтов"; else -> "${p.optLong("ms")} мс · ${java.text.DateFormat.getTimeInstance().format(java.util.Date(p.optLong("time") * 1000))}" }
          Text("${n.optString("name")} · ${n.optString("protocol")}\n$delay", style = MaterialTheme.typography.bodyMedium)
        }
        Text("При смене сети и каждые 60 секунд активный режим проверяет доступность. После двух неудачных кругов он останавливается до ручного повторного включения. Режимы действуют на телефон; настройки раздачи остаются системными.", style = MaterialTheme.typography.bodySmall)
      }
    }
    if (appDialog && settings != null) {
      var search by remember { mutableStateOf("") }
      var picked by remember { mutableStateOf(settings.optJSONArray("apps").strings().toSet()) }
      AlertDialog(onDismissRequest = { appDialog = false }, title = { Text("Приложения · ${picked.size}") }, text = {
        Column {
          OutlinedTextField(search, { search = it }, label = { Text("Поиск") })
          Row { TextButton(onClick = { picked = apps.map { it.packageName }.toSet() }) { Text("Все") }; TextButton(onClick = { picked = apps.map { it.packageName }.toSet() - snapshot.optJSONArray("dns_apps").strings().toSet() }) { Text("Кроме DNS") }; TextButton(onClick = { picked = emptySet() }) { Text("Снять") } }
          LazyColumn(Modifier.height(340.dp)) {
            items(apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }, key = { it.packageName }) { app ->
              Row(Modifier.fillMaxWidth().clickable { picked = if (app.packageName in picked) picked - app.packageName else picked + app.packageName }) {
                Checkbox(app.packageName in picked, onCheckedChange = { checked -> picked = if (checked) picked + app.packageName else picked - app.packageName })
                Column(Modifier.padding(top = 6.dp)) { Text(app.label); Text(app.packageName, style = MaterialTheme.typography.bodySmall) }
              }
            }
          }
        }
      }, confirmButton = { TextButton(onClick = { change("apps", JSONArray(picked.toList())); appDialog = false }) { Text("Применить") } }, dismissButton = { TextButton(onClick = { appDialog = false }) { Text("Отмена") } })
    }
    if (nodeDialog && settings != null) {
      var picked by remember { mutableStateOf(if (editMode == "white") settings.optJSONArray("node_keys").strings() else listOf(settings.optString("selected_key"))) }
      AlertDialog(onDismissRequest = { nodeDialog = false }, title = { Text("Серверы") }, text = {
        LazyColumn(Modifier.height(360.dp)) {
          items(nodes, key = { it.optString("key") }) { node ->
            val key = node.optString("key"); val supported = node.optBoolean("supported")
            Row(Modifier.fillMaxWidth().clickable(enabled = supported) { picked = if (editMode == "white") { if (key in picked) picked - key else picked + key } else listOf(key) }) {
              Checkbox(key in picked, enabled = supported, onCheckedChange = { checked -> picked = if (editMode == "white") { if (checked) picked + key else picked - key } else listOf(key) })
              Column(Modifier.padding(top = 8.dp)) { Text(node.optString("name")); Text(if (supported) node.optString("subscription") else "Протокол/транспорт пока не поддерживается", style = MaterialTheme.typography.bodySmall) }
            }
          }
        }
      }, confirmButton = { TextButton(onClick = { if (editMode == "white") change("node_keys", JSONArray(picked)) else change("selected_key", picked.firstOrNull().orEmpty()); nodeDialog = false }) { Text("Применить") } }, dismissButton = { TextButton(onClick = { nodeDialog = false }) { Text("Отмена") } })
    }
  }
}
