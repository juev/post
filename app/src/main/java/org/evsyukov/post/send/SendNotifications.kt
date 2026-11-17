package org.evsyukov.post.send

import android.Manifest
import android.app.Notification
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
import org.evsyukov.post.PostApplication
import org.evsyukov.post.R
import org.evsyukov.post.data.accounts.Account

/** What the sender tells the user (product.md R5, R32). */
interface SendNotifier {
    /** A delivery failed for good: which account, and the server's message; opens the queue. */
    fun deliveryFailed(account: Account, postId: Long, message: String)

    /** The account's server rejected the credentials (R5); opens the account's details. */
    fun needsSignIn(account: Account)

    /** "Posted to N accounts", sent only when the setting is on (R32). */
    fun posted(postId: Long, accounts: Int)
}

/** What a run shows in its foreground notification. */
data class SendProgress(
    /** The post whose video is being converted; the Cancel action then puts it back to the drafts (A7). */
    val transcodingPostId: Long? = null,
    /** 0..100 while converting, null otherwise. */
    val percent: Int? = null,
    /** The media of queued posts is being prepared and nothing is uploaded yet (A4). */
    val preparing: Boolean = false,
)

/** The foreground of a run that uploads large files or converts video (R32, scheduling.md M2, M5). */
fun interface SendForeground {
    /** Shows [progress]; failing to go to the foreground does not stop the run. */
    suspend fun show(progress: SendProgress)
}

/** The notifications of the sender, posted through [NotificationManagerCompat] with `ic_notification`. */
class AndroidSendNotifier(context: Context) : SendNotifier {
    private val context = context.applicationContext
    private val manager = NotificationManagerCompat.from(this.context)

    /** Creates the channels; creating them again changes nothing the user set. */
    fun createChannels() {
        val channels = listOf(
            channel(CHANNEL_SENDING, NotificationManagerCompat.IMPORTANCE_LOW, R.string.send_channel_sending, R.string.send_channel_sending_description),
            channel(CHANNEL_FAILURES, NotificationManagerCompat.IMPORTANCE_DEFAULT, R.string.send_channel_failures, R.string.send_channel_failures_description),
            channel(CHANNEL_SUCCESS, NotificationManagerCompat.IMPORTANCE_LOW, R.string.send_channel_success, R.string.send_channel_success_description),
            channel(CHANNEL_ACCOUNTS, NotificationManagerCompat.IMPORTANCE_DEFAULT, R.string.send_channel_accounts, R.string.send_channel_accounts_description),
        )
        manager.createNotificationChannelsCompat(channels)
    }

    override fun deliveryFailed(account: Account, postId: Long, message: String) {
        val notification = builder(CHANNEL_FAILURES)
            .setContentTitle(context.getString(R.string.send_failed_title, account.handle))
            .setContentText(message)
            .setStyle(NotificationCompat.BigTextStyle().bigText(message))
            .setContentIntent(openIntent(OPEN_QUEUE, null, requestCode = (FAILURE_BASE + postId).toInt()))
            .setAutoCancel(true)
            .build()
        notify(failureId(postId, account.id), notification)
    }

    override fun needsSignIn(account: Account) {
        val text = context.getString(R.string.send_needs_sign_in_text)
        val notification = builder(CHANNEL_ACCOUNTS)
            .setContentTitle(context.getString(R.string.send_needs_sign_in_title, account.handle))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(openIntent(OPEN_ACCOUNT, account.id, requestCode = (ACCOUNT_BASE + account.id).toInt()))
            .setAutoCancel(true)
            .build()
        notify(signInId(account.id), notification)
    }

    override fun posted(postId: Long, accounts: Int) {
        val notification = builder(CHANNEL_SUCCESS)
            .setContentTitle(context.resources.getQuantityString(R.plurals.send_success_title, accounts, accounts))
            .setContentIntent(openIntent(null, null, requestCode = 0))
            .setAutoCancel(true)
            .build()
        notify(successId(postId), notification)
    }

