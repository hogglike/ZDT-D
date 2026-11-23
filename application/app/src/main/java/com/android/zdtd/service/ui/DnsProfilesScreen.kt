package com.android.zdtd.service.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.android.zdtd.service.ZdtdActions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val DnsConfigPath = "/api/programs/dnsprofiles/config"
private const val DnsValidatePath = "/api/programs/dnsprofiles/validate"
private const val DnsStatusPath = "/api/programs/dnsprofiles/status"
private const val AutomaticDnsEndpoint = "system://android"
private const val GeminiFilterPath = "/api/programs/dnsprofiles/gemini-filter"
private val GeminiPackages = setOf(
  "com.google.android.apps.bard", "com.google.android.googlequicksearchbox",
  "com.google.android.gms", "com.android.vending",
)

@Composable
fun DnsProfilesScreen(
  actions: ZdtdActions,
  topContentPadding: Dp = 0.dp,
  bottomContentPadding: Dp = 0.dp,
  applying: Boolean = false,
) {
  val context = LocalContext.current
  var document by remember { mutableStateOf<JSONObject?>(null) }
  var status by remember { mutableStateOf<JSONObject?>(null) }
  var filterStatus by remember { mutableStateOf<JSONObject?>(null) }
  var refreshRequested by remember { mutableStateOf(false) }
  var filterMessage by remember { mutableStateOf<String?>(null) }
  var loading by remember { mutableStateOf(true) }
  var saving by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }
  var error by remember { mutableStateOf<String?>(null) }
  var editId by rememberSaveable { mutableStateOf<String?>(null) }
  var createGemini by rememberSaveable { mutableStateOf(false) }
  var deleteId by remember { mutableStateOf<String?>(null) }
  var checkId by rememberSaveable { mutableStateOf<String?>(null) }
  var validation by remember { mutableStateOf<JSONObject?>(null) }
  var showValidation by remember { mutableStateOf(false) }
  val reports = remember { mutableStateMapOf<String, JSONObject>() }
  val appLabels by produceState<Map<String, String>>(emptyMap(), context) {
    value = withContext(Dispatchers.IO) {
      runCatching { loadInstalledAppsCached(context.packageManager).associate { it.packageName to it.label } }
        .getOrDefault(emptyMap())
    }
  }

  fun refreshStatus() = actions.loadJsonData(DnsStatusPath) { status = it }
  fun reload() {
    loading = true
    actions.loadJsonData(DnsConfigPath) { result ->
      loading = false
      if (result?.optInt("schema_version") == 1 && result.optJSONObject("profiles") != null) {
        document = result
        error = null
      } else {
        error = "Профили недоступны. Проверь, запущен ли модуль, и обнови экран."
      }
    }
    refreshStatus()
  }
  LaunchedEffect(Unit) { reload() }

  fun submit(candidate: JSONObject, apply: Boolean = true, applyNow: Boolean = false) {
    saving = true
    error = null
    validation = null
    actions.postJsonResult(DnsValidatePath, candidate) { result ->
      if (result?.optBoolean("valid") != true) {
        error = result?.optString("error")?.takeIf { it.isNotBlank() } ?: "Служба не ответила на проверку."
        saving = false
      } else if (!apply) {
        validation = result.optJSONObject("data")
        saving = false
      } else {
        actions.saveJsonData(DnsConfigPath, candidate) { ok ->
          saving = false
          if (ok) {
            editId = null
            deleteId = null
            reports.clear()
            val automatic = context.getSharedPreferences("network_apply", android.content.Context.MODE_PRIVATE)
              .getBoolean("dns", false)
            val oldProfiles = document?.optJSONObject("profiles")
            val newProfiles = candidate.optJSONObject("profiles")
            val removedFilter = oldProfiles?.keys()?.asSequence()?.any { id ->
              val old = oldProfiles?.optJSONObject(id)
              val updated = newProfiles?.optJSONObject(id)
              old?.optBoolean("google_filter") == true && old.optBoolean("enabled") &&
                (updated?.optBoolean("google_filter") != true || !updated.optBoolean("enabled"))
            } == true
            message = if (applyNow) "Сохранено. Применяем настройки профиля…"
              else if (automatic) "Настройки сохранены. Если модуль выключен, они применятся при запуске."
              else "Настройки сохранены. Нажми «Применить»."
            reload()
            if (automatic || applyNow || removedFilter) actions.restartService(onlyIfRunning = !applyNow) { started ->
              refreshStatus()
              if (applyNow) message = if (started) "Модуль запущен. Проверь состояние профиля."
                else "Настройки сохранены. Проверь статус модуля и DNS-профиля."
            }
          } else {
            error = "Не удалось сохранить настройки. Обнови список и повтори."
          }
        }
      }
    }
  }

  val current = document
  val profiles = current?.optJSONObject("profiles")
  val ids = profiles?.keys()?.asSequence()?.toList().orEmpty().sortedWith(
    compareByDescending<String> { profiles?.optJSONObject(it)?.optBoolean("enabled") == true }
      .thenBy { profiles?.optJSONObject(it)?.optString("display_name").orEmpty().lowercase() }
  )
  val filterEnabled = ids.any { id ->
    val candidate = profiles?.optJSONObject(id)
    candidate?.optBoolean("google_filter") == true && candidate.optBoolean("enabled")
  }
  val assigned = ids.flatMap { id -> profiles?.optJSONObject(id)?.optJSONArray("apps")?.strings().orEmpty() }.toSet()
  val unassignedGeminiApps = GeminiPackages.intersect(appLabels.keys) - assigned
  LaunchedEffect(filterEnabled) {
    while (filterEnabled) {
      actions.loadJsonData(GeminiFilterPath) { filterStatus = it }
      delay(2_500)
    }
  }
  LaunchedEffect(applying) { if (!applying) refreshStatus() }
  val suspended = current?.optBoolean("suspend_for_tethering") == true
  val runtime = status?.optJSONObject("profiles")
  val enabled = ids.count { profiles?.optJSONObject(it)?.optBoolean("enabled") == true }
  val active = ids.count { id ->
    val state = runtime?.optJSONObject(id)
    profiles?.optJSONObject(id)?.optBoolean("enabled") == true &&
      state?.optBoolean("netd_applied") == true && state.optBoolean("process_running")
  }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topContentPadding + 4.dp, bottom = bottomContentPadding + 20.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(if (suspended) "Приостановлено для раздачи" else "Активные профили",
            style = MaterialTheme.typography.labelLarge)
          Text(if (status == null) "— / $enabled" else "$active / $enabled",
            style = MaterialTheme.typography.headlineMedium)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { createGemini = false; editId = ""; error = null; validation = null }, enabled = current != null && !saving && !loading && !applying && ids.size < 16) {
              Text("Новый профиль")
            }
            TextButton(onClick = { reload() }, enabled = !saving && !loading) { Text("Обновить") }
          }
          if (ids.none { profiles?.optJSONObject(it)?.optBoolean("google_filter") == true } && unassignedGeminiApps.isNotEmpty()) {
            FilledTonalButton(onClick = {
              createGemini = true
              editId = ""
              error = null
              validation = null
            }, enabled = !saving && !loading && !applying && ids.size < 16) { Text("Добавить Gemini из списка") }
          }
        }
      }
    }
    if (loading || saving) item { LinearProgressIndicator(Modifier.fillMaxWidth()) }
    if (message != null) item {
      Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.medium) {
        Text(message.orEmpty(), Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodySmall)
      }
    }
    if (error != null && editId == null && deleteId == null) item {
      Text(error.orEmpty(), color = MaterialTheme.colorScheme.error)
    }
    if (status?.optBoolean("global_dnscrypt_enabled") == true) item {
      Text("Глобальный DNSCrypt мешает запуску профилей.", color = MaterialTheme.colorScheme.error)
    }
    if (status?.optString("private_dns_mode") == "hostname") item {
      Text("Для применения профилей отключи строгий режим «Частный DNS» в Android.", color = MaterialTheme.colorScheme.error)
    }
    if (filterEnabled) item {
      val updated = filterStatus?.optLong("updated_at") ?: 0L
      val refreshing = refreshRequested || filterStatus?.optBoolean("updating") == true
      val lastError = filterStatus?.optString("last_error").orEmpty().takeUnless { it == "null" }.orEmpty()
      OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("Список Gemini", style = MaterialTheme.typography.titleSmall)
          Text("smart-no-crutch.adguard.txt · обновление раз в час", style = MaterialTheme.typography.bodySmall)
          Text(
            if (updated > 0) "Обновлено " + java.text.DateFormat.getDateTimeInstance(
              java.text.DateFormat.SHORT, java.text.DateFormat.SHORT
            ).format(java.util.Date(updated * 1000))
            else "Ожидаем первую загрузку списка",
            style = MaterialTheme.typography.bodySmall,
          )
          if (lastError.isNotBlank()) Text(lastError, color = MaterialTheme.colorScheme.error)
          filterMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
          if (refreshing) LinearProgressIndicator(Modifier.fillMaxWidth())
          TextButton(enabled = !refreshing, onClick = {
            refreshRequested = true
            filterMessage = null
            actions.postJsonResult(GeminiFilterPath, JSONObject()) {
              filterMessage = if (it?.optBoolean("accepted") == true) "Запрошено обновление" else "Обновление не запустилось"
              actions.loadJsonData(GeminiFilterPath) { result ->
                filterStatus = result
                refreshRequested = false
              }
            }
          }) { Text("Обновить список Gemini") }
        }
      }
    }
    items(ids, key = { it }) { id ->
      val profile = profiles?.optJSONObject(id) ?: JSONObject()
      DnsProfileCard(
        profile, runtime?.optJSONObject(id), reports[id], appLabels, suspended, !saving && !loading && !applying,
        onSwitch = { checked ->
          val candidate = JSONObject(current.toString())
          candidate.getJSONObject("profiles").getJSONObject(id).put("enabled", checked)
          submit(candidate, applyNow = checked && profile.optBoolean("google_filter"))
        },
        onEdit = { createGemini = false; editId = id; error = null; validation = null; showValidation = false },
        onDelete = { deleteId = id; error = null },
        onCheck = { checkId = id },
      )

    }
    if (ids.isEmpty() && !loading && current != null) item {
      Text("Создай профиль со списком Gemini либо настрой DoH или DoT для приложения.", style = MaterialTheme.typography.bodyMedium)
    }
    if (suspended && current != null) item {
      OutlinedButton(enabled = !saving && !loading && !applying, onClick = {
        submit(JSONObject(current.toString()).put("suspend_for_tethering", false), applyNow = true)
      }) { Text("Возобновить DNS-профили") }
    }
  }

  val editing = editId
  if (editing != null && current != null) DnsProfileEditor(
    originalId = editing, original = profiles?.optJSONObject(editing), appLabels = appLabels,
    geminiPreset = createGemini,
    presetApps = unassignedGeminiApps,
    busy = saving, error = error, diagnostics = validation,
    showDiagnostics = showValidation, onToggleDiagnostics = { showValidation = !showValidation },
    onDismiss = { if (!saving) { editId = null; error = null; validation = null } },
    onSubmit = { requestedId, profile, apply ->
      val id = if (requestedId.isNotBlank()) requestedId else generateSequence(1) { it + 1 }
        .map { "dns-$it" }.first { profiles?.has(it) != true }
      if (editing.isEmpty() && profiles?.has(id) == true) {
        error = "ID уже используется."
      } else {
        val candidate = JSONObject(current.toString())
        candidate.getJSONObject("profiles").put(id, profile)
        submit(candidate, apply, applyNow = profile.optBoolean("google_filter") && profile.optBoolean("enabled"))
      }
    },
  )

  val deleting = deleteId
  if (deleting != null && current != null) AlertDialog(
    onDismissRequest = { if (!saving) deleteId = null },
    title = { Text("Удалить ${profiles?.optJSONObject(deleting)?.optString("display_name") ?: deleting}?") },
    text = { Text(error ?: "У профиля будут удалены настройки и назначения приложений.") },
    confirmButton = {
      TextButton(enabled = !saving, onClick = {
        val candidate = JSONObject(current.toString())
        candidate.getJSONObject("profiles").remove(deleting)
        submit(candidate)
      }) { Text("Удалить") }
    },
    dismissButton = { TextButton(enabled = !saving, onClick = { deleteId = null }) { Text("Отмена") } },
  )

  val checking = checkId
  val checkProfile = checking?.let { profiles?.optJSONObject(it) }
  if (checking != null && checkProfile != null) DnsProfileCheckDialog(
    actions = actions, id = checking, name = checkProfile.optString("display_name", checking),
    appLabels = appLabels, previous = reports[checking],
    onResult = { reports[checking] = it; refreshStatus() }, onDismiss = { checkId = null },
    defaultDomain = if (checkProfile.optBoolean("google_filter")) "gemini.google.com" else "example.com",
  )
}

