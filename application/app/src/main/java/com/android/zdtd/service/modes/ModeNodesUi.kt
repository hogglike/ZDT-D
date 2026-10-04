package com.android.zdtd.service.modes

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import org.json.JSONObject

internal fun modePing(node: JSONObject, status: JSONObject): JSONObject? =
  status.optJSONObject("pings")?.optJSONObject(node.optString("key"))?.takeIf {
    ModeState.measurementCurrent(node.optString("revision"), it.optString("revision"))
  }
internal fun nodeRank(node: JSONObject, status: JSONObject): Int {
  val ping = modePing(node, status)
  return ModeState.nodeRank(node.optBoolean("supported"), ping != null, if (ping == null || ping.isNull("ms")) null else ping.optLong("ms"))
}
internal fun sortedModeNodes(nodes: List<JSONObject>, status: JSONObject): List<JSONObject> =
  nodes.sortedWith(compareBy<JSONObject> { nodeRank(it, status) }.thenBy {
    modePing(it, status)?.let { p -> if (p.isNull("ms")) Long.MAX_VALUE else p.optLong("ms") } ?: Long.MAX_VALUE
  })

@Composable
internal fun ModeNodeLabel(node: JSONObject, status: JSONObject, showError: Boolean = false) {
  val ping = modePing(node, status)
  val rank = nodeRank(node, status)
  val dark = isSystemInDarkTheme()
  val color = when (rank) {
    0 -> Color(if (dark) 0xFF81C784 else 0xFF2E7D32)
    2 -> MaterialTheme.colorScheme.error
    3 -> MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
    else -> MaterialTheme.colorScheme.onSurface
  }
  val delay = when (rank) {
    0 -> "✓ ${ping!!.optLong("ms")} мс · ${java.text.DateFormat.getTimeInstance().format(java.util.Date(ping.optLong("time") * 1000))}"
    2 -> "× Нет ответа" + if (showError) " · ${ping?.optString("error").orEmpty()}" else ""
    3 -> if (!node.optBoolean("enabled", true)) "Подписка выключена" else "Не поддерживается этим ядром"
    else -> "Задержка не проверена"
  }
  Column {
    Text(node.optString("name"), color = color, maxLines = 2, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
    Text("${node.optString("subscription")} · ${node.optString("protocol")}", style = MaterialTheme.typography.bodySmall, color = color)
    Text(delay, style = MaterialTheme.typography.bodySmall, color = color)
  }
}

