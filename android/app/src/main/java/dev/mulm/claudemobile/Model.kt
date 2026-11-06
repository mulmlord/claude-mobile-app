package dev.mulm.claudemobile

import org.json.JSONArray
import org.json.JSONObject

private fun JSONObject.s(k: String): String = if (isNull(k)) "" else optString(k, "")

private inline fun <T> JSONArray?.mapObjects(f: (JSONObject) -> T): List<T> {
    if (this == null) return emptyList()
    return (0 until length()).mapNotNull { optJSONObject(it)?.let(f) }
}

data class QOption(val label: String, val description: String)

data class Question(val question: String, val header: String, val multiSelect: Boolean, val options: List<QOption>) {
    companion object {
        fun list(a: JSONArray?): List<Question> = a.mapObjects { o ->
            Question(o.s("question"), o.s("header"), o.optBoolean("multiSelect"),
                o.optJSONArray("options").mapObjects { QOption(it.s("label"), it.s("description")) })
        }
    }
}

data class SessionInfo(
    val id: String,
    val title: String,
    val project: String,
    val cwd: String,
    val status: String,          // starting | working | idle | permission | question | ended
    val detail: String,
    val statusSince: Double,
    val updated: Double,
    val started: Double,
    val lastText: String,
    val connected: Boolean,
    val questions: List<Question>,
    /** Tokens in the last request, and the model's context window. */
    val context: Long = 0,
    val contextMax: Long = 200_000,
    /** owner/name when the session's folder is a GitHub clone. */
    val repo: String? = null,
) {
    val needsYou: Boolean get() = status == "permission" || status == "question"

    companion object {
        fun from(o: JSONObject) = SessionInfo(
            id = o.s("id"), title = o.s("title"), project = o.s("project"), cwd = o.s("cwd"),
            status = o.s("status"), detail = o.s("detail"),
            statusSince = o.optDouble("status_since", 0.0), updated = o.optDouble("updated", 0.0),
            started = o.optDouble("started", 0.0), lastText = o.s("last_text"),
            connected = o.optBoolean("connected", true),
            questions = Question.list(o.optJSONArray("questions")),
            context = o.optLong("context"), contextMax = o.optLong("context_max", 200_000).coerceAtLeast(1),
            repo = if (o.isNull("repo")) null else o.optString("repo").ifBlank { null },
        )
    }
}

data class Item(
    val id: String,
    val seq: Long,
    val kind: String,            // user | assistant | tool | system
    val text: String,
    val name: String,
    val summary: String,
    val detail: String,
    val result: String,
    val status: String,          // tool: running | done | error
    val questions: List<Question>,
    /** File edits: "+line", "-line", " context", "@" (hunk break, optionally "@N more lines"). */
    val diff: List<String> = emptyList(),
    val added: Int = 0,
    val removed: Int = 0,
) {
    companion object {
        fun from(o: JSONObject) = Item(
            id = o.s("id"), seq = o.optLong("seq"), kind = o.s("kind"), text = o.s("text"),
            name = o.s("name"), summary = o.s("summary"), detail = o.s("detail"),
            result = o.s("result"), status = o.s("status"),
            questions = Question.list(o.optJSONArray("questions")),
            diff = o.optJSONArray("diff")?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList(),
            added = o.optInt("add"), removed = o.optInt("del"),
        )
    }
}

data class MenuOption(val index: Int, val key: String?, val label: String, val selected: Boolean)

data class ScreenState(
    val sid: String, val lines: List<String>, val cols: Int, val options: List<MenuOption>,
    /** The open menu's prompt text as shown on screen. */
    val prompt: String = "",
) {
    companion object {
        fun from(o: JSONObject): ScreenState {
            val lines = o.optJSONArray("lines")
            return ScreenState(
                sid = o.s("sid"),
                lines = if (lines == null) emptyList() else (0 until lines.length()).map { lines.optString(it) },
                cols = o.optInt("cols", 80),
                options = o.optJSONArray("options").mapObjects {
                    MenuOption(it.optInt("index"), if (it.isNull("key")) null else it.s("key"), it.s("label"), it.optBoolean("selected"))
                },
                prompt = o.s("prompt"),
            )
        }
    }
}

/** The tool a permission prompt is about: a diff for file edits, otherwise its detail (e.g. the command). */
data class ToolPreview(
    val name: String,
    val summary: String,
    val detail: String,
    /** Same line format as [Item.diff]; null when the tool doesn't edit a file. */
    val diff: List<String>?,
    val added: Int,
    val removed: Int,
) {
    companion object {
        fun from(o: JSONObject?): ToolPreview? = o?.let {
            ToolPreview(
                it.s("name"), it.s("summary"), it.s("detail"),
                it.optJSONArray("diff")?.let { a -> (0 until a.length()).map { i -> a.optString(i) } },
                it.optInt("add"), it.optInt("del"),
            )
        }
    }
}

data class NotifyEvent(
    val id: Long,
    val sid: String,
    val kind: String,
    val title: String,
    /** Complete plain text; the fields below are extras that older bridges don't send. */
    val body: String,
    /** permission: the tool waiting for approval. */
    val tool: ToolPreview? = null,
    /** question: option labels that one tap can answer, and the question they belong to. */
    val options: List<String> = emptyList(),
    val qid: String = "",
    /** permission: the prompt as shown on the laptop's screen, and its fingerprint (sent back with Allow/Deny). */
    val dialog: String = "",
    val dh: String = "",
    /** permission: more than one tool call is waiting, so a single tool preview would be a guess. */
    val waiting: Int = 0,
) {
    companion object {
        fun from(o: JSONObject) = NotifyEvent(
            o.optLong("id"), o.s("sid"), o.s("kind"), o.s("title"), o.s("body"),
            tool = ToolPreview.from(o.optJSONObject("tool")),
            options = o.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }
                ?: emptyList(),
            qid = o.s("qid"),
            dialog = o.s("dialog"), dh = o.s("dh"), waiting = o.optInt("waiting"),
        )
    }
}

/** A past Claude Code session the phone can resume (or open, when it's already live). */
data class PastSession(val sessionId: String, val title: String, val updated: Double, val live: String?, val running: Boolean) {
    companion object {
        fun from(o: JSONObject) = PastSession(
            o.s("session_id"), o.s("title"), o.optDouble("updated", 0.0),
            if (o.isNull("live")) null else o.s("live").ifBlank { null }, o.optBoolean("running"),
        )
    }
}

/** A folder Claude Code has run in, newest first. */
data class Project(val cwd: String, val name: String, val path: String, val updated: Double, val sessions: List<PastSession>) {
    companion object {
        fun list(a: JSONArray?): List<Project> = a.mapObjects { o ->
            Project(o.s("cwd"), o.s("name"), o.s("path"), o.optDouble("updated", 0.0),
                o.optJSONArray("sessions").mapObjects { PastSession.from(it) })
        }
    }
}
