package dev.mulm.claudemobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mulm.claudemobile.App
import dev.mulm.claudemobile.Conn
import dev.mulm.claudemobile.SessionInfo
import kotlinx.coroutines.delay
import java.util.Calendar
import dev.mulm.claudemobile.Updater
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember

@Composable
fun rememberNow(): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(15_000)
            value = System.currentTimeMillis()
        }
    }
    return now
}

private fun greeting(nowMs: Long): String {
    val h = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
    return when (h) {
        in 5..11 -> "Good morning"
        in 12..17 -> "Good afternoon"
        else -> "Good evening"
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsScreen() {
    val bridge = App.instance.bridge
    val conn by bridge.conn.collectAsState()
    val host by bridge.host.collectAsState()
    val error by bridge.error.collectAsState()
    val sessions by bridge.sessions.collectAsState()
    val awake by bridge.awake.collectAsState()
    val build by bridge.appBuild.collectAsState()
    val ctx = LocalContext.current
    val installed = remember { Updater.installedCode(ctx) }
    val now = rememberNow()
    val c = Claude.colors

    Scaffold(
        containerColor = c.bg,
        floatingActionButton = {
            if (conn == Conn.Connected && sessions.isNotEmpty()) {
                ExtendedFloatingActionButton(
                    onClick = { Nav.go(Route.NewSession) },
                    containerColor = c.accentButton, contentColor = c.onAccent,
                    shape = RoundedCornerShape(16.dp),
                    icon = { Icon(Icons.Filled.Add, null) },
                    text = { Text("New session", fontWeight = FontWeight.Medium) },
                )
            }
        },
        topBar = {
            TopAppBar(
                colors = claudeTopBarColors(),
                title = { Text("Claude", fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp) },
                actions = {
                    IconButton(onClick = { Nav.go(Route.Settings()) }) { Icon(Icons.Filled.Settings, "Settings") }
                },
            )
        },
    ) { pad ->
        when {
            conn == Conn.NotConfigured -> Centered(pad) {
                Spark(size = 44.dp)
                Spacer(Modifier.size(20.dp))
                Text("Connect to your laptop", fontFamily = Serif, fontSize = 26.sp, color = c.text, textAlign = TextAlign.Center)
                Spacer(Modifier.size(10.dp))
                Text(
                    "Enter the bridge address and token shown by the install script on your laptop.",
                    textAlign = TextAlign.Center, color = c.muted, fontSize = 15.sp, lineHeight = 22.sp,
                )
                Spacer(Modifier.size(24.dp))
                PrimaryButton(onClick = { Nav.go(Route.Settings()) }) { Text("Set up") }
            }
            sessions.isEmpty() -> Centered(pad) {
                Spark(size = 44.dp, working = conn == Conn.Connecting)
                Spacer(Modifier.size(20.dp))
                Text(
                    if (conn == Conn.Connected) "No sessions running" else "Not connected",
                    fontFamily = Serif, fontSize = 26.sp, color = c.text, textAlign = TextAlign.Center,
                )
                Spacer(Modifier.size(10.dp))
                Text(
                    if (conn == Conn.Connected) "Start one from here, or run claude on your laptop and it shows up."
                    else "Make sure the laptop is awake and Tailscale is on.",
                    textAlign = TextAlign.Center, color = c.muted, fontSize = 15.sp, lineHeight = 22.sp,
                )
                if (conn == Conn.Connected) {
                    Spacer(Modifier.size(22.dp))
                    PrimaryButton(onClick = { Nav.go(Route.NewSession) }) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Start a session")
                    }
                }
                Spacer(Modifier.size(18.dp))
                ConnLine(conn, host, error, awake)
            }
            else -> {
                val sorted = sessions.sortedWith(
                    compareByDescending<SessionInfo> { it.needsYou }.thenByDescending { it.updated }
                )
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = pad.calculateTopPadding() + 8.dp, bottom = pad.calculateBottomPadding() + 96.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "header") {
                        Column(Modifier.padding(top = 8.dp, bottom = 18.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Spark(size = 30.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(greeting(now), fontFamily = Serif, fontWeight = FontWeight.Light,
                                    fontSize = 32.sp, lineHeight = 38.sp, color = c.text)
                            }
                            Spacer(Modifier.size(10.dp))
                            ConnLine(conn, host, error, awake)
                            build?.takeIf { it.versionCode > installed }?.let { b ->
                                Surface(
                                    onClick = { Nav.go(Route.Settings()) }, shape = RoundedCornerShape(12.dp),
                                    color = c.accent.copy(alpha = 0.12f), modifier = Modifier.padding(top = 14.dp),
                                ) {
                                    Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Spark(size = 14.dp)
                                        Spacer(Modifier.width(8.dp))
                                        Text("Update ready: ${b.versionName}", fontSize = 13.5.sp, color = c.text)
                                        Spacer(Modifier.width(10.dp))
                                        Text("Install ›", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = c.accentButton)
                                    }
                                }
                            }
                        }
                    }
                    item(key = "label") {
                        Text(
                            "Sessions · ${sessions.size}", fontSize = 13.sp, fontWeight = FontWeight.Medium,
                            color = c.faint, modifier = Modifier.padding(start = 2.dp, bottom = 2.dp),
                        )
                    }
                    items(sorted, key = { it.id }) { s -> SessionCard(s, now) { Nav.go(Route.Chat(s.id)) } }
                }
            }
        }
    }
}

