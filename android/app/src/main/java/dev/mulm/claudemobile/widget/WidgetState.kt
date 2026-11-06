package dev.mulm.claudemobile.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.Build
import androidx.core.content.edit
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.updateAll
import dev.mulm.claudemobile.BridgeClient
import dev.mulm.claudemobile.Conn
import dev.mulm.claudemobile.SessionInfo
import dev.mulm.claudemobile.Updater
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** One row of the widget: the part of [SessionInfo] it shows. */
data class WidgetSession(
    val id: String,
    val title: String,
    val project: String,
    val status: String,
    val connected: Boolean,
) {
    val needsYou: Boolean get() = status == "permission" || status == "question"
}

/**
 * What the widget draws. [sessions] are in display order; while offline they are the last ones
 * seen, and [asOf] is when the laptop was last heard from.
 */
data class WidgetSnapshot(
    val conn: Conn,
    val host: String,
    val sessions: List<WidgetSession>,
    val asOf: Long,
) {
    /** Same picture on screen; [asOf] alone changing isn't worth a redraw. */
    fun sameView(o: WidgetSnapshot) = conn == o.conn && host == o.host && sessions == o.sessions

    /**
     * What to actually show. A snapshot saved by a process that has since died (or by a service
     * that stopped) can claim a connection nobody holds any more.
     */
    fun shown(live: Boolean, configured: Boolean): WidgetSnapshot = when {
        !configured -> copy(conn = Conn.NotConfigured, sessions = emptyList())
        !live && (conn == Conn.Connected || conn == Conn.Connecting) -> copy(conn = Conn.Offline)
        else -> this
    }

    companion object {
        val Empty = WidgetSnapshot(Conn.Offline, "", emptyList(), 0)

        /** Needs you first, then most recently active: the same order as the app's list. */
        fun rows(list: List<SessionInfo>): List<WidgetSession> =
            list.sortedWith(compareByDescending<SessionInfo> { it.needsYou }.thenByDescending { it.updated })
                .map { WidgetSession(it.id, it.title, it.project, it.status, it.connected) }
    }
}

/**
 * The widget's data. Kept in memory for the Glance composition to collect, and in
 * SharedPreferences because the launcher can ask for a redraw (resize, reboot, a new instance)
 * while the process is gone and nothing is connected.
 */
object WidgetStore {
    private const val PREFS = "widget"
    private const val KEY = "snapshot"
    private var flow: MutableStateFlow<WidgetSnapshot>? = null

    /** True while BridgeService feeds the snapshot from a running bridge in this process. */
    val live = MutableStateFlow(false)

    fun snapshot(ctx: Context): StateFlow<WidgetSnapshot> = state(ctx)

    fun save(ctx: Context, s: WidgetSnapshot) {
        state(ctx).value = s
        prefs(ctx).edit { putString(KEY, toJson(s).toString()) }
    }

    private fun state(ctx: Context) = synchronized(this) {
        flow ?: MutableStateFlow(load(ctx)).also { flow = it }
    }

    internal fun prefs(ctx: Context) = ctx.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(ctx: Context): WidgetSnapshot = try {
        prefs(ctx).getString(KEY, null)?.let { fromJson(JSONObject(it)) } ?: WidgetSnapshot.Empty
    } catch (e: Exception) {
        WidgetSnapshot.Empty
    }

    private fun toJson(s: WidgetSnapshot) = JSONObject()
        .put("conn", s.conn.name).put("host", s.host).put("as_of", s.asOf)
        .put("sessions", JSONArray().apply {
            s.sessions.forEach {
                put(JSONObject().put("id", it.id).put("title", it.title).put("project", it.project)
                    .put("status", it.status).put("connected", it.connected))
            }
        })

