package dev.mulm.claudemobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.mikepenz.markdown.m3.Markdown
import com.mikepenz.markdown.m3.markdownColor
import com.mikepenz.markdown.m3.markdownTypography
import dev.mulm.claudemobile.App
import dev.mulm.claudemobile.Conn
import dev.mulm.claudemobile.Item
import dev.mulm.claudemobile.Notifier
import dev.mulm.claudemobile.ScreenState
import dev.mulm.claudemobile.SessionInfo
import kotlinx.coroutines.launch
import androidx.compose.material3.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.speech.RecognizerIntent
import android.content.Intent
import android.content.ActivityNotFoundException
import dev.mulm.claudemobile.prepareImage
import androidx.compose.material.icons.filled.Add
import androidx.activity.result.PickVisualMediaRequest
import android.net.Uri

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(sid: String) {
    val app = App.instance
    val bridge = app.bridge
    val ctx = LocalContext.current
    val c = Claude.colors
    val sessions by bridge.sessions.collectAsState()
    val session = sessions.firstOrNull { it.id == sid }
    val allItems by bridge.items.collectAsState()
    val items = allItems[sid].orEmpty()
    val conn by bridge.conn.collectAsState()
    var showTerminal by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var menu by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var ending by remember { mutableStateOf(false) }
    val needsYou = session?.needsYou == true
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    // Search: matches are item ids, oldest first; the current one is pinned by id so
    // messages arriving meanwhile don't move it. Starts at the newest match.
    var searching by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var currentMatch by rememberSaveable { mutableStateOf<String?>(null) }
    var jump by remember { mutableIntStateOf(0) }  // bumped to scroll even when the match didn't change
    val matches = remember(items, query) { searchMatches(items, query) }
    val cur = matches.indexOf(currentMatch).let { if (it >= 0) it else matches.lastIndex }
    fun step(by: Int) {
        if (matches.isEmpty()) return
        currentMatch = matches[(cur + by).mod(matches.size)]
        jump++
    }
    fun closeSearch() {
        searching = false
        query = ""
        currentMatch = null
    }
    BackHandler(enabled = searching) { closeSearch() }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                snackbar.showSnackbar(e.message ?: "Failed")
            }
        }
    }

    DisposableEffect(sid) {
        bridge.subscribe(sid)
        onDispose {
            bridge.subscribe(null)
            bridge.subscribeScreen(null)
        }
    }
    LifecycleResumeEffect(sid) {
        app.visibleSession = sid
        Notifier.cancel(ctx, sid)
        bridge.ensureConnected()
        onPauseOrDispose { app.visibleSession = null }
    }
    LaunchedEffect(sid, showTerminal, needsYou) {
        bridge.subscribeScreen(if (showTerminal || needsYou) sid else null)
    }

    Scaffold(
        containerColor = c.bg,
        topBar = {
            Column {
                if (searching) SearchBar(
                    query = query,
                    onQuery = { q ->
                        query = q
                        currentMatch = searchMatches(items, q).lastOrNull()
                        jump++
                    },
                    position = if (query.isBlank()) null else "${cur + 1} / ${matches.size}",
                    hasMatches = matches.isNotEmpty(),
                    onPrev = { step(-1) }, onNext = { step(1) }, onClose = { closeSearch() },
                ) else TopAppBar(
                    colors = claudeTopBarColors(),
                    navigationIcon = {
                        IconButton(onClick = { Nav.go(Route.Sessions) }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                        }
                    },
                    title = {
                        Column {
                            Text(
                                session?.title?.ifBlank { null } ?: "Session", maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontSize = 16.sp, fontWeight = FontWeight.Medium, color = c.text,
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    if (session == null) "Session ended" else "${session.project} · ${statusText(session)}",
                                    fontSize = 12.5.sp, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f, fill = false),
                                )
                                if (session != null && session.context > 0) {
                                    val pct = (session.context * 100 / session.contextMax).toInt()
                                    Text(
                                        " · $pct% context", fontSize = 12.5.sp, maxLines = 1,
                                        color = if (pct >= 80) c.accentButton else c.faint,
                                    )
                                }
                            }
                        }
                    },
                    actions = {
                        // Kept to icons so the title keeps its room; GitHub lives in the ⋮ menu.
                        AnimatedVisibility(
                            visible = session?.status == "working",
                            enter = fadeIn() + scaleIn(initialScale = 0.7f) + expandHorizontally(expandFrom = Alignment.End),
                            exit = fadeOut() + scaleOut(targetScale = 0.7f) + shrinkHorizontally(shrinkTowards = Alignment.End),
                        ) {
                            Row {
                                HeaderButton(onClick = { act { bridge.keys(sid, "esc") } }, tint = c.danger, active = true, pulse = true) {
                                    Icon(StopIcon, "Stop", Modifier.size(12.dp), tint = c.danger)
                                }
                                Spacer(Modifier.width(8.dp))
                            }
                        }
                        HeaderButton(onClick = { showTerminal = !showTerminal }, tint = c.accentButton, active = showTerminal) {
                            Icon(TerminalIcon, "Terminal", Modifier.size(19.dp), tint = if (showTerminal) c.accentButton else c.muted)
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "More") }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = c.surface) {
                                MenuEntry("Search") { menu = false; searching = true }
                                session?.repo?.let { repo -> MenuEntry("GitHub") { menu = false; Nav.go(Route.Repo(repo)) } }
                                MenuEntry("Rename…", enabled = session?.connected == true) { menu = false; renaming = true }
                                MenuEntry("End session…", enabled = session != null) { menu = false; ending = true }
                            }
                        }
                    },
                )
                HorizontalDivider(thickness = 1.dp, color = c.border)
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            Column(Modifier.background(c.bg).windowInsetsPadding(WindowInsets.ime.union(WindowInsets.navigationBars))) {
                // Read here, not at the top: the screen changes several times a second while
                // Claude works, and only this part of the chat shows it.
                val screen by bridge.screen.collectAsState()
                val sc = screen?.takeIf { it.sid == sid }
                AnimatedVisibility(visible = showTerminal) {
                    TerminalPanel(sc) { keys -> act { bridge.keys(sid, *keys) } }
                }
                if (needsYou || (sc != null && sc.options.isNotEmpty())) {
                    MenuPanel(session, sc,
                        onSelect = { i -> act { bridge.select(sid, i) } },
                        onEsc = { act { bridge.keys(sid, "esc") } })
                }
                val live = session?.connected == true && conn == Conn.Connected
                Composer(
                    quickActions = if (!live || needsYou) null else {
                        {
                            QuickActions(
                                onText = { t -> act { bridge.sendText(sid, t) } },
                                onMode = {
                                    act { bridge.keys(sid, "shift-tab") }
                                    showTerminal = true
                                },
                                onClear = { confirmClear = true },
                            )
                        }
                    },
                    enabled = live,
                    onAttach = { uri -> bridge.upload(sid, prepareImage(ctx, uri), "image/jpeg") },
                    onError = { msg -> scope.launch { snackbar.showSnackbar(msg) } },
                    hint = when {
                        session == null -> "Session ended"
                        conn != Conn.Connected -> "Not connected"
                        !session.connected -> "Session not reachable"
                        session.status == "working" -> "Reply (sent when Claude is done)"
                        else -> "Reply to Claude…"
                    },
                    onSend = { text, restore -> act { try { bridge.sendText(sid, text) } catch (e: Exception) { restore(); throw e } } },
                )
            }
        },
    ) { pad ->
        MessageList(
            items, pad, working = session?.status == "working" && session.connected,
            query = query, matches = remember(matches) { matches.toSet() },
            current = if (searching) matches.getOrNull(cur) else null, jump = jump,
        )
    }
    if (renaming && session != null) RenameSessionDialog(session) { renaming = false }
    if (ending && session != null) EndSessionDialog(session) { ending = false }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = c.surface,
            title = { Text("Clear this conversation?", fontFamily = Serif, fontSize = 21.sp, color = c.text) },
            text = {
                Text("Claude starts fresh in this session. The old conversation stays in your history and can be resumed later.",
                    fontSize = 14.sp, lineHeight = 20.sp, color = c.muted)
            },
            confirmButton = {
                TextButton(onClick = { confirmClear = false; act { bridge.sendText(sid, "/clear") } }) {
                    Text("Clear", color = c.danger, fontWeight = FontWeight.Medium)
                }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel", color = c.muted) } },
        )
    }
}

