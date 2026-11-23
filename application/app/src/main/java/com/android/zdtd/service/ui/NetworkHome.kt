package com.android.zdtd.service.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.UiState
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import com.android.zdtd.service.diagnostics.connection.ConnectionDiagnosticsActivity
import com.android.zdtd.service.diagnostics.connection.ConnectionWidgetProvider
import com.android.zdtd.service.modes.ConnectionModesActivity
import com.android.zdtd.service.ui.theme.NetworkBackdrop
import com.android.zdtd.service.ui.theme.NetworkPanel
import com.android.zdtd.service.ui.theme.NetworkAppearance
import com.android.zdtd.service.ui.theme.appearancePreferences
import kotlinx.coroutines.flow.StateFlow

private enum class NetworkPage(val title: String) {
  HOME("ZDT-D"),
  APPEARANCE("Оформление"),
  JOURNAL("Журналы служб"),
  DNS("DNS для приложений"),
  ZAPRET("Zapret для YouTube"),
  STRATEGY("Стратегия Zapret"),
  TESTER("Проверка стратегий"),
  SUBSCRIPTIONS("Подписка VPN"),
}

@Composable
internal fun NetworkHome(uiStateFlow: StateFlow<UiState>, actions: ZdtdActions) {
  val context = LocalContext.current
  val ui by uiStateFlow.collectAsStateWithLifecycle()
  var page by rememberSaveable { mutableStateOf(NetworkPage.HOME) }
  val appearance = remember(context) { appearancePreferences(context) }
  var layout by remember { mutableStateOf(appearance.getString("layout", "tiles") ?: "tiles") }
  var journalFrom by rememberSaveable { mutableStateOf(NetworkPage.HOME) }
  var profile by rememberSaveable { mutableStateOf("") }
  var zapretEngine by rememberSaveable { mutableStateOf("nfqws2") }
  val snackbar = remember { SnackbarHostState() }

  fun up() {
    page = when (page) {
      NetworkPage.STRATEGY, NetworkPage.TESTER -> NetworkPage.ZAPRET
      NetworkPage.JOURNAL -> journalFrom
      else -> NetworkPage.HOME
    }
  }

  BackHandler(page != NetworkPage.HOME) { up() }
  LaunchedEffect(page) {
    actions.setActiveMainTab(if (page == NetworkPage.HOME) "HOME" else "APPS")
    actions.refreshStatus()
    if (page == NetworkPage.ZAPRET) actions.refreshPrograms()
  }

  Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
    Column(NetworkBackdrop(Modifier.fillMaxSize()).padding(padding)) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (page != NetworkPage.HOME) {
          IconButton(onClick = ::up) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        }
        Text(page.title, Modifier.weight(1f).padding(vertical = 8.dp), style = MaterialTheme.typography.headlineSmall)
        if (page in listOf(NetworkPage.HOME, NetworkPage.ZAPRET, NetworkPage.STRATEGY, NetworkPage.TESTER)) {
          IconButton(onClick = {
            journalFrom = page
            page = NetworkPage.JOURNAL
          }) { Icon(Icons.Default.Description, "Журналы служб") }
        }
      }
      val applySection = when (page) {
        NetworkPage.DNS -> "dns"
        NetworkPage.ZAPRET, NetworkPage.STRATEGY -> "zapret"
        else -> null
      }
      if (applySection != null) NetworkApplyControls(ui, actions, applySection)
      when (page) {
        NetworkPage.HOME -> Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          val running = ApiModels.isServiceOn(ui.status)
          val applying = ui.busy || ui.status?.let { it.startInProgress || it.stopInProgress } == true
          NetworkPanel(Modifier.fillMaxWidth(), accent = true) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Row(verticalAlignment = Alignment.CenterVertically) {
                Text(when {
                  !ui.daemonOnline -> "Служба недоступна"
                  applying -> ui.restartPhase ?: "Применяем настройки…"
                  ui.status?.servicesPartial == true -> "Запущен частично"
                  running -> "Модуль включён"
                  else -> "Модуль выключен"
                }, Modifier.weight(1f), style = MaterialTheme.typography.titleLarge)
                Switch(
                  checked = running, onCheckedChange = { actions.toggleService() },
                  enabled = ui.daemonOnline && !applying,
                )
              }
              if (applying) LinearProgressIndicator(Modifier.fillMaxWidth())
              if (!applying && ui.status?.servicesPartial == true) {
                val issue = ui.status?.startupIssues?.firstOrNull()
                if (issue != null) Text("${issue.service} · ${issue.message}",
                  style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, maxLines = 3)
                TextButton(onClick = {
                  journalFrom = NetworkPage.HOME
                  page = NetworkPage.JOURNAL
                }) { Text("Причины запуска и журналы") }
              }
              TextButton(enabled = ui.daemonOnline && running && !applying, onClick = {
                actions.restartService(onlyIfRunning = true)
              }) {
                Icon(Icons.Default.Refresh, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Перезапустить")
              }
            }
          }
          val pages = listOf(
            NetworkDestination(Icons.Default.Dns, "DNS профили", "Для приложений") { page = NetworkPage.DNS },
            NetworkDestination(Icons.Default.PlayCircle, "Zapret", "YouTube и Music") { page = NetworkPage.ZAPRET },
            NetworkDestination(Icons.Default.VpnKey, "VPN", "Подключение") {
              context.startActivity(Intent(context, ConnectionModesActivity::class.java))
            },
            NetworkDestination(Icons.Default.NetworkCheck, "Проверка связи", "Сайты и сервисы") {
              context.startActivity(Intent(context, ConnectionDiagnosticsActivity::class.java))
            },
          )
          if (layout == "list") pages.forEach { NetworkEntry(it) }
          else pages.chunked(2).forEach { pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
              pair.forEach { item -> NetworkEntry(item, Modifier.weight(1f), tile = true) }
            }
          }
          Row {
            TextButton(onClick = { page = NetworkPage.SUBSCRIPTIONS }) { Text("Подписки VPN") }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { page = NetworkPage.APPEARANCE }) { Text("Оформление") }
          }
        }
        NetworkPage.APPEARANCE -> AppearanceSettings(layout) { value ->
          layout = value
          appearance.edit().putString("layout", value).apply()
        }
        NetworkPage.JOURNAL -> ServiceJournalScreen(
          zapret = journalFrom != NetworkPage.HOME,
          statusIssues = ui.status?.startupIssues.orEmpty(),
        )
        NetworkPage.DNS -> DnsProfilesScreen(actions, applying = ui.busy || ui.status?.let { it.startInProgress || it.stopInProgress } == true)
        NetworkPage.ZAPRET -> Column(Modifier.fillMaxSize()) {
          NetworkPanel(Modifier.fillMaxWidth().padding(horizontal = 16.dp), accent = true) {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("nfqws2" to "Zapret2", "nfqws" to "Zapret 1").forEach { (id, label) ->
                  val count = ui.programs.firstOrNull { it.id == id }?.profiles?.size ?: 0
                  FilterChip(selected = zapretEngine == id, onClick = { zapretEngine = id },
                    label = { Text(if (count > 0) "$label · $count" else label) })
                }
              }
              Button(onClick = { page = NetworkPage.TESTER }, modifier = Modifier.fillMaxWidth()) { Text("Подобрать стратегию") }
            }
          }
          Box(Modifier.weight(1f)) {
            ProgramScreen(
              programs = ui.programs,
              programId = zapretEngine,
              onOpenProfile = { _, name ->
                profile = name
                page = NetworkPage.STRATEGY
              },
              actions = actions,
              snackHost = snackbar,
            )
          }
        }
        NetworkPage.STRATEGY -> ProfileScreen(ui.programs, zapretEngine, profile, actions, snackbar)
        NetworkPage.TESTER -> NfqwsTesterScreen(uiStateFlow = uiStateFlow, actions = actions, initialProgram = zapretEngine)
        NetworkPage.SUBSCRIPTIONS -> SubscriptionsScreen(actions = actions, snackHost = snackbar)
      }
    }
  }
}

