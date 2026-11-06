package dev.mulm.claudemobile.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mulm.claudemobile.App
import dev.mulm.claudemobile.GhComment
import dev.mulm.claudemobile.GhFile
import dev.mulm.claudemobile.GhIssue
import dev.mulm.claudemobile.GhLocal
import dev.mulm.claudemobile.GhPr
import dev.mulm.claudemobile.GhRepo
import dev.mulm.claudemobile.GhRun
import dev.mulm.claudemobile.GitHub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.time.Instant

// ---- small shared helpers ----------------------------------------------------------

fun isoAgo(iso: String, nowMs: Long): String =
    try { ago(Instant.parse(iso).toEpochMilli() / 1000.0, nowMs) } catch (e: Exception) { "" }

/** Data loaded for a screen, with error and a refresh counter. */
private class Loaded<T> {
    var data by mutableStateOf<T?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(true)
    var tick by mutableIntStateOf(0)
    fun refresh() { tick++ }
}

@Composable
private fun <T> rememberLoaded(vararg keys: Any?, load: suspend () -> T): Loaded<T> {
    val st = remember(*keys) { Loaded<T>() }
    LaunchedEffect(st, st.tick) {
        st.loading = true
        st.error = null
        try {
            st.data = load()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            st.error = e.message ?: "Failed"
        }
        st.loading = false
    }
    return st
}

/** Runs one GitHub action at a time; reports success or the error in the snackbar. */
private class Actions(val scope: CoroutineScope, val snackbar: SnackbarHostState) {
    var busy by mutableStateOf(false)
    fun run(done: String?, after: () -> Unit = {}, block: suspend () -> Unit) {
        if (busy) return
        scope.launch {
            busy = true
            try {
                block()
                after()
                done?.let { snackbar.showSnackbar(it) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed")
            } finally {
                busy = false
            }
        }
    }
}

@Composable
private fun rememberActions(snackbar: SnackbarHostState): Actions {
    val scope = rememberCoroutineScope()
    return remember { Actions(scope, snackbar) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GhTopBar(title: String, subtitle: String?, onBack: () -> Unit, actions: @Composable RowScope.() -> Unit = {}) {
    val c = Claude.colors
    Column {
        TopAppBar(
            colors = claudeTopBarColors(),
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            title = {
                Column {
                    Text(title, fontFamily = Serif, fontWeight = FontWeight.Medium, fontSize = 21.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (subtitle != null) Text(subtitle, fontSize = 12.5.sp, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            },
            actions = actions,
        )
        HorizontalDivider(color = c.border)
    }
}

@Composable
private fun Centered(title: String, body: String?, working: Boolean = false, action: (@Composable () -> Unit)? = null) {
    val c = Claude.colors
    Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 56.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Spark(size = 36.dp, working = working)
            Spacer(Modifier.height(16.dp))
            Text(title, fontFamily = Serif, fontSize = 20.sp, color = c.text, textAlign = TextAlign.Center)
            if (body != null) {
                Spacer(Modifier.height(6.dp))
                Text(body, fontSize = 13.5.sp, lineHeight = 19.sp, color = c.muted, textAlign = TextAlign.Center)
            }
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
        }
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val c = Claude.colors
    val shape = RoundedCornerShape(14.dp)
    if (onClick != null) {
        Surface(onClick = onClick, shape = shape, color = c.surface, border = BorderStroke(1.dp, c.border),
            modifier = modifier.fillMaxWidth()) { content() }
    } else {
        Surface(shape = shape, color = c.surface, border = BorderStroke(1.dp, c.border), modifier = modifier.fillMaxWidth()) { content() }
    }
}

@Composable
private fun Label(text: String, modifier: Modifier = Modifier) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = Claude.colors.faint,
        modifier = modifier.padding(start = 2.dp, top = 6.dp, bottom = 2.dp))
}

@Composable
private fun Tag(text: String, fg: Color, bg: Color = fg.copy(alpha = 0.12f)) {
    Box(Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = fg, maxLines = 1)
    }
}

@Composable
private fun ciColor(state: String?): Color {
    val c = Claude.colors
    return when (state?.uppercase()) {
        "SUCCESS" -> c.success
        "FAILURE", "ERROR", "TIMED_OUT", "STARTUP_FAILURE", "ACTION_REQUIRED" -> c.danger
        "PENDING", "EXPECTED", "QUEUED", "IN_PROGRESS" -> c.accent
        else -> c.faint
    }
}

@Composable
private fun CiDot(state: String?, label: Boolean = true) {
    if (state == null) return
    val col = ciColor(state)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(7.dp).background(col, RoundedCornerShape(50)))
        if (label) {
            Spacer(Modifier.width(5.dp))
            Text(
                when (state.uppercase()) { "SUCCESS" -> "Checks pass"; "PENDING", "EXPECTED" -> "Checks running"; else -> "Checks fail" },
                fontSize = 12.5.sp, color = col,
            )
        }
    }
}

@Composable
private fun ReviewTag(review: String) {
    val c = Claude.colors
    when (review) {
        "APPROVED" -> Tag("Approved", c.success)
        "CHANGES_REQUESTED" -> Tag("Changes requested", c.danger)
        "REVIEW_REQUIRED" -> Tag("Review required", c.muted, c.soft)
    }
}

