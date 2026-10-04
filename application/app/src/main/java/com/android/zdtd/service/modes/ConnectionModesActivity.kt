package com.android.zdtd.service.modes

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
private fun JSONArray?.objects(): List<JSONObject> = if (this == null) emptyList() else (0 until length()).mapNotNull { optJSONObject(it) }
private data class ModeApp(val packageName: String, val label: String, val uid: Int)
private fun pickedApps(settings: JSONObject?, apps: List<ModeApp>, dns: List<String>): List<String> {
  if (settings == null) return emptyList()
  val dnsUids = apps.filter { it.packageName in dns }.map { it.uid }.toSet()
  return when (settings.optString("app_policy")) {
    "all" -> apps.map { it.packageName }
    "except_dns" -> apps.filter { it.uid !in dnsUids }.map { it.packageName }
    else -> settings.optJSONArray("apps").strings()
  }
}
private fun normalizePolicies(config: JSONObject, apps: List<ModeApp>, dns: List<String>): JSONObject {
  val copy = JSONObject(config.toString())
  ModeClient.modes.forEach { mode ->
    val settings = copy.optJSONObject("modes")?.optJSONObject(mode)
    if (settings != null && settings.optString("app_policy") in listOf("all", "except_dns")) {
      settings.put("apps", JSONArray(pickedApps(settings, apps, dns))).put("app_policy", "selected")
    }
  }
  return copy
}

