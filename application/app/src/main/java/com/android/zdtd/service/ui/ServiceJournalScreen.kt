package com.android.zdtd.service.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.android.zdtd.service.BuildConfig
import com.android.zdtd.service.RootConfigManager
import com.android.zdtd.service.api.ApiModels
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private const val ModuleDirectory = "/data/adb/modules/ZDT-D"
private const val MaxLogBytes = 2 * 1024 * 1024
private const val MaxExportBytes = 24 * 1024 * 1024
private const val StatusPath = "api/status.json"
private const val DaemonLog = "log/deamon.log"
private val ConnectionLinks = Regex("(?i)\\b(?:vless|vmess|trojan|ss|hysteria2?|hy2|tuic|wg)://[^\\s\"'<>]+")
private val HttpLinks = Regex("(?i)\\bhttps?://[^\\s\"'<>]+")
private val SecretValues = Regex(
  """(?i)(\b(?:authorization|password|passwd|token|secret|private[_ -]?key|psk|uuid)\b["']?\s*[:=]\s*)(?:(?:bearer|basic)\s+)?(?:"[^"]*"|'[^']*'|[^\s,;}]+)""",
)
private val PemKey = Regex("(?s)-----BEGIN [A-Z ]*PRIVATE KEY-----.*?-----END [A-Z ]*PRIVATE KEY-----")

private fun safeLog(text: String): String =
  SecretValues.replace(
    HttpLinks.replace(ConnectionLinks.replace(PemKey.replace(text, "[private key]"), "[connection link]")) { match ->
      val url = match.value
      val authority = url.substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#').substringAfterLast('@')
      url.substringBefore("://") + "://" + authority
    }
  ) { match -> match.groupValues[1] + "[hidden]" }

private fun safeJson(text: String): String {
  fun clean(value: Any?): Any? = when (value) {
    is org.json.JSONObject -> org.json.JSONObject().apply {
      value.keys().forEach { key ->
        put(key, if (key in setOf("token", "password", "private_key", "authorization")) "[hidden]" else clean(value.opt(key)))
      }
    }
    is org.json.JSONArray -> org.json.JSONArray().apply {
      for (index in 0 until value.length()) put(clean(value.opt(index)))
    }
    is String -> safeLog(value)
    else -> value
  }
  return runCatching { clean(org.json.JSONObject(text)).toString() }
    .getOrDefault("{\"state\":\"unavailable\"}")
}

private class ServiceJournal(context: Context) {
  private val app = context.applicationContext
  private val root = RootConfigManager(app)
  private fun quote(text: String) = "'" + text.replace("'", "'\\''") + "'"

  fun sources(): List<String> {
    val result = root.execRootSh(
      "cd '$ModuleDirectory' || exit 1; find log working_folder -maxdepth 12 -type f " +
        "\\( -name '*.log' -o -name '*.log.[0-9]*' -o -name '*.log.old' \\) -print 2>/dev/null; true"
    )
    check(result.isSuccess) { "Не удалось прочитать журналы с root" }
    return result.out.filter { path ->
      (path.startsWith("log/") || path.startsWith("working_folder/")) &&
        path.split('/').none { it == ".." || it == "." || it.isBlank() || it.startsWith(".probe-") } &&
        path.none { it.isISOControl() }
    }.distinct().sortedWith(compareBy<String> {
      when {
        it == DaemonLog -> 0
        it.startsWith("log/") -> 1
        it.startsWith("working_folder/nfqws") -> 2
        it.startsWith("working_folder/dnsprofiles/") -> 3
        else -> 4
      }
    }.thenBy { it })
  }

