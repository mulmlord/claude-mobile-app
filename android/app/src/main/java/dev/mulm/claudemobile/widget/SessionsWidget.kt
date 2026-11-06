package dev.mulm.claudemobile.widget

import android.content.Context
import android.content.Intent
import android.text.format.DateFormat
import android.text.format.DateUtils
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.state.GlanceStateDefinition
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import dev.mulm.claudemobile.App
import dev.mulm.claudemobile.Conn
import dev.mulm.claudemobile.MainActivity
import dev.mulm.claudemobile.Notifier
import dev.mulm.claudemobile.R
import java.util.Calendar
import java.util.Date

class SessionsWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = SessionsWidget()
}

/** Home-screen widget: which sessions need you, at a glance. Fed by [WidgetSync]. */
class SessionsWidget : GlanceAppWidget() {
    // Exact, not buckets: the layout counts how many rows fit the real size.
    override val sizeMode: SizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(300.dp, 190.dp)))
    // Everything lives in WidgetStore; no per-widget DataStore.
    override val stateDefinition: GlanceStateDefinition<*>? = null

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val snapshot = WidgetStore.snapshot(context)
        val configured = App.instance.prefs.configured
        provideContent {
            // Collected, not read once: while this composition is alive, updates arrive here
            // without restarting provideGlance.
            val snap by snapshot.collectAsState()
            val live by WidgetStore.live.collectAsState()
            SessionsContent(snap.shown(live, configured))
        }
    }

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        provideContent { SessionsContent(PreviewSnapshot) }
    }
}

internal val PreviewSnapshot = WidgetSnapshot(
    Conn.Connected, "laptop",
    listOf(
        WidgetSession("p1", "Fix the login redirect loop", "webapp", "permission", true),
        WidgetSession("p2", "Add a home-screen widget", "claude-mobile", "working", true),
        WidgetSession("p3", "Tidy up the bridge tests", "bridge", "idle", true),
    ),
    asOf = 0,
)

// The app's palette (ui/Theme.kt) as resources, so the launcher picks day or night itself.
private val Ink = ColorProvider(R.color.widget_text)
private val Muted = ColorProvider(R.color.widget_muted)
private val Faint = ColorProvider(R.color.widget_faint)
private val Accent = ColorProvider(R.color.widget_accent)
private val AccentText = ColorProvider(R.color.widget_accent_text)
private val Success = ColorProvider(R.color.widget_success)
private val Danger = ColorProvider(R.color.widget_danger)

/**
 * Sizes in dp. Rows get explicit heights so the number that fits is computed, not left to
 * clipping; text heights are at font scale 1 and grow with it.
 */
internal object Dims {
    const val PAD_H = 16f
    const val PAD_V = 12f
    const val HEADER = 30f
    const val GAP = 6f
    const val MAX_ROWS = 8 // Glance containers hold at most 10 children
    const val TWO_LINE = 35f // title + project
    const val ONE_LINE = 20f // title, or the status pill beside it
    const val FOOTER = 20f
    // Space around a row's text: at least TIGHT, up to ROOMY when there's height to spare.
    const val TIGHT = 6f
    const val ROOMY = 12f
}

/** How the list fits: [rows] sessions of [rowHeight] dp, one or two lines each, [more] left out. */
internal data class Fit(val rows: Int, val twoLine: Boolean, val rowHeight: Float, val more: Int)

/**
 * Two-line rows (title, project) when every session fits that way, else one-line rows, which
 * get four into a 4×2 widget; past that, "+N more". Sorted "needs you" first, so those are the
 * last to be cut.
 */
internal fun fit(count: Int, avail: Float, scale: Float): Fit {
    val two = Dims.TWO_LINE * scale
    val one = Dims.ONE_LINE * scale
    fun fits(n: Int, text: Float, room: Float) = n * (text + Dims.TIGHT) <= room
    fun rows(n: Int, twoLine: Boolean, room: Float, more: Int): Fit {
        val text = if (twoLine) two else one
        return Fit(n, twoLine, minOf(text + Dims.ROOMY, room / n), more)
    }
    if (count <= Dims.MAX_ROWS) {
        if (fits(count, two, avail)) return rows(count, true, avail, 0)
        if (fits(count, one, avail)) return rows(count, false, avail, 0)
    }
    val room = avail - Dims.FOOTER * scale
    val nTwo = (room / (two + Dims.TIGHT)).toInt().coerceIn(0, Dims.MAX_ROWS)
    val nOne = (room / (one + Dims.TIGHT)).toInt().coerceIn(0, Dims.MAX_ROWS)
    return when {
        nOne == 0 -> Fit(0, false, 0f, count)
        nTwo >= nOne -> rows(nTwo, true, room, count - nTwo)
        else -> rows(nOne, false, room, count - nOne)
    }
}

