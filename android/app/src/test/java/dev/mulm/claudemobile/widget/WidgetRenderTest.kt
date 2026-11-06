package dev.mulm.claudemobile.widget

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.compose
import androidx.test.core.app.ApplicationProvider
import dev.mulm.claudemobile.App
import dev.mulm.claudemobile.Conn
import dev.mulm.claudemobile.MainActivity
import dev.mulm.claudemobile.Notifier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream
import kotlin.math.roundToInt

/**
 * Draws the widget the way a launcher would (Glance -> RemoteViews -> View) into PNGs under
 * app/build/widget-renders, to look at layout changes without a phone. preview-light/-dark.png
 * are the picker previews: copy them over res/drawable(-night)-nodpi/widget_preview.png.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w411dp-h914dp-xxhdpi")
class WidgetRenderTest {
    private val out = File(System.getProperty("widgetRenders") ?: "build/widget-renders").apply { mkdirs() }
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun s(id: String, title: String, project: String, status: String, connected: Boolean = true) =
        WidgetSession(id, title, project, status, connected)

    private val four = listOf(
        s("a", "Fix the login redirect loop", "webapp", "permission"),
        s("b", "Which database should the cache use?", "api", "question"),
        s("c", "Add a home-screen widget to the Android app", "claude-mobile", "working"),
        s("d", "Tidy up the bridge tests", "bridge", "idle"),
    )
    private val many = four + listOf(
        s("e", "Write the release notes", "docs", "ended"),
        s("f", "Spike: offline mode", "claude-mobile", "starting"),
        s("g", "Old laptop session", "dotfiles", "idle", connected = false),
    )
    private val connected = WidgetSnapshot(Conn.Connected, "omarchy", emptyList(), System.currentTimeMillis())
    private val light = 0xFF7A8C99.toInt()
    private val dark = 0xFF20262B.toInt()

    private fun view(snap: WidgetSnapshot, w: Int, h: Int, live: Boolean = true, configured: Boolean = true): View = runBlocking {
        val prefs = (ctx as App).prefs
        prefs.url = if (configured) "http://laptop:8787" else ""
        prefs.token = if (configured) "t" else ""
        WidgetStore.save(ctx, snap)
        WidgetStore.live.value = live
        val rv = SessionsWidget().compose(ctx, size = DpSize(w.dp, h.dp))
        val d = ctx.resources.displayMetrics.density
        rv.apply(ctx, FrameLayout(ctx)).apply {
            measure(
                View.MeasureSpec.makeMeasureSpec((w * d).roundToInt(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec((h * d).roundToInt(), View.MeasureSpec.EXACTLY),
            )
            layout(0, 0, measuredWidth, measuredHeight)
        }
    }

    /** [wallpaper] null leaves the corners transparent, as a preview image needs. */
    private fun render(name: String, snap: WidgetSnapshot, w: Int, h: Int, live: Boolean = true, configured: Boolean = true, wallpaper: Int? = light) {
        val v = view(snap, w, h, live, configured)
        val m = if (wallpaper != null) (16 * ctx.resources.displayMetrics.density).roundToInt() else 0
        val bmp = Bitmap.createBitmap(v.width + 2 * m, v.height + 2 * m, Bitmap.Config.ARGB_8888)
        Canvas(bmp).apply {
            if (wallpaper != null) drawColor(wallpaper)
            translate(m.toFloat(), m.toFloat())
            v.draw(this)
        }
        FileOutputStream(File(out, "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun light() {
        render("01-preview-4x2", PreviewSnapshot, 300, 190)
        render("02-four-4x2", connected.copy(sessions = four), 300, 190)
        render("03-many-4x2", connected.copy(sessions = many), 300, 190)
        render("04-many-4x4", connected.copy(sessions = many), 300, 400)
        render("05-two-4x2", connected.copy(sessions = four.take(2)), 300, 190)
        render("06-empty-connected", connected, 300, 190)
        render("07-offline-stale", connected.copy(conn = Conn.Offline, sessions = four.take(3), asOf = System.currentTimeMillis() - 3_600_000), 300, 190)
        render("08-offline-empty", connected.copy(conn = Conn.Offline), 300, 190)
        render("09-not-configured", WidgetSnapshot.Empty, 300, 190, configured = false)
        render("10-4x1-summary", connected.copy(sessions = four), 300, 96)
        render("11-3x2-narrow", connected.copy(sessions = four), 200, 190)
        render("12-dead-process", connected.copy(sessions = four.take(2)), 300, 190, live = false)
        render("13-one-4x1", connected.copy(sessions = four.take(1)), 300, 96)
        render("14-250w", connected.copy(sessions = four.take(2)), 250, 190)
        render("15-360x210-five", connected.copy(sessions = many.take(5)), 360, 210)
        render("preview-light", PreviewSnapshot, 300, 190, wallpaper = null)
    }

    @Config(qualifiers = "+night")
    @Test fun dark() {
        render("21-preview-dark", PreviewSnapshot, 300, 190, wallpaper = dark)
        render("22-four-dark", connected.copy(sessions = four), 300, 190, wallpaper = dark)
        render("23-empty-dark", connected.copy(conn = Conn.Offline), 300, 190, wallpaper = dark)
        render("preview-dark", PreviewSnapshot, 300, 190, wallpaper = null)
    }

    @Test fun bigFont() {
        RuntimeEnvironment.setFontScale(1.3f)
        render("31-four-font130", connected.copy(sessions = four), 300, 190)
        render("32-two-font130", connected.copy(sessions = four.take(2)), 300, 190)
    }

    /** Taps go where they should: a row to its chat, "+" to New session, anywhere else to the app. */
    @Test fun taps() {
        val v = view(connected.copy(sessions = four), 300, 190)
        fun all(x: View): List<View> =
            listOf(x) + ((x as? ViewGroup)?.let { g -> (0 until g.childCount).flatMap { all(g.getChildAt(it)) } } ?: emptyList())
        fun tap(x: View): Intent {
            var c: View? = x
            while (c != null && !c.hasOnClickListeners()) c = c.parent as? View
            c!!.performClick()
            shadowOf(Looper.getMainLooper()).idle()
            return shadowOf(ctx as Application).nextStartedActivity
        }
        val views = all(v)
        fun text(t: String) = views.first { it is TextView && it.text.toString() == t }

        val row = tap(text("Tidy up the bridge tests"))
        assertEquals(MainActivity::class.java.name, row.component?.className)
        assertEquals("d", row.getStringExtra(Notifier.EXTRA_SID))

        val plus = tap(views.first { it.contentDescription?.toString() == "New session" })
        assertTrue(plus.getBooleanExtra(MainActivity.EXTRA_NEW_SESSION, false))
        assertNull(plus.getStringExtra(Notifier.EXTRA_SID))

        val elsewhere = tap(text("Claude"))
        assertNull(elsewhere.getStringExtra(Notifier.EXTRA_SID))
        assertFalse(elsewhere.getBooleanExtra(MainActivity.EXTRA_NEW_SESSION, false))
    }
}
