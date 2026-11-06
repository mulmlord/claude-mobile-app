package dev.mulm.claudemobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mulm.claudemobile.App
import dev.mulm.claudemobile.PastSession
import dev.mulm.claudemobile.Project
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NewSessionScreen() {
    val bridge = App.instance.bridge
    val c = Claude.colors
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val host by bridge.host.collectAsState()
    val now = rememberNow()
    var projects by remember { mutableStateOf<List<Project>?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var filter by rememberSaveable { mutableStateOf("") }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    var opening by remember { mutableStateOf(false) }
    val laptop = host.ifBlank { "the laptop" }

    suspend fun load() {
        loadError = null
        try {
            val p = bridge.projects()
            projects = p
            if (expanded == null) expanded = p.firstOrNull()?.cwd
        } catch (e: Exception) {
            loadError = e.message ?: "Couldn't load your projects"
        }
    }
    LaunchedEffect(Unit) { load() }

    fun open(cwd: String, resume: String?) {
        if (opening) return
        opening = true
        scope.launch {
            try {
                val sid = bridge.spawn(cwd, resume)
                // No id yet means it's still starting; it appears in the list once it connects.
                Nav.go(if (sid != null) Route.Chat(sid) else Route.Sessions)
            } catch (e: Exception) {
                opening = false
                snackbar.showSnackbar(e.message ?: "Couldn't start the session")
            }
        }
    }

    Scaffold(
        containerColor = c.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                colors = claudeTopBarColors(),
                navigationIcon = {
                    IconButton(onClick = { Nav.go(Route.Sessions) }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") }
                },
                title = { Text("New session", fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 22.sp) },
            )
        },
    ) { pad ->
        val list = projects
        when {
            loadError != null -> CenteredMessage(pad, "Couldn't load your projects", loadError!!) {
                SecondaryButton(onClick = { scope.launch { load() } }) { Text("Try again") }
            }
            list == null -> CenteredMessage(pad, "Loading your projects…", null, working = true)
            list.isEmpty() -> CenteredMessage(pad, "No projects yet", "Run claude in a folder on $laptop once and it shows up here.")
            else -> {
                val q = filter.trim()
                val shown = if (q.isEmpty()) list else list.filter { it.name.contains(q, true) || it.path.contains(q, true) }
                LazyColumn(
                    contentPadding = PaddingValues(
                        start = 16.dp, end = 16.dp,
                        top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item(key = "intro") {
                        Column(Modifier.padding(bottom = 6.dp)) {
                            Text(
                                "Opens a terminal on $laptop and connects it here, so you can carry on at the desk later.",
                                fontSize = 14.sp, lineHeight = 20.sp, color = c.muted,
                            )
                            if (list.size > 6) {
                                OutlinedTextField(
                                    value = filter, onValueChange = { filter = it }, singleLine = true,
                                    placeholder = { Text("Search folders", color = c.faint) },
                                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.faint) },
                                    shape = RoundedCornerShape(12.dp), colors = claudeFieldColors(),
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                )
                            }
                        }
                    }
                    items(shown, key = { it.cwd }) { p ->
                        ProjectCard(
                            p, now, expanded = expanded == p.cwd,
                            onToggle = { expanded = if (expanded == p.cwd) null else p.cwd },
                            onNew = { open(p.cwd, null) },
                            onPick = { s -> if (s.live != null) Nav.go(Route.Chat(s.live)) else open(p.cwd, s.sessionId) },
                        )
                    }
                    if (shown.isEmpty()) {
                        item(key = "none") {
                            Text("No folder matches “$q”.", color = c.faint, fontSize = 14.sp, modifier = Modifier.padding(8.dp))
                        }
                    }
                }
            }
        }
        if (opening) OpeningOverlay(laptop)
    }
}

@Composable
private fun ProjectCard(
    p: Project, now: Long, expanded: Boolean,
    onToggle: () -> Unit, onNew: () -> Unit, onPick: (PastSession) -> Unit,
) {
    val c = Claude.colors
    Surface(shape = RoundedCornerShape(14.dp), color = c.surface, border = BorderStroke(1.dp, c.border), modifier = Modifier.fillMaxWidth()) {
        Column {
            Row(
                Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier.size(36.dp).background(c.soft, RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(p.name.take(1).uppercase(), fontFamily = Serif, fontSize = 18.sp, fontWeight = FontWeight.Medium, color = c.accentButton)
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(p.name, fontSize = 15.5.sp, fontWeight = FontWeight.Medium, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${p.path} · ${ago(p.updated, now)}", fontSize = 12.5.sp, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp).rotate(turn), tint = c.faint)
            }
            AnimatedVisibility(visible = expanded) {
                Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                    PrimaryButton(onClick = onNew, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("New session here")
                    }
                    if (p.sessions.isNotEmpty()) {
                        Text(
                            "Recent", fontSize = 12.5.sp, fontWeight = FontWeight.Medium, color = c.faint,
                            modifier = Modifier.padding(top = 14.dp, bottom = 2.dp, start = 2.dp),
                        )
                        p.sessions.forEachIndexed { i, s ->
                            if (i > 0) HorizontalDivider(color = c.border)
                            PastRow(s, now) { onPick(s) }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun PastRow(s: PastSession, now: Long, onClick: () -> Unit) {
    val c = Claude.colors
    val blocked = s.running && s.live == null
    Row(
        Modifier.fillMaxWidth().clickable(enabled = !blocked, onClick = onClick).padding(horizontal = 2.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.title, fontFamily = Serif, fontSize = 15.sp, lineHeight = 21.sp,
                color = if (blocked) c.faint else c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(ago(s.updated, now), fontSize = 12.sp, color = c.faint)
        }
        Spacer(Modifier.width(10.dp))
        when {
            s.live != null -> Text("Open", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.accentButton)
            blocked -> Text("In a terminal", fontSize = 12.sp, color = c.faint)
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Resume", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = c.text)
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(18.dp), tint = c.faint)
            }
        }
    }
}

@Composable
private fun CenteredMessage(pad: PaddingValues, title: String, body: String?, working: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val c = Claude.colors
    Box(Modifier.fillMaxSize().padding(pad).padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spark(size = 40.dp, working = working)
            Spacer(Modifier.height(18.dp))
            Text(title, fontFamily = Serif, fontSize = 22.sp, color = c.text, textAlign = TextAlign.Center)
            if (body != null) {
                Spacer(Modifier.height(8.dp))
                Text(body, fontSize = 14.sp, lineHeight = 20.sp, color = c.muted, textAlign = TextAlign.Center)
            }
            if (action != null) {
                Spacer(Modifier.height(18.dp))
                action()
            }
        }
    }
}

@Composable
private fun OpeningOverlay(laptop: String) {
    val c = Claude.colors
    Box(
        Modifier.fillMaxSize().background(c.bg.copy(alpha = 0.94f))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(32.dp)) {
            Spark(size = 48.dp, working = true)
            Spacer(Modifier.height(20.dp))
            Text("Opening a terminal on $laptop…", fontFamily = Serif, fontSize = 22.sp, color = c.text, textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Text("You'll land in the chat as soon as it connects.", fontSize = 14.sp, color = c.muted, textAlign = TextAlign.Center)
        }
    }
}
