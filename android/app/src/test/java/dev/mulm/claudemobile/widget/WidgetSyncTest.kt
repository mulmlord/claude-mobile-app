package dev.mulm.claudemobile.widget

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.mulm.claudemobile.Conn
import dev.mulm.claudemobile.SessionInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The widget's data flow (WidgetSync), in real time, and how rows are fitted. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class WidgetSyncTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()

    private fun si(id: String, status: String, updated: Double) =
        SessionInfo(id, "title $id", "proj", "/tmp", status, "", 0.0, updated, 0.0, "", true, emptyList())

    private fun ids() = WidgetStore.snapshot(ctx).value.sessions.map { it.id }

    @Test fun flow() = runBlocking {
        // As left behind by a process that's gone: claims Connected, which isn't believed.
        WidgetStore.save(ctx, WidgetSnapshot(Conn.Connected, "omarchy", listOf(WidgetSession("old", "t", "p", "idle", true)), 1000))
        WidgetStore.live.value = false
        assertEquals(Conn.Offline, WidgetStore.snapshot(ctx).value.shown(live = false, configured = true).conn)

        val conn = MutableStateFlow(Conn.Connecting)
        val host = MutableStateFlow("")
        val list = MutableStateFlow<List<SessionInfo>>(emptyList())
        val job = launch { WidgetSync.run(ctx, conn, host, list) }

        delay(300)
        assertFalse("nothing written in the first second", WidgetStore.live.value)
        delay(1000)
        var s = WidgetStore.snapshot(ctx).value
        assertTrue(WidgetStore.live.value)
        assertEquals(Conn.Connecting, s.conn)
        assertEquals("saved host kept", "omarchy", s.host)
        assertEquals("saved sessions kept until connected", listOf("old"), ids())
        assertTrue("Android 15+ picker preview published", WidgetStore.prefs(ctx).contains("preview_version"))

        // Connected, then the list a moment later: lands as one update, no "No sessions" in between.
        conn.value = Conn.Connected
        host.value = "omarchy"
        delay(30)
        list.value = listOf(si("a", "working", 10.0), si("b", "idle", 30.0), si("c", "permission", 5.0))
        delay(1300)
        s = WidgetStore.snapshot(ctx).value
        assertEquals(Conn.Connected, s.conn)
        assertEquals("needs you first, then most recent", listOf("c", "b", "a"), ids())

        // Two sessions trading places every 200 ms must not hold updates back.
        var t = 100.0
        val started = System.currentTimeMillis()
        var landed = false
        while (System.currentTimeMillis() - started < 3000) {
            t += 1
            list.value = if (t.toInt() % 2 == 0) listOf(si("a", "working", t), si("b", "working", t - 0.5))
            else listOf(si("a", "working", t - 0.5), si("b", "working", t))
            delay(200)
            if (ids().toSet() == setOf("a", "b")) landed = true
        }
        assertTrue("updates keep landing during a burst", landed)

        // Laptop goes away: the last list stays, stamped with when it was last heard from.
        list.value = listOf(si("a", "permission", 500.0))
        delay(1300)
        val before = System.currentTimeMillis()
        conn.value = Conn.Offline
        delay(1300)
        s = WidgetStore.snapshot(ctx).value
        assertEquals(Conn.Offline, s.conn)
        assertEquals(listOf("a"), ids())
        assertTrue("asOf stamped at the disconnect", s.asOf >= before - 50)

        // Service stops: no longer live.
        job.cancel()
        job.join()
        assertFalse(WidgetStore.live.value)
    }

    @Test fun fitting() {
        // A 300x190 dp widget leaves 130 dp for rows.
        assertEquals(Fit(3, true, 130f / 3, 0), fit(3, 130f, 1f))
        val four = fit(4, 130f, 1f)
        assertEquals(4, four.rows)
        assertFalse("four only fit as one-line rows", four.twoLine)
        assertEquals(0, four.more)
        assertEquals(4, fit(7, 130f, 1f).rows)
        assertEquals(3, fit(7, 130f, 1f).more)
        assertEquals("4x1 with several: summary line", 0, fit(4, 36f, 1f).rows)
        assertEquals(1, fit(1, 36f, 1f).rows)
        assertEquals(8, fit(12, 1000f, 1f).rows)
        assertEquals(4, fit(12, 1000f, 1f).more)
    }
}
