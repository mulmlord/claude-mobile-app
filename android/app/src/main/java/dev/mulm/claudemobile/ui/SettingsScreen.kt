package dev.mulm.claudemobile.ui

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import dev.mulm.claudemobile.App
import kotlinx.coroutines.launch
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.graphics.Color
import androidx.compose.material3.Surface
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.clickable
import androidx.compose.foundation.BorderStroke
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.border
import androidx.compose.foundation.background
import dev.mulm.claudemobile.Updater
import dev.mulm.claudemobile.UpdateState
import androidx.compose.material3.LinearProgressIndicator

@SuppressLint("BatteryLife")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(route: Route.Settings) {
    val app = App.instance
    val prefs = app.prefs
    val ctx = LocalContext.current
    val c = Claude.colors
    val scope = rememberCoroutineScope()
    var url by rememberSaveable { mutableStateOf(route.url ?: prefs.url.ifBlank { "https://" }) }
    var token by rememberSaveable { mutableStateOf(route.token ?: prefs.token) }
    var showToken by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<String?>(null) }
    var notify by remember { mutableStateOf(prefs.notifyEnabled) }
    var batteryOk by remember { mutableStateOf(true) }
    LifecycleResumeEffect(Unit) {
        batteryOk = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)
        onPauseOrDispose { }
    }
    val fieldShape = RoundedCornerShape(12.dp)
    val keepAwake by app.bridge.keepAwake.collectAsState()
    val awake by app.bridge.awake.collectAsState()
    var awakeError by remember { mutableStateOf<String?>(null) }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            TopAppBar(
                colors = claudeTopBarColors(),
                navigationIcon = {
                    IconButton(onClick = { Nav.go(Route.Sessions) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("Settings", fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp) },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            val build by app.bridge.appBuild.collectAsState()
            val updating by Updater.state.collectAsState()
            val installedCode = remember { Updater.installedCode(ctx) }
            val newer = build.let { it != null && it.versionCode > installedCode }
            val updateFirst = newer || updating !is UpdateState.Idle
            if (updateFirst) AppSection()

            if (route.url != null) {
                Notice(c.accent.copy(alpha = 0.12f), c.accent.copy(alpha = 0.35f)) {
                    Text("Pairing link received. Check the address, then tap Save.", fontSize = 14.sp, color = c.text)
                }
            }
            Section("Laptop connection") {
                OutlinedTextField(
                    value = url, onValueChange = { url = it }, label = { Text("Bridge address") },
                    supportingText = { Text("e.g. https://my-laptop.your-tailnet.ts.net (its Tailscale name)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = fieldShape, colors = claudeFieldColors(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it }, label = { Text("Token") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(), shape = fieldShape, colors = claudeFieldColors(),
                    visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
                    trailingIcon = {
                        TextButton(onClick = { showToken = !showToken }) { Text(if (showToken) "Hide" else "Show", color = c.muted) }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                    PrimaryButton(onClick = {
                        prefs.url = url
                        prefs.token = token
                        App.startService(ctx)
                        app.bridge.start()
                        app.bridge.reconnectNow()
                        Nav.go(Route.Sessions)
                    }) { Text("Save") }
                    SecondaryButton(onClick = {
                        testResult = "Testing…"
                        scope.launch {
                            testResult = try {
                                app.bridge.test(url, token)
                            } catch (e: Exception) {
                                "Failed: ${e.message ?: e.javaClass.simpleName}"
                            }
                        }
                    }) { Text("Test") }
                }
                testResult?.let { Text(it, fontSize = 14.sp, color = c.muted) }
                HorizontalDivider(color = c.border)
                Row(
                    Modifier.fillMaxWidth().clickable { Nav.go(Route.ConnLog) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Connection log", fontSize = 15.sp, color = c.text)
                        Text("When the phone connected, dropped, or couldn't connect, and why", fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
                    }
                    Text("›", fontSize = 20.sp, color = c.faint)
                }
            }

            Section("Notifications") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Notify me", fontSize = 15.sp, color = c.text)
                        Text(
                            "When a session finishes, needs permission or asks a question. Muted while you're typing in that session on the laptop.",
                            fontSize = 13.sp, lineHeight = 18.sp, color = c.muted,
                        )
                    }
                    Spacer(Modifier.size(12.dp))
                    Switch(checked = notify, onCheckedChange = { notify = it; prefs.notifyEnabled = it }, colors = claudeSwitchColors())
                }
                HorizontalDivider(color = c.border)
                Text(
                    "Notification sounds and channels", fontSize = 15.sp, color = c.text,
                    modifier = Modifier.fillMaxWidth().clickable {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        )
                    }.padding(vertical = 4.dp),
                )
                if (batteryOk) {
                    Text("✓ Background connection allowed", fontSize = 13.sp, color = c.success)
                }
            }

            Section("Appearance") {
                val theme by ThemeMode.state
                Segmented(
                    options = listOf("system" to "System", "light" to "Light", "dark" to "Dark"),
                    selected = theme, enabled = true,
                ) { ThemeMode.set(it) }
            }

            Section("Keep the laptop awake") {
                val mode = keepAwake
                Segmented(
                    options = listOf("working" to "While working", "always" to "Always", "off" to "Off"),
                    selected = mode, enabled = mode != null,
                ) { m ->
                    awakeError = null
                    scope.launch {
                        try {
                            app.bridge.setKeepAwake(m)
                        } catch (e: Exception) {
                            awakeError = e.message ?: "Couldn't change it"
                        }
                    }
                }
                Text(
                    when (mode) {
                        null -> "Connect to the laptop to change this."
                        "always" -> "Closing the lid never suspends the laptop while the bridge runs. Turn this on before you leave home."
                        "off" -> "The laptop sleeps as usual when you close the lid."
                        else -> "Closing the lid won't suspend the laptop while Claude is working or waiting for you (up to 2 hours), plus 3 minutes after."
                    },
                    fontSize = 13.sp, lineHeight = 18.sp, color = c.muted,
                )
                if (mode != null) {
                    Text(
                        if (awake) "● Keeping it awake right now" else "○ Not holding it awake right now",
                        fontSize = 13.sp, color = if (awake) c.accentButton else c.faint,
                    )
                }
                awakeError?.let { Text(it, fontSize = 13.sp, color = c.danger) }
            }

            if (!batteryOk) {
                Notice(c.danger.copy(alpha = 0.08f), c.danger.copy(alpha = 0.3f)) {
                    Text("Battery optimization is on", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
                    Text(
                        "Android may cut the background connection, so notifications would arrive late or not at all.",
                        fontSize = 13.sp, lineHeight = 18.sp, color = c.muted, modifier = Modifier.padding(top = 2.dp),
                    )
                    Spacer(Modifier.size(10.dp))
                    PrimaryButton(onClick = {
                        ctx.startActivity(
                            Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        )
                    }) { Text("Allow background connection") }
                }
            }

            if (isXiaomi()) {
                Section("Autostart (Xiaomi / HyperOS)") {
                    Text(
                        "Turn Autostart on for Claude Mobile, otherwise HyperOS won't restart the connection after killing it or after a reboot. Also lock the app in Recents (long-press its card → lock).",
                        fontSize = 13.sp, lineHeight = 18.sp, color = c.muted,
                    )
                    SecondaryButton(onClick = { openAutostart(ctx) }) { Text("Open Autostart settings") }
                }
            }

            if (!updateFirst) AppSection()

            Text(
                "Away from home: install Tailscale on this phone and on the laptop, sign in to the same account, and use the laptop's Tailscale name as the address.",
                fontSize = 13.sp, lineHeight = 18.sp, color = c.faint, modifier = Modifier.padding(horizontal = 4.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    val c = Claude.colors
    Column {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.faint,
            modifier = Modifier.padding(start = 4.dp, bottom = 8.dp))
        Surface(shape = RoundedCornerShape(16.dp), color = c.surface, border = BorderStroke(1.dp, c.border)) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) { content() }
        }
    }
}

/** Version, and "Update to x.y" when the laptop has a newer build. */
@Composable
private fun AppSection() {
    val ctx = LocalContext.current
    val app = App.instance
    val c = Claude.colors
    val build by app.bridge.appBuild.collectAsState()
    val state by Updater.state.collectAsState()
    val host by app.bridge.host.collectAsState()
    var canInstall by remember { mutableStateOf(Updater.canInstall(ctx)) }
    LifecycleResumeEffect(Unit) {
        canInstall = Updater.canInstall(ctx)
        onPauseOrDispose { }
    }
    val installed = remember { Updater.installedCode(ctx) }
    val name = remember { Updater.installedName(ctx) }
    val b = build
    Section("App") {
        Text("Claude Mobile $name", fontSize = 15.sp, color = c.text)
        when (val st = state) {
            is UpdateState.Downloading -> {
                LinearProgressIndicator(
                    progress = { st.progress }, color = c.accentButton, trackColor = c.soft,
                    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
                )
                Text("Downloading ${b?.versionName.orEmpty()}… ${(st.progress * 100).toInt()}%", fontSize = 13.sp, color = c.muted)
            }
            UpdateState.Installing -> Text("Waiting for Android's installer…", fontSize = 13.sp, color = c.muted)
            else -> when {
                b == null -> Text("Connect to the laptop to check for updates.", fontSize = 13.sp, color = c.faint)
                b.versionCode > installed -> {
                    Text(
                        "Version ${b.versionName} is ready on ${host.ifBlank { "the laptop" }} (${"%.1f".format(b.size / 1048576.0)} MB).",
                        fontSize = 13.sp, lineHeight = 18.sp, color = c.muted,
                    )
                    if (!canInstall) {
                        Text("Android needs your OK once before Claude Mobile can install its own updates.",
                            fontSize = 13.sp, lineHeight = 18.sp, color = c.muted)
                        SecondaryButton(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}")))
                        }) { Text("Allow installing updates") }
                    } else {
                        PrimaryButton(onClick = { Updater.start(ctx, app.bridge, b) }) { Text("Update to ${b.versionName}") }
                    }
                }
                else -> Text("✓ Up to date", fontSize = 13.sp, color = c.success)
            }
        }
        (state as? UpdateState.Failed)?.let { Text(it.message, fontSize = 13.sp, color = c.danger) }
    }
}

