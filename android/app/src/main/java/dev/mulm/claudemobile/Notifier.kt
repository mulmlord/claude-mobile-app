package dev.mulm.claudemobile

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Typeface
import android.os.Build
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.text.style.TypefaceSpan
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
    const val EXTRA_QID = "qid"
    const val EXTRA_DH = "dh"
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

    private fun action(
        ctx: Context, action: String, sid: String, extra: String = "", mutable: Boolean = false, qid: String = "", dh: String = "",
    ): PendingIntent {
        val i = Intent(ctx, ActionReceiver::class.java).apply {
            this.action = action
            putExtra(EXTRA_SID, sid)
            putExtra("choice", extra)
            if (qid.isNotEmpty()) putExtra(EXTRA_QID, qid)
            // The prompt this button answers; the bridge refuses if a different one is up by then.
            if (dh.isNotEmpty()) putExtra(EXTRA_DH, dh)
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(ctx, (action + sid + extra + dh).hashCode(), i, flags)
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
        // Newer bridges say which tool is waiting: one line collapsed, the diff or command expanded.
        val tool = ev.tool.takeIf { ev.kind == "permission" }
        val b = NotificationCompat.Builder(ctx, if (attention) CH_ATTENTION else CH_DONE)
            .setSmallIcon(R.drawable.ic_stat)
            .setColor(0xFFD97757.toInt())
            .setContentTitle("${ev.title} · $headline")
            .setContentText(tool?.let { toolLine(it) } ?: ev.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                tool?.takeIf { ev.waiting <= 1 }?.let { toolPreview(ctx, it) }
                    ?: ev.dialog.takeIf { it.isNotBlank() }?.let { dialogPreview(it) }
                    ?: ev.body
            ))
            .setContentIntent(openIntent(ctx, ev.sid))
            .setAutoCancel(true)
            .setCategory(if (attention) NotificationCompat.CATEGORY_REMINDER else NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setGroup("sessions")
        if (ev.kind == "permission") {
            b.addAction(0, "Allow", action(ctx, ActionReceiver.ANSWER, ev.sid, "yes", dh = ev.dh))
            b.addAction(0, "Deny", action(ctx, ActionReceiver.ANSWER, ev.sid, "no", dh = ev.dh))
        }
        if (ev.kind == "question") {
            // Android shows at most three action buttons; the rest stay in the chat.
            for (label in ev.options.take(3)) {
                b.addAction(0, label, action(ctx, ActionReceiver.CHOOSE, ev.sid, label, qid = ev.qid))
            }
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

    /** "Edit · app/src/x.kt (+3 −1)", "Bash · Build the app". */
    private fun toolLine(t: ToolPreview): String = buildString {
        append(t.name.ifBlank { "Tool" })
        if (t.summary.isNotBlank()) append(" · ").append(t.summary)
        if (t.diff != null) {
            val counts = listOfNotNull("+${t.added}".takeIf { t.added > 0 }, "−${t.removed}".takeIf { t.removed > 0 })
            if (counts.isNotEmpty()) append(" (").append(counts.joinToString(" ")).append(")")
        }
    }

    /**
     * Expanded permission notification: the tool line, then the diff or the command in
     * monospace. The "+"/"−" marks stay in the text because some systems drop colour spans
     * (and the shade only fits so many lines, which is why the diff is compacted).
     */
    /** The prompt exactly as the laptop shows it, monospace. */
    private fun dialogPreview(text: String): CharSequence {
        val sb = SpannableStringBuilder(text.lines().take(14).joinToString("\n"))
        sb.setSpan(TypefaceSpan("monospace"), 0, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    private fun toolPreview(ctx: Context, t: ToolPreview): CharSequence {
        val sb = SpannableStringBuilder()
        sb.append(toolLine(t), StyleSpan(Typeface.BOLD), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        val body = sb.length
        if (t.diff != null) {
            // The shade follows the system's dark mode, not the app's theme setting.
            val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
            val (add, del, faint) = if (night) Triple(0xFF7DB38A, 0xFFE5735F, 0xFF9C9A92) else Triple(0xFF3F7D4E, 0xFFB53333, 0xFF8E8C85)
            for ((kind, text) in compactDiff(t.diff)) {
                sb.append('\n')
                val start = sb.length
                sb.append(when (kind) { '+' -> "+ "; '-' -> "− "; '@' -> ""; else -> "  " }).append(text)
                val color = when (kind) { '+' -> add; '-' -> del; '@' -> faint; else -> null }
                if (color != null) sb.setSpan(ForegroundColorSpan(color.toInt()), start, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        } else if (t.detail.isNotBlank()) {
            sb.append('\n').append(if (t.name == "Bash") "$ " else "").append(t.detail.trim())
        }
        if (sb.length > body) sb.setSpan(TypefaceSpan("monospace"), body, sb.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        return sb
    }

    /**
     * Diff lines ([Item.diff] format) as (kind, text) with kind '+', '-', ' ' or '@' (a gap or
     * "… N more lines"): changed lines plus one line of context, and the indentation they share
     * removed, so the change itself fits the few narrow lines a notification shows.
     */
    private fun compactDiff(lines: List<String>): List<Pair<Char, String>> {
        fun changed(i: Int) = lines.getOrNull(i)?.let { it.startsWith("+") || it.startsWith("-") } == true
        val rows = ArrayList<Pair<Char, String>>()
        var gap = false
        for ((i, ln) in lines.withIndex()) {
            when {
                ln.startsWith("@") && ln.length > 1 -> rows += '@' to "… ${ln.drop(1)}"
                ln.isEmpty() || ln.startsWith("@") -> gap = true
                changed(i) || changed(i - 1) || changed(i + 1) -> {
                    if (gap && rows.isNotEmpty()) rows += '@' to "⋯"
                    gap = false
                    rows += ln[0] to ln.drop(1).replace("\t", "    ")
                }
                else -> gap = true
            }
        }
        val indent = rows.filter { it.first != '@' && it.second.isNotBlank() }
            .minOfOrNull { (_, s) -> s.length - s.trimStart(' ').length } ?: 0
        return rows.map { (k, s) -> if (k == '@') k to s else k to s.drop(indent).take(120) }
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
