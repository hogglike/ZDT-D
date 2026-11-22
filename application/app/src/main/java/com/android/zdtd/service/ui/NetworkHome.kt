package com.android.zdtd.service.ui

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
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
      Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        if (page != NetworkPage.HOME) TextButton(onClick = ::up) { Text("Назад") }
        Text(page.title, Modifier.padding(vertical = 10.dp), style = MaterialTheme.typography.headlineSmall)
      }
      when (page) {
        NetworkPage.HOME -> Column(
          Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          val running = ApiModels.isServiceOn(ui.status)
          val applying = ui.status?.let { it.startInProgress || it.stopInProgress } == true
          Card(Modifier.fillMaxWidth()) {
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.SpaceBetween) {
              Column(Modifier.weight(1f)) {
                Text("Модуль", style = MaterialTheme.typography.titleMedium)
                Text(when {
                  !ui.daemonOnline -> "Нет связи со службой"
                  applying -> "Применяются настройки"
                  running -> "Работает"
                  else -> "Выключен"
                }, style = MaterialTheme.typography.bodyMedium)
              }
              Switch(
                checked = running,
                onCheckedChange = { actions.toggleService() },
                enabled = ui.daemonOnline && !ui.busy && !applying,
              )
            }
          }
          NetworkEntry("DNS для приложений", "DoH и DoT для выбранных приложений") {
            page = NetworkPage.DNS
          }
          NetworkEntry("Zapret для YouTube", "Приложения и стратегия Zapret2") {
            page = NetworkPage.ZAPRET
          }
          Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
              Text("VPN", style = MaterialTheme.typography.titleMedium)
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { page = NetworkPage.SUBSCRIPTIONS }) { Text("Подписка") }
                TextButton(onClick = {
                  context.startActivity(Intent(context, ConnectionModesActivity::class.java))
                }) { Text("Подключение") }
              }
            }
          }
          NetworkEntry("Проверка связи", "Интернет, DNS, YouTube и VPN") {
            context.startActivity(Intent(context, ConnectionDiagnosticsActivity::class.java))
          }
        }
        NetworkPage.DNS -> DnsProfilesScreen(actions)
        NetworkPage.ZAPRET -> Column(Modifier.fillMaxSize()) {
          Text(
            "В профиле Zapret2 выбери YouTube или ReVanced.",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.bodyMedium,
          )
          TextButton(onClick = { page = NetworkPage.TESTER }, modifier = Modifier.padding(horizontal = 12.dp)) {
            Text("Проверить стратегию")
          }
          Box(Modifier.weight(1f)) {
            ProgramScreen(
              programs = ui.programs,
              programId = "nfqws2",
              onOpenProfile = { _, name ->
                profile = name
                page = NetworkPage.STRATEGY
              },
              actions = actions,
              snackHost = snackbar,
            )
          }
        }
        NetworkPage.STRATEGY -> ProfileScreen(ui.programs, "nfqws2", profile, actions, snackbar)
        NetworkPage.TESTER -> NfqwsTesterScreen(uiStateFlow = uiStateFlow, actions = actions)
        NetworkPage.SUBSCRIPTIONS -> SubscriptionsScreen(actions = actions, snackHost = snackbar)
      }
    }
  }
}

@Composable
private fun NetworkEntry(title: String, detail: String, open: () -> Unit) {
  Card(onClick = open, modifier = Modifier.fillMaxWidth()) {
    Column(Modifier.padding(16.dp)) {
      Text(title, style = MaterialTheme.typography.titleMedium)
      Text(detail, style = MaterialTheme.typography.bodyMedium)
    }
  }
}
