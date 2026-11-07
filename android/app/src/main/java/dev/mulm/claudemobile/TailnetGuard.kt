package dev.mulm.claudemobile

import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/**
 * Tailnet addresses only make sense through the Tailscale VPN. If Tailscale is off (paused,
 * killed by the system, still reconnecting), a *.ts.net name would be resolved by whatever
 * DNS the current Wi-Fi offers, and the bridge token would go to that answer in clear text.
 * So requests to tailnet hosts are refused until a VPN is up.
 */
object TailnetGuard {
    fun isTailnet(host: String): Boolean {
        if (host.endsWith(".ts.net", ignoreCase = true)) return true
        if (host.startsWith("fd7a:115c:a1e0", ignoreCase = true) || host.startsWith("[fd7a:115c:a1e0", ignoreCase = true)) return true
        val p = host.split('.')
        val a = p.getOrNull(0)?.toIntOrNull()
        val b = p.getOrNull(1)?.toIntOrNull()
        return p.size == 4 && a == 100 && b != null && b in 64..127  // Tailscale's 100.64.0.0/10
    }

    fun blocked(host: String): Boolean {
        if (!isTailnet(host)) return false
        if (tunnelUp() || vpnNetworkSeen()) return false
        android.util.Log.w("TailnetGuard", "refusing $host: no VPN network and no Tailscale tunnel interface")
        return true
    }

    /** Tailscale's tun interface holds the phone's tailnet address. Checked directly because
     *  what ConnectivityManager reports to an app in the background can lag or be filtered. */
    private fun tunnelUp(): Boolean = try {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().any { ni ->
            ni.isUp && ni.name.startsWith("tun") && ni.inetAddresses.toList().any { a ->
                val b = a.address
                b.size == 4 && (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 64
            }
        }
    } catch (e: Exception) {
        false
    }

    private fun vpnNetworkSeen(): Boolean {
        val cm = App.instance.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        val nets = listOfNotNull(cm.activeNetwork) + cm.allNetworks
        return nets.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true }
    }

    const val MESSAGE = "Tailscale is off on this phone"

    private const val TAILSCALE = "com.tailscale.ipn"

    /** Opens the Tailscale app so the user can reconnect it; false if it isn't installed. */
    fun openTailscale(ctx: android.content.Context): Boolean {
        val i = ctx.packageManager.getLaunchIntentForPackage(TAILSCALE) ?: return false
        ctx.startActivity(i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
        return true
    }
}
