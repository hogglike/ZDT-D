package com.android.zdtd.service.ui.theme

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

@Composable
internal fun NetworkBackdrop(modifier: Modifier = Modifier): Modifier {
  val colors = MaterialTheme.colorScheme
  return modifier.background(
    Brush.verticalGradient(listOf(colors.primary.copy(alpha = 0.10f), colors.background, colors.background))
  )
}

@Composable
internal fun NetworkPanel(
  modifier: Modifier = Modifier,
  accent: Boolean = false,
  content: @Composable () -> Unit,
) {
  val colors = MaterialTheme.colorScheme
  Surface(
    modifier = modifier,
    shape = MaterialTheme.shapes.large,
    color = Color.Transparent,
    contentColor = colors.onSurface,
    border = BorderStroke(1.dp, colors.primary.copy(alpha = if (accent) 0.25f else 0.14f)),
  ) {
    Box(
      Modifier.background(
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