  // Reads only existing regular files whose resolved path stays in the module.
  fun read(path: String, maxBytes: Int, plain: Boolean = false): Pair<String, Boolean> {
    val result = root.execRootSh("""
      cd '$ModuleDirectory' || exit 1
      base=${'$'}(pwd -P)
      entry=${'$'}(readlink -f ${quote(path)}) || exit 1
      [ "${'$'}entry" = "${'$'}base"/${quote(path)} ] || exit 1
      [ -f "${'$'}entry" ] || exit 1
      count=${'$'}(stat -c %s "${'$'}entry") || exit 1
      printf '%s\n' "${'$'}count"
      tail -c $maxBytes "${'$'}entry"
    """.trimIndent())
    check(result.isSuccess && result.out.isNotEmpty()) { "Журнал недоступен" }
    val size = result.out.first().trim().toLongOrNull() ?: error("Не удалось определить размер файла")
    val text = result.out.drop(1).joinToString("\n")
    return (if (plain) text else safeLog(text)) to (size > maxBytes)
  }

  fun startupIssues(): List<ApiModels.StartupIssue> = runCatching {
    ApiModels.parseStartupIssues(org.json.JSONObject(read(StatusPath, 64 * 1024, plain = true).first))
  }.getOrDefault(emptyList())

  fun archive(): File {
    val date = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.ROOT).format(Date())
    val directory = File(app.cacheDir, "service-journal").apply { mkdirs() }
    directory.listFiles()?.filter { it.name.startsWith("ZDT-D_") && System.currentTimeMillis() - it.lastModified() > 86_400_000 }
      ?.forEach { it.delete() }
    val output = File(directory, "ZDT-D_${BuildConfig.VERSION_NAME}_$date.zip")
    val logs = sources()
    val status = runCatching { safeJson(read(StatusPath, 64 * 1024, plain = true).first) }.getOrNull()
    val selection = app.getSharedPreferences("zapret_auto", Context.MODE_PRIVATE).getString("report", null)
    val manifest = StringBuilder("ZDT-D ${BuildConfig.VERSION_NAME}\n$date\n")
    manifest.appendLine("Все доступные журналы служб. До 2 МиБ на файл и до 24 МиБ текста на архив.")
    manifest.appendLine("Журналы могут содержать адреса и имена приложений.")
    var remaining = MaxExportBytes
    try {
      ZipOutputStream(output.outputStream().buffered()).use { zip ->
        fun entry(name: String, bytes: ByteArray) {
          zip.putNextEntry(ZipEntry(name))
          zip.write(bytes)
          zip.closeEntry()
        }
        status?.let { entry("status.json", it.toByteArray()) }
        selection?.let { entry("zapret/strategy-test.json", safeJson(it).toByteArray()) }
        for (path in logs) {
          if (remaining <= 0) {
            manifest.appendLine("Лимит архива · $path")
            continue
          }
          val result = runCatching { read(path, minOf(MaxLogBytes, remaining)) }.getOrNull()
          if (result == null) {
            manifest.appendLine("Недоступен · $path")
            continue
          }
          val bytes = result.first.toByteArray()
          if (bytes.size > remaining) {
            manifest.appendLine("Лимит архива · $path")
            continue
          }
          entry(path, bytes)
          remaining -= bytes.size
          manifest.appendLine((if (result.second) "Последняя часть · " else "") + path)
        }
        check(logs.isNotEmpty() || status != null || selection != null) { "Журналы пока недоступны" }
        entry("contents.txt", manifest.toString().toByteArray())
      }
    } catch (e: Exception) {
      output.delete()
      throw e
    }
    return output
  }
}

private fun sourceName(path: String) = when (path) {
  DaemonLog -> "Служба ZDT-D (подробный)"
  "log/zdtd.log" -> "Служба ZDT-D (основной)"
  else -> path.removePrefix("working_folder/").replace("/log/", " / ")
}