    private fun fromJson(o: JSONObject): WidgetSnapshot {
        val a = o.optJSONArray("sessions") ?: JSONArray()
        return WidgetSnapshot(
            conn = Conn.entries.firstOrNull { it.name == o.optString("conn") } ?: Conn.Offline,
            host = o.optString("host"),
            sessions = (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map {
                WidgetSession(it.optString("id"), it.optString("title"), it.optString("project"),
                    it.optString("status"), it.optBoolean("connected", true))
            },
            asOf = o.optLong("as_of"),
        )
    }
}

/** Keeps the widget in step with the bridge, pushed from BridgeService; the widget never polls. */
object WidgetSync {
    /** Runs until the calling (service) scope is cancelled. */
    suspend fun run(ctx: Context, bridge: BridgeClient) = run(ctx, bridge.conn, bridge.host, bridge.sessions)

    internal suspend fun run(ctx: Context, conns: Flow<Conn>, hosts: Flow<String>, lists: Flow<List<SessionInfo>>) = coroutineScope {
        val app = ctx.applicationContext
        var asOf = WidgetStore.snapshot(app).value.asOf
        var wasConnected = false
        try {
            // Beside, not before: a slow preview render must not hold up the widget itself.
            launch { publishPreviews(app) }
            combine(conns, hosts, lists) { conn, host, list ->
                val kept = WidgetStore.snapshot(app).value
                // Stamped while connected and once more on the way down, so "Offline since" is
                // when the laptop was last heard from, not when the list last changed.
                if (conn == Conn.Connected || wasConnected) asOf = System.currentTimeMillis()
                wasConnected = conn == Conn.Connected
                WidgetSnapshot(
                    conn = conn,
                    host = host.ifBlank { kept.host },
                    // Offline, the bridge still holds the last list it got. A fresh process has none
                    // yet: keep the saved one rather than blanking the widget until it connects.
                    sessions = if (conn == Conn.Connected || list.isNotEmpty()) WidgetSnapshot.rows(list) else kept.sessions,
                    asOf = asOf,
                )
            }
                .distinctUntilChanged { a, b -> a.sameView(b) }
                .atMostEvery(1000)
                .distinctUntilChanged { a, b -> a.sameView(b) }
                .collect { snap ->
                    WidgetStore.save(app, snap)
                    WidgetStore.live.value = true
                    redraw(app)
                }
        } finally {
            // The service is going away: its "Connected" no longer holds.
            WidgetStore.live.value = false
            withContext(NonCancellable) { redraw(app) }
        }
    }

    private suspend fun redraw(ctx: Context) {
        // No widget placed: nothing to draw, and no reason to wake Glance's WorkManager.
        val ids = AppWidgetManager.getInstance(ctx).getAppWidgetIds(ComponentName(ctx, SessionsWidgetReceiver::class.java))
        if (ids.isEmpty()) return
        try {
            SessionsWidget().updateAll(ctx)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // A widget hiccup must never take the background connection down with it.
        }
    }

    /**
     * Android 15+ widget pickers show a preview rendered from the real layout (sample data).
     * The system rate-limits this, so it runs once per installed version.
     */
    private suspend fun publishPreviews(ctx: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        val version = try { Updater.installedCode(ctx) } catch (e: Exception) { return }
        val sp = WidgetStore.prefs(ctx)
        if (sp.getLong("preview_version", -1) == version) return
        try {
            val result = GlanceAppWidgetManager(ctx).setWidgetPreviews(SessionsWidgetReceiver::class)
            if (result == GlanceAppWidgetManager.SET_WIDGET_PREVIEWS_RESULT_SUCCESS) sp.edit { putLong("preview_version", version) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Rate-limited or unsupported by the launcher: the static preview image still shows.
        }
    }
}

/**
 * Passes on the latest value at most once per [ms]. A burst (connected, then the session list a
 * moment later) lands as one redraw. Unlike a debounce it can't be starved: two sessions working
 * side by side reorder the list every few hundred milliseconds and would hold it back forever.
 */
private fun <T> Flow<T>.atMostEvery(ms: Long): Flow<T> = channelFlow {
    val latest = Channel<T>(Channel.CONFLATED)
    launch {
        collect { latest.send(it) }
        latest.close()
    }
    for (first in latest) {
        delay(ms)
        send(latest.tryReceive().getOrNull() ?: first)
    }
}
