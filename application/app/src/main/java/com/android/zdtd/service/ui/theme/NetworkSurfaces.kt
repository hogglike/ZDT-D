package com.android.zdtd.service.ui.theme

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal enum class NetworkAppearance(val key: String, val label: String) {
  RED("red", "Красный и белый"),
  GLASS("glass", "Стекло"),
  GRAPHITE("graphite", "Графит");

  companion object {
    fun fromKey(value: String?) = entries.firstOrNull { it.key == value } ?: RED
  }
}

internal val LocalNetworkAppearance = staticCompositionLocalOf { NetworkAppearance.RED }
internal fun appearancePreferences(context: Context) =
  context.getSharedPreferences("network_appearance", Context.MODE_PRIVATE)

@Composable
internal fun NetworkBackdrop(modifier: Modifier = Modifier): Modifier {
  val colors = MaterialTheme.colorScheme
  val glass = LocalNetworkAppearance.current == NetworkAppearance.GLASS
  val top = colors.primary.copy(alpha = if (glass) 0.18f else 0.07f)
  return modifier.background(colors.background).background(
    Brush.verticalGradient(listOf(top, colors.background, colors.background))
  )
}

@Composable
internal fun NetworkPanel(
  modifier: Modifier = Modifier,
  accent: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  val glass = LocalNetworkAppearance.current == NetworkAppearance.GLASS
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.large,
    color = Color.Transparent,
    contentColor = colors.onSurface,
    border = BorderStroke(1.dp, colors.primary.copy(alpha = if (accent) 0.25f else 0.14f)),
  ) {
    Box(
      if (!glass) Modifier.background(if (accent) colors.primaryContainer else colors.surface)
      else Modifier.background(
        Brush.linearGradient(
          listOf(
            colors.surfaceContainerLowest.copy(alpha = 0.93f),
            if (accent) colors.primaryContainer.copy(alpha = 0.74f)
            else colors.surface.copy(alpha = 0.72f),
          )
        )
      )
    ) { content() }
  }
}