@Composable
internal fun ServiceJournalScreen(zapret: Boolean = false, statusIssues: List<ApiModels.StartupIssue> = emptyList()) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  val journal = remember(context) { ServiceJournal(context) }
  var sources by remember { mutableStateOf<List<String>>(emptyList()) }
  var savedIssues by remember { mutableStateOf<List<ApiModels.StartupIssue>>(emptyList()) }
  var selected by rememberSaveable { mutableStateOf<String?>(null) }
  var lines by remember { mutableStateOf(emptyList<String>()) }
  var refresh by remember { mutableIntStateOf(0) }
  var loading by remember { mutableStateOf(false) }
  var exporting by remember { mutableStateOf(false) }
  var menu by remember { mutableStateOf(false) }
  var note by remember { mutableStateOf<String?>(null) }
  var archivePath by rememberSaveable { mutableStateOf<String?>(null) }

  fun share(file: File) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).setType("application/zip")
      .putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    intent.clipData = ClipData.newRawUri("Журналы ZDT-D", uri)
    context.startActivity(Intent.createChooser(intent, "Поделиться журналами"))
  }
  val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri: Uri? ->
    val file = archivePath?.let(::File)
    if (uri != null && file != null) scope.launch {
      val result = withContext(Dispatchers.IO) {
        runCatching {
          checkNotNull(context.contentResolver.openOutputStream(uri)).use { out ->
            file.inputStream().use { it.copyTo(out) }
          }
        }
      }
      note = if (result.isSuccess) "Журналы сохранены" else "Не удалось сохранить ZIP"
    }
  }
  fun export(shareNow: Boolean) {
    if (exporting) return
    exporting = true
    note = null
    scope.launch {
      try {
        val file = withContext(Dispatchers.IO) { journal.archive() }
        archivePath = file.path
        if (shareNow) share(file) else save.launch(file.name)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        note = e.message ?: "Не удалось собрать журналы"
      } finally {
        exporting = false
      }
    }
  }
  LaunchedEffect(refresh) {
    try {
      sources = withContext(Dispatchers.IO) { journal.sources() }
      savedIssues = withContext(Dispatchers.IO) { journal.startupIssues() }
      if (selected?.let { it in sources } != true) selected =
        (if (zapret) sources.firstOrNull { it.startsWith("working_folder/nfqws") } else null) ?: sources.firstOrNull()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      note = e.message
    }
  }
  LaunchedEffect(selected, refresh) {
    val path = selected
    lines = emptyList()
    if (path != null) {
      loading = true
      try {
        val log = withContext(Dispatchers.IO) { journal.read(path, 96 * 1024) }
        lines = log.first.lines().takeLast(1000)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        note = e.message
      } finally {
        loading = false
      }
    }
  }
  Column(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
    val issues = statusIssues.ifEmpty { savedIssues }
    if (issues.isNotEmpty()) {
      Text("Причины частичного запуска · ${issues.size}", style = MaterialTheme.typography.titleSmall)
      Text(safeLog("${issues.first().service} · ${issues.first().message}"),
        color = MaterialTheme.colorScheme.error, maxLines = 3, style = MaterialTheme.typography.bodySmall)
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Button(onClick = { export(true) }, enabled = !exporting) { Text("Поделиться ZIP") }
      OutlinedButton(onClick = { export(false) }, enabled = !exporting) { Text("Сохранить") }
    }
    Text("ZIP включает журналы всех служб и результат автоподбора. В логах могут быть адреса и имена приложений.",
      style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      Box(Modifier.weight(1f)) {
        TextButton(onClick = { menu = true }, enabled = sources.isNotEmpty()) {
          Text(selected?.let(::sourceName) ?: "Выбрать журнал", maxLines = 1)
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, modifier = Modifier.heightIn(max = 340.dp)) {
          sources.forEach { source ->
            DropdownMenuItem(text = { Text(sourceName(source)) }, onClick = { selected = source; menu = false })
          }
        }
      }
      TextButton(onClick = { note = null; refresh++ }, enabled = !loading) { Text("Обновить") }
    }
    if (loading || exporting) LinearProgressIndicator(Modifier.fillMaxWidth())
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (!loading && lines.none(String::isNotBlank)) Text("Этот журнал пока пуст.")
    SelectionContainer(Modifier.weight(1f)) {
      LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 10.dp)) {
        if (issues.size > 1) items(issues) { issue ->
          Text(safeLog("${issue.service} · ${issue.message}"), color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall)
        }
        items(lines) { line ->
          Text(line, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
      }
    }
  }
}
