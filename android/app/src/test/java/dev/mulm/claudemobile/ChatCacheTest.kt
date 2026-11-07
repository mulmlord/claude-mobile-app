package dev.mulm.claudemobile

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** What ChatCache writes reads back as the same sessions and items (the bridge's JSON format). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])  // like the widget tests: Robolectric's 36 needs JRE internals this JDK closed
class ChatCacheTest {
    @Test
    fun itemsSurviveTheRoundTrip() {
        val q = Question("Which color?", "Color", false, listOf(QOption("Red", "warm"), QOption("Blue", "")))
        val items = listOf(
            Item("u1:0", 1, "user", "hi", "", "", "", "", "", emptyList()),
            Item("a1:0", 2, "assistant", "**done**", "", "", "", "", "", emptyList()),
            Item("a1:1", 3, "tool", "", "Edit", "app.kt", "", "ok", "done", emptyList(),
                diff = listOf("-old", "+new"), added = 1, removed = 1),
            Item("a2:0", 4, "tool", "", "Read", "shot.png", "", "", "done", emptyList(), image = true),
            Item("a3:0", 5, "tool", "", "AskUserQuestion", "Which color?", "", "", "running", listOf(q)),
            Item("a4:0", 6, "thinking", "Checking.", "", "", "", "", "", emptyList()),
        )
        assertEquals(items, items.map { Item.from(it.toJson()) })
    }

    @Test
    fun sessionsSurviveTheRoundTrip() {
        val s = SessionInfo(
            id = "abc", title = "Fix the bug", project = "app", cwd = "/home/u/app", status = "question",
            detail = "Which color?", statusSince = 1.5, updated = 2.5, started = 0.5, lastText = "Which color?",
            connected = true, questions = listOf(Question("Which color?", "Color", true, listOf(QOption("Red", "")))),
            questionId = "toolu_1", context = 1234, contextMax = 200_000, repo = "u/app",
        )
        assertEquals(s, SessionInfo.from(s.toJson()))
        assertEquals(s.copy(repo = null), SessionInfo.from(s.copy(repo = null).toJson()))
    }
}
