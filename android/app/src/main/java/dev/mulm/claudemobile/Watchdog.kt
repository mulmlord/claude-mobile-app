package dev.mulm.claudemobile

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Alarms that keep the background connection alive through deep sleep (Doze), where
 * coroutine delays and ordinary alarms wait for the next maintenance window, sometimes an
 * hour or more:
 * - a check every 15 minutes: restarts the service if the system or the OEM battery manager
 *   killed it, and pings the bridge in case the socket died while the phone slept;
 * - a one-shot reconnect when a retry is due later than a few seconds (BridgeClient.scheduleRetry);
 * - a one-shot restart right after the app is swiped away.
 * All are "allow while idle", so they also fire in deep sleep. Starting the foreground
 * service from here is allowed because the app is exempt from battery optimization (on
 * Xiaomi, Autostart must also be on).
 */
object Watchdog {
    private const val PERIOD = 15 * 60_000L
    const val ACTION_RETRY = "dev.mulm.claudemobile.RETRY"

    // The check keeps request code 7 and no action, so it replaces the repeating alarm older
    // versions set (same PendingIntent) instead of running alongside it.
    private fun checkIntent(ctx: Context, code: Int = 7) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, WatchdogReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun retryIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 9, Intent(ctx, WatchdogReceiver::class.java).setAction(ACTION_RETRY),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun alarms(ctx: Context) = ctx.getSystemService(AlarmManager::class.java)

    /** (Re)arms the 15-minute check; each check arms the next one. */
    fun schedule(ctx: Context) {
        alarms(ctx).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + PERIOD, checkIntent(ctx))
    }

    fun restartSoon(ctx: Context) {
        alarms(ctx).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + 3_000, checkIntent(ctx, 8))
    }

    fun retryIn(ctx: Context, ms: Long) {
        alarms(ctx).setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + ms, retryIntent(ctx))
    }

    fun cancelRetry(ctx: Context) = alarms(ctx).cancel(retryIntent(ctx))
}

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val bridge = App.instance.bridge
        // The alarm's own wakelock ends with onReceive; hold the CPU until the attempt settles.
        val wake = context.getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "claudemobile:watchdog")
            .apply { acquire(30_000) }
        val retry = intent.action == Watchdog.ACTION_RETRY
        if (retry && bridge.running) {
            bridge.retryNow()
        } else {
            // Re-arms the check and (re)starts the service, which tries to connect if it isn't.
            App.startService(context)
        }
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (!retry) bridge.probe()
                bridge.awaitSettled(20_000)
            } finally {
                if (wake.isHeld) wake.release()
                pending.finish()
            }
        }
    }
}
