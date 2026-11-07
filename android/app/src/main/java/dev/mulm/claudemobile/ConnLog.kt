package dev.mulm.claudemobile

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import java.io.File

/** One line of the connection log; [count] > 1 when the same thing kept happening, last at [lastAt]. */
data class ConnEvent(val at: Long, val text: String, val kind: Kind, val count: Int = 1, val lastAt: Long = at) {
    enum class Kind { Good, Bad, Info }
}

/**
 * The last connects, drops and failures, for Settings → Connection log. Kept on disk so it
 * survives the process being killed (an "App started" line then shows the gap). The same
 * line repeated (a retry streak) collapses into one with a count.
 */
object ConnLog {
    private const val MAX = 300
    private val lock = Any()
    private var file: File? = null
    private val _events = MutableStateFlow<List<ConnEvent>>(emptyList())
    val events: StateFlow<List<ConnEvent>> = _events

    fun init(ctx: Context) {
        synchronized(lock) {
            val f = File(ctx.filesDir, "connlog.jsonl")
            file = f
            _events.value = try {
                if (!f.exists()) emptyList() else f.readLines().mapNotNull { line ->
                    runCatching {
                        val o = JSONObject(line)
                        ConnEvent(o.getLong("at"), o.getString("text"), ConnEvent.Kind.valueOf(o.getString("kind")),
                            o.optInt("count", 1), o.optLong("last", o.getLong("at")))
                    }.getOrNull()
                }
            } catch (e: Exception) {
                emptyList()
            }
        }
    }

    fun good(text: String) = add(text, ConnEvent.Kind.Good)
    fun bad(text: String) = add(text, ConnEvent.Kind.Bad)
    fun info(text: String) = add(text, ConnEvent.Kind.Info)

    private fun add(text: String, kind: ConnEvent.Kind) {
        synchronized(lock) {
            val now = System.currentTimeMillis()
            val list = _events.value
            val last = list.lastOrNull()
            val next = if (last != null && last.text == text && last.kind == kind) {
                list.dropLast(1) + last.copy(count = last.count + 1, lastAt = now)
            } else {
                (list + ConnEvent(now, text, kind)).takeLast(MAX)
            }
            _events.value = next
            save(next)
        }
    }

    fun clear() {
        synchronized(lock) {
            _events.value = emptyList()
            save(emptyList())
        }
    }

    /** Plain text for Copy, oldest first. */
    fun asText(): String = _events.value.joinToString("\n") { e ->
        val t = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(e.at))
        "$t  ${e.text}" + if (e.count > 1) "  (×${e.count}, last ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.ROOT).format(java.util.Date(e.lastAt))})" else ""
    }

    private fun save(list: List<ConnEvent>) {
        val f = file ?: return
        try {
            f.writeText(list.joinToString("") { e ->
                JSONObject().put("at", e.at).put("text", e.text).put("kind", e.kind.name)
                    .put("count", e.count).put("last", e.lastAt).toString() + "\n"
            })
        } catch (e: Exception) {
            // A log that can't be written isn't worth failing over.
        }
    }

    /** "45 s", "12 min", "2 h 5 min". */
    fun duration(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return when {
            s < 60 -> "$s s"
            s < 3600 -> "${s / 60} min"
            else -> "${s / 3600} h ${s % 3600 / 60} min"
        }
    }
}
