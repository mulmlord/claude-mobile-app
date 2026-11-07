package dev.mulm.claudemobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.mulm.claudemobile.ScreenState
import dev.mulm.claudemobile.TermStyle

/**
 * The terminal's colour scheme: a warm dark ground in both app themes (Claude Code's own
 * colours assume a dark terminal), with the 16 ANSI colours tuned to the Claude palette.
 */
private object TermPalette {
    val bg = Color(0xFF1C1B19)
    val header = Color(0xFF242320)
    val border = Color(0xFF34332F)
    val fg = Color(0xFFE8E6DC)
    val faint = Color(0xFF8E8C85)
    private val ansi = mapOf(
        "black" to Color(0xFF55534D), "red" to Color(0xFFE5735F), "green" to Color(0xFF8FBF8F),
        "yellow" to Color(0xFFE3B45F), "blue" to Color(0xFF7FA7D9), "magenta" to Color(0xFFC89BD8),
        "cyan" to Color(0xFF76BFB8), "white" to Color(0xFFD6D3C8),
        "brightblack" to Color(0xFF7A7872), "brightred" to Color(0xFFF09080), "brightgreen" to Color(0xFFA8D4A0),
        "brightyellow" to Color(0xFFF0CB7A), "brightblue" to Color(0xFF9DBDE6), "brightmagenta" to Color(0xFFD9B3E5),
        "brightcyan" to Color(0xFF93D3CC), "brightwhite" to Color(0xFFFAF9F5),
    )

    fun color(name: String): Color? = when {
        name.isEmpty() -> null
        name.startsWith("#") && name.length == 7 -> name.substring(1).toLongOrNull(16)?.let { Color(0xFF000000 or it) }
        else -> ansi[name]
    }
}

private sealed interface TermBlock {
    /** [fit]: the status lines under the prompt box, each shrunk to one row like on the laptop. */
    data class Text(val text: AnnotatedString, val fit: Boolean = false) : TermBlock
    /** A full-width ─── line: drawn as a hairline instead of wrapping into rows of dashes. */
    data class Rule(val color: Color) : TermBlock
}

/**
 * Stand-ins for symbols Claude Code draws that the phone's fonts may lack (they'd show as
 * empty boxes): the media-control triangles of "⏵⏵ auto mode on", "⏸ plan mode", the ⏺ bullet.
 */
private val STAND_INS = mapOf(
    '⏵' to "▸", '⏴' to "◂", '⏶' to "▴", '⏷' to "▾", '⏸' to "‖", '⏹' to "■", '⏺' to "●",
    '⏻' to "●", '⏼' to "●", '⏽' to "|", '⏯' to "▸", '⏭' to "»", '⏮' to "«", '⏩' to "»", '⏪' to "«",
)
private val glyphPaint by lazy { android.graphics.Paint().apply { typeface = android.graphics.Typeface.MONOSPACE } }
private val drawable = HashMap<Int, Boolean>()

/** [t] with characters no font on this phone can draw swapped for a stand-in. */
private fun drawableText(t: String): String {
    if (t.all { it.code < 0x2300 }) return t
    val sb = StringBuilder(t.length)
    var i = 0
    while (i < t.length) {
        val cp = t.codePointAt(i)
        val n = Character.charCount(cp)
        val ok = cp < 0x2300 || drawable.getOrPut(cp) { glyphPaint.hasGlyph(String(Character.toChars(cp))) }
        val sub = if (ok) null else STAND_INS[t[i]]
        if (sub != null) sb.append(sub) else sb.append(t, i, i + n)
        i += n
    }
    return sb.toString()
}

private const val BOX_CHARS = "─━═╌╍┄┅┈┉╭╮╰╯┌┐└┘├┤┬┴┼╞╡│┃ "
private val SIDE = charArrayOf('│', '┃', '|')

/** A line as (text, style) runs, from the bridge's code-point runs. */
private fun segments(line: String, runs: IntArray?, styles: List<TermStyle>): List<Pair<String, TermStyle>> {
    if (runs == null || runs.isEmpty()) return listOf(line to TermStyle.Plain)
    val out = ArrayList<Pair<String, TermStyle>>()
    var at = 0
    var k = 0
    while (k + 1 < runs.size && at < line.length) {
        val end = try { line.offsetByCodePoints(at, runs[k]) } catch (e: IndexOutOfBoundsException) { line.length }
        out += line.substring(at, end) to (styles.getOrNull(runs[k + 1]) ?: TermStyle.Plain)
        at = end
        k += 2
    }
    if (at < line.length) out += line.substring(at) to TermStyle.Plain
    return out
}

