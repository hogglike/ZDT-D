package com.android.zdtd.service.proxy

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.android.zdtd.service.modes.ModeClient
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.delay
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProxyCatalogActivity : ComponentActivity() {
  companion object {
    const val EXTRA_PICK = "pick_proxy"
    const val EXTRA_URL = "selected_proxy_url"
    const val EXTRA_URLS = "selected_proxy_urls"
  }
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setContent { MaterialTheme { Catalog() } }
  }

  @Composable
  private fun Catalog() {
    val store = remember { ProxyCatalogStore(this) }
    val scope = rememberCoroutineScope()
    val pick = intent.getBooleanExtra(EXTRA_PICK, false)
    var entries by remember { mutableStateOf<List<SavedProxy>>(emptyList()) }
    var message by remember { mutableStateOf("") }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var protocol by remember { mutableStateOf("http") }
    var search by remember { mutableStateOf("") }
    var paste by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    var preview by remember { mutableStateOf<ProxyImport?>(null) }
    var deleting by remember { mutableStateOf<SavedProxy?>(null) }
    var applying by remember { mutableStateOf(false) }
    var applyingApi by remember { mutableStateOf(false) }
    var resetUpstreams by remember { mutableStateOf(true) }
    var statuses by remember { mutableStateOf<List<JSONObject>>(emptyList()) }
    LaunchedEffect(Unit) {
      while (true) {
        runCatching { withContext(Dispatchers.IO) { ModeClient.api(this@ProxyCatalogActivity).getJsonData("/api/programs/operaproxy/upstreams") } }.onSuccess { result ->
          val list = result.optJSONArray("statuses")
          statuses = if (list == null) emptyList() else (0 until list.length()).mapNotNull { list.optJSONObject(it) }
        }
        delay(5000)
      }
    }
    LaunchedEffect(Unit) {
      try { entries = withContext(Dispatchers.IO) { store.read() }; loaded = true }
      catch (_: Exception) { message = "Не удалось прочитать каталог. Существующий список не изменён." }
    }
    fun parse(value: String) {
      scope.launch {
        busy = true
        try { preview = withContext(Dispatchers.Default) { parseProxyList(value, protocol) } }
        catch (e: IllegalArgumentException) { message = e.message ?: "Не удалось разобрать список" }
        finally { busy = false }
      }
    }
    fun save(updated: List<SavedProxy>, success: String) {
      scope.launch {
        busy = true
        try { withContext(Dispatchers.IO) { store.write(updated) }; entries = updated; message = success; preview = null; deleting = null }
        catch (_: Exception) { message = "Не удалось сохранить. Список не изменён." }
        finally { busy = false }
      }
    }
    val openFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
      if (uri != null) scope.launch {
        busy = true
        try {
          val value = withContext(Dispatchers.IO) {
            val bytes = contentResolver.openInputStream(uri)?.use { input ->
              val output = java.io.ByteArrayOutputStream()
              val buffer = ByteArray(8192)
              while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                require(output.size() + count <= MAX_PROXY_TEXT_BYTES) { "Файл больше 1 МБ" }
                output.write(buffer, 0, count)
              }
              output.toByteArray()
            } ?: error("Не удалось открыть файл")
            val decoder = Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString()
          }
          preview = withContext(Dispatchers.Default) { parseProxyList(value, protocol) }
        } catch (e: IllegalArgumentException) { message = e.message ?: "Неверный список" }
        catch (_: Exception) { message = "Не удалось прочитать TXT. Нужен текстовый файл UTF-8." }
        finally { busy = false }
      }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
      Column(Modifier.safeDrawingPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
          Text(if (pick) "Выбрать прокси" else "Прокси", style = MaterialTheme.typography.headlineSmall)
          TextButton(onClick = { finish() }) { Text("Назад") }
        }
        Text("Webshare для обхода геоблока Opera: импортируй TXT адрес:порт:логин:пароль как HTTP и примени к API Opera. SNI — отдельные домены, например m.vk.com.", style = MaterialTheme.typography.bodySmall)
        Text("Протокол строк без http:// или socks5://:", style = MaterialTheme.typography.labelMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          FilterChip(protocol == "http", onClick = { protocol = "http" }, label = { Text("HTTP") }, enabled = !busy)
          FilterChip(protocol == "socks5", onClick = { protocol = "socks5" }, label = { Text("SOCKS5") }, enabled = !busy)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
          OutlinedButton(onClick = { openFile.launch(arrayOf("text/*", "application/octet-stream")) }, enabled = loaded && !busy) { Text("Загрузить TXT") }
          OutlinedButton(onClick = { text = ""; paste = true }, enabled = loaded && !busy) { Text("Вставить список") }
        }
        Text("Сохранено: ${entries.size}. Импорт добавляет серверы, совпадения пропускаются. Пароли скрыты в списке.", style = MaterialTheme.typography.bodySmall)
        if (message.isNotBlank()) Text(message, style = MaterialTheme.typography.bodySmall)
        if (!pick) Button(onClick = { applyingApi = true }, enabled = loaded && !busy && entries.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
          Text("Для API Opera · весь список")
        }
        OutlinedButton(onClick = { startActivity(Intent(this@ProxyCatalogActivity, OperaDiagnosticsActivity::class.java)) }, modifier = Modifier.fillMaxWidth()) { Text("Диагностика Opera и SNI") }
        Button(onClick = {
          if (pick) { setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_URLS, JSONArray(entries.map { it.url }).toString())); finish() }
          else applying = true
        }, enabled = loaded && !busy && entries.isNotEmpty(), modifier = Modifier.fillMaxWidth()) {
          Text(if (pick) "Авто — весь список · ${entries.size}" else "Для всего трафика · Авто")
        }
        statuses.forEach { status ->
          val state = status.optString("state")
          Text("${status.optString("sni")}: ${when(state) { "ready" -> "Соединение установлено · ${status.optString("endpoint")}"; "connecting" -> "Перебор · ${status.optString("endpoint")}"; "error" -> status.optString("error"); "stopped" -> "Opera остановлена или нужен перезапуск"; else -> "Ожидание подключения" }}", color = when(state) { "error" -> MaterialTheme.colorScheme.error; "ready" -> androidx.compose.ui.graphics.Color(0xFF2E7D32); else -> MaterialTheme.colorScheme.onSurface }, style = MaterialTheme.typography.bodySmall)
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        OutlinedTextField(search, { search = it }, label = { Text("Поиск адреса") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        if (loaded && entries.isEmpty()) Text("Список пуст. Загрузи файл или вставь строки.")
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          items(entries.filter { it.endpoint.contains(search, true) }, key = { it.url }) { entry ->
            OutlinedCard(Modifier.fillMaxWidth()) {
              Column(Modifier.padding(12.dp)) {
                Text(entry.endpoint, style = MaterialTheme.typography.titleSmall)
                Text(entry.display, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                val failed = statuses.flatMap { s -> s.optJSONArray("failures")?.let { list -> (0 until list.length()).mapNotNull { list.optJSONObject(it) } } ?: emptyList() }
                  .firstOrNull { it.optString("endpoint") == entry.url.substringBefore("://") + "://" + java.net.URI(entry.url).rawAuthority.substringAfterLast('@') }
                if (failed != null) Text(failed.optString("error"), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                Row {
                  if (pick) TextButton(onClick = {
                    setResult(Activity.RESULT_OK, Intent().putExtra(EXTRA_URL, entry.url)); finish()
                  }, enabled = !busy) { Text("Выбрать") }
                  TextButton(onClick = { deleting = entry }, enabled = !busy) { Text("Удалить") }
                }
              }
            }
          }
        }
        Text("Для API: список проверяется при запуске Opera, пока не пройдут авторизация и получение серверов. Для всего трафика: прокси перебираются при новых соединениях. После изменения настроек перезапусти ZDT-D.", style = MaterialTheme.typography.bodySmall)
      }
    }
    if (paste) AlertDialog(onDismissRequest = { paste = false }, title = { Text("Список прокси") }, text = {
      OutlinedTextField(text, { if (it.length <= MAX_PROXY_TEXT_BYTES) text = it }, modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), label = { Text("По одному серверу на строку") })
    }, confirmButton = { TextButton(onClick = { paste = false; parse(text); text = "" }, enabled = text.isNotBlank()) { Text("Проверить список") } }, dismissButton = { TextButton(onClick = { paste = false; text = "" }) { Text("Отмена") } })
    preview?.let { result ->
      val added = (entries + result.entries).distinctBy { it.url }
      val count = added.size - entries.size
      AlertDialog(onDismissRequest = { if (!busy) preview = null }, title = { Text("Импорт списка") }, text = {
        Column {
          Text("Найдено: ${result.entries.size} · новых: $count · повторов: ${result.duplicates + result.entries.size - count}")
          if (result.invalidLines.isNotEmpty()) Text("Неверные строки: ${result.invalidLines.take(20).joinToString()}${if (result.invalidLines.size > 20) "…" else ""}. Они будут пропущены.")
          if (added.size > MAX_PROXY_ENTRIES) Text("Максимум $MAX_PROXY_ENTRIES серверов. Удали лишние строки.")
        }
      }, confirmButton = { TextButton(onClick = { save(added, "Добавлено серверов: $count") }, enabled = !busy && count > 0 && added.size <= MAX_PROXY_ENTRIES) { Text("Добавить $count") } }, dismissButton = { TextButton(onClick = { preview = null }, enabled = !busy) { Text("Отмена") } })
    }
    if (applying) AlertDialog(onDismissRequest = { if (!busy) applying = false }, title = { Text("Авто для Opera Proxy") }, text = { Text("Весь трафик Opera будет проходить через список из ${entries.size} прокси. Это расходует лимит Webshare на обычные сайты. Для инструкции с 4PDA используй «Для API Opera». После сохранения перезапусти ZDT-D.") }, confirmButton = {
      TextButton(onClick = { scope.launch {
        busy = true
        try {
          withContext(Dispatchers.IO) {
            val api = ModeClient.api(this@ProxyCatalogActivity)
            val list = JSONArray(api.getTextContent("/api/programs/operaproxy/sni"))
            check(list.length() > 0) { "Сначала создай запись SNI в Opera Proxy" }
            for (i in 0 until list.length()) { list.getJSONObject(i).put("proxy_mode", "auto").put("use_byedpi", false).put("custom_proxies", JSONArray(entries.map { it.url })) }
            check(api.putTextContent("/api/programs/operaproxy/sni", list.toString(2))) { "Не удалось сохранить настройки Opera" }
          }
          applying = false; message = "Авто сохранено. Перезапусти ZDT-D для применения списка."
        } catch (_: Exception) { message = "Не удалось применить список. Проверь связь со службой и наличие записей SNI." }
        finally { busy = false }
      } }, enabled = !busy) { Text("Применить Авто") }
    }, dismissButton = { TextButton(onClick = { applying = false }, enabled = !busy) { Text("Отмена") } })
    if (applyingApi) AlertDialog(onDismissRequest = { if (!busy) applyingApi = false }, title = { Text("Прокси только для API Opera") }, text = {
      Column {
        Text("Opera проверит авторизацию и получение серверов через весь список, затем выберет рабочий прокси. Обычные сайты будут идти через серверы Opera. HTTP рекомендуется для TXT Webshare; rotating endpoint можно импортировать как один URL.")
        Row { Checkbox(resetUpstreams, { resetUpstreams = it }); Text("Вернуть записи SNI, использующие этот каталог, к ByeDPI", style = MaterialTheme.typography.bodySmall) }
        Text("Настройки SNI и адресов серверов сохранятся. После применения перезапусти ZDT-D.", style = MaterialTheme.typography.bodySmall)
      }
    }, confirmButton = { TextButton(onClick = { scope.launch {
      busy = true
      try {
        withContext(Dispatchers.IO) {
          val api = ModeClient.api(this@ProxyCatalogActivity)
          val original = api.getJsonData("/api/programs/operaproxy/args")
          check(original.has("api_proxy")) { "Нет связи с модулем" }
          val updated = JSONObject(original.toString()).put("api_proxy", entries.joinToString("\n") { it.url })
          val validation = api.validateOperaInput("args", updated.toString())
          check(validation == null) { validation ?: "Неверные настройки" }
          api.putJsonData("/api/programs/operaproxy/args", updated)
          if (resetUpstreams) {
            try {
              val list = JSONArray(api.getTextContent("/api/programs/operaproxy/sni"))
              val urls = entries.map { it.url }.toSet()
              for (i in 0 until list.length()) {
                val entry = list.getJSONObject(i)
                if (entry.optString("proxy_mode") == "auto" || (entry.optString("proxy_mode") == "custom" && entry.optString("custom_proxy") in urls)) {
                  entry.put("proxy_mode", "byedpi").put("use_byedpi", true)
                }
              }
              check(api.putTextContent("/api/programs/operaproxy/sni", list.toString(2))) { "Не удалось сохранить SNI" }
            } catch (e: Exception) {
              api.putJsonData("/api/programs/operaproxy/args", original)
              throw e
            }
          }
        }
        applyingApi = false; message = "Список для API сохранён. Перезапусти ZDT-D, затем открой диагностику."
      } catch (e: Exception) { message = e.message ?: "Не удалось применить список для API" }
      finally { busy = false }
    } }, enabled = !busy) { Text("Применить к API") } }, dismissButton = { TextButton(onClick = { applyingApi = false }, enabled = !busy) { Text("Отмена") } })
    deleting?.let { entry -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("Удалить прокси?") }, text = { Text("${entry.endpoint}\nНастройки SNI останутся прежними.") }, confirmButton = { TextButton(onClick = { save(entries - entry, "Сервер удалён из каталога") }, enabled = !busy) { Text("Удалить") } }, dismissButton = { TextButton(onClick = { deleting = null }, enabled = !busy) { Text("Отмена") } }) }
  }
}