@Composable
private fun DnsProfileCard(
  profile: JSONObject, runtime: JSONObject?, report: JSONObject?, appLabels: Map<String, String>,
  suspended: Boolean, editable: Boolean, onSwitch: (Boolean) -> Unit, onEdit: () -> Unit,
  onDelete: () -> Unit, onCheck: () -> Unit,
) {
  var menu by remember { mutableStateOf(false) }
  val enabled = profile.optBoolean("enabled")
  val active = runtime?.optBoolean("process_running") == true && runtime.optBoolean("netd_applied")
  val status = when {
    suspended -> "Приостановлен"
    !enabled -> "Выключен"
    active -> "Применён"
    runtime == null -> "Статус недоступен"
    else -> "Не запущен"
  }
  val endpoint = profile.optString("endpoint")
  val filter = profile.optBoolean("google_filter")
  val protocol = when {
    filter -> "Обновляемый список Gemini"
    endpoint.startsWith("tls://", true) -> "DoT"
    else -> "DoH"
  }
  val host = runCatching { java.net.URI(endpoint).host }.getOrNull().orEmpty()
  val apps = profile.optJSONArray("apps")?.strings().orEmpty().map { appLabels[it] ?: it }.sorted()
  OutlinedCard(Modifier.fillMaxWidth()) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
          Text(profile.optString("display_name"), style = MaterialTheme.typography.titleLarge)
          Text(if (filter) protocol else "$protocol · $host", maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(enabled, onSwitch, enabled = editable && (apps.isNotEmpty() || enabled))
        Box {
          IconButton(onClick = { menu = true }, enabled = editable) { Icon(Icons.Default.MoreVert, "Действия профиля") }
          DropdownMenu(menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("Редактировать") }, onClick = { menu = false; onEdit() })
            DropdownMenuItem(text = { Text("Удалить") }, onClick = { menu = false; onDelete() })
          }
        }
      }
      Text(status, style = MaterialTheme.typography.labelLarge, color = when {
        active && enabled && !suspended -> MaterialTheme.colorScheme.primary
        enabled && !suspended && runtime != null -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
      })
      HorizontalDivider(Modifier.padding(top = 2.dp, bottom = 4.dp))
      Text(if (apps.isEmpty()) "Приложения не выбраны" else "Приложения · ${apps.size}",
        style = MaterialTheme.typography.labelMedium)
      if (apps.isNotEmpty()) Text(
        apps.take(4).joinToString(", ") + if (apps.size > 4) " · ещё ${apps.size - 4}" else "",
        style = MaterialTheme.typography.bodyMedium,
      )
      if (filter) {
        report?.optJSONObject("gemini_filter")?.takeIf { it.optBoolean("matches_domain") }?.let {
          val fromList = it.optBoolean("redirect_matches")
          Text(if (fromList) "DNS вернул адрес из списка" else "Локальный ответ по списку не подтверждён",
            style = MaterialTheme.typography.bodySmall,
            color = if (fromList) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        }
      } else report?.optJSONObject("upstream")?.let {
        Text("Последний тест сервера · ${dnsStageText(it)}",
          color = if (it.optString("state") == "ok") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
          style = MaterialTheme.typography.bodySmall)
      }
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilledTonalButton(onClick = onCheck, enabled = editable) { Text(if (report == null) "Проверить" else "Диагностика") }
        TextButton(onClick = onEdit, enabled = editable) { Text("Изменить") }
      }
    }
  }
}

