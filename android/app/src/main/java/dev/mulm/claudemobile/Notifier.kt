package dev.mulm.claudemobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat

object Notifier {
    const val CH_ATTENTION = "attention"
    const val CH_DONE = "done"
    const val CH_SERVICE = "service"
    const val SERVICE_ID = 1
    const val EXTRA_SID = "sid"
    const val KEY_REPLY = "reply"

    fun createChannels(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_ATTENTION, "Claude needs you", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Permission prompts and questions"
        })
        nm.createNotificationChannel(NotificationChannel(CH_DONE, "Claude finished", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "A session finished its turn and is waiting for you"
        })
        nm.createNotificationChannel(NotificationChannel(CH_SERVICE, "Background connection", NotificationManager.IMPORTANCE_MIN).apply {
            description = "Keeps the connection to your laptop open"
            setShowBadge(false)
        })
    }

    fun idFor(sid: String) = 1000 + (sid.hashCode() and 0x7fffffff) % 1_000_000

    private fun openIntent(ctx: Context, sid: String?): PendingIntent {
        val i = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (sid != null) putExtra(EXTRA_SID, sid)
        }
        return PendingIntent.getActivity(ctx, sid?.hashCode() ?: 0, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun action(ctx: Context, action: String, sid: String, extra: String = "", mutable: Boolean = false): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_SID, sid)
            putExtra("choice", extra)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(ctx, (action + sid + extra).hashCode(), i, flags)
    }

    fun serviceNotification(ctx: Context, text: String): Notification =
        NotificationCompat.Builder(ctx, CH_SERVICE)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFFD97757.toInt())
            .setContentTitle(text)
            .setContentIntent(openIntent(ctx, null))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()

    fun show(ctx: Context, ev: NotifyEvent) {
        if (!canPost(ctx)) return
        val attention = ev.kind == "permission" || ev.kind == "question"
        val headline = when (ev.kind) {
            "permission" -> "Needs permission"
            "question" -> "Has a question"
            else -> "Finished"
        }
        val b = NotificationCompat.Builder(ctx, if (attention) CH_ATTENTION else CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFFD97757.toInt())
            .setContentTitle("${ev.title} · $headline")
            .setContentText(ev.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(ev.body))
            .setContentIntent(openIntent(ctx, ev.sid))
            .setAutoCancel(true)
            .setCategory(if (attention) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup("sessions")
        if (ev.kind == "permission") {
            b.addAction(0, "Allow", action(ctx, ActionReceiver.ANSWER, ev.sid, "yes"))
            b.addAction(0, "Deny", action(ctx, ActionReceiver.ANSWER, ev.sid, "no"))
        }
        if (ev.kind == "done") {
            val input = RemoteInput.Builder(KEY_REPLY).setLabel("Message Claude").build()
            b.addAction(
                NotificationCompat.Action.Builder(0, "Reply", action(ctx, ActionReceiver.REPLY, ev.sid, mutable = true))
                    .addRemoteInput(input)
                    .setAllowGeneratedReplies(false)
                    .build()
            )
        }
        NotificationManagerCompat.from(ctx).notify(idFor(ev.sid), b.build())
    }

    /** Replace a session notification with a short confirmation (needed after RemoteInput). */
    fun confirm(ctx: Context, sid: String, text: String, error: Boolean) {
        if (!canPost(ctx)) return
        val n = NotificationCompat.Builder(ctx, CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFFD97757.toInt())
            .setContentTitle(text)
            .setContentIntent(openIntent(ctx, sid))
            .setAutoCancel(true)
            .setSilent(true)
            .setTimeoutAfter(if (error) 60_000 else 4_000)
            .build()
        NotificationManagerCompat.from(ctx).notify(idFor(sid), n)
    }

    fun cancel(ctx: Context, sid: String) = NotificationManagerCompat.from(ctx).cancel(idFor(sid))

    private fun canPost(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
