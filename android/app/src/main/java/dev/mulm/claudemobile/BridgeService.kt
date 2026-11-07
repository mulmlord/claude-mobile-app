package dev.mulm.claudemobile

import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import dev.mulm.claudemobile.widget.WidgetSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service holding the WebSocket to the laptop, so session events arrive as
 * notifications while the app is closed. No Google push service involved: the phone keeps
 * one idle socket open over Tailscale.
 */
class BridgeService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val app get() = application as App
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val previous = HashMap<String, String>()
    /** Kind of the notification last posted per session (both collectors run on Main). */
    private val shown = HashMap<String, String>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, Notifier.SERVICE_ID, Notifier.serviceNotification(this, "Connecting…"), type)

        val bridge = app.bridge
        ConnLog.info("Background connection started")
        bridge.start()

        scope.launch {
            bridge.notifications.collect { ev ->
                if (!app.prefs.notifyEnabled) return@collect
                if (ev.kind == "done" && app.visibleSession == ev.sid) return@collect
                Notifier.show(this@BridgeService, ev)
                shown[ev.sid] = ev.kind
            }
        }
        scope.launch {
            // Dismiss "needs permission"/"question" notifications once answered elsewhere
            // (e.g. at the laptop), and when a session goes away. Not when the one up is
            // already for the new status: an AskUserQuestion can show as "permission" for a
            // moment, and its question notification arrives before the status does.
            bridge.sessions.collect { list ->
                val now = list.associate { it.id to it.status }
                for ((sid, old) in previous) {
                    val cur = now[sid]
                    if ((old == "permission" || old == "question") && cur != old && shown[sid] != cur || cur == null) {
                        Notifier.cancel(this@BridgeService, sid)
                        shown.remove(sid)
                    }
                }
                previous.clear()
                previous.putAll(now)
            }
        }
        scope.launch {
            combine(bridge.conn, bridge.host, bridge.sessions, bridge.tailscaleDown) { c, host, list, tailscaleDown ->
                when (c) {
                    Conn.Connected -> {
                        val busy = list.count { it.status == "working" }
                        val waiting = list.count { it.needsYou }
                        buildString {
                            append("Connected to ${host.ifBlank { "laptop" }} · ${list.size} session${if (list.size == 1) "" else "s"}")
                            if (busy > 0) append(" · $busy working")
                            if (waiting > 0) append(" · $waiting waiting")
                        }
                    }
                    Conn.Connecting -> "Connecting…"
                    Conn.Unauthorized -> "Token rejected. Open settings"
                    Conn.NotConfigured -> "Not set up"
                    Conn.Offline -> if (tailscaleDown) "Tailscale isn't connected on this phone" else "Laptop unreachable, retrying"
                }
            }.distinctUntilChanged().collect { text ->
                getSystemService(NotificationManager::class.java)
                    .notify(Notifier.SERVICE_ID, Notifier.serviceNotification(this@BridgeService, text))
            }
        }
        // Home-screen widget: redrawn from here, as the bridge's state changes.
        scope.launch { WidgetSync.run(this@BridgeService, bridge) }

        val cm = getSystemService(ConnectivityManager::class.java)
        netCallback = object : ConnectivityManager.NetworkCallback() {
            private var last: Network? = null
            override fun onAvailable(network: Network) {
                // A socket opened on the old network may hang until the next ping; start fresh.
                val changed = last != null && network != last
                if (changed || bridge.conn.value != Conn.Connected) {
                    if (changed) ConnLog.info("Network changed, reconnecting")
                    bridge.reconnectNow()
                }
                last = network
            }
        }.also { cm.registerDefaultNetworkCallback(it) }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // Some OEMs kill the whole process shortly after the task is swiped away.
        Watchdog.restartSoon(this)
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        app.bridge.ensureConnected()
        return START_STICKY
    }

    override fun onDestroy() {
        ConnLog.info("Background connection stopped")
        netCallback?.let { getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it) }
        app.bridge.stop()
        scope.cancel()
        super.onDestroy()
    }
}