/**
 * The chat header's round-cornered icon buttons: tinted while active (terminal open, Stop),
 * outlined otherwise; [pulse] breathes the border while Claude works.
 */
@Composable
private fun HeaderButton(onClick: () -> Unit, tint: Color, active: Boolean, pulse: Boolean = false, content: @Composable () -> Unit) {
    val c = Claude.colors
    val fill by animateColorAsState(if (active) tint.copy(alpha = if (c.dark) 0.20f else 0.12f) else c.surface, label = "fill")
    val edge by animateColorAsState(if (active) tint.copy(alpha = 0.45f) else c.border, label = "edge")
    val breath = if (pulse) {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0.35f, 0.9f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "pulse").value
    } else 1f
    Surface(
        onClick = onClick, shape = RoundedCornerShape(12.dp), color = fill,
        border = BorderStroke(1.dp, if (pulse) edge.copy(alpha = edge.alpha * breath) else edge),
        modifier = Modifier.size(38.dp),
    ) {
        Box(contentAlignment = Alignment.Center) { content() }
    }
}

/** One-tap replies and commands, shown above the empty composer. */
@Composable
private fun QuickActions(onText: (String) -> Unit, onMode: () -> Unit, onClear: () -> Unit) {
    val c = Claude.colors
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        @Composable
        fun chip(label: String, mono: Boolean = false, onClick: () -> Unit) {
            Surface(onClick = onClick, shape = RoundedCornerShape(50), color = c.surface, border = BorderStroke(1.dp, c.border)) {
                Text(label, fontSize = 13.5.sp, color = c.text, fontFamily = if (mono) FontFamily.Monospace else FontFamily.Default,
                    modifier = Modifier.padding(horizontal = 13.dp, vertical = 7.dp))
            }
        }
        chip("Continue") { onText("continue") }
        chip("Yes") { onText("yes") }
        chip("/compact", mono = true) { onText("/compact") }
        chip("Switch mode") { onMode() }
        chip("/clear", mono = true) { onClear() }
    }
}