@Composable
private fun StateTag(state: String, draft: Boolean = false) {
    val c = Claude.colors
    when {
        draft && state == "OPEN" -> Tag("Draft", c.muted, c.soft)
        state == "OPEN" -> Tag("Open", c.success)
        state == "MERGED" -> Tag("Merged", c.accentButton)
        state == "CLOSED" -> Tag("Closed", c.danger)
        else -> Tag(state.lowercase().replaceFirstChar { it.uppercase() }, c.muted, c.soft)
    }
}

@Composable
private fun Overflow(items: List<Pair<String, () -> Unit>>) {
    if (items.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) { Icon(Icons.Filled.MoreVert, "More") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = Claude.colors.surface) {
            items.forEach { (label, action) ->
                DropdownMenuItem(text = { Text(label, fontSize = 14.5.sp, color = Claude.colors.text) },
                    onClick = { open = false; action() })
            }
        }
    }
}

// ---- dialogs -------------------------------------------------------------------------

@Composable
private fun TextDialog(
    title: String, label: String, confirm: String, onDismiss: () -> Unit, initial: String = "",
    required: Boolean = true, onConfirm: (String) -> Unit,
) {
    val c = Claude.colors
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface,
        title = { Text(title, fontFamily = Serif, fontSize = 21.sp, color = c.text) },
        text = {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, label = { Text(label) }, minLines = 4, maxLines = 10,
                shape = RoundedCornerShape(12.dp), colors = claudeFieldColors(), modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = !required || text.isNotBlank()) {
                Text(confirm, color = if (!required || text.isNotBlank()) c.accentButton else c.faint, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
    )
}

@Composable
private fun ConfirmDialog(title: String, body: String, confirm: String, danger: Boolean = true, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    val c = Claude.colors
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface,
        title = { Text(title, fontFamily = Serif, fontSize = 21.sp, color = c.text) },
        text = { Text(body, fontSize = 14.sp, lineHeight = 20.sp, color = c.muted) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(confirm, color = if (danger) c.danger else c.accentButton, fontWeight = FontWeight.Medium) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
    )
}

/** Opens a session on the laptop in the repo's clone, seeded with an editable prompt. */
@Composable
private fun AskClaudeDialog(title: String, initial: String, cwd: String?, onDismiss: () -> Unit) {
    val c = Claude.colors
    val scope = rememberCoroutineScope()
    var prompt by remember { mutableStateOf(initial) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() }, containerColor = c.surface,
        icon = { Spark(size = 26.dp, working = busy) },
        title = { Text(title, fontFamily = Serif, fontSize = 21.sp, color = c.text, textAlign = TextAlign.Center) },
        text = {
            Column {
                if (cwd == null) {
                    Text("This repository isn't cloned on the laptop. Clone it from the Laptop tab first.",
                        fontSize = 14.sp, lineHeight = 20.sp, color = c.muted)
                } else {
                    Text("Starts a session in $cwd with this message:", fontSize = 13.sp, color = c.faint)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = prompt, onValueChange = { prompt = it }, minLines = 5, maxLines = 12,
                        shape = RoundedCornerShape(12.dp), colors = claudeFieldColors(), modifier = Modifier.fillMaxWidth(),
                    )
                }
                error?.let { Text(it, fontSize = 13.sp, color = c.danger, modifier = Modifier.padding(top = 8.dp)) }
            }
        },
        confirmButton = {
            if (cwd != null) {
                TextButton(enabled = !busy && prompt.isNotBlank(), onClick = {
                    busy = true
                    error = null
                    scope.launch {
                        try {
                            val sid = App.instance.bridge.spawn(cwd, prompt = prompt.trim())
                            Nav.go(if (sid != null) Route.Chat(sid) else Route.Sessions)
                        } catch (e: Exception) {
                            error = e.message ?: "Couldn't start the session"
                            busy = false
                        }
                    }
                }) { Text(if (busy) "Starting…" else "Start session", color = c.accentButton, fontWeight = FontWeight.Medium) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, enabled = !busy) { Text(if (cwd == null) "OK" else "Cancel", color = c.muted) } },
    )
}

// ---- GitHub home ---------------------------------------------------------------------

@Composable
fun GitHubScreen() {
    val c = Claude.colors
    val host by App.instance.bridge.host.collectAsState()
    val now = rememberNow()
    var fresh by remember { mutableStateOf(false) }
    val st = rememberLoaded("repos") { GitHub.repos(fresh).also { fresh = false } }
    var filter by rememberSaveable { mutableStateOf("") }
    Scaffold(
        containerColor = c.bg,
        topBar = {
            GhTopBar("GitHub", null, onBack = { Nav.go(Route.Sessions) }) {
                IconButton(onClick = { fresh = true; st.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
            }
        },
    ) { pad ->
        val d = st.data
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 12.dp,
                bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                d == null && st.error != null -> item {
                    Centered("Couldn't reach GitHub", st.error) { SecondaryButton(onClick = { st.refresh() }) { Text("Try again") } }
                }
                d == null -> item { Centered("Loading your repositories…", null, working = true) }
                else -> {
                    val (login, repos) = d
                    val q = filter.trim()
                    val shown = if (q.isEmpty()) repos else repos.filter { it.repo.contains(q, true) || it.description.contains(q, true) }
                    item(key = "intro") {
                        Column {
                            Text("Signed in as @$login, through gh on ${host.ifBlank { "the laptop" }}.", fontSize = 13.5.sp, color = c.muted)
                            if (repos.size > 6) {
                                OutlinedTextField(
                                    value = filter, onValueChange = { filter = it }, singleLine = true,
                                    placeholder = { Text("Search repositories", color = c.faint) },
                                    leadingIcon = { Icon(Icons.Filled.Search, null, tint = c.faint) },
                                    shape = RoundedCornerShape(12.dp), colors = claudeFieldColors(),
                                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
                                )
                            }
                        }
                    }
                    items(shown, key = { it.repo }) { r -> RepoCard(r, login, now) { Nav.go(Route.Repo(r.repo)) } }
                }
            }
        }
    }
}