private val GAP = Regex(" {6,}")

/**
 * Long runs of spaces inside a line (right-aligned status lines, columns padded for 130+
 * characters) shrink to three, so they don't wrap into empty rows. Indentation stays, up to
 * 11 spaces; deeper leading space is alignment and becomes four.
 */
private fun squeeze(segs: List<Pair<String, TermStyle>>): List<Pair<String, TermStyle>> {
    val text = segs.joinToString("") { it.first }
    val lead = text.indexOfFirst { it != ' ' }
    if (lead < 0) return segs
    // 12+ leading spaces are right-aligned or centred text, not indentation.
    val rest = if (lead >= 12) listOf("    " to TermStyle.Plain) + cut(segs, lead, text.length) else segs
    var started = false
    return rest.map { (t, st) ->
        if (started) return@map t.replace(GAP, "   ") to st
        val i = t.indexOfFirst { it != ' ' }
        if (i < 0) return@map t to st
        started = true
        t.substring(0, i) + t.substring(i).replace(GAP, "   ") to st
    }
}

/** Cuts [from, to) out of the line's runs. */
private fun cut(segs: List<Pair<String, TermStyle>>, from: Int, to: Int): List<Pair<String, TermStyle>> {
    val out = ArrayList<Pair<String, TermStyle>>()
    var at = 0
    for ((t, st) in segs) {
        val s = maxOf(from, at)
        val e = minOf(to, at + t.length)
        if (s < e) out += t.substring(s - at, e - at) to st
        at += t.length
    }
    return out
}

/**
 * The screen as wrapped, coloured blocks. Lines wider than the phone wrap instead of
 * scrolling sideways, so the boxes Claude Code draws around its prompt lose their side walls
 * (they'd wrap into a mess), full-width rules become hairlines, and runs of blank lines shrink to one.
 */
private fun blocks(sc: ScreenState): List<TermBlock> {
    val out = ArrayList<TermBlock>()
    val cur = ArrayList<List<Pair<String, TermStyle>>>()
    fun flush() {
        while (cur.isNotEmpty() && cur.last().all { it.first.isBlank() }) cur.removeAt(cur.lastIndex)
        if (cur.isEmpty()) return
        out += TermBlock.Text(buildAnnotatedString {
            cur.forEachIndexed { i, segs ->
                if (i > 0) append('\n')
                for ((raw, st) in segs) {
                    val t = drawableText(raw)
                    val span = spanFor(st)
                    if (span == null) append(t) else { pushStyle(span); append(t); pop() }
                }
            }
        })
        cur.clear()
    }
    var blank = 0
    sc.lines.forEachIndexed { i, line ->
        val segs = segments(line, sc.runs.getOrNull(i), sc.styles)
        val trimmed = line.trim()
        val horizontal = trimmed.count { it in "─━═╌╍┄┅┈┉" }
        if (trimmed.isNotEmpty() && horizontal >= 3 && trimmed.all { it in BOX_CHARS }) {
            flush()
            val st = segs.firstOrNull { it.first.isNotBlank() }?.second
            out += TermBlock.Rule(st?.let { TermPalette.color(it.fg) } ?: TermPalette.border)
            blank = 0
            return@forEachIndexed
        }
        var row = segs
        val first = line.indexOfFirst { !it.isWhitespace() }
        val last = line.indexOfLast { !it.isWhitespace() }
        if (first >= 0 && last > first && line[first] in SIDE && line[last] in SIDE) {
            var from = first + 1
            if (from < line.length && line[from] == ' ') from++
            var to = last
            while (to > from && line[to - 1] == ' ') to--
            row = cut(segs, from, to)
        }
        row = squeeze(row)
        if (row.all { it.first.isBlank() }) {
            if (++blank > 1 || cur.isEmpty()) return@forEachIndexed
            row = emptyList()
        } else {
            blank = 0
        }
        cur += row
    }
    flush()
    // Everything after the last rule is the status area (status line, mode hints).
    val lastRule = out.indexOfLast { it is TermBlock.Rule }
    if (lastRule >= 0) {
        for (k in lastRule + 1 until out.size) (out[k] as? TermBlock.Text)?.let { out[k] = it.copy(fit = true) }
    }
    return out
}

/**
 * The status lines under the prompt box, like on the laptop: one row each, all in the same
 * size, shrunk (down to 7 sp) until the longest fits; only beyond that would one wrap. The
 * indentation they all share goes, their alignment to each other stays.
 */
