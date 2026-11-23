package com.android.zdtd.service.ui.theme

import android.content.Context
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

internal enum class NetworkAppearance(val key: String, val label: String, val swatch: Color) {
  RED("red", "Рубин", Color(0xFFC11623)),
  GLASS("glass", "Розовый", Color(0xFFA13267)),
  GRAPHITE("graphite", "Графит", Color(0xFF524A58)),
  OCEAN("ocean", "Океан", Color(0xFF2356A8)),
  SAGE("sage", "Шалфей", Color(0xFF2B6654)),
  AMBER("amber", "Янтарь", Color(0xFF8B551C));

  companion object {
    fun fromKey(value: String?) = entries.firstOrNull { it.key == value } ?: RED
  }
}

internal val LocalNetworkAppearance = staticCompositionLocalOf { NetworkAppearance.RED }
internal val LocalPanelStyle = staticCompositionLocalOf { "preset" }

@Composable
private fun glassPanels(): Boolean = when (LocalPanelStyle.current) {
  "glass" -> true
  "solid", "sharp" -> false
  else -> LocalNetworkAppearance.current == NetworkAppearance.GLASS
}
internal fun appearancePreferences(context: Context) =
  context.getSharedPreferences("network_appearance", Context.MODE_PRIVATE)

@Composable
internal fun NetworkBackdrop(modifier: Modifier = Modifier): Modifier {
  val colors = MaterialTheme.colorScheme
  val glass = glassPanels()
  val top = colors.primary.copy(alpha = if (glass) 0.21f else 0.025f)
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
  val glass = glassPanels()
  Surface(
    modifier = modifier,
    shape = if (LocalPanelStyle.current == "sharp") RoundedCornerShape(8.dp) else MaterialTheme.shapes.large,
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
