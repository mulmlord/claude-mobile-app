package dev.mulm.claudemobile

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import dev.mulm.claudemobile.ui.AppRoot
import dev.mulm.claudemobile.ui.AppTheme
import dev.mulm.claudemobile.ui.Nav
import dev.mulm.claudemobile.ui.Route

class MainActivity : ComponentActivity() {
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleIntent(intent)
        App.startService(this)
        setContent { AppTheme { AppRoot() } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        App.instance.bridge.ensureConnected()
    }

    private fun handleIntent(i: Intent?) {
        if (i == null) return
        i.getStringExtra(Notifier.EXTRA_SID)?.let { sid ->
            Notifier.cancel(this, sid)
            Nav.go(Route.Chat(sid))
        }
        if (i.getBooleanExtra(EXTRA_NEW_SESSION, false)) {
            Nav.go(if (App.instance.prefs.configured) Route.NewSession else Route.Settings())
        }
        val data = i.data
        if (data != null && data.scheme == "claudemobile" && data.host == "setup") {
            // No token in the link (e.g. only a new address): keep the saved one.
            Nav.go(Route.Settings(url = data.getQueryParameter("url") ?: "", token = data.getQueryParameter("token")))
        }
    }

    companion object {
        /** Set by the home-screen widget's "+": open on New session. */
        const val EXTRA_NEW_SESSION = "new_session"
    }
}