@Composable
private fun FitBlock(text: AnnotatedString, style: TextStyle) {
    val lines = remember(text) {
        val all = ArrayList<AnnotatedString>()
        var at = 0
        while (at <= text.length) {
            val end = text.indexOf('\n', at).let { if (it < 0) text.length else it }
            all += text.subSequence(at, end)
            at = end + 1
        }
        val indent = all.filter { it.isNotBlank() }.minOfOrNull { l -> l.indexOfFirst { it != ' ' }.coerceAtLeast(0) } ?: 0
        all.map { if (it.length >= indent) it.subSequence(indent, it.length) else it }
    }
    val measurer = rememberTextMeasurer()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val maxPx = constraints.maxWidth
        val size = remember(lines, maxPx) {
            val widest = lines.maxOfOrNull { measurer.measure(it, style, softWrap = false, maxLines = 1).size.width } ?: 0
            if (widest <= maxPx || widest == 0) style.fontSize
            else (style.fontSize.value * maxPx / widest * 0.97f).coerceAtLeast(7f).sp
        }
        val fitted = style.copy(fontSize = size, lineHeight = (size.value * 1.4f).sp)
        Column { for (l in lines) Text(l, style = fitted, softWrap = true) }
    }
}

private fun spanFor(st: TermStyle): SpanStyle? {
    if (st == TermStyle.Plain) return null
    var fg = TermPalette.color(st.fg)
    var bg = TermPalette.color(st.bg)
    if (st.reverse) {
        val f = fg ?: TermPalette.fg
        fg = bg ?: TermPalette.bg
        bg = f
    }
    // A near-black foreground on the default ground would vanish: lift it.
    if (fg != null && bg == null && fg.luminance() < 0.06f) fg = lerp(fg, TermPalette.fg, 0.55f)
    if (st.dim) fg = (fg ?: TermPalette.fg).copy(alpha = 0.55f)
    return SpanStyle(
        color = fg ?: Color.Unspecified,
        background = bg ?: Color.Unspecified,
        fontWeight = if (st.bold) FontWeight.Bold else null,
        fontStyle = if (st.italic) FontStyle.Italic else null,
        textDecoration = when {
            st.underline && st.strike -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            st.underline -> TextDecoration.Underline
            st.strike -> TextDecoration.LineThrough
            else -> null
        },
    )
}

@Composable
internal fun TerminalPanel(sc: ScreenState?, onRedraw: () -> Unit) {
    val c = Claude.colors
    val v = rememberScrollState()
    val content = remember(sc) { sc?.let { blocks(it) } }
    // Follow new output unless the user scrolled up to read; scrolling back down re-attaches.
    var follow by remember { mutableStateOf(true) }
    LaunchedEffect(v) {
        snapshotFlow { v.isScrollInProgress }.collect { busy -> if (!busy) follow = v.value >= v.maxValue - 48 }
    }
    LaunchedEffect(v) {
        snapshotFlow { v.maxValue }.collect { if (follow) v.scrollTo(it) }
    }
    Surface(
        color = TermPalette.bg, contentColor = TermPalette.fg, shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, TermPalette.border),
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp),
    ) {
        Column {
            Row(
                Modifier.fillMaxWidth().background(TermPalette.header).padding(horizontal = 14.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).background(c.accent, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text("Laptop terminal", fontSize = 12.sp, color = TermPalette.faint, modifier = Modifier.weight(1f))
                if (sc != null) Text("${sc.cols} cols", fontSize = 11.sp, color = TermPalette.faint, fontFamily = FontFamily.Monospace)
                Spacer(Modifier.width(6.dp))
                // Claude Code only redraws what changes: if parts look missing, have it repaint.
                Text(
                    "↻ Redraw", fontSize = 12.sp, color = TermPalette.fg.copy(alpha = 0.8f),
                    modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onRedraw)
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                )
            }
            Box(Modifier.fillMaxWidth().heightIn(max = 300.dp).verticalScroll(v)) {
                if (content == null) {
                    Text("Loading screen…", fontSize = 12.sp, color = TermPalette.faint, modifier = Modifier.padding(14.dp))
                } else {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        val style = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.5.sp, lineHeight = 15.5.sp, color = TermPalette.fg)
                        for (b in content) when (b) {
                            is TermBlock.Text -> if (b.fit) {
                                FitBlock(b.text, style)
                            } else {
                                Text(b.text, style = style, softWrap = true)
                            }
                            is TermBlock.Rule -> Box(
                                Modifier.fillMaxWidth().padding(vertical = 3.dp).height(1.dp).background(b.color.copy(alpha = 0.45f)),
                            )
                        }
                    }
                }
            }
        }
    }
}
