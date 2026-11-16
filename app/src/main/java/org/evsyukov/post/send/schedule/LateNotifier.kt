package org.evsyukov.post.send.schedule

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.evsyukov.post.MainActivity
import org.evsyukov.post.R
import org.evsyukov.post.send.AndroidSendNotifier

/** The notification about late posts (scheduling.md Q6). */
interface LateNotifier {
    /** "N posts missed their time", with Send now, Reschedule when [canReschedule], and Review. */
    fun showLate(count: Int, canReschedule: Boolean)

    /** A decision was made, or the Late group is empty. */
    fun cancelLate()
}

/**
 * Posts the late notification. It is ongoing, so the app never drops it before a decision; Android 14 and
 * later still let the user swipe it away, and the Late group and the composer's badge stay (Q7). Posting
 * it again with a new count updates it in place.
 */
class AndroidLateNotifier(context: Context) : LateNotifier {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    override fun showLate(count: Int, canReschedule: Boolean) {
        manager.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_LATE, NotificationManagerCompat.IMPORTANCE_DEFAULT)
                .setName(context.getString(R.string.schedule_channel_late))
                .setDescription(context.getString(R.string.schedule_channel_late_description))
                .build(),
        )
        val review = PendingIntent.getActivity(
            context,
            REQUEST_REVIEW,
            Intent(context, MainActivity::class.java)
                .addFlags(AndroidSendNotifier.OPEN_FLAGS)
                .putExtra(AndroidSendNotifier.EXTRA_OPEN, AndroidSendNotifier.OPEN_QUEUE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = context.getString(R.string.schedule_late_text)
        val builder = NotificationCompat.Builder(context, CHANNEL_LATE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(context.resources.getQuantityString(R.plurals.schedule_late_title, count, count))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(false)
            .setContentIntent(review)
            .addAction(0, context.getString(R.string.schedule_late_send_now), action(LateActionReceiver.ACTION_SEND_NOW, REQUEST_SEND_NOW))
        if (canReschedule) {
            builder.addAction(0, context.getString(R.string.schedule_late_reschedule), action(LateActionReceiver.ACTION_RESCHEDULE, REQUEST_RESCHEDULE))
        }
        builder.addAction(0, context.getString(R.string.schedule_late_review), review)
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        try {
            manager.notify(LATE_ID, builder.build())
        } catch (_: SecurityException) {
            // The permission was taken away between the check and the call.
        }
    }

    override fun cancelLate() {
        manager.cancel(LATE_ID)
    }

    private fun action(action: String, requestCode: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        requestCode,
        Intent(context, LateActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        const val CHANNEL_LATE = "late"

        /** The sender's ids are 1 and numbers that leave 0, 2 or 3 divided by 4; 5 leaves 1 and is free. */
        const val LATE_ID = 5
        private const val REQUEST_REVIEW = 3_000_001
        private const val REQUEST_SEND_NOW = 3_000_002
        private const val REQUEST_RESCHEDULE = 3_000_003
    }
}

/** Send now and Reschedule of the late notification (Q6). */
class LateActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val host = context.applicationContext as? SchedulingHost ?: return
        when (intent.action) {
            ACTION_SEND_NOW -> runAsync(host) { host.queueActions.sendHeldNow() }
            ACTION_RESCHEDULE -> runAsync(host) { host.queueActions.rescheduleHeld() }
        }
    }

    companion object {
        const val ACTION_SEND_NOW = "org.evsyukov.post.action.LATE_SEND_NOW"
        const val ACTION_RESCHEDULE = "org.evsyukov.post.action.LATE_RESCHEDULE"
    }
}