/** The chat's top bar while searching: query field, "3 / 12", older ↑ / newer ↓. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchBar(
    query: String, onQuery: (String) -> Unit, position: String?, hasMatches: Boolean,
    onPrev: () -> Unit, onNext: () -> Unit, onClose: () -> Unit,
) {
    val c = Claude.colors
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    TopAppBar(
        colors = claudeTopBarColors(),
        navigationIcon = {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close search") }
        },
        title = {
            BasicTextField(
                value = query, onValueChange = onQuery, singleLine = true,
                textStyle = TextStyle(fontSize = 16.sp, color = c.text),
                cursorBrush = SolidColor(c.accentButton),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                // Results are already shown while typing; Search just gets the keyboard out of the way.
                keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.CenterStart) {
                        if (query.isEmpty()) Text("Search this chat", fontSize = 16.sp, color = c.faint, maxLines = 1)
                        inner()
                    }
                },
            )
        },
        actions = {
            if (position != null) {
                Text(position, fontSize = 13.sp, color = if (hasMatches) c.muted else c.faint, maxLines = 1,
                    modifier = Modifier.padding(start = 8.dp, end = 2.dp))
            }
            IconButton(onClick = onPrev, enabled = hasMatches) { Icon(Icons.Filled.KeyboardArrowUp, "Previous match") }
            IconButton(onClick = onNext, enabled = hasMatches) { Icon(Icons.Filled.KeyboardArrowDown, "Next match") }
        },
    )
}

/** Ids of the items containing [query] (case-insensitive), oldest first. */
private fun searchMatches(items: List<Item>, query: String): List<String> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return items.filter { it.matchesQuery(q) }.map { it.id }
}

private fun Item.matchesQuery(q: String) =
    listOf(text, name, summary, detail, result).any { it.contains(q, ignoreCase = true) }

