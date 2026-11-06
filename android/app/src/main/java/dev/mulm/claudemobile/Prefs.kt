package dev.mulm.claudemobile

import android.content.Context
import androidx.core.content.edit

class Prefs(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var url: String
        get() = sp.getString("url", "") ?: ""
        set(v) = sp.edit { putString("url", v.trim().trimEnd('/')) }

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) = sp.edit { putString("token", v.trim()) }

    var notifyEnabled: Boolean
        get() = sp.getBoolean("notify", true)
        set(v) = sp.edit { putBoolean("notify", v) }

    /** Highest notification id already shown; the bridge replays newer ones after a reconnect. */
    var lastNotifyId: Long
        get() = sp.getLong("last_notify", 0L)
        set(v) = sp.edit { putLong("last_notify", v) }

    /** system | light | dark */
    var theme: String
        get() = sp.getString("theme", "system") ?: "system"
        set(v) = sp.edit { putString("theme", v) }

    val configured: Boolean get() = url.isNotBlank() && token.isNotBlank()
}
