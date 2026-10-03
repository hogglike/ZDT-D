package com.android.zdtd.service.modes

/** Only verified runtime state may paint an active button green. */
object ModeState {
  const val WHITE = -1
  const val YELLOW = -256
  const val GREEN = -11355904
  const val RED = -2937041
  fun color(button: String, active: String, state: String): Int = when {
    button != active -> WHITE
    state == "connecting" -> YELLOW
    state == "connected" -> GREEN
    state == "error" -> RED
    else -> WHITE
  }
  fun title(mode: String) = when (mode) { "white" -> "Белый"; "browser" -> "Браузер"; else -> "Обычный" }
}