private fun open(ctx: Context, extras: Intent.() -> Unit = {}): Action = actionStartActivity(
    Intent(ctx, MainActivity::class.java).apply {
        // As the notifications do: reuse the running activity, which reads the extras in onNewIntent.
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        extras()
    }
)

private fun style(color: ColorProvider, size: TextUnit, weight: FontWeight? = null, family: FontFamily? = null, align: TextAlign? = null) =
    TextStyle(color = color, fontSize = size, fontWeight = weight, fontFamily = family, textAlign = align)

@Composable
internal fun SessionsContent(s: WidgetSnapshot) {
    val ctx = LocalContext.current
    val size = LocalSize.current
    val scale = ctx.resources.configuration.fontScale.coerceIn(0.8f, 2f)
    val avail = size.height.value - 2 * Dims.PAD_V - Dims.HEADER - Dims.GAP
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground()
            .background(ImageProvider(R.drawable.widget_bg))
            .padding(horizontal = Dims.PAD_H.dp, vertical = Dims.PAD_V.dp)
            .clickable(open(ctx)),
    ) {
        Header(s, size.width.value, scale)
        Spacer(GlanceModifier.height(Dims.GAP.dp))
        if (s.sessions.isEmpty()) Empty(s.conn, avail, scale) else Sessions(s.sessions, avail, scale)
    }
}

@Composable
private fun Header(s: WidgetSnapshot, width: Float, scale: Float) {
    val ctx = LocalContext.current
    Row(GlanceModifier.fillMaxWidth().height(Dims.HEADER.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(ImageProvider(R.drawable.ic_stat), null, GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(Accent))
        Spacer(GlanceModifier.width(7.dp))
        Text("Claude", style = style(Ink, 16.sp, FontWeight.Medium, FontFamily.Serif), maxLines = 1)
        Spacer(GlanceModifier.width(10.dp))
        val line = connLine(ctx, s)
        // Rough width left beside the spark, "Claude", the dot and "+": rather a short hint
        // ("omarchy") or just the dot than "Con…".
        val room = width - 117f - 52f * scale
        val perChar = 6f * scale
        val hint = when {
            room >= line.full.length * perChar -> line.full
            room >= maxOf(40f, line.short.length * perChar) -> line.short
            else -> ""
        }
        Row(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            Dot(line.dot)
            if (hint.isNotEmpty()) {
                Spacer(GlanceModifier.width(6.dp))
                Text(hint, style = style(Muted, 12.sp), maxLines = 1)
            }
        }
        Spacer(GlanceModifier.width(8.dp))
        Box(
            GlanceModifier.size(30.dp).background(ImageProvider(R.drawable.widget_button))
                .clickable(open(ctx) { putExtra(MainActivity.EXTRA_NEW_SESSION, true) }),
            contentAlignment = Alignment.Center,
        ) {
            Image(ImageProvider(R.drawable.ic_widget_add), "New session", GlanceModifier.size(18.dp), colorFilter = ColorFilter.tint(Ink))
        }
    }
}

private class ConnLine(val dot: ColorProvider, val full: String, val short: String = full)

/** Same wording and colours as the app's connection line. */
private fun connLine(ctx: Context, s: WidgetSnapshot): ConnLine = when (s.conn) {
    Conn.Connected -> s.host.ifBlank { "laptop" }.let { ConnLine(Success, "Connected to $it", it) }
    Conn.Connecting -> ConnLine(Accent, "Connecting…")
    Conn.Unauthorized -> ConnLine(Danger, "Token rejected")
    Conn.NotConfigured -> ConnLine(Faint, "Not set up")
    // An absolute time on purpose: nothing redraws the widget to keep "5 min ago" true.
    Conn.Offline -> ConnLine(Danger, if (s.sessions.isNotEmpty() && s.asOf > 0) "Offline since ${timeOf(ctx, s.asOf)}" else "Offline", "Offline")
}

private fun timeOf(ctx: Context, ms: Long): String {
    val now = Calendar.getInstance()
    val then = Calendar.getInstance().apply { timeInMillis = ms }
    val today = now.get(Calendar.YEAR) == then.get(Calendar.YEAR) && now.get(Calendar.DAY_OF_YEAR) == then.get(Calendar.DAY_OF_YEAR)
    return if (today) DateFormat.getTimeFormat(ctx).format(Date(ms))
    else DateUtils.formatDateTime(ctx, ms, DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_ABBREV_MONTH or DateUtils.FORMAT_NO_YEAR)
}

@Composable
private fun Dot(color: ColorProvider) {
    Image(ImageProvider(R.drawable.widget_dot), null, GlanceModifier.size(6.dp), colorFilter = ColorFilter.tint(color))
}

@Composable
private fun Empty(conn: Conn, avail: Float, scale: Float) {
    val (title, sub) = when (conn) {
        Conn.Connected -> "No sessions running" to "Tap + to start one, or run claude on your laptop."
        Conn.Connecting -> "Connecting…" to ""
        Conn.NotConfigured -> "Not set up" to "Open the app to connect it to your laptop."
        Conn.Unauthorized -> "Not connected" to "The token was rejected. Open the app to fix it."
        Conn.Offline -> "Not connected" to "Is the laptop awake and on Tailscale?"
    }
    Column(
        GlanceModifier.fillMaxSize(),
        verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = style(Ink, 17.sp, family = FontFamily.Serif, align = TextAlign.Center), maxLines = 1)
        if (sub.isNotEmpty() && avail >= 60f * scale) {
            Spacer(GlanceModifier.height(4.dp))
            Text(sub, style = style(Muted, 12.5.sp, align = TextAlign.Center), maxLines = 2)
        }
    }
}