@Composable
private fun Centered(pad: PaddingValues, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) { content() }
    }
}

@Composable
fun ConnLine(conn: Conn, host: String, error: String?, awake: Boolean = false) {
    val c = Claude.colors
    val (color, text) = when (conn) {
        Conn.Connected -> c.success to ("Connected to ${host.ifBlank { "laptop" }}" + if (awake) " · keeping it awake" else "")
        Conn.Connecting -> c.accent to "Connecting…"
        Conn.Unauthorized -> c.danger to "Token rejected"
        Conn.NotConfigured -> c.faint to "Not set up"
        Conn.Offline -> c.danger to ("Offline" + (error?.let { " · $it" } ?: ""))
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(text, fontSize = 13.sp, color = c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun SessionCard(s: SessionInfo, now: Long, onClick: () -> Unit) {
    val c = Claude.colors
    val needs = s.needsYou && s.connected
    Surface(
        onClick = onClick, shape = RoundedCornerShape(14.dp), color = c.surface,
        border = BorderStroke(1.dp, if (needs) c.accent.copy(alpha = 0.55f) else c.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.title.ifBlank { "New session" }, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(10.dp))
                StatusTag(s)
            }
            Spacer(Modifier.size(3.dp))
            Text("${s.project} · ${ago(s.updated, now)}", fontSize = 12.5.sp, color = c.faint, maxLines = 1)
            if (needs && s.detail.isNotBlank()) {
                Text(
                    s.detail, fontSize = 14.sp, lineHeight = 20.sp, color = c.accentButton,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
                )
            } else if (s.lastText.isNotBlank()) {
                Text(
                    s.lastText, fontFamily = Serif, fontSize = 14.5.sp, lineHeight = 21.sp, color = c.muted,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
fun StatusTag(s: SessionInfo) {
    val c = Claude.colors
    when {
        !s.connected -> PlainTag("Offline", c.faint, hollow = true)
        s.status == "working" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Spark(size = 14.dp, working = true)
            Spacer(Modifier.width(5.dp))
            Text("Working", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = c.accentButton)
        }
        s.needsYou -> Box(
            Modifier.background(c.accent.copy(alpha = 0.14f), RoundedCornerShape(50))
                .padding(horizontal = 10.dp, vertical = 3.dp),
        ) {
            Text(if (s.status == "question") "Question" else "Needs you", fontSize = 12.5.sp,
                fontWeight = FontWeight.Medium, color = c.accentButton)
        }
        s.status == "idle" -> PlainTag("Ready", c.success)
        s.status == "ended" -> PlainTag("Ended", c.faint, hollow = true)
        else -> PlainTag("Starting", c.faint)
    }
}

@Composable
private fun PlainTag(label: String, dot: Color, hollow: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        val m = Modifier.size(7.dp)
        Box(if (hollow) m.border(1.dp, dot, CircleShape) else m.background(dot, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.5.sp, color = Claude.colors.faint)
    }
}