class ConnectionModesActivity : ComponentActivity() {
  private val refreshTick = mutableIntStateOf(0)
  override fun onResume() { super.onResume(); refreshTick.intValue++ }
  @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Screen() } }
  }
  @OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    var page by remember { mutableIntStateOf(0) }
    var serverSearch by remember { mutableStateOf("") }
    var diagnosticsExpanded by remember { mutableStateOf(false) }
    var rulesExpanded by remember(editMode) { mutableStateOf(false) }
    var checksExpanded by remember(editMode) { mutableStateOf(false) }
    var latencyExpanded by remember { mutableStateOf(false) }
    var apps by remember { mutableStateOf<List<ModeApp>>(emptyList()) }
    fun change(field: String, value: Any) {
      val copy = JSONObject(draft.toString())
      copy.getJSONObject("modes").getJSONObject(editMode).put(field, value)
      draft = copy; dirty = true
    }
    suspend fun load(resetDraft: Boolean) {
      try {
        val value = withContext(Dispatchers.IO) { ModeClient.api(this@ConnectionModesActivity).getJsonData("/api/connection-modes") }
        check(value.has("config")) { value.optString("error", "Служба недоступна. Запусти ZDT-D; модуль и APK должны быть mod31.") }
        snapshot = value
        if (resetDraft) { draft = JSONObject(value.getJSONObject("config").toString()); dirty = value.optInt("selection_cleanup_count") > 0 }
        status = value.getJSONObject("status")
        message = if (value.optInt("selection_cleanup_count") > 0) "Убрано недоступных старых записей: ${value.optInt("selection_cleanup_count")}. Сохрани настройки." else ""
      } catch (e: Exception) { message = e.message.orEmpty() }
    }
    LaunchedEffect(refreshTick.intValue) { load(!dirty) }
    LaunchedEffect(status.optLong("maintenance_last_completed")) {
      if (status.optLong("maintenance_last_completed") > 0) load(!dirty)
    }
    LaunchedEffect(Unit) {
      apps = withContext(Dispatchers.IO) {
        @Suppress("DEPRECATION")
        packageManager.getInstalledApplications(0).filter { it.uid % 100000 >= 10000 && !it.packageName.startsWith(packageName.substringBeforeLast('.')) }
          .map { ModeApp(it.packageName, packageManager.getApplicationLabel(it).toString(), it.uid) }.sortedBy { it.label.lowercase() }
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
    val selectedApps = pickedApps(settings, apps, snapshot.optJSONArray("dns_apps").strings())
    val multiple = editMode == "white" || settings?.optBoolean("auto_enabled") == true
    Scaffold(bottomBar = {
      if (dirty) Surface(tonalElevation = 4.dp) {
        Button(onClick = {
          val value = normalizePolicies(draft, apps, snapshot.optJSONArray("dns_apps").strings())
          scope.launch {
            try {
              withContext(Dispatchers.IO) { check(ModeClient.api(this@ConnectionModesActivity).putJsonData("/api/connection-modes", value)) }
              dirty = false; load(true); message = "Сохранено"
            } catch (e: Exception) { message = "Не сохранено: ${e.message}" }
          }
        }, enabled = apps.isNotEmpty(), modifier = Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp)) { Text("Сохранить изменения") }
      }
    }) { contentPadding ->
      Column(Modifier.padding(contentPadding).safeDrawingPadding().padding(16.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Режимы подключения · mod31", style = MaterialTheme.typography.headlineSmall)
        Text("Нажми режим для подключения. Выбор сервера доступен удержанием кнопки.", style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          ModeClient.modes.forEach { mode ->
            Surface(modifier = Modifier.weight(1f).combinedClickable(
              onClick = { if (dirty) message = "Сначала сохрани настройки" else ModeClient.switch(this@ConnectionModesActivity, mode) },
              onLongClick = { if (dirty) message = "Сначала сохрани настройки" else ModeClient.pickServer(this@ConnectionModesActivity, mode) }),
              shape = MaterialTheme.shapes.large, color = Color(ModeState.color(mode, status.optString("mode"), status.optString("state")))) {
              Text(ModeState.title(mode), Modifier.padding(horizontal = 4.dp, vertical = 14.dp), color = Color.Black, maxLines = 1, style = MaterialTheme.typography.labelMedium)
            }
          }
        }
        Text(status.optString("message", "Выключено"), color = if (status.optString("state") == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        TextButton(onClick = { diagnosticsExpanded = !diagnosticsExpanded }) { Text("Диагностика ${if (diagnosticsExpanded) "▴" else "▾"}") }
        if (diagnosticsExpanded) {
        val probes = status.optJSONArray("probe_results").objects()
        if (probes.isNotEmpty()) {
          Text("Результаты последней проверки:", style = MaterialTheme.typography.titleSmall)
          probes.forEach { result ->
            Text("${if (result.optBoolean("ok")) "✓" else "×"} ${result.optString("url")}\n${if (result.isNull("http_status")) "Без HTTP-ответа" else "HTTP ${result.optInt("http_status")}"} · ${result.optLong("ms")} мс${if (result.optString("error").isBlank()) "" else "\n${result.optString("error")}"}", style = MaterialTheme.typography.bodySmall)
          }
        }
        OutlinedButton(onClick = {
          scope.launch {
            try {
              val report = withContext(Dispatchers.IO) { ModeClient.api(this@ConnectionModesActivity).getJsonData("/api/connection-modes/diagnostics") }
              check(report.optBoolean("ok")) { "Не удалось прочитать диагностику" }
              val clipboard = getSystemService(android.content.ClipboardManager::class.java)
              clipboard.setPrimaryClip(android.content.ClipData.newPlainText("ZDT-D mod31", report.toString(2)))
              message = "Диагностика скопирована; ссылка подписки и ключи серверов не включены"
            } catch (e: Exception) { message = "Не удалось скопировать: ${e.message}" }
          }
        }) { Text("Скопировать диагностику подключения") }
        }
        if (status.optInt("total") > 0 && status.optString("state") == "connecting") Text("Попытка ${status.optInt("attempt")} из ${status.optInt("total")} · круг ${status.optInt("round")}")
        OutlinedButton(onClick = { ModeClient.switch(this@ConnectionModesActivity, "") }, modifier = Modifier.fillMaxWidth()) { Text("Отключить режим") }
        HorizontalDivider()
        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
        TabRow(selectedTabIndex = page) {
          listOf("Настройки", "Серверы и задержка").forEachIndexed { index, title ->
            Tab(selected = page == index, onClick = { page = index }, text = { Text(title) })
          }
        }
        if (page == 0) {
        Text("Настроить режим", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
          ModeClient.modes.forEach { mode -> FilterChip(selected = editMode == mode, onClick = { editMode = mode }, label = { Text(ModeState.title(mode)) }) }
        }
        if (settings != null) {
          if (editMode != "white") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
              Text("Несколько серверов · автоперебор", Modifier.weight(1f).padding(top = 10.dp))
              Checkbox(settings.optBoolean("auto_enabled"), onCheckedChange = {
                change("auto_enabled", it); change("manual_override", !it)
                if (it) {
                  val keys = settings.optJSONArray("node_keys").strings()
                  val selected = settings.optString("selected_key")
                  if (keys.isEmpty() && selected.isNotBlank()) change("node_keys", JSONArray(listOf(selected)))
                  change("check_enabled", true)
                }
              })
            }
          }
          Text(if (multiple) "Авто: два круга, последний рабочий сервер — первым. Ручной выбор сохраняет список." else "Один сервер для выбранных приложений.", style = MaterialTheme.typography.bodySmall)
          if (multiple) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(selected = !settings.optBoolean("manual_override"), onClick = { change("manual_override", false); change("check_enabled", true) }, label = { Text("Авто") })
              FilterChip(selected = settings.optBoolean("manual_override"), onClick = { change("manual_override", true) }, label = { Text("Вручную") })
            }
            if (settings.optBoolean("manual_override")) Text("Ручной сервер: ${nodes.find { it.optString("key") == settings.optString("selected_key").ifBlank { settings.optString("last_key") } }?.optString("name") ?: "не выбран"}")
            if (settings.optBoolean("manual_override")) OutlinedButton(onClick = {
              if (dirty) message = "Сначала сохрани настройки" else ModeClient.pickServer(this@ConnectionModesActivity, editMode)
            }) { Text("Выбрать ручной сервер") }
          }
          val policies = listOf("selected" to "Только выбранные приложения", "blacklist" to "Чёрный список: все, кроме выбранных")
          policies.forEach { (policy, label) ->
            Row(Modifier.fillMaxWidth().clickable { change("apps", JSONArray(selectedApps)); change("app_policy", policy) }) {
              RadioButton((if (settings.optString("app_policy") == "blacklist") "blacklist" else "selected") == policy, onClick = { change("apps", JSONArray(selectedApps)); change("app_policy", policy) })
              Text(label, Modifier.padding(top = 12.dp))
            }
          }
          Text(if (settings.optString("app_policy") == "blacklist") "Отмеченные приложения исключены из VPN-режима." else "Через VPN работают только отмеченные приложения.", style = MaterialTheme.typography.bodySmall)
          OutlinedButton(onClick = { appDialog = true }, modifier = Modifier.fillMaxWidth()) { Text("Выбрать приложения · ${selectedApps.size}") }
          if (editMode == "browser") OutlinedButton(onClick = {
            @Suppress("DEPRECATION")
            val browsers = packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE), PackageManager.MATCH_DEFAULT_ONLY).map { it.activityInfo.packageName }.distinct()
            change("apps", JSONArray(browsers)); change("app_policy", "selected")
            message = "Найдено браузеров: ${browsers.size}. Проверь список перед сохранением."
          }) { Text("Выбрать установленные браузеры") }
          OutlinedButton(onClick = { nodeDialog = true }, modifier = Modifier.fillMaxWidth()) { Text(if (multiple) "Выбрать серверы и порядок" else "Выбрать сервер") }
          if (multiple) {
            val keys = settings.optJSONArray("node_keys").strings()
            Text("Выбрано: ${keys.size}", style = MaterialTheme.typography.titleSmall)
            keys.take(3).forEachIndexed { index, key ->
              val node = nodes.find { it.optString("key") == key }
              Text("${index + 1}. ${node?.optString("name") ?: "Временно недоступен"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (keys.size > 3) Text("Ещё ${keys.size - 3} · полный порядок в выборе серверов", style = MaterialTheme.typography.bodySmall)
            val filters = settings.optJSONArray("name_filters").strings().filter { it.isNotBlank() }
            TextButton(onClick = { rulesExpanded = !rulesExpanded }) {
              Text("Добавлять новые серверы по имени${if (filters.isEmpty()) "" else " · ${filters.joinToString()}"} ${if (rulesExpanded) "▴" else "▾"}")
            }
            if (rulesExpanded) {
              Text("Необязательно. Например, LTE добавит LTE 1, LTE 2 и LTE AUTO после выбранных серверов.", style = MaterialTheme.typography.bodySmall)
              OutlinedTextField(value = settings.optJSONArray("name_filters").strings().joinToString("\n"), onValueChange = { change("name_filters", JSONArray(it.lines())) }, label = { Text("Часть имени · одна на строку") }, modifier = Modifier.fillMaxWidth())
              Text("Где искать совпадения по имени:", style = MaterialTheme.typography.titleSmall)
              Text("Пустой выбор — во всех включённых подписках. Галочки ниже ограничивают только поиск по имени.", style = MaterialTheme.typography.bodySmall)
              val subscriptions = snapshot.optJSONArray("subscriptions").objects().ifEmpty { nodes.distinctBy { it.optString("subscription_id") }.map { JSONObject().put("id", it.optString("subscription_id")).put("name", it.optString("subscription")) } }
              val ids = settings.optJSONArray("subscription_ids").strings()
              TextButton(onClick = { change("subscription_ids", JSONArray()) }) { Text(if (ids.isEmpty()) "✓ Все включённые подписки" else "Искать во всех подписках") }
              subscriptions.forEach { subscription ->
                val id = subscription.optString("id")
                Row(Modifier.fillMaxWidth().clickable { change("subscription_ids", JSONArray(if (id in ids) ids - id else ids + id)) }) {
                  Checkbox(id in ids, onCheckedChange = { checked -> change("subscription_ids", JSONArray(if (checked) ids + id else ids - id)) })
                  Text("Искать в ${subscription.optString("name")}", Modifier.padding(top = 12.dp))
                }
              }
            }
          } else {
            val key = settings.optString("selected_key").ifBlank { settings.optString("last_key") }
            Text("Сервер: ${nodes.find { it.optString("key") == key }?.optString("name") ?: "не выбран"}")
          }
          TextButton(onClick = { checksExpanded = !checksExpanded }) { Text("Проверка соединения · ${if (settings.optBoolean("check_enabled", true)) "включена" else "выключена"} ${if (checksExpanded) "▴" else "▾"}") }
          if (checksExpanded) {
          HorizontalDivider()
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("Проверять сайты перед подключением", Modifier.weight(1f).padding(top = 10.dp))
            Switch(settings.optBoolean("check_enabled", true), onCheckedChange = { change("check_enabled", it) })
          }
          Text(if (settings.optBoolean("check_enabled", true)) "Проверка применяется только к этому режиму. Каждый сайт можно выключить отдельно. HTTP-код 0: подходит любой ответ 200–399; можно задать точный код." else "Сервер и маршруты будут применены без проверки доступа к сайтам. Зелёный цвет означает, что режим применён; работоспособность интернета не подтверждена.", style = MaterialTheme.typography.bodySmall)
          if (multiple && !settings.optBoolean("check_enabled", true)) Text("Без проверки применяется только первый кандидат; автоперебор не может определить работоспособность.", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
          val sites = settings.optJSONArray("check_sites").objects()
          sites.forEachIndexed { index, site ->
            fun editSite(field: String, value: Any) {
              val copy = JSONArray(settings.optJSONArray("check_sites").toString())
              copy.getJSONObject(index).put(field, value); change("check_sites", copy)
            }
            Row(Modifier.fillMaxWidth()) {
              Checkbox(site.optBoolean("enabled", true), onCheckedChange = { editSite("enabled", it) })
              OutlinedTextField(site.optString("url"), onValueChange = { editSite("url", it) }, label = { Text("HTTPS-адрес ${index + 1}") }, singleLine = true, modifier = Modifier.weight(1f))
              TextButton(onClick = { change("check_sites", JSONArray(sites.filterIndexed { i, _ -> i != index })) }) { Text("×") }
            }
            OutlinedTextField(site.optInt("expected_status").toString(), onValueChange = { if (it.isEmpty() || it.all(Char::isDigit)) editSite("expected_status", it.toIntOrNull() ?: 0) }, label = { Text("HTTP-код · 0 = автоматически") }, singleLine = true)
          }
          TextButton(enabled = sites.size < 12, onClick = { change("check_sites", JSONArray(sites + JSONObject().put("url", "https://example.com/").put("enabled", true).put("expected_status", 0))) }) { Text("Добавить сайт") }
          OutlinedTextField(settings.optInt("min_success", 2).toString(), onValueChange = { if (it.isEmpty() || it.all(Char::isDigit)) change("min_success", it.toIntOrNull() ?: 0) }, label = { Text("Сколько успешных ответов требуется") }, singleLine = true)
          OutlinedTextField(settings.optInt("timeout_seconds", 8).toString(), onValueChange = { if (it.isEmpty() || it.all(Char::isDigit)) change("timeout_seconds", it.toIntOrNull() ?: 0) }, label = { Text("Таймаут одного сайта · 2–30 секунд") }, singleLine = true)
          HorizontalDivider()
          }

        }
        } else {
        Text("Серверы и задержка", style = MaterialTheme.typography.titleMedium)
        Text("Обновить серверы: загрузить включённые подписки, убрать удалённые записи и проверить задержку всех поддерживаемых серверов.", style = MaterialTheme.typography.bodySmall)
        val maintenanceBusy = status.optString("maintenance_state") in listOf("pending", "refreshing", "checking")
        Button(onClick = { ModeClient.maintenance(this@ConnectionModesActivity, true) }, enabled = !dirty && !maintenanceBusy, modifier = Modifier.fillMaxWidth()) { Text("Обновить серверы и проверить задержку") }
        if (status.optString("maintenance_message").isNotBlank()) Text(status.optString("maintenance_message"), color = if (status.optString("maintenance_state") == "error") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
        if (maintenanceBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(Modifier.weight(1f)) {
            Text("Автоматически обновлять и проверять")
            Text("При запущенном ZDT-D. Обычное автообновление подписок сохраняется.", style = MaterialTheme.typography.bodySmall)
          }
          Switch(draft.optBoolean("refresh_enabled"), onCheckedChange = { draft = JSONObject(draft.toString()).put("refresh_enabled", it); dirty = true })
        }
        if (draft.optBoolean("refresh_enabled")) {
          OutlinedTextField(draft.optInt("refresh_interval_minutes", 60).toString(), onValueChange = { if (it.isEmpty() || it.all(Char::isDigit)) { draft = JSONObject(draft.toString()).put("refresh_interval_minutes", it.toIntOrNull() ?: 0); dirty = true } }, label = { Text("Интервал, минут · 15–10080") }, singleLine = true)
          Text("Первый запуск после включения расписания — сразу, если прежняя проверка уже просрочена. Массовый тест расходует трафик и заряд.", style = MaterialTheme.typography.bodySmall)
        }
        Text("Порядок выбранных серверов сохраняется. Новые добавляются в Авто по правилам имени; без правила — выбери их вручную. Ручной выбор через виджет сохраняется.", style = MaterialTheme.typography.bodySmall)
        Text("Задержка HTTPS-запроса через сервер. Отдельная проверка не переключает текущий режим.", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = { latencyExpanded = !latencyExpanded }) { Text("Адрес и таймаут задержки ${if (latencyExpanded) "▴" else "▾"}") }
        if (latencyExpanded) {
        OutlinedTextField(draft.optString("latency_url", "https://www.gstatic.com/generate_204"), onValueChange = { draft = JSONObject(draft.toString()).put("latency_url", it); dirty = true }, label = { Text("HTTPS-адрес для задержки всех серверов") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.optInt("latency_timeout_seconds", 8).toString(), onValueChange = { if (it.isEmpty() || it.all(Char::isDigit)) { draft = JSONObject(draft.toString()).put("latency_timeout_seconds", it.toIntOrNull() ?: 0); dirty = true } }, label = { Text("Таймаут задержки · 2–30 секунд") }, singleLine = true)
        Text("Изменения сохраняются кнопкой внизу экрана.", style = MaterialTheme.typography.bodySmall)
        }
        OutlinedButton(onClick = {
          ModeClient.maintenance(this@ConnectionModesActivity, false)
        }, enabled = !dirty && !maintenanceBusy, modifier = Modifier.fillMaxWidth()) { Text("Только проверить задержку") }
        OutlinedButton(onClick = { scope.launch { load(false) } }) { Text("Обновить список и статус") }
        OutlinedTextField(serverSearch, { serverSearch = it }, label = { Text("Поиск сервера или подписки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        val ordered = sortedModeNodes(nodes, status).filter { it.optString("name").contains(serverSearch, true) || it.optString("subscription").contains(serverSearch, true) }
        Text("Работают: ${nodes.count { nodeRank(it, status) == 0 }} · без ответа: ${nodes.count { nodeRank(it, status) == 2 }} · всего: ${nodes.size}", style = MaterialTheme.typography.bodySmall)
        ordered.forEach { n ->
          var details by remember(n.optString("key")) { mutableStateOf(false) }
          Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f), shape = MaterialTheme.shapes.medium) {
            Column(Modifier.fillMaxWidth().padding(12.dp)) {
              ModeNodeLabel(n, status)
              if (nodeRank(n, status) == 2) {
                TextButton(onClick = { details = !details }) { Text(if (details) "Скрыть ошибку" else "Причина ошибки") }
                if (details) Text(modePing(n, status)?.optString("error").orEmpty(), style = MaterialTheme.typography.bodySmall)
              }
            }
          }
        }
        }

      }
    }
    if (appDialog && settings != null) {
      var search by remember { mutableStateOf("") }
      var picked by remember { mutableStateOf(selectedApps.toSet()) }
      AlertDialog(onDismissRequest = { appDialog = false }, title = { Text("Приложения · ${picked.size}") }, text = {
        Column {
          OutlinedTextField(search, { search = it }, label = { Text("Поиск") })
          Row { TextButton(onClick = { picked = apps.map { it.packageName }.toSet() }) { Text("Все") }; TextButton(onClick = { picked = apps.filter { it.uid !in apps.filter { a -> a.packageName in snapshot.optJSONArray("dns_apps").strings() }.map { a -> a.uid }.toSet() }.map { it.packageName }.toSet() }) { Text("Кроме DNS") }; TextButton(onClick = { picked = emptySet() }) { Text("Снять") } }
          LazyColumn(Modifier.height(340.dp)) {
            items(apps.filter { it.label.contains(search, true) || it.packageName.contains(search, true) }, key = { it.packageName }) { app ->
              Row(Modifier.fillMaxWidth().clickable { picked = if (app.packageName in picked) picked - app.packageName else picked + app.packageName }) {
                Checkbox(app.packageName in picked, onCheckedChange = { checked -> picked = if (checked) picked + app.packageName else picked - app.packageName })
                Column(Modifier.padding(top = 6.dp)) { Text(app.label); Text(app.packageName, style = MaterialTheme.typography.bodySmall) }
              }
            }
          }
        }
      }, confirmButton = { TextButton(onClick = { change("apps", JSONArray(picked.toList())); if (settings.optString("app_policy") != "blacklist") change("app_policy", "selected"); appDialog = false }) { Text("Применить") } }, dismissButton = { TextButton(onClick = { appDialog = false }) { Text("Отмена") } })
    }
    if (nodeDialog && settings != null) {
      var search by remember { mutableStateOf("") }
      var onlyPicked by remember { mutableStateOf(false) }
      var picked by remember { mutableStateOf((if (multiple) settings.optJSONArray("node_keys").strings() else listOf(settings.optString("selected_key"))).filter { key -> nodes.any { it.optString("key") == key } }.distinct()) }
      fun toggle(node: JSONObject) {
        val key = node.optString("key")
        if (key in picked && multiple) picked = picked - key
        else if (node.optBoolean("supported")) picked = if (multiple) picked + key else listOf(key)
      }
      Dialog(onDismissRequest = { nodeDialog = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
          Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("${ModeState.title(editMode)} · ${if (multiple) "серверы для Авто" else "сервер"}", style = MaterialTheme.typography.headlineSmall)
            Text(if (multiple) "Отметь серверы. В «Выбранных» стрелки меняют порядок перебора." else "Выбери один сервер.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(search, { search = it }, label = { Text("Поиск сервера или подписки") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (multiple) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              FilterChip(!onlyPicked, onClick = { onlyPicked = false }, label = { Text("Все") })
              FilterChip(onlyPicked, onClick = { onlyPicked = true }, label = { Text("Выбранные · ${picked.size}") })
              TextButton(onClick = { picked = emptyList() }) { Text("Снять") }
            }
            val ordered = if (onlyPicked) picked.mapNotNull { key -> nodes.find { it.optString("key") == key } } else sortedModeNodes(nodes, status)
            LazyColumn(Modifier.weight(1f)) {
              items(ordered.filter { it.optString("name").contains(search, true) || it.optString("subscription").contains(search, true) }, key = { it.optString("key") }) { node ->
                val key = node.optString("key"); val selected = key in picked
                val index = picked.indexOf(key)
                Row(Modifier.fillMaxWidth().clickable(enabled = node.optBoolean("supported") || (multiple && selected)) { toggle(node) }.padding(vertical = 8.dp)) {
                  if (multiple) Checkbox(selected, enabled = node.optBoolean("supported") || selected, onCheckedChange = { toggle(node) })
                  else RadioButton(selected, enabled = node.optBoolean("supported"), onClick = { toggle(node) })
                  Column(Modifier.weight(1f).padding(top = 8.dp)) {
                    if (multiple && selected) Text("${index + 1} в списке", style = MaterialTheme.typography.labelSmall)
                    ModeNodeLabel(node, status)
                  }
                  if (multiple && onlyPicked) Column {
                    TextButton(enabled = index > 0, onClick = { val list = picked.toMutableList(); java.util.Collections.swap(list, index, index - 1); picked = list }) { Text("↑") }
                    TextButton(enabled = index < picked.lastIndex, onClick = { val list = picked.toMutableList(); java.util.Collections.swap(list, index, index + 1); picked = list }) { Text("↓") }
                  }
                }
                HorizontalDivider()
              }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              OutlinedButton(onClick = { nodeDialog = false }, modifier = Modifier.weight(1f)) { Text("Отмена") }
              Button(onClick = {
                if (multiple) change("node_keys", JSONArray(picked)) else { change("selected_key", picked.firstOrNull().orEmpty()); change("manual_override", true) }
                nodeDialog = false
              }, enabled = multiple || picked.isNotEmpty(), modifier = Modifier.weight(1f)) { Text("Применить") }
            }
          }
        }
      }
    }
  }
}