@Composable
private fun RepoCard(r: GhRepo, login: String, now: Long, onClick: () -> Unit) {
    val c = Claude.colors
    Card(onClick = onClick) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (r.owner.equals(login, true)) r.name else r.repo, fontSize = 15.5.sp, fontWeight = FontWeight.Medium,
                    color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                if (r.private) { Spacer(Modifier.width(8.dp)); Tag("Private", c.muted, c.soft) }
                if (r.archived) { Spacer(Modifier.width(6.dp)); Tag("Archived", c.muted, c.soft) }
            }
            if (r.description.isNotBlank()) {
                Text(r.description, fontSize = 13.5.sp, lineHeight = 19.sp, color = c.muted, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            }
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${r.prs} PR${if (r.prs == 1) "" else "s"}", fontSize = 12.5.sp, color = if (r.prs > 0) c.text else c.faint)
                Text("${r.issues} issue${if (r.issues == 1) "" else "s"}", fontSize = 12.5.sp, color = if (r.issues > 0) c.text else c.faint)
                CiDot(r.ci, label = false)
                if (r.local != null) Text("On laptop", fontSize = 12.5.sp, color = c.accentButton)
                Spacer(Modifier.weight(1f))
                Text(isoAgo(r.pushed, now), fontSize = 12.sp, color = c.faint)
            }
        }
    }
}

// ---- one repository ------------------------------------------------------------------

@Composable
fun RepoScreen(repo: String, initialTab: String) {
    val c = Claude.colors
    var tab by rememberSaveable(repo) { mutableStateOf(initialTab) }
    var refresh by remember { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val acts = rememberActions(snackbar)
    Scaffold(
        containerColor = c.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            GhTopBar(repo.substringAfter('/'), repo.substringBefore('/'), onBack = { Nav.go(Route.GitHub) }) {
                IconButton(onClick = { refresh++ }) { Icon(Icons.Filled.Refresh, "Refresh") }
            }
        },
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(top = pad.calculateTopPadding())) {
            Box(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                Segmented(
                    options = listOf("prs" to "PRs", "issues" to "Issues", "runs" to "Actions", "local" to "Laptop"),
                    selected = tab, enabled = true,
                ) { tab = it }
            }
            val bottom = pad.calculateBottomPadding() + 24.dp
            when (tab) {
                "prs" -> PrList(repo, refresh, bottom)
                "issues" -> IssueList(repo, refresh, bottom, acts)
                "runs" -> RunList(repo, refresh, bottom, acts)
                else -> LocalPanel(repo, refresh, bottom, acts) { refresh++ }
            }
        }
    }
}

@Composable
private fun <T> TabList(
    st: Loaded<List<T>>, empty: String, bottom: androidx.compose.ui.unit.Dp,
    header: (LazyListScope.() -> Unit)? = null, content: LazyListScope.(List<T>) -> Unit,
) {
    val c = Claude.colors
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        header?.invoke(this)
        val d = st.data
        when {
            d == null && st.error != null -> item {
                Centered("Couldn't load this", st.error) { SecondaryButton(onClick = { st.refresh() }) { Text("Try again") } }
            }
            d == null -> item { Centered("Loading…", null, working = true) }
            d.isEmpty() -> item { Text(empty, fontSize = 14.sp, color = c.faint, modifier = Modifier.padding(8.dp)) }
            else -> content(d)
        }
    }
}

@Composable
private fun PrList(repo: String, refresh: Int, bottom: androidx.compose.ui.unit.Dp) {
    val now = rememberNow()
    val st = rememberLoaded(repo, refresh) { GitHub.prs(repo) }
    TabList(st, "No open pull requests.", bottom) { list ->
        items(list, key = { it.number }) { pr -> PrRow(pr, now) { Nav.go(Route.Pr(repo, pr.number)) } }
    }
}