private data class NetworkDestination(val icon: ImageVector, val title: String, val detail: String, val open: () -> Unit)

@Composable
private fun NetworkEntry(item: NetworkDestination, modifier: Modifier = Modifier.fillMaxWidth(), tile: Boolean = false) {
  NetworkPanel(modifier) {
    if (tile) {
      Column(Modifier.fillMaxWidth().heightIn(min = 112.dp).clickable(onClick = item.open).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(5.dp)) {
        NetworkIcon(item.icon)
        Text(item.title, style = MaterialTheme.typography.titleSmall)
        Text(item.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    } else ListItem(
      modifier = Modifier.clickable(onClick = item.open),
      colors = ListItemDefaults.colors(containerColor = Color.Transparent),
      leadingContent = { NetworkIcon(item.icon) },
      headlineContent = { Text(item.title) },
      supportingContent = { Text(item.detail) },
      trailingContent = { Icon(Icons.Default.ChevronRight, null) },
    )
  }
}

@Composable
private fun AppearanceSettings(layout: String, changeLayout: (String) -> Unit) {
  val context = LocalContext.current
  val prefs = remember(context) { appearancePreferences(context) }
  var palette by remember { mutableStateOf(NetworkAppearance.fromKey(prefs.getString("style", null))) }
  var display by remember { mutableStateOf(prefs.getString("theme", "preset")) }
  var panels by remember { mutableStateOf(prefs.getString("panels", "preset")) }
  Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp)) {
    NetworkPanel(Modifier.fillMaxWidth(), accent = true) {
      Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        NetworkIcon(Icons.Default.Dns)
        Spacer(Modifier.width(12.dp))
        Column {
          Text("Предпросмотр", style = MaterialTheme.typography.titleMedium)
          Text("Акцент, фон и карточки", style = MaterialTheme.typography.bodySmall)
        }
      }
    }
    Text("Палитра", style = MaterialTheme.typography.titleMedium)
    NetworkAppearance.entries.chunked(2).forEach { row ->
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        row.forEach { style ->
          FilterChip(
            selected = palette == style,
            onClick = {
              palette = style
              prefs.edit().putString("style", style.key).apply()
              ConnectionWidgetProvider.render(context)
            },
            modifier = Modifier.weight(1f),
            leadingIcon = { Box(Modifier.size(14.dp).background(style.swatch, CircleShape)) },
            label = { Text(style.label) },
          )
        }
      }
    }
    AppearanceChoices("Режим", display, listOf("system" to "Система", "light" to "Светлый", "dark" to "Тёмный")) {
      display = it
      prefs.edit().putString("theme", it).apply()
    }
    AppearanceChoices("Карточки", panels, listOf("solid" to "Матовые", "glass" to "Стекло", "sharp" to "Строгие")) {
      panels = it
      prefs.edit().putString("panels", it).apply()
    }
    AppearanceChoices("Главный экран", layout, listOf("tiles" to "Плитки", "list" to "Список"), changeLayout)
    TextButton(onClick = {
      prefs.edit().remove("style").remove("theme").remove("panels").apply()
      palette = NetworkAppearance.RED
      display = "preset"
      panels = "preset"
      changeLayout("tiles")
      ConnectionWidgetProvider.render(context)
    }) { Text("Сбросить оформление") }
  }
}

