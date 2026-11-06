package dev.mulm.claudemobile

import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

enum class Conn { NotConfigured, Connecting, Connected, Unauthorized, Offline }

/**
 * Single connection to the laptop bridge, shared by the UI and the background service.
 * State is exposed as flows; actions are plain HTTP calls so they also work from
 * notification actions while the socket is reconnecting.
 */
class BridgeClient(private val prefs: Prefs) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        // Never send the token to a tailnet name while Tailscale is down (see TailnetGuard).
        .addInterceptor { chain ->
            if (TailnetGuard.blocked(chain.request().url.host)) throw java.io.IOException(TailnetGuard.MESSAGE)
            chain.proceed(chain.request())
        }
        .build()

    private val _conn = MutableStateFlow(if (prefs.configured) Conn.Offline else Conn.NotConfigured)
    val conn: StateFlow<Conn> = _conn
    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error
    private val _host = MutableStateFlow("")
    val host: StateFlow<String> = _host
    private val _sessions = MutableStateFlow<List<SessionInfo>>(emptyList())
    val sessions: StateFlow<List<SessionInfo>> = _sessions
    private val _items = MutableStateFlow<Map<String, List<Item>>>(emptyMap())
    val items: StateFlow<Map<String, List<Item>>> = _items
    private val _screen = MutableStateFlow<ScreenState?>(null)
    val screen: StateFlow<ScreenState?> = _screen
    /** The bridge is holding the laptop awake right now. */
    private val _awake = MutableStateFlow(false)
    val awake: StateFlow<Boolean> = _awake
    /** working | always | off, as configured on the bridge. */
    private val _keepAwake = MutableStateFlow<String?>(null)
    val keepAwake: StateFlow<String?> = _keepAwake
    /** Newest app build on the laptop, for Settings > Update app. */
    private val _appBuild = MutableStateFlow<AppBuild?>(null)
    val appBuild: StateFlow<AppBuild?> = _appBuild
    private val _notifications = MutableSharedFlow<NotifyEvent>(extraBufferCapacity = 64)
    val notifications: SharedFlow<NotifyEvent> = _notifications

    @Volatile private var ws: WebSocket? = null
    @Volatile private var wanted = false
    @Volatile private var sub: String? = null
    @Volatile private var screenSub: String? = null
    private var attempt = 0
    private var retryJob: Job? = null
    private var deadlineJob: Job? = null
    /** elapsedRealtime when the pending handshake started; 0 while none is pending. */
    private var connectingSince = 0L
    private val lock = Any()
    /**
     * Guards [ws], the retry state and [conn]. Listener callbacks check under it that their socket
     * is still the current one, and [connect] assigns [ws] while holding it. Without that, a failure
     * OkHttp reports before newWebSocket() has returned (Tailscale down: the guard refuses at once)
     * was dropped as stale, and the app sat on "Connecting…" with no socket at all.
     */
    private val connLock = Any()

    fun start() {
        wanted = true
        connect()
    }

    fun stop() {
        val old: WebSocket?
        synchronized(connLock) {
            wanted = false
            retryJob?.cancel()
            deadlineJob?.cancel()
            old = ws
            ws = null
            connectingSince = 0
            _conn.value = if (prefs.configured) Conn.Offline else Conn.NotConfigured
        }
        old?.close(1000, null)
    }

    /** Settings changed or the network came back: drop backoff and reconnect now. */
    fun reconnectNow() {
        val old: WebSocket?
        synchronized(connLock) {
            retryJob?.cancel()
            deadlineJob?.cancel()
            attempt = 0
            old = ws
            ws = null
            connectingSince = 0
        }
        old?.cancel()
        if (wanted) connect()
    }

    /** App brought to the front, or the watchdog fired: reconnect unless a socket is up or still handshaking. */
    fun ensureConnected() {
        if (!wanted) return
        val stale = synchronized(connLock) {
            ws == null || (connectingSince != 0L && SystemClock.elapsedRealtime() - connectingSince > HANDSHAKE_MS)
        }
        if (stale) reconnectNow()
    }

    private fun connect() {
        synchronized(connLock) {
            if (!wanted) return
            if (!prefs.configured) {
                _conn.value = Conn.NotConfigured
                return
            }
            if (ws != null) return
            val req = try {
                Request.Builder().url(prefs.url + "/api/ws")
                    .header("Authorization", "Bearer " + prefs.token).build()
            } catch (e: IllegalArgumentException) {
                _conn.value = Conn.Offline
                _error.value = "Invalid URL: ${prefs.url}"
                return
            }
            _conn.value = Conn.Connecting
            connectingSince = SystemClock.elapsedRealtime()
            val socket = http.newWebSocket(req, Listener())
            ws = socket
            Log.i(TAG, "connecting to ${req.url.host} (attempt ${attempt + 1})")
            // OkHttp's own timeouts don't cover every step (DNS, for one); never wait forever.
            deadlineJob?.cancel()
            deadlineJob = scope.launch {
                delay(HANDSHAKE_MS)
                if (lost(socket, Conn.Offline, "No answer from the laptop")) {
                    Log.w(TAG, "handshake took over ${HANDSHAKE_MS / 1000}s, retrying")
                    socket.cancel()
                    scheduleRetry(false)
                }
            }
        }
    }

    /** Forgets [socket] if it is still the current one; false for a stale socket's late callback. */
    private fun lost(socket: WebSocket, state: Conn, error: String?): Boolean {
        synchronized(connLock) {
            if (socket !== ws) return false
            ws = null
            connectingSince = 0
            deadlineJob?.cancel()
            _conn.value = state
            if (error != null) _error.value = error
            return true
        }
    }

    private fun scheduleRetry(unauthorized: Boolean) {
        synchronized(connLock) {
            if (!wanted) return
            val secs = if (unauthorized) 60L else minOf(30L, 1L shl minOf(attempt, 5))
            attempt++
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(secs * 1000)
                connect()
            }
        }
    }

    private inner class Listener : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(connLock) {
                if (webSocket !== ws) return
                attempt = 0
                connectingSince = 0
                deadlineJob?.cancel()
                _conn.value = Conn.Connected
                _error.value = null
            }
            Log.i(TAG, "connected")
            webSocket.send(JSONObject().put("t", "hello").put("last_notify", prefs.lastNotifyId).toString())
            sub?.let { webSocket.send(JSONObject().put("t", "sub").put("sid", it).toString()) }
            screenSub?.let { webSocket.send(JSONObject().put("t", "screen").put("sid", it).toString()) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            val o = try { JSONObject(text) } catch (e: Exception) { return }
            when (o.optString("t")) {
                "hello" -> {
                    _host.value = o.optString("host")
                    _appBuild.value = AppBuild.from(o.optJSONObject("app"))
                    // First ever connection: start counting from now instead of replaying history.
                    if (prefs.lastNotifyId == 0L) prefs.lastNotifyId = o.optLong("notify_id")
                }
                "sessions" -> {
                    val a = o.optJSONArray("sessions") ?: JSONArray()
                    _sessions.value = (0 until a.length()).map { SessionInfo.from(a.getJSONObject(it)) }
                    _awake.value = o.optBoolean("awake")
                    if (o.has("keep_awake")) _keepAwake.value = o.optString("keep_awake")
                }
                "items" -> mergeItems(o)
                "app" -> _appBuild.value = AppBuild.from(o.optJSONObject("app"))
                "screen" -> {
                    val st = ScreenState.from(o)
                    if (st.sid == screenSub) _screen.value = st
                }
                "notify" -> {
                    val ev = NotifyEvent.from(o)
                    if (ev.id > prefs.lastNotifyId) {
                        prefs.lastNotifyId = ev.id
                        _notifications.tryEmit(ev)
                    }
                }
            }
        }

        override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
            webSocket.close(1000, null)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
            if (!lost(webSocket, Conn.Offline, null)) return
            Log.i(TAG, "closed by the laptop ($code)")
            scheduleRetry(false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val unauthorized = response?.code == 401
            val msg = if (unauthorized) "The token was rejected" else (t.message ?: t.javaClass.simpleName)
            if (!lost(webSocket, if (unauthorized) Conn.Unauthorized else Conn.Offline, msg)) return
            Log.i(TAG, "connection failed: $msg")
            scheduleRetry(unauthorized)
        }
    }

    private companion object {
        const val TAG = "Bridge"
        const val HANDSHAKE_MS = 45_000L
    }

    private fun mergeItems(o: JSONObject) {
        val sid = o.optString("sid")
        val a = o.optJSONArray("items") ?: JSONArray()
        val incoming = (0 until a.length()).map { Item.from(a.getJSONObject(it)) }
        synchronized(lock) {
            val current = _items.value
            val merged = if (o.optBoolean("reset")) {
                incoming
            } else {
                val byId = LinkedHashMap<String, Item>()
                current[sid]?.forEach { byId[it.id] = it }
                incoming.forEach { byId[it.id] = it }
                byId.values.sortedBy { it.seq }
            }
            _items.value = current + (sid to merged.takeLast(600))
        }
    }

    // ---- subscriptions ---------------------------------------------------------
    fun subscribe(sid: String?) {
        sub = sid
        ws?.send(JSONObject().put("t", "sub").put("sid", sid ?: JSONObject.NULL).toString())
    }

    fun subscribeScreen(sid: String?) {
        if (sid == screenSub) return
        screenSub = sid
        if (sid == null || _screen.value?.sid != sid) _screen.value = null
        ws?.send(JSONObject().put("t", "screen").put("sid", sid ?: JSONObject.NULL).toString())
    }

    // ---- actions ---------------------------------------------------------------
    private val json = "application/json".toMediaType()

    private suspend fun call(path: String, body: JSONObject? = null, url: String = prefs.url, token: String = prefs.token): JSONObject =
        withContext(Dispatchers.IO) {
            val b = Request.Builder().url(url + path).header("Authorization", "Bearer $token")
            if (body != null) b.post(body.toString().toRequestBody(json))
            http.newCall(b.build()).execute().use { r ->
                val text = r.body.string()
                if (!r.isSuccessful) {
                    throw BridgeError(if (r.code == 401) "The token was rejected" else text.ifBlank { "HTTP ${r.code}" })
                }
                if (text.isBlank()) JSONObject() else JSONObject(text)
            }
        }

    /** Plain JSON call for features with their own client (GitHub). */
    suspend fun api(path: String, body: JSONObject? = null): JSONObject = call(path, body)

    suspend fun sendText(sid: String, text: String) = call("/api/sessions/$sid/send", JSONObject().put("text", text))

    suspend fun keys(sid: String, vararg keys: String) =
        call("/api/sessions/$sid/keys", JSONObject().put("keys", JSONArray(keys.toList())))

    suspend fun select(sid: String, index: Int) = call("/api/sessions/$sid/select", JSONObject().put("index", index))

    /** [dh] ties the answer to the prompt the notification showed; the bridge refuses if another is up. */
    suspend fun answer(sid: String, choice: String, dh: String? = null) =
        call("/api/sessions/$sid/answer", JSONObject().put("choice", choice).apply { if (!dh.isNullOrBlank()) put("dh", dh) })

    /** Picks a question's option by label; with [qid] the bridge refuses once that question is no longer waiting. */
    suspend fun choose(sid: String, label: String, qid: String?) =
        call("/api/sessions/$sid/choose", JSONObject().put("label", label).apply { if (!qid.isNullOrBlank()) put("qid", qid) })

    /** Types /exit into the session (after Esc if it's busy); [force] SIGTERMs its claude instead. */
    suspend fun endSession(sid: String, force: Boolean = false) =
        call("/api/sessions/$sid/end", JSONObject().put("force", force))

    /** Types /rename; the new title comes back with the next sessions update. */
    suspend fun rename(sid: String, name: String) = call("/api/sessions/$sid/rename", JSONObject().put("name", name))

    /** Sends a photo to the laptop; returns where the bridge saved it. */
    suspend fun upload(sid: String, bytes: ByteArray, type: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(prefs.url + "/api/sessions/$sid/upload")
            .header("Authorization", "Bearer " + prefs.token)
            .post(bytes.toRequestBody(type.toMediaType())).build()
        http.newCall(req).execute().use { r ->
            val text = r.body.string()
            if (!r.isSuccessful) throw BridgeError(text.ifBlank { "Upload failed (HTTP ${r.code})" })
            JSONObject(text).optString("path")
        }
    }

    /** Streams the laptop's APK into [dest], reporting progress 0..1. */
    suspend fun downloadApk(dest: java.io.File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(prefs.url + "/api/app/apk").header("Authorization", "Bearer " + prefs.token).build()
        http.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw BridgeError(r.body.string().ifBlank { "Download failed (HTTP ${r.code})" })
            val total = r.body.contentLength().coerceAtLeast(1)
            var done = 0L
            var shown = -1
            dest.outputStream().use { out ->
                r.body.byteStream().use { inp ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        val n = inp.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        val pct = (done * 100 / total).toInt()
                        if (pct != shown) {
                            shown = pct
                            onProgress(pct / 100f)
                        }
                    }
                }
            }
        }
    }

    suspend fun projects(): List<Project> = Project.list(call("/api/projects").optJSONArray("projects"))

    /** Opens a terminal on the laptop with a new (or resumed) session; returns its id once it connects. */
    suspend fun spawn(cwd: String, resume: String? = null, prompt: String? = null): String? {
        val body = JSONObject().put("cwd", cwd)
        if (resume != null) body.put("resume", resume)
        if (!prompt.isNullOrBlank()) body.put("prompt", prompt)
        val o = call("/api/spawn", body)
        return if (o.isNull("sid")) null else o.optString("sid").ifBlank { null }
    }

    suspend fun setKeepAwake(mode: String) {
        val o = call("/api/settings", JSONObject().put("keep_awake", mode))
        _keepAwake.value = o.optString("keep_awake", mode)
        _awake.value = o.optBoolean("awake")
    }

    suspend fun refreshItems(sid: String) {
        val o = call("/api/sessions/$sid/items")
        mergeItems(o.put("reset", true))
    }

    /** Settings screen "Test": returns a human readable result. */
    suspend fun test(url: String, token: String): String {
        val o = call("/api/health", url = url.trim().trimEnd('/'), token = token.trim())
        val n = o.optInt("sessions")
        return "Connected to ${o.optString("host")} · $n session${if (n == 1) "" else "s"}"
    }
}

class BridgeError(msg: String) : Exception(msg)