/** Tint behind a search hit, drawn past the item's edges so the layout doesn't shift. */
private fun Modifier.searchMark(fill: Color?, stroke: Color?): Modifier =
    if (fill == null) this else drawBehind {
        val dx = 8.dp.toPx()
        val dy = 4.dp.toPx()  // half the list's item spacing
        val topLeft = Offset(-dx, -dy)
        val area = Size(size.width + 2 * dx, size.height + 2 * dy)
        val r = CornerRadius(14.dp.toPx())
        drawRoundRect(fill, topLeft, area, r)
        if (stroke != null) drawRoundRect(stroke, topLeft, area, r, style = Stroke(1.5.dp.toPx()))
    }

/** Brings item [index] into view: centred when it fits, otherwise with its top at the top. */
private suspend fun LazyListState.reveal(index: Int) {
    // Jump first if it's far away: animating there would compose every item in between.
    if (layoutInfo.visibleItemsInfo.none { it.index == index }) scrollToItem(index)
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.index == index } ?: return
    // Offsets run from the list's start edge, which reverseLayout puts at the bottom; the
    // arithmetic is the same either way.
    val room = info.viewportSize.height - info.beforeContentPadding - info.afterContentPadding
    val target = if (item.size <= room) (room - item.size) / 2 else room - item.size
    animateScrollBy((item.offset - target).toFloat())
}

private fun statusText(s: SessionInfo) = when {
    !s.connected -> "offline"
    s.status == "working" -> "working…"
    s.status == "permission" -> "needs permission"
    s.status == "question" -> "has a question"
    s.status == "idle" -> "ready"
    else -> s.status
}

@Composable
private fun MessageList(
    items: List<Item>, pad: PaddingValues, working: Boolean,
    query: String = "", matches: Set<String> = emptySet(), current: String? = null, jump: Int = 0,
) {
    val c = Claude.colors
    val state = rememberLazyListState()
    val reversed = remember(items) { items.asReversed() }
    // The working spark is the bottom row too, so its appearing must scroll down as well.
    // Not while a search hit is shown, though: new messages mustn't pull the list away from it.
    LaunchedEffect(items.lastOrNull()?.id, items.lastOrNull()?.seq, working) {
        if (current == null && state.firstVisibleItemIndex <= 2) state.animateScrollToItem(0)
    }
    LaunchedEffect(current, jump) {
        val pos = items.indexOfFirst { it.id == current }
        // The list shows items newest first, below the optional working row.
        if (pos >= 0) state.reveal(items.size - 1 - pos + if (working) 1 else 0)
    }
    val hitFill = c.accent.copy(alpha = if (c.dark) 0.16f else 0.10f)
    val hitStroke = c.accent.copy(alpha = 0.75f)
    val otherFill = c.accent.copy(alpha = if (c.dark) 0.07f else 0.045f)
    val q = query.trim()
    if (items.isEmpty()) {
        Column(
            Modifier.padding(pad).fillMaxWidth().padding(40.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(48.dp))
            Spark(size = 40.dp, working = working)
            Spacer(Modifier.height(16.dp))
            Text("Nothing here yet", fontFamily = Serif, fontSize = 22.sp, color = c.text)
            Spacer(Modifier.height(6.dp))
            Text("Messages from this session will appear here.", color = c.muted, fontSize = 14.sp, textAlign = TextAlign.Center)
        }
        return
    }
    LazyColumn(
        state = state,
        reverseLayout = true,
        contentPadding = PaddingValues(
            start = 16.dp, end = 16.dp,
            top = pad.calculateTopPadding() + 12.dp, bottom = pad.calculateBottomPadding() + 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.Bottom),
    ) {
        if (working) {
            item(key = "working") {
                Box(Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp)) { Spark(size = 26.dp, working = true) }
            }
        }
        items(reversed, key = { it.id }) { item ->
            val isCurrent = item.id == current
            val hit = isCurrent || item.id in matches
            Box(Modifier.searchMark(if (isCurrent) hitFill else if (hit) otherFill else null, if (isCurrent) hitStroke else null)) {
                when (item.kind) {
                    "user" -> UserBubble(item.text)
                    "assistant" -> AssistantText(item.text)
                    // A hit only in the command or output: open the row so the match can be seen.
                    "tool" -> ToolRow(item, reveal = isCurrent && !item.name.contains(q, true) && !item.summary.contains(q, true))
                    else -> SystemNote(item.text)
                }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    val c = Claude.colors
    Box(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 6.dp), contentAlignment = Alignment.CenterEnd) {
        Surface(color = c.bubble, contentColor = c.text, shape = RoundedCornerShape(18.dp), modifier = Modifier.widthIn(max = 310.dp)) {
            SelectionContainer {
                Text(text, fontSize = 15.5.sp, lineHeight = 22.sp, modifier = Modifier.padding(horizontal = 15.dp, vertical = 10.dp))
            }
        }
    }
}

@Composable
internal fun AssistantText(text: String) {
    val c = Claude.colors
    val body = ReplyStyle.copy(color = c.text)
    val heading = { size: Int, line: Int -> body.copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = FontWeight.SemiBold) }
    val mono = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 19.sp, color = c.text)
    SelectionContainer {
        Markdown(
            content = text,
            colors = markdownColor(
                text = c.text, codeBackground = c.soft, inlineCodeBackground = c.bubble,
                dividerColor = c.border, tableBackground = c.soft,
            ),
            typography = markdownTypography(
                h1 = heading(23, 31), h2 = heading(21, 29), h3 = heading(19, 27),
                h4 = heading(17, 26), h5 = heading(17, 26), h6 = heading(17, 26),
                text = body, paragraph = body, ordered = body, bullet = body, list = body,
                quote = body.copy(fontStyle = FontStyle.Italic, color = c.muted),
                code = mono, inlineCode = mono.copy(fontSize = 14.5.sp),
                table = body.copy(fontSize = 14.5.sp, lineHeight = 21.sp),
                textLink = TextLinkStyles(SpanStyle(color = c.text, textDecoration = TextDecoration.Underline)),
            ),
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        )
    }
}

