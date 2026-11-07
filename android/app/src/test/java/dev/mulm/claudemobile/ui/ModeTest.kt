package dev.mulm.claudemobile.ui

import dev.mulm.claudemobile.ScreenState
import org.junit.Assert.assertEquals
import org.junit.Test

class ModeTest {
    private fun screen(vararg lines: String) = ScreenState(sid = "s", lines = lines.toList(), cols = 100, options = emptyList())

    @Test
    fun theModeIsReadOffTheStatusArea() {
        assertEquals("auto mode on", modeOf(screen("────", "❯", "────", "  [Opus] 📁 app", "  ⏵⏵ auto mode on (shift+tab to cycle)")))
        assertEquals("manual mode on", modeOf(screen("────", "❯", "────", "    [Haiku 5.5] 📁 qa", "  ⏸ manual mode on")))
        assertEquals("accept edits on", modeOf(screen("  ⏵⏵ accept edits on (shift+tab to cycle)")))
        assertEquals("default", modeOf(screen("────", "❯", "────", "  ? for shortcuts")))
    }
}
