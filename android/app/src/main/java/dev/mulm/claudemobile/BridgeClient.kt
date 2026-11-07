package dev.mulm.claudemobile

import android.content.Context
import android.os.PowerManager
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
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
class BridgeClient(private val ctx: Context, private val prefs: Prefs) {
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
    /** The last attempt failed because Tailscale isn't working on this phone (the UI offers to open it). */
    private val _tailscaleDown = MutableStateFlow(false)
    val tailscaleDown: StateFlow<Boolean> = _tailscaleDown
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
    /** An http:// address is being moved to https:// (see [upgradeToHttps]); guarded by [connLock]. */
    private var upgrading = false
    /** Wall-clock time the connection went down (for "offline for …" in the log); 0 while up. */
    private var offlineSince = 0L
    /** elapsedRealtime of the last message from the bridge, for [probe]. */
    @Volatile private var lastHeard = 0L
    @Volatile private var probing = false
    /**
     * Keeps the CPU up through the first quick retries after a drop. In deep sleep a coroutine
     * delay doesn't run until something else wakes the phone, which can be many minutes later.
     * Longer waits use an alarm instead (Watchdog.retryIn).
     */
    private val wake by lazy {
        ctx.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claudemobile:reconnect")
            .apply { setReferenceCounted(false) }
    }