@Composable
private fun SystemNote(text: String) {
    Text(
        text, fontSize = 12.5.sp, color = Claude.colors.faint,
        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        maxLines = 6, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun ToolRow(item: Item, reveal: Boolean = false) {
    // An edit waiting for your permission opens by itself, so you read the change before allowing it.
    var manual by rememberSaveable(item.id) { mutableStateOf<Boolean?>(null) }
    val hasDiff = item.diff.isNotEmpty()
    val expanded = manual ?: (reveal || (item.status == "running" && hasDiff))
    val c = Claude.colors
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, c.border, shape)) {
        Row(
            Modifier.fillMaxWidth().clickable { manual = !expanded }.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when (item.status) {
                    "running" -> CircularProgressIndicator(Modifier.size(12.dp), strokeWidth = 1.5.dp, color = c.accent)
                    "error" -> Text("✕", color = c.danger, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    else -> Text("✓", color = c.faint, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(item.name, fontSize = 13.5.sp, fontWeight = FontWeight.Medium, color = c.text)
            Spacer(Modifier.width(8.dp))
            Text(
                item.summary, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace,
                color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            if (item.added + item.removed > 0) {
                Spacer(Modifier.width(8.dp))
                Text("+${item.added}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.success)
                Text(" −${item.removed}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = c.danger)
            }
            val turn by animateFloatAsState(if (expanded) 180f else 0f, label = "chevron")
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.size(18.dp).rotate(turn), tint = c.faint)
        }
        if (item.name == "AskUserQuestion" && item.questions.isNotEmpty()) {
            HorizontalDivider(color = c.border)
            Column(Modifier.fillMaxWidth().background(c.soft).padding(horizontal = 14.dp, vertical = 12.dp)) {
                item.questions.forEach { q ->
                    Text(q.question, fontFamily = Serif, fontSize = 15.5.sp, lineHeight = 22.sp, color = c.text)
                    q.options.forEachIndexed { i, o ->
                        Text(
                            "${i + 1}. ${o.label}" + if (o.description.isNotBlank()) " · ${o.description}" else "",
                            fontSize = 13.5.sp, lineHeight = 19.sp, color = c.muted, modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                }
            }
        }
        AnimatedVisibility(visible = expanded) {
            Column {
                HorizontalDivider(color = c.border)
                if (hasDiff) {
                    DiffView(item.diff)
                    if (item.status == "error" && item.result.isNotBlank()) {
                        Column(Modifier.fillMaxWidth().background(c.soft).padding(12.dp)) { Mono(item.result, c.danger) }
                    }
                } else SelectionContainer {
                    Column(
                        Modifier.fillMaxWidth().background(c.soft).heightIn(max = 420.dp)
                            .verticalScroll(rememberScrollState()).padding(12.dp),
                    ) {
                        if (item.detail.isNotBlank()) Mono(item.detail, c.text)
                        if (item.result.isNotBlank()) {
                            if (item.detail.isNotBlank()) {
                                Text("Result", fontSize = 11.5.sp, fontWeight = FontWeight.Medium, color = c.faint,
                                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                            }
                            Mono(item.result, if (item.status == "error") c.danger else c.muted)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun DiffView(lines: List<String>) {
    val c = Claude.colors
    val addBg = c.success.copy(alpha = if (c.dark) 0.20f else 0.13f)
    val delBg = c.danger.copy(alpha = if (c.dark) 0.20f else 0.10f)
    SelectionContainer {
        Column(
            Modifier.fillMaxWidth().background(c.soft).heightIn(max = 460.dp)
                .verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
        ) {
            lines.forEach { ln ->
                if (ln.startsWith("@")) {
                    Text(
                        if (ln.length > 1) "⋯ ${ln.drop(1)}" else "⋯", fontFamily = FontFamily.Monospace,
                        fontSize = 11.5.sp, color = c.faint, modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp),
                    )
                } else {
                    val sign = ln.firstOrNull() ?: ' '
                    val (bg, fg) = when (sign) {
                        '+' -> addBg to c.success
                        '-' -> delBg to c.danger
                        else -> Color.Transparent to c.faint
                    }
                    Row(Modifier.fillMaxWidth().background(bg).padding(horizontal = 8.dp, vertical = 1.dp)) {
                        Text(if (sign == ' ') " " else sign.toString(), fontFamily = FontFamily.Monospace, fontSize = 11.5.sp,
                            lineHeight = 16.sp, color = fg, fontWeight = FontWeight.Bold, modifier = Modifier.width(14.dp))
                        Text(ln.drop(1), fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 16.sp, color = c.text)
                    }
                }
            }
        }
    }
}

@Composable
private fun Mono(text: String, color: Color) {
    Text(text, fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 15.sp, color = color)
}

/** Buttons for whatever menu Claude currently shows (permission prompt, question, ...). */
@Composable
private fun MenuPanel(session: SessionInfo?, sc: ScreenState?, onSelect: (Int) -> Unit, onEsc: () -> Unit) {
    val c = Claude.colors
    Surface(
        color = c.surface, shape = RoundedCornerShape(18.dp), border = BorderStroke(1.dp, c.border),
        shadowElevation = 6.dp, modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
    ) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            val title = when (session?.status) {
                "permission" -> "Claude needs your permission"
                "question" -> "Claude has a question"
                else -> "Claude is asking"
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spark(size = 18.dp)
                Spacer(Modifier.width(10.dp))
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = c.text)
            }
            val detail = session?.detail.orEmpty()
            val prompt = sc?.prompt.orEmpty()
            if (prompt.isNotBlank()) {
                // What the laptop's screen says this prompt is about: the ground truth to approve.
                Text(prompt, fontFamily = FontFamily.Monospace, fontSize = 12.5.sp, lineHeight = 17.sp, color = c.text,
                    maxLines = 14, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(c.soft).heightIn(max = 240.dp).verticalScroll(rememberScrollState()).padding(10.dp))
            } else if (detail.isNotBlank() && detail != title) {
                Text(detail, fontSize = 14.sp, lineHeight = 20.sp, color = c.muted, maxLines = 4, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp))
            }
            Spacer(Modifier.size(12.dp))
            val opts = sc?.options.orEmpty()
            if (opts.isEmpty()) {
                Text("Loading the options… (or open the terminal to see the screen)", fontSize = 13.sp, color = c.faint)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                opts.forEach { o ->
                    val label = (o.key?.let { "$it. " } ?: "") + o.label
                    val m = Modifier.fillMaxWidth()
                    if (o.selected) {
                        PrimaryButton(onClick = { onSelect(o.index) }, modifier = m) {
                            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    } else {
                        SecondaryButton(onClick = { onSelect(o.index) }, modifier = m) {
                            Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
            TextButton(onClick = onEsc, modifier = Modifier.align(Alignment.End)) {
                Text("Esc · cancel", color = c.muted, fontSize = 13.sp)
            }
        }
    }
}

/** claude.ai-style composer: one rounded box, text on the left, terracotta ↑ on the right. */
@Composable
private fun Composer(
    enabled: Boolean, hint: String, onSend: (String, () -> Unit) -> Unit,
    quickActions: (@Composable () -> Unit)? = null,
    onAttach: (suspend (Uri) -> String)? = null,
    onError: (String) -> Unit = {},
) {
    val c = Claude.colors
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var uploading by remember { mutableStateOf(false) }
    // A photo goes to the laptop first; its path lands in the message, where Claude reads it.
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && onAttach != null) {
            uploading = true
            scope.launch {
                try {
                    val path = onAttach(uri)
                    text = if (text.isBlank()) "$path " else text.trimEnd() + " $path "
                } catch (e: Exception) {
                    onError(e.message ?: "Couldn't send the photo")
                } finally {
                    uploading = false
                }
            }
        }
    }
    val speech = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to Claude")
    }
    val canSpeak = remember { ctx.packageManager.queryIntentActivities(speech, 0).isNotEmpty() }
    val dictate = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val said = r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.trim()
        if (!said.isNullOrEmpty()) text = if (text.isBlank()) said else text.trimEnd() + " " + said
    }
    val canSend = enabled && text.isNotBlank()
    fun send() {
        val t = text.trim()
        if (t.isNotEmpty()) {
            text = ""
            onSend(t) { text = t }
        }
    }
    if (quickActions != null && text.isEmpty()) {
        Box(Modifier.padding(top = 8.dp)) { quickActions() }
    }
    Surface(
        shape = RoundedCornerShape(24.dp), color = c.surface, border = BorderStroke(1.dp, c.border),
        shadowElevation = 3.dp,
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 10.dp),
    ) {
        Row(Modifier.padding(start = if (onAttach != null) 6.dp else 18.dp, end = 8.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.Bottom) {
            if (onAttach != null) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).clickable(enabled = enabled && !uploading) {
                        pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    if (uploading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 1.8.dp, color = c.accent)
                    else Icon(Icons.Filled.Add, "Attach a photo", Modifier.size(22.dp), tint = if (enabled) c.muted else c.faint)
                }
                Spacer(Modifier.width(4.dp))
            }
            BasicTextField(
                value = text, onValueChange = { text = it }, enabled = enabled, maxLines = 6,
                textStyle = TextStyle(fontSize = 16.sp, lineHeight = 22.sp, color = c.text),
                cursorBrush = SolidColor(c.accentButton),
                modifier = Modifier.weight(1f).padding(vertical = 7.dp),
                decorationBox = { inner ->
                    Box {
                        if (text.isEmpty()) {
                            Text(hint, fontSize = 16.sp, lineHeight = 22.sp, color = c.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        inner()
                    }
                },
            )
            Spacer(Modifier.width(4.dp))
            if (canSpeak) {
                Box(
                    Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).clickable(enabled = enabled) {
                        try {
                            dictate.launch(speech)
                        } catch (e: ActivityNotFoundException) {
                            // recognizer went away; nothing to do
                        }
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(MicIcon, "Dictate", Modifier.size(21.dp), tint = if (enabled) c.muted else c.faint)
                }
                Spacer(Modifier.width(4.dp))
            }
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(11.dp))
                    .background(if (canSend) c.accentButton else c.accentButton.copy(alpha = 0.35f))
                    .clickable(enabled = canSend) { send() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(ArrowUpIcon, "Send", Modifier.size(19.dp), tint = c.onAccent)
            }
        }
    }
}