@Composable
private fun AppearanceChoices(title: String, value: String?, choices: List<Pair<String, String>>, choose: (String) -> Unit) {
  Column {
    Text(title, style = MaterialTheme.typography.titleMedium)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
      choices.forEach { (key, label) -> FilterChip(value == key, { choose(key) }, label = { Text(label) }) }
    }
  }
}

@Composable
private fun NetworkIcon(icon: ImageVector) {
  Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.primaryContainer) {
    Icon(icon, null, Modifier.padding(9.dp).size(22.dp))
  }
}

@Composable
private fun NetworkApplyControls(ui: UiState, actions: ZdtdActions, section: String? = null) {
  val context = LocalContext.current
  val prefs = remember(context) { context.getSharedPreferences("network_apply", android.content.Context.MODE_PRIVATE) }
  var automatic by remember(section) { mutableStateOf(section?.let { prefs.getBoolean(it, false) } ?: false) }
  val working = ui.busy || ui.status?.let { it.startInProgress || it.stopInProgress } == true
  Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
    if (working) {
      LinearProgressIndicator(Modifier.fillMaxWidth())
      Text(ui.restartPhase ?: "Применяем настройки…", style = MaterialTheme.typography.bodySmall)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      OutlinedButton(onClick = { actions.restartService() }, enabled = ui.daemonOnline && !working) {
        Text("Применить")
      }
      if (section != null) FilterChip(automatic, {
        automatic = !automatic
        prefs.edit().putBoolean(section, automatic).apply()
      }, label = { Text("Перезапуск при сохранении") })
    }
  }
}