@Composable
private fun PrRow(pr: GhPr, now: Long, onClick: () -> Unit) {
    val c = Claude.colors
    Card(onClick = onClick) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Text(pr.title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = c.text, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text("#${pr.number} · ${pr.head} → ${pr.base} · @${pr.author} · ${isoAgo(pr.updated, now)}", fontSize = 12.5.sp,
                color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (pr.draft) Tag("Draft", c.muted, c.soft)
                CiDot(pr.checks)
                ReviewTag(pr.review)
                Spacer(Modifier.weight(1f))
                Text("+${pr.add}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.success)
                Text("−${pr.del}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.danger)
            }
        }
    }
}

@Composable
private fun IssueList(repo: String, refresh: Int, bottom: androidx.compose.ui.unit.Dp, acts: Actions) {
    val now = rememberNow()
    val st = rememberLoaded(repo, refresh) { GitHub.issues(repo) }
    var newIssue by remember { mutableStateOf(false) }
    TabList(st, "No open issues.", bottom, header = {
        item(key = "new") {
            PrimaryButton(onClick = { newIssue = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("New issue")
            }
        }
    }) { list ->
        items(list, key = { it.number }) { i -> IssueRow(i, now) { Nav.go(Route.Issue(repo, i.number)) } }
    }
    if (newIssue) NewIssueDialog(onDismiss = { newIssue = false }) { title, body ->
        newIssue = false
        acts.run(null) {
            val n = GitHub.createIssue(repo, title, body)
            if (n != null) Nav.go(Route.Issue(repo, n)) else st.refresh()
        }
    }
}

@Composable
private fun NewIssueDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
    val c = Claude.colors
    var title by remember { mutableStateOf("") }
    var body by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface,
        title = { Text("New issue", fontFamily = Serif, fontSize = 21.sp, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Title") }, singleLine = true,
                    shape = RoundedCornerShape(12.dp), colors = claudeFieldColors(), modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = body, onValueChange = { body = it }, label = { Text("Description (Markdown)") },
                    minLines = 4, maxLines = 10, shape = RoundedCornerShape(12.dp), colors = claudeFieldColors(),
                    modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(title.trim(), body.trim()) }, enabled = title.isNotBlank()) {
                Text("Create", color = if (title.isNotBlank()) c.accentButton else c.faint, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
    )
}

@Composable
private fun IssueRow(i: GhIssue, now: Long, onClick: () -> Unit) {
    val c = Claude.colors
    Card(onClick = onClick) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 13.dp)) {
            Text(i.title, fontSize = 15.sp, lineHeight = 21.sp, fontWeight = FontWeight.Medium, color = c.text, maxLines = 2,
                overflow = TextOverflow.Ellipsis)
            Text("#${i.number} · @${i.author} · ${isoAgo(i.updated, now)}" + if (i.comments > 0) " · ${i.comments} comment${if (i.comments == 1) "" else "s"}" else "",
                fontSize = 12.5.sp, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 3.dp))
            if (i.labels.isNotEmpty()) {
                Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    i.labels.forEach { Tag(it, c.muted, c.soft) }
                }
            }
        }
    }
}

@Composable
private fun RunList(repo: String, refresh: Int, bottom: androidx.compose.ui.unit.Dp, acts: Actions) {
    val now = rememberNow()
    val st = rememberLoaded(repo, refresh) { GitHub.runs(repo) }
    val local = rememberLoaded(repo, "local-path") { GitHub.local(repo) }
    var ask by remember { mutableStateOf<GhRun?>(null) }
    TabList(st, "No workflow runs.", bottom) { list ->
        items(list, key = { it.id }) { r ->
            RunRow(r, now, repo, acts, onRerun = { acts.run("Re-running the failed jobs", after = { st.refresh() }) { GitHub.rerun(repo, r.id) } },
                onAsk = { ask = r })
        }
    }
    ask?.let { r ->
        AskClaudeDialog(
            "Ask Claude to fix this",
            "The GitHub Actions run \"${r.workflow}\" on branch ${r.branch} failed (run ${r.id}). " +
                "Read the log with `gh run view ${r.id} --log-failed`, find the cause and fix it.",
            local.data?.takeIf { it.cloned }?.cwd,
        ) { ask = null }
    }
}

