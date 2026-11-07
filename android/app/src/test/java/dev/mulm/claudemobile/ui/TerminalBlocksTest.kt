package dev.mulm.claudemobile.ui

import dev.mulm.claudemobile.ScreenState
import dev.mulm.claudemobile.TermStyle
import org.junit.Assert.assertEquals
import org.junit.Test

/** The terminal view's clean-up of the laptop's screen (ui/Terminal.kt), without drawing anything. */
class TerminalBlocksTest {
    private fun screen(vararg lines: String, runs: List<IntArray> = emptyList(), styles: List<TermStyle> = emptyList()) =
        ScreenState(sid = "s", lines = lines.toList(), cols = 133, options = emptyList(), runs = runs, styles = styles)

    /** Blocks as text: "---" for a rule, "fit:" before the status area. */
    private fun shown(sc: ScreenState, canDraw: (Int) -> Boolean = { true }) = blocks(sc, canDraw).map {
        when (it) {
            is TermBlock.Text -> (if (it.fit) "fit:" else "") + it.text.text
            is TermBlock.Rule -> "---"
        }
    }

    private fun plain(vararg parts: String) = parts.map { it to TermStyle.Plain }

    @Test
    fun boxLinesBecomeRulesAndTheSideWallsGo() {
        assertEquals(
            listOf("---", "> fix the bug\n  and the test", "---"),
            shown(screen("╭──────────────────────╮", "│ > fix the bug        │", "│   and the test       │", "╰──────────────────────╯")),
        )
    }

    @Test
    fun theStatusAreaIsEverythingAfterTheLastRule() {
        val sc = screen(
            "● Done.", "──────────────────", "❯", "──────────────────",
            "    [Opus 5.5] 📁 app | 🌿 master", "  ⏵⏵ auto mode on (shift+tab to cycle)",
        )
        assertEquals(
            listOf("● Done.", "---", "❯", "---", "fit:    [Opus 5.5] 📁 app | 🌿 master\n  ▸▸ auto mode on (shift+tab to cycle)"),
            shown(sc, canDraw = { it != '⏵'.code }),
        )
    }

    @Test
    fun runsOfBlankLinesShrinkToOne() {
        assertEquals(listOf("a\n\nb"), shown(screen("a", "", "", "", "b", "", "")))
    }

    @Test
    fun aLeftWallAloneStays() {
        assertEquals(listOf("│ a"), shown(screen("│ a")))
    }

    @Test
    fun paddingShrinksButIndentationStays() {
        assertEquals(plain("x   y"), squeeze(plain("x          y")))
        assertEquals(plain("      six"), squeeze(plain("      six")))
        // 12+ leading spaces are alignment (a right-aligned status line), not indentation.
        assertEquals(plain("    ", "right"), squeeze(plain("              right")))
        // Leading space spread over several runs counts as one.
        val bold = TermStyle("", "", 1)
        assertEquals(listOf("    " to TermStyle.Plain, "right" to bold), squeeze(listOf("        " to TermStyle.Plain, "      " to bold, "right" to bold)).filter { it.first.isNotEmpty() })
    }

    @Test
    fun runsCountCodePointsNotUtf16Units() {
        val green = TermStyle("green", "", 0)
        assertEquals(listOf("📁" to green, " ok" to TermStyle.Plain), segments("📁 ok", intArrayOf(1, 1, 3, 0), listOf(TermStyle.Plain, green)))
    }

    @Test
    fun missingSymbolsGetStandIns() {
        assertEquals("▸▸ on", drawableText("⏵⏵ on") { false })
        assertEquals("⏵⏵ on", drawableText("⏵⏵ on") { true })
        assertEquals("plain text", drawableText("plain text") { false })
        assertEquals("⏎ x", drawableText("⏎ x") { false })  // no stand-in: left as it is
    }
}
