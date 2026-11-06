package dev.mulm.claudemobile.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.cos
import kotlin.math.sin

// Same geometry as the launcher icon (scratch generator spark.py): 12 tapered,
// round-tipped rays of slightly uneven length and angle around a filled hub.
private val RayLength = floatArrayOf(1f, .80f, .94f, .76f, 1f, .86f, .90f, .78f, .97f, .83f, .92f, .80f)
private val RayJitter = floatArrayOf(0f, 3f, -2f, 4f, -3f, 2f, 0f, -4f, 3f, -2f, 4f, -1f)

private fun sparkPath(c: Offset, r: Float): Path = Path().apply {
    val rb = .10f * r
    val hb = .12f * r
    val ht = .088f * r
    for (i in 0 until 12) {
        val deg = i * 30f + RayJitter[i] - 90f
        val a = Math.toRadians(deg.toDouble())
        val ux = cos(a).toFloat()
        val uy = sin(a).toFloat()
        val px = -uy
        val py = ux
        val l = RayLength[i] * r - ht
        val tip = Offset(c.x + ux * l, c.y + uy * l)
        moveTo(c.x + ux * rb + px * hb, c.y + uy * rb + py * hb)
        lineTo(tip.x + px * ht, tip.y + py * ht)
        arcTo(Rect(tip, ht), deg + 90f, -180f, false)
        lineTo(c.x + ux * rb - px * hb, c.y + uy * rb - py * hb)
        close()
    }
    addOval(Rect(c, .20f * r))
}

/** The app's terracotta spark. [working] makes it turn and breathe, like Claude thinking. */
@Composable
fun Spark(modifier: Modifier = Modifier, size: Dp = 24.dp, color: Color = Claude.colors.accent, working: Boolean = false) {
    var angle = 0f
    var pulse = 1f
    if (working) {
        val t = rememberInfiniteTransition(label = "spark")
        val a by t.animateFloat(0f, 360f, infiniteRepeatable(tween(5200, easing = LinearEasing)), label = "turn")
        val p by t.animateFloat(.86f, 1.04f, infiniteRepeatable(tween(900), RepeatMode.Reverse), label = "breathe")
        angle = a
        pulse = p
    }
    Canvas(modifier.size(size)) {
        val path = sparkPath(center, this.size.minDimension / 2f)
        rotate(angle) { scale(pulse) { drawPath(path, color) } }
    }
}

private fun strokeIcon(name: String, block: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit) =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
        fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = 2.2f,
        strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
    ).build()

/** ↑, as on claude.ai's send button. */
val ArrowUpIcon: ImageVector by lazy {
    strokeIcon("ArrowUp") {
        moveTo(12f, 19f); lineTo(12f, 5.5f)
        moveTo(5.8f, 11.5f); lineTo(12f, 5.3f); lineTo(18.2f, 11.5f)
    }
}

val StopIcon: ImageVector by lazy {
    ImageVector.Builder("Stop", 24.dp, 24.dp, 24f, 24f).path(fill = SolidColor(Color.Black)) {
        moveTo(7.5f, 6f); lineTo(16.5f, 6f)
        arcTo(1.5f, 1.5f, 0f, false, true, 18f, 7.5f); lineTo(18f, 16.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 16.5f, 18f); lineTo(7.5f, 18f)
        arcTo(1.5f, 1.5f, 0f, false, true, 6f, 16.5f); lineTo(6f, 7.5f)
        arcTo(1.5f, 1.5f, 0f, false, true, 7.5f, 6f); close()
    }.build()
}

val TerminalIcon: ImageVector by lazy {
    strokeIcon("Terminal") {
        moveTo(5f, 7.5f); lineTo(9.5f, 12f); lineTo(5f, 16.5f)
        moveTo(12.5f, 17f); lineTo(19f, 17f)
    }
}

val MicIcon: ImageVector by lazy {
    strokeIcon("Mic") {
        moveTo(9f, 6f); arcTo(3f, 3f, 0f, false, true, 15f, 6f); lineTo(15f, 11f)
        arcTo(3f, 3f, 0f, false, true, 9f, 11f); close()
        moveTo(5.5f, 11f); arcTo(6.5f, 6.5f, 0f, false, false, 18.5f, 11f)
        moveTo(12f, 17.5f); lineTo(12f, 21f)
    }
}
