package io.github.ninte86hl.hugminn

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri

/** Progress notification for feedback uploads (screenshot, description, logs). */
class UploadNotification(private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)
    private val id = 4711

    init {
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, "Feedback-Uploads", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Fortschritt beim Hochladen von Screenshots und Berichten"
            })
        }
    }

    private fun base() = Notification.Builder(ctx, CHANNEL)
        .setSmallIcon(android.R.drawable.stat_sys_upload)
        .setContentTitle("Hugminn Feedback")
        .setOnlyAlertOnce(true)

    /** [percent] < 0 shows an indeterminate bar. */
    fun progress(text: String, percent: Int) {
        nm.notify(id, base()
            .setContentText(text)
            .setOngoing(true)
            .setProgress(100, percent.coerceIn(0, 100), percent < 0)
            .build())
    }

    fun done(title: String, text: String) {
        val open = PendingIntent.getActivity(
            ctx, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Ninte86hl/Hugminn/tree/feedback/feedback")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE,
        )
        nm.notify(id, base()
            .setSmallIcon(android.R.drawable.stat_sys_upload_done)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setTimeoutAfter(15_000)
            .build())
    }

    fun failed(title: String, text: String) {
        nm.notify(id, base()
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .build())
    }

    private companion object {
        const val CHANNEL = "feedback"
    }
}
