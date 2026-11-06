package dev.mulm.claudemobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

/**
 * Brings the background connection back if the system or the OEM battery manager killed it.
 * An inexact 15-minute alarm plus a one-shot restart right after the app is swiped away.
 * Starting the foreground service from here is allowed because the app is exempt from
 * battery optimization (on Xiaomi, Autostart must also be on).
 */
object Watchdog {
    private const val PERIOD = AlarmManager.INTERVAL_FIFTEEN_MINUTES

    private fun pi(ctx: Context, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, WatchdogReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun schedule(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setInexactRepeating(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PERIOD, PERIOD, pi(ctx, 7))
    }

    fun restartSoon(ctx: Context) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 3_000, pi(ctx, 8))
    }
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // No-op if the service is already running (it just re-checks the socket).
        App.startService(context)
    }
}