@Composable
private fun DnsProfileEditor(
  originalId: String, original: JSONObject?, appLabels: Map<String, String>,
  geminiPreset: Boolean, presetApps: Set<String>,
  busy: Boolean, error: String?, diagnostics: JSONObject?,
  showDiagnostics: Boolean, onToggleDiagnostics: () -> Unit,
  onDismiss: () -> Unit, onSubmit: (String, JSONObject, Boolean) -> Unit,
) {
  var id by remember(originalId) { mutableStateOf(originalId) }
  var name by remember(originalId, geminiPreset) { mutableStateOf(original?.optString("display_name") ?: if (geminiPreset) "Gemini" else "") }
  var endpoint by remember(originalId) { mutableStateOf(original?.optString("endpoint").orEmpty().takeUnless { it == AutomaticDnsEndpoint }.orEmpty()) }
  var dot by remember(originalId) { mutableStateOf(endpoint.startsWith("tls://", true)) }
  var dohAddress by remember(originalId) { mutableStateOf(if (dot) "" else endpoint) }
  var dotAddress by remember(originalId) { mutableStateOf(if (dot) endpoint else "") }
  var bootstrap by remember(originalId) { mutableStateOf(original?.optJSONArray("bootstrap")?.strings()?.joinToString(", ") ?: "1.1.1.1") }
  var timeout by remember(originalId) { mutableStateOf(original?.optInt("timeout_ms")?.toString() ?: "7000") }
  var selected by remember(originalId, geminiPreset) {
    mutableStateOf(original?.optJSONArray("apps")?.strings()?.toSet() ?: if (geminiPreset) presetApps else emptySet())
  }
  var enabled by remember(originalId, geminiPreset) { mutableStateOf(original?.optBoolean("enabled") ?: geminiPreset) }
  var googleFilter by remember(originalId, geminiPreset) { mutableStateOf(original?.optBoolean("google_filter") ?: geminiPreset) }
  val unrelatedApps = googleFilter && selected.any { it !in GeminiPackages }
  var showApps by remember { mutableStateOf(false) }
  var advanced by remember { mutableStateOf(false) }
  val address = endpoint.trim()
  val validProtocol = if (dot) !address.contains("://") || address.startsWith("tls://", true)
    else address.startsWith("https://", true)

  val canSubmit = !busy && !unrelatedApps && name.isNotBlank() &&
    (!enabled || selected.isNotEmpty()) && (googleFilter || validProtocol && address.isNotBlank())

  fun payload(): JSONObject {
    val url = when {
      googleFilter && (address.isBlank() || !validProtocol) -> AutomaticDnsEndpoint
      dot && !address.contains("://") -> "tls://$address"
      else -> address
    }
    return JSONObject().put("display_name", name.trim()).put("endpoint", url)
      .put("bootstrap", JSONArray(bootstrap.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }))
      .put("timeout_ms", timeout.toIntOrNull() ?: 0).put("apps", JSONArray(selected.sorted())).put("enabled", enabled)
      .put("google_filter", googleFilter)
  }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text(if (originalId.isEmpty()) "Новый профиль" else "Редактировать профиль") },
    text = {
      Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(name, { name = it }, label = { Text("Название") }, enabled = !busy,
          singleLine = true, modifier = Modifier.fillMaxWidth())
        ListItem(
          headlineContent = { Text("Использовать список Gemini") },
          supportingContent = { Text("Адреса берутся из обновляемого smart-no-crutch.adguard.txt. Для других доменов используется обычный DNS сети.") },
          trailingContent = { Switch(checked = googleFilter, enabled = !busy, onCheckedChange = { googleFilter = it }) },
        )
        if (!googleFilter) {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = !dot, onClick = {
              if (dot) { dot = false; endpoint = dohAddress }
            }, label = { Text("DoH") })
            FilterChip(selected = dot, onClick = {
              if (!dot) { dot = true; endpoint = dotAddress }
            }, label = { Text("DoT") })
          }
          OutlinedTextField(
            endpoint, {
              endpoint = it
              if (dot) dotAddress = it else dohAddress = it
            },
            label = { Text(if (dot) "Адрес DNS over TLS" else "Адрес DNS over HTTPS") },
            placeholder = { Text(if (dot) "dns.google" else "https://dns.google/dns-query") },
            enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth(),
          )
        }
        OutlinedButton(onClick = { showApps = true }, enabled = !busy,
          modifier = Modifier.fillMaxWidth()) { Text("Приложения · ${selected.size}") }
        if (selected.isNotEmpty()) {
          Text(selected.map { appLabels[it] ?: it }.sorted().joinToString(", "),
            style = MaterialTheme.typography.bodySmall)
        }
        ListItem(
          headlineContent = { Text("Включить профиль") },
          trailingContent = {
            Switch(checked = enabled, onCheckedChange = { enabled = it },
              enabled = !busy && (selected.isNotEmpty() || enabled))
          },
        )
        if (unrelatedApps) {
          Text("В профиле есть приложения вне списка Google. Убери их для подключения подписки.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
          TextButton(enabled = !busy, onClick = {
            selected = selected.intersect(GeminiPackages)
            if (selected.isEmpty()) enabled = false
          }) { Text("Оставить выбранные приложения Google") }
        }
        TextButton(onClick = { advanced = !advanced }) {
          Text(if (advanced) "Скрыть дополнительные настройки" else "Дополнительные настройки")
        }
        if (advanced) {
          if (originalId.isEmpty()) OutlinedTextField(id, { id = it },
            label = { Text("ID профиля (необязательно)") }, enabled = !busy, singleLine = true)
          OutlinedTextField(bootstrap, { bootstrap = it },
            label = { Text("Bootstrap DNS") },
            supportingText = { Text(if (googleFilter) "Запасной обычный DNS, если Android не сообщил DNS сети." else "Первый IPv4 используется для поиска адреса сервера.") }, enabled = !busy)
          OutlinedTextField(timeout, { timeout = it }, label = { Text("Таймаут, мс") },
            supportingText = { Text("От 250 до 30 000") }, enabled = !busy, singleLine = true)
          TextButton(onClick = { onSubmit(id.trim(), payload(), false) }, enabled = canSubmit) {
            Text("Проверить настройки")
          }
          if (diagnostics != null) {
            Text("Настройки и назначения приложений прошли проверку.",
              color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onToggleDiagnostics) {
              Text(if (showDiagnostics) "Скрыть технические данные" else "Технические данные")
            }
            if (showDiagnostics) Text(diagnostics.toString(2), style = MaterialTheme.typography.bodySmall)
          }
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
      }
    },
    confirmButton = {
      TextButton(enabled = canSubmit, onClick = { onSubmit(id.trim(), payload(), true) }) {
        Text(if (googleFilter && enabled) "Сохранить и применить" else "Сохранить")
      }
    },
    dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Отмена") } },
  )
  if (showApps) DnsProfileAppPicker(
    selected, allowed = GeminiPackages.takeIf { googleFilter },
    onDismiss = { showApps = false },
    onSave = { selected = it; if (it.isEmpty()) enabled = false; showApps = false },
  )
}


