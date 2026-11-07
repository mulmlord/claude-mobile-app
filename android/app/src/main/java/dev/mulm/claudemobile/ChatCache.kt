package dev.mulm.claudemobile

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.json.JSONArray
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The last session list and each chat's latest items, kept on the phone: the app shows them
 * at once on start and while the laptop is asleep, off or out of reach (read-only), until the
 * bridge sends the live state. Written a few seconds after each change, off the main thread.
 */
object ChatCache {
    private const val KEEP_ITEMS = 200
    private const val SAVE_AFTER_MS = 2_500L
    private const val KEEP_DAYS = 14
    private lateinit var dir: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val pending = ConcurrentHashMap<String, Job>()

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "chats").apply { mkdirs() }
        scope.launch {
            val cutoff = System.currentTimeMillis() - KEEP_DAYS * 86_400_000L
            dir.listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    private fun file(name: String) = File(dir, name.filter { it.isLetterOrDigit() || it == '-' || it == '_' } + ".json")

    private fun read(name: String): JSONArray? = try {
        file(name).takeIf { it.exists() }?.readText()?.let { JSONArray(it) }
    } catch (e: Exception) {
        null
    }

    private fun later(key: String, write: () -> String) {
        pending.remove(key)?.cancel()
        pending[key] = scope.launch {
            delay(SAVE_AFTER_MS)
            try {
                val f = file(key)
                val tmp = File(f.path + ".tmp")
                tmp.writeText(write())
                tmp.renameTo(f)
            } catch (e: Exception) {
                // A cache that can't be written just isn't there next time.
            }
        }
    }

    /** As last seen, marked not connected (the live list replaces them once the bridge answers). */
    fun sessions(): List<SessionInfo> = read("sessions")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let { o -> SessionInfo.from(o).copy(connected = false) } }
    }.orEmpty()

    fun saveSessions(list: List<SessionInfo>) = later("sessions") { JSONArray(list.map { it.toJson() }).toString() }

    fun items(sid: String): List<Item> = read("chat-$sid")?.let { a ->
        (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(Item::from) }
    }.orEmpty()

    fun saveItems(sid: String, items: List<Item>) = later("chat-$sid") { JSONArray(items.takeLast(KEEP_ITEMS).map { it.toJson() }).toString() }
}
