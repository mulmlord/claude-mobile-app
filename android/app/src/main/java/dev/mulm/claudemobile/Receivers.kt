package dev.mulm.claudemobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.RemoteInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/** Handles "Reply" / "Allow" / "Deny" / a question's option straight from a notification. */
class ActionReceiver : BroadcastReceiver() {
    companion object {
        const val REPLY = "dev.mulm.claudemobile.REPLY"
        const val ANSWER = "dev.mulm.claudemobile.ANSWER"
        const val CHOOSE = "dev.mulm.claudemobile.CHOOSE"
        /** Deny a permission prompt and send the typed text as what Claude should do instead. */
        const val DENY_WITH = "dev.mulm.claudemobile.DENY_WITH"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val sid = intent.getStringExtra(Notifier.EXTRA_SID) ?: return
        val bridge = (context.applicationContext as App).bridge
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (intent.action) {
                    REPLY -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
                        if (!text.isNullOrBlank()) {
                            bridge.sendText(sid, text)
                            Notifier.confirm(context, sid, "Sent: ${text.take(60)}", error = false)
                        }
                    }
                    ANSWER -> {
                        val choice = intent.getStringExtra("choice") ?: "yes"
                        bridge.answer(sid, choice, intent.getStringExtra(Notifier.EXTRA_DH))
                        Notifier.confirm(context, sid, if (choice == "no") "Denied" else "Allowed", error = false)
                    }
                    DENY_WITH -> {
                        val text = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifier.KEY_REPLY)?.toString()
                        bridge.answer(sid, "no", intent.getStringExtra(Notifier.EXTRA_DH), text?.trim())
                        Notifier.confirm(context, sid, if (text.isNullOrBlank()) "Denied" else "Denied · told Claude: ${text.take(50)}", error = false)
                    }
                    CHOOSE -> {
                        val label = intent.getStringExtra("choice").orEmpty()
                        if (label.isNotBlank()) {
                            bridge.choose(sid, label, intent.getStringExtra(Notifier.EXTRA_QID))
                            Notifier.confirm(context, sid, "Answered: ${label.take(60)}", error = false)
                        }
                    }
                }
            } catch (e: Exception) {
                Notifier.confirm(context, sid, "Failed: ${e.message ?: "laptop unreachable"}", error = true)
            } finally {
                pending.finish()
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        App.startService(context)
    }
}