@Composable
private fun Sessions(list: List<WidgetSession>, avail: Float, scale: Float) {
    val ctx = LocalContext.current
    val f = fit(list.size, avail, scale)
    if (f.rows == 0) {
        Summary(list)
        return
    }
    Column(GlanceModifier.fillMaxWidth()) {
        list.take(f.rows).forEach { s ->
            val row = GlanceModifier.fillMaxWidth().clickable(open(ctx) { putExtra(Notifier.EXTRA_SID, s.id) })
            if (f.twoLine) {
                Column(row.height(f.rowHeight.dp), verticalAlignment = Alignment.CenterVertically) {
                    TitleLine(s)
                    Text(s.project.ifBlank { " " }, style = style(Faint, 12.sp), maxLines = 1)
                }
            } else {
                Box(row.height(f.rowHeight.dp), contentAlignment = Alignment.CenterStart) { TitleLine(s) }
            }
        }
        if (f.more > 0) {
            Box(GlanceModifier.fillMaxWidth().height((Dims.FOOTER * scale).dp), contentAlignment = Alignment.BottomStart) {
                Text("+${f.more} more", style = style(Faint, 12.sp), maxLines = 1)
            }
        }
    }
}

@Composable
private fun TitleLine(s: WidgetSession) {
    Row(GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            s.title.ifBlank { "New session" }, style = style(Ink, 14.sp, FontWeight.Medium),
            maxLines = 1, modifier = GlanceModifier.defaultWeight(),
        )
        Spacer(GlanceModifier.width(8.dp))
        StatusTag(s)
    }
}

/** The app's status tags, quieter: only "needs you" gets a fill, "working" just the accent. */
@Composable
private fun StatusTag(s: WidgetSession) {
    when {
        !s.connected -> Plain("Offline")
        s.needsYou -> Box(GlanceModifier.background(ImageProvider(R.drawable.widget_pill)).padding(horizontal = 8.dp, vertical = 2.dp)) {
            Text(if (s.status == "question") "Question" else "Needs you", style = style(AccentText, 12.sp, FontWeight.Medium), maxLines = 1)
        }
        s.status == "working" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Image(ImageProvider(R.drawable.ic_stat), null, GlanceModifier.size(12.dp), colorFilter = ColorFilter.tint(Accent))
            Spacer(GlanceModifier.width(4.dp))
            Text("Working", style = style(AccentText, 12.sp, FontWeight.Medium), maxLines = 1)
        }
        s.status == "idle" -> Row(verticalAlignment = Alignment.CenterVertically) {
            Dot(Success)
            Spacer(GlanceModifier.width(5.dp))
            Text("Ready", style = style(Faint, 12.sp), maxLines = 1)
        }
        s.status == "ended" -> Plain("Ended")
        else -> Plain("Starting")
    }
}

@Composable
private fun Plain(label: String) {
    Text(label, style = style(Faint, 12.sp), maxLines = 1)
}

/** Too short for even one row (e.g. 4×1): a count, in the accent when something waits for you. */
@Composable
private fun Summary(list: List<WidgetSession>) {
    val waiting = list.count { it.needsYou && it.connected }
    val working = list.count { it.status == "working" && it.connected }
    val parts = buildList {
        if (waiting > 0) add("$waiting need${if (waiting == 1) "s" else ""} you")
        if (working > 0) add("$working working")
        if (isEmpty()) add("${list.size} session${if (list.size == 1) "" else "s"}")
    }
    Text(parts.joinToString(" · "), style = style(if (waiting > 0) AccentText else Muted, 13.sp, FontWeight.Medium), maxLines = 1)
}
