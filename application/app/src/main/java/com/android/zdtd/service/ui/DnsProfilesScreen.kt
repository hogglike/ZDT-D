package com.android.zdtd.service.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.android.zdtd.service.ZdtdActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val DnsConfigPath = "/api/programs/dnsprofiles/config"
private const val DnsValidatePath = "/api/programs/dnsprofiles/validate"
private const val DnsStatusPath = "/api/programs/dnsprofiles/status"

/** Per-app DoH profile editor. Runtime changes take effect after a normal ZDT-D restart. */
@Composable
fun DnsProfilesScreen(actions: ZdtdActions, topContentPadding: Dp = 0.dp, bottomContentPadding: Dp = 0.dp) {
  var document by remember { mutableStateOf<JSONObject?>(null) }
  var loading by remember { mutableStateOf(true) }
  var busy by remember { mutableStateOf(false) }
  var error by remember { mutableStateOf<String?>(null) }
  var editId by remember { mutableStateOf<String?>(null) }
  var deleteId by remember { mutableStateOf<String?>(null) }
  var diagnostics by remember { mutableStateOf<String?>(null) }
  var runtimeStatus by remember { mutableStateOf<JSONObject?>(null) }

  fun reload() {
    loading = true
    actions.loadJsonData(DnsConfigPath) { result ->
      loading = false
      if (result?.optInt("schema_version") == 1 && result.optJSONObject("profiles") != null) {
        document = result
        error = null
      } else {
        document = null
        error = "Не удалось прочитать профили. Проверь подключение к демону и обнови список."
      }
    }
    actions.loadJsonData(DnsStatusPath) { result -> runtimeStatus = result }
  }
  LaunchedEffect(Unit) { reload() }

  fun submit(candidate: JSONObject) {
    busy = true
    error = null
    actions.postJsonResult(DnsValidatePath, candidate) { result ->
      if (result?.optBoolean("valid") != true) {
        error = result?.optString("error") ?: "Ошибка проверки профиля."
        busy = false
      } else {
        actions.saveJsonData(DnsConfigPath, candidate) { ok ->
          busy = false
          if (ok) {
            editId = null
            deleteId = null
            diagnostics = null
            reload()
          } else {
            error = "Сохранение отклонено. Если настройки изменены в другом окне, обнови список. Подробности — в журнале."
          }
        }
      }
    }
  }

  val current = document
  val profiles = current?.optJSONObject("profiles")
  val ids = profiles?.keys()?.asSequence()?.toList()?.sorted().orEmpty()
  val enabledCount = ids.count { profiles?.optJSONObject(it)?.optBoolean("enabled") == true }
  val assignedApps = ids.sumOf { profiles?.optJSONObject(it)?.optJSONArray("apps")?.length() ?: 0 }
  val runningCount = ids.count { id ->
    val rs = runtimeStatus?.optJSONObject("profiles")?.optJSONObject(id)
    rs?.optBoolean("netd_applied") == true && rs.optBoolean("process_running")
  }
  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topContentPadding + 12.dp, bottom = bottomContentPadding + 16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
      ) {
        Column(
          Modifier.padding(16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text("DNS-профили", style = MaterialTheme.typography.headlineSmall)
          Text(
            "Отдельный DoH для выбранных приложений. Остальные приложения и клиенты раздачи используют обычную системную сеть.",
            style = MaterialTheme.typography.bodyMedium,
          )
          Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
          ) {
            DnsMetricCard("Профили", ids.size.toString(), Modifier.weight(1f))
            DnsMetricCard("Активно", "${runningCount}/${enabledCount}", Modifier.weight(1f))
            DnsMetricCard("Приложения", assignedApps.toString(), Modifier.weight(1f))
          }
          Text(
            "Изменения применяются после перезапуска ZDT-D. DNS-профили не должны управлять раздачей или системным IPv4 forwarding.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          runtimeStatus?.let { st ->
            if (st.optBoolean("global_dnscrypt_enabled")) {
              Text("⚠ Глобальный DNSCrypt включён — per-app DNS не запустится.", color = MaterialTheme.colorScheme.error)
            }
            val pdns = st.optString("private_dns_mode", "unknown")
            if (pdns == "hostname") {
              Text("⚠ Android Private DNS в строгом режиме — выключи его перед запуском.", color = MaterialTheme.colorScheme.error)
            }
          }
        }
      }
    }
    item {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { editId = ""; error = null }, enabled = current != null && !busy && !loading && ids.size < 16) { Text("Создать") }
        OutlinedButton(onClick = { reload() }, enabled = !busy && !loading) { Text("Обновить") }
      }
      if (loading || busy) LinearProgressIndicator(Modifier.fillMaxWidth())
      error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    }
    items(ids, key = { it }) { id ->
      val profile = profiles?.optJSONObject(id) ?: JSONObject()
      Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
              Text(profile.optString("display_name"), style = MaterialTheme.typography.titleMedium)
              Text("DoH · приложений: ${profile.optJSONArray("apps")?.length() ?: 0}")
            }
            Switch(
              checked = profile.optBoolean("enabled"),
              onCheckedChange = { checked ->
                val candidate = JSONObject(current.toString())
                candidate.getJSONObject("profiles").getJSONObject(id).put("enabled", checked)
                submit(candidate)
              },
              enabled = !busy && !loading && (profile.optJSONArray("apps")?.length() ?: 0) > 0,
            )
          }
          Text(profile.optString("endpoint"))
          runtimeStatus?.optJSONObject("profiles")?.optJSONObject(id)?.let { rs ->
            val applied = rs.optBoolean("netd_applied")
            val running = rs.optBoolean("process_running")
            Text(if (profile.optBoolean("enabled")) {
              if (applied && running) "● Работает · ${rs.optString("tun")} · DNS ${rs.optString("dns")}" else "○ Включён в настройках · нужен перезапуск/проверь журнал"
            } else "Выключен")
          }
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { editId = id; error = null }, enabled = !busy && !loading) { Text("Изменить") }
            TextButton(onClick = { deleteId = id }, enabled = !busy && !loading) { Text("Удалить") }
          }
        }
      }
    }
    if (ids.isEmpty() && !loading && current != null) item { Text("Профилей пока нет. Можно начать с Xbox DNS.") }
  }

  val editing = editId
  if (editing != null && current != null) {
    DnsProfileEditor(
      originalId = editing, original = profiles?.optJSONObject(editing), busy = busy,
      error = error, diagnostics = diagnostics,
      onDismiss = { if (!busy) { editId = null; error = null; diagnostics = null } },
      onSubmit = { id, profile, save ->
        if (editing.isEmpty() && profiles?.has(id) == true) {
          error = "Профиль с таким ID уже существует."
        } else {
          val candidate = JSONObject(current.toString())
          candidate.getJSONObject("profiles").put(id, profile)
          if (save) submit(candidate) else {
            busy = true
            actions.postJsonResult(DnsValidatePath, candidate) { result ->
              busy = false
              error = if (result?.optBoolean("valid") == true) null else result?.optString("error") ?: "Нет ответа от демона."
              diagnostics = result?.optJSONObject("data")?.toString(2)
            }
          }
        }
      },
    )
  }
  val deleting = deleteId
  if (deleting != null && current != null) AlertDialog(
    onDismissRequest = { if (!busy) deleteId = null },
    title = { Text("Удалить профиль $deleting?") },
    text = { Column { Text("Будут удалены настройки и назначения этого профиля. Из работающего runtime он исчезнет после перезапуска ZDT-D."); error?.let { Text(it, color = MaterialTheme.colorScheme.error) } } },
    confirmButton = { TextButton(enabled = !busy, onClick = {
      val candidate = JSONObject(current.toString())
      candidate.getJSONObject("profiles").remove(deleting)
      submit(candidate)
    }) { Text("Удалить") } },
    dismissButton = { TextButton(enabled = !busy, onClick = { deleteId = null }) { Text("Отмена") } },
  )
}

