package dev.mulm.claudemobile

import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat

class App : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var bridge: BridgeClient
        private set

    /** Session whose chat is currently on screen (no "done" notifications for it). */
    @Volatile var visibleSession: String? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        ConnLog.init(this)
        ConnLog.info("App started · " + (runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "?"))
        ChatCache.init(this)
        prefs = Prefs(this)
        bridge = BridgeClient(this, prefs)
        Notifier.createChannels(this)
        Updater.cleanup(this)
    }

    companion object {
        lateinit var instance: App
            private set

        fun startService(ctx: Context) {
            if (!instance.prefs.configured) return
            Watchdog.schedule(ctx)
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, BridgeService::class.java))
            } catch (e: Exception) {
                // Background start refused (e.g. battery optimization back on): the next alarm retries.
            }
        }
    }
}
