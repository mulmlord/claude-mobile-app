package dev.mulm.claudemobile.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import dev.mulm.claudemobile.App
import dev.mulm.claudemobile.Conn
import dev.mulm.claudemobile.R
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * The real path: Glance's session worker on WorkManager renders into AppWidgetManager, and the
 * running session follows WidgetStore without another update call.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetEndToEndTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun texts(v: View?): List<String> = when (v) {
        is TextView -> listOf(v.text.toString())
        is ViewGroup -> (0 until v.childCount).flatMap { texts(v.getChildAt(it)) }
        else -> emptyList()
    }

    private fun shows(id: Int, text: String): Boolean {
        val awm = shadowOf(AppWidgetManager.getInstance(ctx))
        val end = System.currentTimeMillis() + 20_000
        while (System.currentTimeMillis() < end) {
            // Glance sessions run on the main looper; let its (delayed) work happen.
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
            if (texts(awm.getViewFor(id)).any { it.startsWith(text) }) return true
            Thread.sleep(20)
        }
        return false
    }

    @Test fun throughWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, Configuration.Builder().setExecutor(SynchronousExecutor()).build())
        val prefs = (ctx as App).prefs
        prefs.url = "http://laptop:8787"
        prefs.token = "t"
        WidgetStore.save(ctx, WidgetSnapshot(Conn.Connected, "omarchy", listOf(WidgetSession("a", "First session", "p", "working", true)), 1))
        WidgetStore.live.value = true

        val awm = shadowOf(AppWidgetManager.getInstance(ctx))
        awm.setAlwaysRecreateViewsDuringUpdate(true)
        // createWidget calls onUpdate directly, where goAsync() is null under Robolectric, so the
        // receiver's own update dies; the id is registered all the same and is updated by hand.
        val id = awm.createWidget(SessionsWidgetReceiver::class.java, R.layout.widget_loading)
        @Suppress("DEPRECATION") // still works: attaches provider info to the id createWidget made
        awm.putWidgetInfo(id, AppWidgetProviderInfo().apply {
            provider = ComponentName(ctx, SessionsWidgetReceiver::class.java)
            initialLayout = R.layout.widget_loading
            // No size options under Robolectric: Glance falls back to these (px, mdpi here).
            minWidth = 300
            minHeight = 190
        })
        runBlocking { SessionsWidget().update(ctx, GlanceAppWidgetManager(ctx).getGlanceIdBy(id)) }
        assertTrue("first render", shows(id, "First session"))

        WidgetStore.save(ctx, WidgetSnapshot(Conn.Connected, "omarchy", listOf(WidgetSession("b", "Second session", "p", "question", true)), 2))
        assertTrue("store change reaches the running session", shows(id, "Second session"))

        WidgetStore.live.value = false
        assertTrue("service gone: shown offline", shows(id, "Offline"))
    }
}