@Composable
private fun DnsProfileEditor(
  originalId: String, original: JSONObject?, busy: Boolean, error: String?, diagnostics: String?,
  onDismiss: () -> Unit, onSubmit: (String, JSONObject, Boolean) -> Unit,
) {
  var id by remember(originalId) { mutableStateOf(originalId.ifEmpty { "xbox" }) }
  var name by remember(originalId) { mutableStateOf(original?.optString("display_name") ?: "Xbox DNS") }
  var endpoint by remember(originalId) { mutableStateOf(original?.optString("endpoint") ?: "https://xbox-dns.ru/dns-query") }
  var bootstrap by remember(originalId) { mutableStateOf(original?.optJSONArray("bootstrap")?.strings()?.joinToString(", ") ?: "1.1.1.1, 9.9.9.9") }
  var timeout by remember(originalId) { mutableStateOf(original?.optInt("timeout_ms")?.toString() ?: "7000") }
  var selected by remember(originalId) { mutableStateOf(original?.optJSONArray("apps")?.strings()?.toSet() ?: emptySet()) }
  var enabled by remember(originalId) { mutableStateOf(original?.optBoolean("enabled") ?: false) }
  var showApps by remember { mutableStateOf(false) }
  fun payload() = JSONObject().put("display_name", name.trim()).put("endpoint", endpoint.trim())
    .put("bootstrap", JSONArray(bootstrap.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }))
    .put("timeout_ms", timeout.toIntOrNull() ?: 0).put("apps", JSONArray(selected.sorted())).put("enabled", enabled)

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (originalId.isEmpty()) "Новый DNS-профиль" else "DNS-профиль") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(id, { id = it }, label = { Text("ID: латиница, цифры, _ и -") }, enabled = !busy && originalId.isEmpty(), singleLine = true)
        OutlinedTextField(name, { name = it }, label = { Text("Название") }, enabled = !busy, singleLine = true)
        OutlinedTextField(endpoint, { endpoint = it }, label = { Text("DoH URL (HTTPS)") }, enabled = !busy, singleLine = true)
        OutlinedTextField(bootstrap, { bootstrap = it }, label = { Text("Bootstrap IPv4 через запятую") }, enabled = !busy)
        OutlinedTextField(timeout, { timeout = it }, label = { Text("Таймаут, мс (250–30000)") }, enabled = !busy, singleLine = true)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Column(Modifier.weight(1f)) { Text("Включить профиль"); Text("Применится после перезапуска ZDT-D", style = MaterialTheme.typography.bodySmall) }
          Switch(checked = enabled, onCheckedChange = { enabled = it }, enabled = !busy && (selected.isNotEmpty() || enabled))
        }
        OutlinedButton(onClick = { showApps = true }, enabled = !busy) { Text("Приложения: ${selected.size}") }
        if (selected.isNotEmpty()) TextButton(onClick = { selected = emptySet(); enabled = false }, enabled = !busy) { Text("Очистить назначения") }
        Text("Один DoH upstream. Обычный трафик выбранных приложений идёт DIRECT. Встроенный DoH самого приложения этот профиль не переопределяет.")
        OutlinedButton(onClick = { onSubmit(id.trim(), payload(), false) }, enabled = !busy) { Text("Проверить настройки и UID") }
        Text("Проверка валидирует настройки/UID и показывает будущие netId/TUN. Реальный DoH проверяется при запуске профиля.")
        diagnostics?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = { TextButton(enabled = !busy, onClick = { onSubmit(id.trim(), payload(), true) }) { Text("Сохранить") } },
    dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Отмена") } },
  )
  if (showApps) DnsProfileAppPicker(selected, onDismiss = { showApps = false }, onSave = { selected = it; showApps = false })
}

