package com.android.zdtd.service.modes
import org.junit.Assert.assertEquals
import org.junit.Test
class ModeStateTest {
  @Test fun greenRequiresVerifiedMatchingMode() {
    assertEquals(ModeState.GREEN, ModeState.color("white", "white", "connected"))
    assertEquals(ModeState.WHITE, ModeState.color("normal", "white", "connected"))
    assertEquals(ModeState.RED, ModeState.color("white", "white", "error"))
    assertEquals(ModeState.WHITE, ModeState.color("white", "white", "idle"))
    assertEquals(ModeState.WHITE, ModeState.color("white", "white", "unavailable"))
  }
  @Test fun pendingIsYellowAndOthersStayNeutral() {
    assertEquals(ModeState.YELLOW, ModeState.color("browser", "browser", "connecting"))
    assertEquals(ModeState.WHITE, ModeState.color("normal", "browser", "connecting"))
  }
}