private fun JSONArray.strings(): List<String> = (0 until length()).map { getString(it) }

@Composable
private fun DnsProfileAppPicker(initial: Set<String>, allowed: Set<String>?, onDismiss: () -> Unit, onSave: (Set<String>) -> Unit) {
  val context = LocalContext.current
  var apps by remember { mutableStateOf<List<InstalledApp>?>(null) }
  var selected by remember { mutableStateOf(initial) }
  var query by rememberSaveable { mutableStateOf("") }
  var system by rememberSaveable { mutableStateOf(false) }
  var onlySelected by rememberSaveable { mutableStateOf(false) }
  var error by remember { mutableStateOf(false) }
  LaunchedEffect(context) {
    val result = withContext(Dispatchers.IO) { runCatching { loadInstalledAppsCached(context.packageManager) } }
    apps = result.getOrDefault(emptyList()).filter { it.packageName != context.packageName && it.packageName != ZDTD_APP_PACKAGE_NAME }
    error = result.isFailure
  }
  val known = apps.orEmpty()
  val installed = known.mapTo(mutableSetOf()) { it.packageName }
  val visible = (known + (selected - installed).map { InstalledApp(it, it, false) })
    .filter { app ->
      val picked = app.packageName in selected
      (allowed == null || app.packageName in allowed || picked) &&
        (!onlySelected || picked) && (!app.isSystem || system || picked || allowed != null) &&
        (app.label.contains(query.trim(), true) || app.packageName.contains(query.trim(), true))
    }
    .sortedWith(compareByDescending<InstalledApp> { it.packageName in selected }.thenBy { it.sortKey })
  Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
    Surface(Modifier.fillMaxSize()) {
      Column(Modifier.safeDrawingPadding().imePadding().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Приложения профиля", style = MaterialTheme.typography.headlineSmall)
        OutlinedTextField(query, { query = it }, label = { Text("Поиск по названию или пакету") },
          singleLine = true, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(onlySelected, { onlySelected = !onlySelected }, label = { Text("Выбрано · ${selected.size}") })
          FilterChip(system, { system = !system }, label = { Text("Системные") })
        }
        if (apps == null) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (error) Text("Не удалось прочитать список приложений.", color = MaterialTheme.colorScheme.error)
        LazyColumn(Modifier.weight(1f)) {
          items(visible, key = { it.packageName }) { app ->
            val checked = app.packageName in selected
            Row(
              Modifier.fillMaxWidth().toggleable(checked, enabled = checked || allowed == null || app.packageName in allowed, role = Role.Checkbox) {
                selected = if (it) selected + app.packageName else selected - app.packageName
              }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
              Checkbox(checked, onCheckedChange = null)
              Column(Modifier.padding(start = 12.dp)) {
                Text(app.label)
                Text(if (app.packageName in installed) app.packageName else "Приложение не установлено",
                  style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
              }
            }
            HorizontalDivider()
          }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onDismiss, Modifier.weight(1f)) { Text("Отмена") }
          Button(onClick = { onSave(selected) }, enabled = apps != null && !error, modifier = Modifier.weight(1f)) {
            Text("Готово")
          }
        }
      }
    }
  }
}