private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

@Composable
private fun DnsProfileAppPicker(initial: Set<String>, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
  val context = LocalContext.current
  var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
  var selected by remember { mutableStateOf(initial) }
  var query by remember { mutableStateOf("") }
  var system by remember { mutableStateOf(false) }
  var loading by remember { mutableStateOf(true) }
  var error by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(Unit) {
    val result = withContext(Dispatchers.IO) { runCatching { loadInstalledAppsCached(context.packageManager) } }
    apps = result.getOrDefault(emptyList()).filter { it.packageName != "com.android.zdtd.service" }
    error = result.exceptionOrNull()?.let { "Не удалось загрузить приложения." }
    loading = false
  }
  val filtered = apps.filter { (!it.isSystem || system || it.packageName in selected) &&
    (it.label.contains(query, true) || it.packageName.contains(query, true)) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Выбрать приложения") },
    text = {
      Column {
        OutlinedTextField(query, { query = it }, label = { Text("Поиск") }, singleLine = true)
        Row { Checkbox(system, { system = it }); Text("Системные приложения") }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        LazyColumn(Modifier.heightIn(max = 360.dp)) {
          items(filtered, key = { it.packageName }) { app ->
            fun toggle() { selected = if (app.packageName in selected) selected - app.packageName else selected + app.packageName }
            Row(Modifier.fillMaxWidth().clickable { toggle() }.padding(vertical = 4.dp)) {
              Checkbox(app.packageName in selected, { toggle() })
              Column { Text(app.label); Text(app.packageName, style = MaterialTheme.typography.bodySmall) }
            }
          }
        }
        Text("Выбрано: ${selected.size}. Общие UID проверяются перед сохранением.")
      }
    },
    confirmButton = { TextButton(enabled = !loading && error == null, onClick = { onSave(selected) }) { Text("Готово") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
  )
}
