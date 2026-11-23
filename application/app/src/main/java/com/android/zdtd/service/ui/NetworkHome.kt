package com.android.zdtd.service.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.android.zdtd.service.UiState
import com.android.zdtd.service.ZdtdActions
import com.android.zdtd.service.api.ApiModels
import com.android.zdtd.service.diagnostics.connection.ConnectionDiagnosticsActivity
import com.android.zdtd.service.modes.ConnectionModesActivity
import kotlinx.coroutines.flow.StateFlow

private enum class NetworkPage(val title: String) {
  HOME("Сеть"),
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
  var profile by rememberSaveable { mutableStateOf("") }
  var zapretEngine by rememberSaveable { mutableStateOf("nfqws2") }
  val snackbar = remember { SnackbarHostState() }

  fun up() {
    page = when (page) {
      NetworkPage.STRATEGY, NetworkPage.TESTER -> NetworkPage.ZAPRET
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
    Column(Modifier.fillMaxSize().padding(padding)) {
      Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (page != NetworkPage.HOME) {
          IconButton(onClick = ::up) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад") }
        }
        Text(page.title, Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.headlineSmall)
      }
      when (page) {
        NetworkPage.HOME -> Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          val running = ApiModels.isServiceOn(ui.status)
          val applying = ui.status?.let { it.startInProgress || it.stopInProgress } == true
          Surface(
            Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.primaryContainer,
          ) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
              Column(Modifier.weight(1f)) {
                Text("Модуль ZDT-D", style = MaterialTheme.typography.labelLarge)
                Text(when {
                  !ui.daemonOnline -> "Нет связи со службой"
                  applying -> "Применяются настройки"
                  running -> "Модуль работает"
                  else -> "Модуль выключен"
                }, style = MaterialTheme.typography.titleLarge)
              }
              Switch(
                checked = running,
                onCheckedChange = { actions.toggleService() },
                enabled = ui.daemonOnline && !ui.busy && !applying,
              )
            }
          }
          NetworkEntry(Icons.Default.Dns, "DNS для приложений", "Профили DoH и DoT") {
            page = NetworkPage.DNS
          }
          NetworkEntry(Icons.Default.PlayCircle, "Zapret для YouTube", "Профили и стратегии") {
            page = NetworkPage.ZAPRET
          }
          OutlinedCard(Modifier.fillMaxWidth()) {
            ListItem(
              leadingContent = { NetworkIcon(Icons.Default.VpnKey) },
              headlineContent = { Text("VPN", style = MaterialTheme.typography.titleMedium) },
              supportingContent = { Text("Режимы, приложения и серверы") },
            )
            Row(Modifier.padding(start = 12.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
              TextButton(onClick = {
                context.startActivity(Intent(context, ConnectionModesActivity::class.java))
              }) { Text("Подключение") }
              TextButton(onClick = { page = NetworkPage.SUBSCRIPTIONS }) { Text("Подписки") }
            }
          }
          NetworkEntry(Icons.Default.NetworkCheck, "Проверка связи", "Интернет, DNS, YouTube и VPN") {
            context.startActivity(Intent(context, ConnectionDiagnosticsActivity::class.java))
          }
          Spacer(Modifier.height(6.dp))
        }
        NetworkPage.DNS -> DnsProfilesScreen(actions)
        NetworkPage.ZAPRET -> Column(Modifier.fillMaxSize()) {
          Text(
            "В профиле выбери YouTube или ReVanced. Старые профили видны на вкладке Zapret 1.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
          )
          Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("nfqws2" to "Zapret2", "nfqws" to "Zapret 1").forEach { (id, label) ->
              val count = ui.programs.firstOrNull { it.id == id }?.profiles?.size ?: 0
              FilterChip(selected = zapretEngine == id, onClick = { zapretEngine = id },
                label = { Text(if (count > 0) "$label · $count" else label) })
            }
          }
          TextButton(onClick = { page = NetworkPage.TESTER }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Проверить стратегию")
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
        NetworkPage.TESTER -> NfqwsTesterScreen(uiStateFlow = uiStateFlow, actions = actions)
        NetworkPage.SUBSCRIPTIONS -> SubscriptionsScreen(actions = actions, snackHost = snackbar)
      }
    }
  }
}

@Composable
private fun NetworkEntry(icon: ImageVector, title: String, detail: String, open: () -> Unit) {
  OutlinedCard(onClick = open, modifier = Modifier.fillMaxWidth()) {
    ListItem(
      leadingContent = { NetworkIcon(icon) },
      headlineContent = { Text(title, style = MaterialTheme.typography.titleMedium) },
      supportingContent = { Text(detail) },
      trailingContent = { Icon(Icons.Default.ChevronRight, null) },
    )
  }
}

@Composable
private fun NetworkIcon(icon: ImageVector) {
  Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.secondaryContainer) {
    Icon(icon, null, Modifier.padding(9.dp).size(22.dp))
  }
}