    /** The notification of a run in the foreground: progress, and Cancel while a video is converted (A7). */
    fun progressNotification(progress: SendProgress): Notification {
        val builder = builder(CHANNEL_SENDING)
            .setContentTitle(context.getString(R.string.send_progress_title))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
        val postId = progress.transcodingPostId
        if (postId != null) {
            val percent = progress.percent ?: 0
            builder.setContentText(context.getString(R.string.send_progress_preparing, percent))
                .setProgress(100, percent, false)
                .addAction(0, context.getString(R.string.send_cancel), cancelIntent(postId))
        } else if (progress.preparing) {
            builder.setContentText(context.getString(R.string.send_progress_preparing_media)).setProgress(0, 0, true)
        } else {
            builder.setContentText(context.getString(R.string.send_progress_uploading)).setProgress(0, 0, true)
        }
        return builder.build()
    }

    private fun builder(channel: String) = NotificationCompat.Builder(context, channel)
        .setSmallIcon(R.drawable.ic_notification)

    private fun notify(id: Int, notification: Notification) {
        createChannels()
        val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (!granted) return
        try {
            manager.notify(id, notification)
        } catch (_: SecurityException) {
            // The permission was taken away between the check and the call.
        }
    }

    private fun channel(id: String, importance: Int, name: Int, description: Int) =
        NotificationChannelCompat.Builder(id, importance)
            .setName(context.getString(name))
            .setDescription(context.getString(description))
            .build()

    private fun openIntent(open: String?, accountId: Long?, requestCode: Int): PendingIntent {
        val intent = Intent(context, MainActivity::class.java)
            // The open app takes the intent as it is (MainActivity.onNewIntent): clearing the task would drop
            // an unsaved draft in the composer without the R15 question.
            .addFlags(OPEN_FLAGS)
        if (open != null) intent.putExtra(EXTRA_OPEN, open)
        if (accountId != null) intent.putExtra(EXTRA_ACCOUNT_ID, accountId)
        return PendingIntent.getActivity(context, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun cancelIntent(postId: Long): PendingIntent {
        val intent = Intent(context, CancelSendReceiver::class.java).putExtra(EXTRA_POST_ID, postId)
        return PendingIntent.getBroadcast(context, postId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    companion object {
        const val CHANNEL_SENDING = "sending"
        const val CHANNEL_FAILURES = "failures"
        const val CHANNEL_SUCCESS = "success"
        const val CHANNEL_ACCOUNTS = "accounts"

        /**
         * How a notification opens MainActivity: a running app gets the intent in onNewIntent and opens the screen
         * over what it shows; otherwise a new task starts.
         */
        const val OPEN_FLAGS = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP

        /** The extra of MainActivity that names the screen to open: [OPEN_QUEUE] or [OPEN_ACCOUNT]. */
        const val EXTRA_OPEN = "org.evsyukov.post.extra.OPEN"
        const val EXTRA_ACCOUNT_ID = "org.evsyukov.post.extra.ACCOUNT_ID"
        const val EXTRA_POST_ID = "org.evsyukov.post.extra.POST_ID"
        const val OPEN_QUEUE = "queue"
        const val OPEN_ACCOUNT = "account"

        /** The id of the foreground notification of a run. */
        const val PROGRESS_ID = 1

        private const val FAILURE_BASE = 1_000_000L
        private const val ACCOUNT_BASE = 2_000_000L

        fun failureId(postId: Long, accountId: Long): Int = (31 * postId + accountId).toInt() * 4 + 2
        fun signInId(accountId: Long): Int = accountId.toInt() * 4 + 3
        fun successId(postId: Long): Int = postId.toInt() * 4 + 4
    }
}

/** The Cancel action of the conversion notification (media.md A7): stops it and returns the post to the drafts. */
class CancelSendReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val postId = intent.getLongExtra(AndroidSendNotifier.EXTRA_POST_ID, -1)
        if (postId < 0) return
        val host = context.applicationContext as? PublisherHost ?: return
        host.publisher.cancelPreparation(postId)
    }
}

/** The application hands workers and receivers the [Publisher], as [PostApplication] does. */
interface PublisherHost {
    val publisher: Publisher
    val publishScheduler: PublishScheduler
}