@Composable
private fun RunRow(r: GhRun, now: Long, repo: String, acts: Actions, onRerun: () -> Unit, onAsk: () -> Unit) {
    val c = Claude.colors
    var open by rememberSaveable(r.id) { mutableStateOf(false) }
    var log by remember { mutableStateOf<String?>(null) }
    val failed = r.conclusion in setOf("failure", "cancelled", "timed_out", "startup_failure")
    val running = r.status != "completed"
    Card {
        Column {
            Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(18.dp), contentAlignment = Alignment.Center) {
                    when {
                        running -> CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 1.6.dp, color = c.accent)
                        r.conclusion == "success" -> Text("✓", color = c.success, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                        failed -> Text("✕", color = c.danger, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        else -> Text("–", color = c.faint, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${r.workflow} · ${r.title}", fontSize = 14.5.sp, fontWeight = FontWeight.Medium, color = c.text,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${r.branch} · ${r.event} · ${isoAgo(r.created, now)}", fontSize = 12.5.sp, color = c.faint, maxLines = 1,
                        overflow = TextOverflow.Ellipsis)
                }
                val turn by animateFloatAsState(if (open) 180f else 0f, label = "chev")
                Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(20.dp).rotate(turn), tint = c.faint)
            }
            AnimatedVisibility(visible = open) {
                Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (failed) SecondaryButton(onClick = onRerun, enabled = !acts.busy) { Text("Re-run failed") }
                        SecondaryButton(onClick = {
                            if (log == null) acts.run(null) { log = GitHub.runLog(repo, r.id) } else log = null
                        }) { Text(if (log == null) "Show log" else "Hide log") }
                    }
                    if (failed) PrimaryButton(onClick = onAsk, modifier = Modifier.fillMaxWidth()) { Text("Ask Claude to fix it") }
                    log?.let {
                        SelectionContainer {
                            Text(it.ifBlank { "(empty)" }, fontFamily = FontFamily.Monospace, fontSize = 10.5.sp, lineHeight = 13.sp,
                                color = c.terminalText,
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.terminalBg)
                                    .heightIn(max = 360.dp).verticalScroll(rememberScrollState()).padding(10.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun LocalPanel(repo: String, refresh: Int, bottom: androidx.compose.ui.unit.Dp, acts: Actions, reload: () -> Unit) {
    val c = Claude.colors
    val st = rememberLoaded(repo, refresh) { GitHub.local(repo) }
    var confirmPush by remember { mutableStateOf(false) }
    var newSession by remember { mutableStateOf(false) }
    val d = st.data
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = bottom),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        when {
            d == null && st.error != null -> item { Centered("Couldn't read the clone", st.error) { SecondaryButton(onClick = { st.refresh() }) { Text("Try again") } } }
            d == null -> item { Centered("Loading…", null, working = true) }
            !d.cloned -> item {
                Centered("Not on the laptop yet", "Clone it to ${d.suggested} to work on it with Claude.") {
                    PrimaryButton(onClick = { acts.run("Cloned", after = reload) { GitHub.localAction(repo, "clone") } }, enabled = !acts.busy) {
                        Text(if (acts.busy) "Cloning…" else "Clone to the laptop")
                    }
                }
            }
            else -> {
                item(key = "head") { LocalHead(d, acts, onPull = { acts.run("Pulled", after = reload) { GitHub.localAction(repo, "pull") } },
                    onPush = { confirmPush = true },
                    onPr = { acts.run("Pull request opened", after = reload) { GitHub.localAction(repo, "pr") } },
                    onSession = { newSession = true }) }
                item(key = "changes-label") { Label(if (d.changeCount == 0) "Working tree clean" else "Uncommitted changes · ${d.changeCount}") }
                if (d.changes.isNotEmpty()) item(key = "changes") {
                    Card {
                        Column(Modifier.padding(vertical = 6.dp)) {
                            d.changes.forEach { ch ->
                                Row(Modifier.padding(horizontal = 14.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(ch.status, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, fontWeight = FontWeight.Bold,
                                        color = when (ch.status) { "A", "?" -> c.success; "D" -> c.danger; "U" -> c.accentButton; else -> c.accent },
                                        modifier = Modifier.width(18.dp))
                                    Text(ch.path, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = c.text, maxLines = 1,
                                        overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
                item(key = "commits-label") { Label("Recent commits") }
                item(key = "commits") {
                    Card {
                        Column {
                            d.commits.forEachIndexed { i, cm ->
                                if (i > 0) HorizontalDivider(color = c.border)
                                Column(Modifier.padding(horizontal = 14.dp, vertical = 9.dp)) {
                                    Text(cm.subject, fontSize = 14.sp, lineHeight = 19.sp, color = c.text, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    Text("${cm.hash} · ${cm.time} · ${cm.author}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.faint)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    if (confirmPush && d != null) {
        ConfirmDialog(
            "Push ${d.branch}?",
            if (d.upstream.isBlank()) "Publishes the branch to GitHub as origin/${d.branch}." else "Sends ${d.ahead} commit${if (d.ahead == 1) "" else "s"} to ${d.upstream}.",
            "Push", danger = false, onDismiss = { confirmPush = false },
        ) {
            confirmPush = false
            acts.run("Pushed", after = reload) { GitHub.localAction(repo, "push") }
        }
    }
    if (newSession && d != null) {
        AskClaudeDialog("New session in ${repo.substringAfter('/')}", "", d.cwd) { newSession = false }
    }
}

@Composable
private fun LocalHead(d: GhLocal, acts: Actions, onPull: () -> Unit, onPush: () -> Unit, onPr: () -> Unit, onSession: () -> Unit) {
    val c = Claude.colors
    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column {
                Text(d.path, fontFamily = FontFamily.Monospace, fontSize = 13.sp, color = c.muted)
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                    Icon(BranchIcon, null, Modifier.size(16.dp), tint = c.accentButton)
                    Spacer(Modifier.width(6.dp))
                    Text(d.branch, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = c.text)
                    if (d.upstream.isNotBlank()) Text("  → ${d.upstream}", fontSize = 13.sp, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    when {
                        d.upstream.isBlank() -> "Not on GitHub yet"
                        d.ahead == 0 && d.behind == 0 -> "Up to date with GitHub"
                        else -> listOfNotNull(
                            if (d.ahead > 0) "${d.ahead} to push" else null,
                            if (d.behind > 0) "${d.behind} to pull" else null,
                        ).joinToString(" · ")
                    },
                    fontSize = 13.sp, color = if (d.ahead > 0 || d.behind > 0) c.accentButton else c.faint, modifier = Modifier.padding(top = 2.dp),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(onClick = onPull, enabled = !acts.busy && d.upstream.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Pull") }
                SecondaryButton(onClick = onPush, enabled = !acts.busy && (d.ahead > 0 || d.upstream.isBlank()), modifier = Modifier.weight(1f)) { Text("Push") }
                SecondaryButton(onClick = onPr, enabled = !acts.busy, modifier = Modifier.weight(1f)) { Text("Open PR") }
            }
            PrimaryButton(onClick = onSession, modifier = Modifier.fillMaxWidth()) { Text("New session here") }
        }
    }
}

// ---- pull request --------------------------------------------------------------------

@Composable
fun PrScreen(repo: String, number: Int) {
    val c = Claude.colors
    val now = rememberNow()
    val uri = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    val acts = rememberActions(snackbar)
    val st = rememberLoaded(repo, number) { GitHub.pr(repo, number) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val pr = st.data
    Scaffold(
        containerColor = c.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            GhTopBar("Pull request #$number", repo, onBack = { Nav.back() }) {
                IconButton(onClick = { st.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                if (pr != null) Overflow(listOfNotNull(
                    "Ask Claude to review" to { dialog = "ask-review" },
                    if (pr.checksState == "FAILURE") "Ask Claude to fix the checks" to { dialog = "ask-fix" } else null,
                    if (pr.draft && pr.state == "OPEN") "Mark ready for review" to { acts.run("Marked ready", after = { st.refresh() }) { GitHub.prState(repo, number, "ready") } } else null,
                    if (pr.state == "OPEN") "Close pull request" to { dialog = "close" } else null,
                    if (pr.state == "CLOSED") "Reopen pull request" to { acts.run("Reopened", after = { st.refresh() }) { GitHub.prState(repo, number, "reopen") } } else null,
                    if (pr.url.isNotBlank()) "Open on GitHub" to { uri.openUri(pr.url) } else null,
                ))
            }
        },
        bottomBar = {
            if (pr != null && pr.state == "OPEN") {
                ActionBar {
                    SecondaryButton(onClick = { dialog = "comment" }, enabled = !acts.busy, modifier = Modifier.weight(1f)) { Text("Comment") }
                    SecondaryButton(onClick = { dialog = "review" }, enabled = !acts.busy, modifier = Modifier.weight(1f)) { Text("Review") }
                    PrimaryButton(onClick = { dialog = "merge" }, enabled = !acts.busy && !pr.draft, modifier = Modifier.weight(1f)) { Text("Merge") }
                }
            }
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 14.dp,
                bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                pr == null && st.error != null -> item { Centered("Couldn't load the pull request", st.error) { SecondaryButton(onClick = { st.refresh() }) { Text("Try again") } } }
                pr == null -> item { Centered("Loading…", null, working = true) }
                else -> {
                    item(key = "head") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(pr.title, fontFamily = Serif, fontSize = 22.sp, lineHeight = 29.sp, color = c.text)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                StateTag(pr.state, pr.draft)
                                ReviewTag(pr.review)
                                CiDot(pr.checksState)
                            }
                            Text("${pr.head} → ${pr.base} · @${pr.author} · opened ${isoAgo(pr.created, now)}", fontSize = 13.sp, color = c.faint)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Text("+${pr.add}", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = c.success)
                                Text("−${pr.del}", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = c.danger)
                                Text("· ${pr.changedFiles} file${if (pr.changedFiles == 1) "" else "s"}", fontSize = 13.sp, color = c.faint)
                            }
                            if (pr.state == "OPEN") MergeLine(pr.mergeable, pr.mergeState)
                        }
                    }
                    if (pr.body.isNotBlank()) item(key = "body") { Card { Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) { AssistantText(pr.body) } } }
                    if (pr.checks.isNotEmpty()) {
                        item(key = "checks-label") { Label("Checks") }
                        item(key = "checks") {
                            Card {
                                Column(Modifier.padding(vertical = 6.dp)) {
                                    pr.checks.forEach { ch ->
                                        Row(Modifier.padding(horizontal = 14.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Box(Modifier.size(8.dp).background(ciColor(ch.state), RoundedCornerShape(50)))
                                            Spacer(Modifier.width(10.dp))
                                            Text(ch.name, fontSize = 14.sp, color = c.text, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            Text(ch.state.lowercase(), fontSize = 12.5.sp, color = ciColor(ch.state))
                                        }
                                    }
                                }
                            }
                        }
                    }
                    item(key = "files-label") { Label("Files changed · ${pr.changedFiles}") }
                    if (pr.diffError.isNotBlank()) item(key = "diff-error") { Text(pr.diffError, fontSize = 13.sp, color = c.danger) }
                    items(pr.files, key = { "f:" + it.path }) { f -> FileDiff(f) }
                    if (pr.files.size < pr.changedFiles && pr.diffError.isBlank()) {
                        item(key = "more-files") { Text("Only part of the diff is shown here.", fontSize = 12.5.sp, color = c.faint) }
                    }
                    if (pr.conversation.isNotEmpty()) {
                        item(key = "convo-label") { Label("Conversation") }
                        items(pr.conversation.withIndex().toList(), key = { "c:" + it.index }) { (_, cm) -> CommentCard(cm, now) }
                    }
                }
            }
        }
    }
    val p = pr ?: return
    when (dialog) {
        "comment" -> TextDialog("Comment on #$number", "Comment (Markdown)", "Comment", onDismiss = { dialog = null }) { t ->
            dialog = null
            acts.run("Comment posted", after = { st.refresh() }) { GitHub.prComment(repo, number, t) }
        }
        "review" -> ReviewDialog(onDismiss = { dialog = null }) { event, body ->
            dialog = null
            acts.run(if (event == "approve") "Approved" else "Review sent", after = { st.refresh() }) { GitHub.review(repo, number, event, body) }
        }
        "merge" -> MergeDialog(p.head, p.base, onDismiss = { dialog = null }) { method, delete ->
            dialog = null
            acts.run("Merged", after = { st.refresh() }) { GitHub.merge(repo, number, method, delete) }
        }
        "close" -> ConfirmDialog("Close #$number?", "Closes the pull request without merging. You can reopen it later.", "Close",
            onDismiss = { dialog = null }) {
            dialog = null
            acts.run("Closed", after = { st.refresh() }) { GitHub.prState(repo, number, "close") }
        }
        "ask-review" -> AskClaudeDialog("Ask Claude to review",
            "Please review pull request #$number in $repo (\"${p.title}\", ${p.head} → ${p.base}). " +
                "Read it with `gh pr view $number --comments` and `gh pr diff $number`, then tell me what you think: " +
                "bugs, risks, and anything to change before merging.", p.local) { dialog = null }
        "ask-fix" -> AskClaudeDialog("Ask Claude to fix the checks",
            "The checks on pull request #$number in $repo are failing. Look at them with `gh pr checks $number` and the " +
                "failed logs, then fix the problem on branch ${p.head}.", p.local) { dialog = null }
    }
}

@Composable
private fun MergeLine(mergeable: String, state: String) {
    val c = Claude.colors
    val (text, col) = when {
        mergeable == "CONFLICTING" || state == "DIRTY" -> "Has conflicts with the base branch" to c.danger
        state == "CLEAN" || state == "HAS_HOOKS" -> "Ready to merge" to c.success
        state == "BLOCKED" -> "Blocked by required reviews or checks" to c.accentButton
        state == "BEHIND" -> "Behind the base branch" to c.accentButton
        state == "UNSTABLE" -> "Mergeable, but some checks fail" to c.accentButton
        else -> "GitHub is still checking whether it can merge" to c.faint
    }
    Text(text, fontSize = 13.sp, color = col)
}

@Composable
private fun ActionBar(content: @Composable RowScope.() -> Unit) {
    val c = Claude.colors
    Column(Modifier.background(c.bg).windowInsetsPadding(WindowInsets.navigationBars)) {
        HorizontalDivider(color = c.border)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun FileDiff(f: GhFile) {
    val c = Claude.colors
    var open by rememberSaveable(f.path) { mutableStateOf(false) }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.border, shape).background(c.surface)) {
        Row(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(f.path, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, color = c.text, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Spacer(Modifier.width(8.dp))
            Text("+${f.add}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.success)
            Text(" −${f.del}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.danger)
            val turn by animateFloatAsState(if (open) 180f else 0f, label = "chev")
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp).rotate(turn), tint = c.faint)
        }
        AnimatedVisibility(visible = open) {
            Column {
                HorizontalDivider(color = c.border)
                if (f.lines.isEmpty()) Text("No text changes", fontSize = 12.5.sp, color = c.faint, modifier = Modifier.padding(12.dp))
                else DiffView(f.lines)
            }
        }
    }
}

@Composable
private fun CommentCard(cm: GhComment, now: Long) {
    val c = Claude.colors
    Card {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("@${cm.author}", fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = c.text)
                Spacer(Modifier.width(8.dp))
                when (cm.state) {
                    "APPROVED" -> Tag("approved", c.success)
                    "CHANGES_REQUESTED" -> Tag("requested changes", c.danger)
                    "COMMENTED" -> Tag("reviewed", c.muted, c.soft)
                }
                Spacer(Modifier.weight(1f))
                Text(isoAgo(cm.at, now), fontSize = 12.sp, color = c.faint)
            }
            if (cm.body.isNotBlank()) AssistantText(cm.body)
        }
    }
}

@Composable
private fun ReviewDialog(onDismiss: () -> Unit, onSend: (String, String) -> Unit) {
    val c = Claude.colors
    var event by remember { mutableStateOf("comment") }
    var body by remember { mutableStateOf("") }
    val ok = event == "approve" || body.isNotBlank()
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface,
        title = { Text("Review", fontFamily = Serif, fontSize = 21.sp, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Segmented(listOf("comment" to "Comment", "approve" to "Approve", "request_changes" to "Changes"), event, true) { event = it }
                OutlinedTextField(value = body, onValueChange = { body = it },
                    label = { Text(if (event == "approve") "Message (optional)" else "Message") },
                    minLines = 4, maxLines = 10, shape = RoundedCornerShape(12.dp), colors = claudeFieldColors(), modifier = Modifier.fillMaxWidth())
                Text("You can't approve or request changes on your own pull request.", fontSize = 12.sp, color = c.faint)
            }
        },
        confirmButton = {
            TextButton(onClick = { onSend(event, body.trim()) }, enabled = ok) {
                Text("Send review", color = if (ok) c.accentButton else c.faint, fontWeight = FontWeight.Medium)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
    )
}

@Composable
private fun MergeDialog(head: String, base: String, onDismiss: () -> Unit, onMerge: (String, Boolean) -> Unit) {
    val c = Claude.colors
    var method by remember { mutableStateOf("squash") }
    var delete by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss, containerColor = c.surface,
        title = { Text("Merge into $base?", fontFamily = Serif, fontSize = 21.sp, color = c.text) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Segmented(listOf("squash" to "Squash", "merge" to "Merge", "rebase" to "Rebase"), method, true) { method = it }
                Text(
                    when (method) {
                        "squash" -> "All commits of $head become one commit on $base."
                        "merge" -> "Adds a merge commit that keeps every commit of $head."
                        else -> "Replays the commits of $head on top of $base."
                    },
                    fontSize = 13.5.sp, lineHeight = 19.sp, color = c.muted,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { delete = !delete }) {
                    Checkbox(checked = delete, onCheckedChange = { delete = it },
                        colors = CheckboxDefaults.colors(checkedColor = c.accentButton, uncheckedColor = c.borderStrong))
                    Text("Delete $head afterwards", fontSize = 14.sp, color = c.text)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onMerge(method, delete) }) { Text("Merge", color = c.accentButton, fontWeight = FontWeight.Medium) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel", color = c.muted) } },
    )
}

// ---- issue ------------------------------------------------------------------------------

@Composable
fun IssueScreen(repo: String, number: Int) {
    val c = Claude.colors
    val now = rememberNow()
    val uri = LocalUriHandler.current
    val snackbar = remember { SnackbarHostState() }
    val acts = rememberActions(snackbar)
    val st = rememberLoaded(repo, number) { GitHub.issue(repo, number) }
    var dialog by remember { mutableStateOf<String?>(null) }
    val issue = st.data
    Scaffold(
        containerColor = c.bg,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            GhTopBar("Issue #$number", repo, onBack = { Nav.back() }) {
                IconButton(onClick = { st.refresh() }) { Icon(Icons.Filled.Refresh, "Refresh") }
                if (issue != null && issue.url.isNotBlank()) Overflow(listOf("Open on GitHub" to { uri.openUri(issue.url) }))
            }
        },
        bottomBar = {
            if (issue != null) {
                ActionBar {
                    SecondaryButton(onClick = { dialog = "comment" }, enabled = !acts.busy, modifier = Modifier.weight(1f)) { Text("Comment") }
                    if (issue.state == "OPEN") {
                        SecondaryButton(onClick = { dialog = "close" }, enabled = !acts.busy, modifier = Modifier.weight(1f)) { Text("Close") }
                        PrimaryButton(onClick = { dialog = "ask" }, enabled = !acts.busy, modifier = Modifier.weight(1.3f)) { Text("Work on it") }
                    } else {
                        SecondaryButton(onClick = { acts.run("Reopened", after = { st.refresh() }) { GitHub.issueState(repo, number, "reopen") } },
                            enabled = !acts.busy, modifier = Modifier.weight(1f)) { Text("Reopen") }
                    }
                }
            }
        },
    ) { pad ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 14.dp,
                bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when {
                issue == null && st.error != null -> item { Centered("Couldn't load the issue", st.error) { SecondaryButton(onClick = { st.refresh() }) { Text("Try again") } } }
                issue == null -> item { Centered("Loading…", null, working = true) }
                else -> {
                    item(key = "head") {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(issue.title, fontFamily = Serif, fontSize = 22.sp, lineHeight = 29.sp, color = c.text)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())) {
                                StateTag(issue.state)
                                issue.labels.forEach { Tag(it, c.muted, c.soft) }
                            }
                            Text("@${issue.author} · opened ${isoAgo(issue.created, now)}", fontSize = 13.sp, color = c.faint)
                        }
                    }
                    if (issue.body.isNotBlank()) item(key = "body") { Card { Box(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) { AssistantText(issue.body) } } }
                    if (issue.conversation.isNotEmpty()) {
                        item(key = "convo-label") { Label("Comments · ${issue.conversation.size}") }
                        items(issue.conversation.withIndex().toList(), key = { "c:" + it.index }) { (_, cm) -> CommentCard(cm, now) }
                    }
                }
            }
        }
    }
    val i = issue ?: return
    when (dialog) {
        "comment" -> TextDialog("Comment on #$number", "Comment (Markdown)", "Comment", onDismiss = { dialog = null }) { t ->
            dialog = null
            acts.run("Comment posted", after = { st.refresh() }) { GitHub.issueComment(repo, number, t) }
        }
        "close" -> ConfirmDialog("Close #$number?", "Marks the issue as done. You can reopen it later.", "Close", onDismiss = { dialog = null }) {
            dialog = null
            acts.run("Closed", after = { st.refresh() }) { GitHub.issueState(repo, number, "close") }
        }
        "ask" -> AskClaudeDialog("Work on #$number with Claude",
            "Please work on GitHub issue #$number in $repo: \"${i.title}\". Read it with `gh issue view $number --comments`, " +
                "make the change and test it. Tell me when it's ready so we can open a pull request.", i.local) { dialog = null }
    }
}