    /** The background service wants a connection (false before it starts and after it stops). */
    val running get() = wanted

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
            if (wake.isHeld) wake.release()
        }
        Watchdog.cancelRetry(ctx)
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

    /**
     * App brought to the front, or the watchdog fired: try now unless a socket is up or still
     * handshaking. Keeps the backoff, so a laptop that's off isn't hammered every time.
     */
    fun ensureConnected() {
        if (!wanted) return
        val now = SystemClock.elapsedRealtime()
        var quiet = false
        val stuck = synchronized(connLock) {
            quiet = ws != null && _conn.value == Conn.Connected && now - lastHeard > QUIET_MS
            connectingSince != 0L && now - connectingSince > HANDSHAKE_MS
        }
        when {
            stuck -> reconnectNow()
            // Back from sleep, the socket may be dead without OkHttp knowing yet (its own ping
            // only notices within a minute): check now, so the app is live when you look.
            quiet -> scope.launch { probe(QUICK_PROBE_MS) }
            else -> retryNow()
        }
    }

    /** The retry alarm fired (or the app came to the front): attempt now if nothing is connected. */
    fun retryNow() {
        synchronized(connLock) {
            if (ws != null) return
            retryJob?.cancel()
        }
        connect()
    }

    /** Waits until an attempt in progress has succeeded or failed, so a receiver knows how long to hold the CPU. */
    suspend fun awaitSettled(timeoutMs: Long) {
        withTimeoutOrNull(timeoutMs) { _conn.first { it != Conn.Connecting } }
    }

    /**
     * Watchdog check: a socket that looks open may be dead (the phone slept through the
     * drop, so OkHttp's own pings never ran). Ask the bridge for a pong; reconnect if none comes.
     */
    suspend fun probe(timeoutMs: Long = PROBE_MS) {
        val socket = ws ?: return
        if (_conn.value != Conn.Connected || probing) return
        probing = true
        val before = lastHeard
        socket.send(JSONObject().put("t", "ping").toString())
        val answered = try {
            withTimeoutOrNull(timeoutMs) {
                while (lastHeard == before) delay(100)
                true
            } ?: false
        } finally {
            probing = false
        }
        if (!answered && socket === ws) {
            Log.w(TAG, "no answer to a ping in ${timeoutMs / 1000}s, reconnecting")
            ConnLog.bad("No answer to a ping, the connection had died. Reconnecting")
            reconnectNow()
        }
    }

    private fun connect() {
        synchronized(connLock) {
            if (!wanted) return
            if (!prefs.configured) {
                _conn.value = Conn.NotConfigured
                return
            }
            if (ws != null) return
            if (needsHttps()) {
                if (!upgrading) {
                    upgrading = true
                    _conn.value = Conn.Connecting
                    scope.launch { upgradeToHttps() }
                }
                return
            }
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
                if (lost(socket, Conn.Offline, "No answer from the laptop") != Drop.Stale) {
                    Log.w(TAG, "handshake took over ${HANDSHAKE_MS / 1000}s, retrying")
                    ConnLog.bad("Couldn't connect: no answer within ${HANDSHAKE_MS / 1000} s")
                    socket.cancel()
                    scheduleRetry(false)
                }
            }
        }
    }

    /** http:// is blocked (res/xml/network_security_config.xml) except to this phone itself (adb reverse). */
    private fun needsHttps(): Boolean {
        val u = prefs.url.toHttpUrlOrNull() ?: return false
        return !u.isHttps && u.host != "127.0.0.1" && u.host != "localhost"
    }

    /**
     * Pairings from before 0.9.7 point at http://<laptop>.<tailnet>.ts.net:8787. The bridge is
     * now also behind tailscale serve's HTTPS on 443: if the same machine answers there with
     * this token, save that address. Nothing for the user to do.
     */
    private suspend fun upgradeToHttps() {
        val candidate = prefs.url.toHttpUrlOrNull()?.let { "https://${it.host}" }
        val ok = candidate != null && try {
            call("/api/health", url = candidate)
            true
        } catch (e: Exception) {
            Log.i(TAG, "no HTTPS at $candidate: ${e.message}")
            false
        }
        synchronized(connLock) {
            upgrading = false
            if (ok) {
                prefs.url = candidate!!
            } else {
                _conn.value = Conn.Offline
                _error.value = HTTPS_ONLY
            }
        }
        if (ok) {
            Log.i(TAG, "switched to $candidate")
            ConnLog.good("Switched to HTTPS: $candidate")
            connect()
        } else {
            ConnLog.bad("Couldn't switch to HTTPS: ${candidate ?: prefs.url} didn't answer")
            scheduleRetry(false)
        }
    }

    private enum class Drop { Stale, WasUp, WasDown }

    /** Forgets [socket] if it is still the current one; [Drop.Stale] for an old socket's late callback. */
    private fun lost(socket: WebSocket, state: Conn, error: String?): Drop {
        synchronized(connLock) {
            if (socket !== ws) return Drop.Stale
            val wasUp = _conn.value == Conn.Connected
            ws = null
            connectingSince = 0
            deadlineJob?.cancel()
            _conn.value = state
            if (error != null) _error.value = error
            if (offlineSince == 0L) offlineSince = System.currentTimeMillis()
            return if (wasUp) Drop.WasUp else Drop.WasDown
        }
    }

    private fun scheduleRetry(unauthorized: Boolean) {
        synchronized(connLock) {
            if (!wanted) return
            // 1, 2, 4, 8, 16, then every 30 s; after ~3 minutes of failures with the screen
            // off (laptop asleep or away), every 5 minutes. Opening the app tries at once.
            val interactive = ctx.getSystemService(PowerManager::class.java).isInteractive
            val cap = if (attempt >= 8 && !interactive) 300L else 30L
            val secs = if (unauthorized) 60L else minOf(cap, 1L shl minOf(attempt, 9))
            attempt++
            retryJob?.cancel()
            retryJob = scope.launch {
                delay(secs * 1000)
                connect()
            }
            if (secs <= QUICK_RETRY_S) {
                wake.acquire((secs + 25) * 1000)
            } else {
                if (wake.isHeld) wake.release()
                Watchdog.retryIn(ctx, secs * 1000)
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
                lastHeard = SystemClock.elapsedRealtime()
                _conn.value = Conn.Connected
                _error.value = null
                _tailscaleDown.value = false
                if (wake.isHeld) wake.release()
            }
            Watchdog.cancelRetry(ctx)
            Log.i(TAG, "connected")
            val down = offlineSince
            offlineSince = 0
            ConnLog.good("Connected to ${webSocket.request().url.host.substringBefore('.')}" +
                if (down > 0) " · offline for ${ConnLog.duration(System.currentTimeMillis() - down)}" else "")
            webSocket.send(JSONObject().put("t", "hello").put("last_notify", prefs.lastNotifyId).toString())
            sub?.let { webSocket.send(JSONObject().put("t", "sub").put("sid", it).toString()) }
            screenSub?.let { webSocket.send(JSONObject().put("t", "screen").put("sid", it).toString()) }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            if (webSocket !== ws) return
            lastHeard = SystemClock.elapsedRealtime()
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
            val drop = lost(webSocket, Conn.Offline, null)
            if (drop == Drop.Stale) return
            Log.i(TAG, "closed by the laptop ($code)")
            ConnLog.bad(if (drop == Drop.WasUp) "The laptop closed the connection" else "Couldn't connect: the laptop closed the connection")
            scheduleRetry(false)
        }

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val unauthorized = response?.code == 401
            val (msg, tailscale) = when {
                unauthorized -> "The token was rejected" to false
                // tailscale serve answers for the laptop while nothing is behind it.
                response?.code in 502..504 -> "The bridge on the laptop isn't answering" to false
                else -> describe(t, webSocket.request().url.host)
            }
            val drop = lost(webSocket, if (unauthorized) Conn.Unauthorized else Conn.Offline, msg)
            if (drop == Drop.Stale) return
            _tailscaleDown.value = tailscale
            Log.i(TAG, "connection failed: ${t.javaClass.simpleName}: ${t.message}")
            ConnLog.bad(if (drop == Drop.WasUp) "Connection lost: $msg" else "Couldn't connect: $msg")
            scheduleRetry(unauthorized)
        }
    }

    /** A failure in words, and whether it means Tailscale isn't working on this phone. */
    private fun describe(t: Throwable, host: String): Pair<String, Boolean> = when {
        t.message == TailnetGuard.MESSAGE -> TailnetGuard.MESSAGE to true
        t is java.net.UnknownServiceException -> HTTPS_ONLY to false
        // MagicDNS knows every tailnet machine, asleep or not: a failed lookup means the
        // phone's Tailscale isn't answering (or, with "Block connections without VPN", is down).
        t is UnknownHostException && TailnetGuard.isTailnet(host) -> "Tailscale isn't connected on this phone" to true
        t is UnknownHostException -> "Can't find $host" to false
        t is SocketTimeoutException -> "The laptop isn't answering. Is it awake?" to false
        t is ConnectException -> "The laptop refused the connection. Is the bridge running?" to false
        // Tailscale on the laptop takes the connection, but nothing is behind it (bridge restarting or stopped).
        t is java.io.EOFException || t.message.orEmpty().startsWith("unexpected end of stream") ->
            "The bridge on the laptop isn't answering" to false
        else -> (t.message ?: t.javaClass.simpleName) to false
    }

    private companion object {
        const val TAG = "Bridge"
        const val HANDSHAKE_MS = 45_000L
        const val PROBE_MS = 10_000L
        /** On coming to the front: ping if nothing came for this long, and allow this long for the pong. */
        const val QUIET_MS = 30_000L
        const val QUICK_PROBE_MS = 4_000L
        const val HTTPS_ONLY = "Use an https:// address. Unencrypted connections are off"
        /** Retries this soon keep the CPU awake; later ones wait for an alarm. */
        const val QUICK_RETRY_S = 10L
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
    suspend fun answer(sid: String, choice: String, dh: String? = null, message: String? = null) =
        call("/api/sessions/$sid/answer", JSONObject().put("choice", choice).apply {
            if (!dh.isNullOrBlank()) put("dh", dh)
            // With "no": what Claude should do instead, typed in once the prompt closes.
            if (!message.isNullOrBlank()) put("message", message)
        })

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
        val o = try {
            call("/api/health", url = url.trim().trimEnd('/'), token = token.trim())
        } catch (e: java.net.UnknownServiceException) {
            throw BridgeError(HTTPS_ONLY)
        }
        val n = o.optInt("sessions")
        return "Connected to ${o.optString("host")} · $n session${if (n == 1) "" else "s"}"
    }
}

class BridgeError(msg: String) : Exception(msg)