/** claude.ai-style segmented control: ink fill for the chosen option. */
@Composable
internal fun Segmented(options: List<Pair<String, String>>, selected: String?, enabled: Boolean, onPick: (String) -> Unit) {
    val c = Claude.colors
    Row(
        Modifier.fillMaxWidth().background(c.soft, RoundedCornerShape(12.dp)).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        options.forEach { (key, label) ->
            val on = key == selected
            Box(
                Modifier.weight(1f).clip(RoundedCornerShape(9.dp))
                    .background(if (on) c.surface else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, c.border, RoundedCornerShape(9.dp)) else Modifier)
                    .clickable(enabled = enabled && !on) { onPick(key) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.Medium else FontWeight.Normal,
                    color = if (!enabled) c.faint else if (on) c.text else c.muted, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Notice(bg: Color, border: Color, content: @Composable () -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = bg, border = BorderStroke(1.dp, border)) {
        Column(Modifier.fillMaxWidth().padding(14.dp)) { content() }
    }
}

private fun isXiaomi() = Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true) ||
    Build.BRAND.lowercase() in setOf("xiaomi", "redmi", "poco")

private fun openAutostart(ctx: Context) {
    val candidates = listOf(
        Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
        Intent("miui.intent.action.APP_PERM_EDITOR").putExtra("extra_pkgname", ctx.packageName),
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")),
    )
    for (i in candidates) {
        try {
            ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: Exception) {
            // try the next one
        }
    }
}
