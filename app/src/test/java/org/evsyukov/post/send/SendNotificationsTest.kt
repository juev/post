package org.evsyukov.post.send

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.R
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.db.AccountState
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Instant

/** The notifications of the sender (product.md R5, R32) as Android shows them. */
@RunWith(AndroidJUnit4::class)
class SendNotificationsTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = AndroidSendNotifier(context)
    private val account = Account(
        id = 7,
        network = Network.MASTODON,
        server = "https://mastodon.example",
        handle = "alice@mastodon.example",
        remoteId = "101",
        displayName = "Alice",
        avatarUrl = null,
        label = "",
        position = 0,
        state = AccountState.OK,
        limits = null,
        limitsFetchedAt = null,
        defaults = AccountOptions(),
        createdAt = Instant.EPOCH,
    )

    @Before
    fun grant() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun posted() = shadowOf(manager).allNotifications

    @Test
    fun aFailureNamesTheAccountAndTheServersMessageAndOpensTheQueue() {
        notifier.deliveryFailed(account, postId = 3, message = "Validation failed: Text too long")

        val notification = posted().single()
        val shadow = shadowOf(notification)
        assertThat(shadow.contentTitle.toString()).isEqualTo("Could not post to alice@mastodon.example")
        assertThat(shadow.contentText.toString()).isEqualTo("Validation failed: Text too long")
        assertThat(notification.channelId).isEqualTo(AndroidSendNotifier.CHANNEL_FAILURES)
        assertThat(notification.smallIcon.resId).isEqualTo(R.drawable.ic_notification)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertThat(intent.getStringExtra(AndroidSendNotifier.EXTRA_OPEN)).isEqualTo(AndroidSendNotifier.OPEN_QUEUE)
    }

    @Test
    fun needsSignInOpensTheAccountDetails() {
        notifier.needsSignIn(account)

        val notification = posted().single()
        assertThat(shadowOf(notification).contentTitle.toString()).isEqualTo("Sign in to alice@mastodon.example again")
        assertThat(notification.channelId).isEqualTo(AndroidSendNotifier.CHANNEL_ACCOUNTS)
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertThat(intent.getStringExtra(AndroidSendNotifier.EXTRA_OPEN)).isEqualTo(AndroidSendNotifier.OPEN_ACCOUNT)
        assertThat(intent.getLongExtra(AndroidSendNotifier.EXTRA_ACCOUNT_ID, -1)).isEqualTo(7)
    }

    @Test
    fun theSuccessSummaryCountsTheAccounts() {
        notifier.posted(postId = 3, accounts = 2)

        val notification = posted().single()
        assertThat(shadowOf(notification).contentTitle.toString()).isEqualTo("Posted to 2 accounts")
        assertThat(notification.channelId).isEqualTo(AndroidSendNotifier.CHANNEL_SUCCESS)
    }

    @Test
    fun theChannelsExist() {
        notifier.createChannels()
        assertThat(manager.notificationChannels.map { it.id }).containsAtLeast(
            AndroidSendNotifier.CHANNEL_SENDING,
            AndroidSendNotifier.CHANNEL_FAILURES,
            AndroidSendNotifier.CHANNEL_SUCCESS,
            AndroidSendNotifier.CHANNEL_ACCOUNTS,
        )
        assertThat(manager.getNotificationChannel(AndroidSendNotifier.CHANNEL_SENDING).importance).isEqualTo(NotificationManager.IMPORTANCE_LOW)
    }

    @Test
    fun withoutThePermissionNothingIsPosted() {
        shadowOf(context as Application).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        notifier.deliveryFailed(account, postId = 3, message = "x")
        assertThat(posted()).isEmpty()
    }

    @Test
    fun theConversionProgressOffersCancelForThePost() {
        val notification = notifier.progressNotification(SendProgress(transcodingPostId = 5, percent = 40))
        assertThat(shadowOf(notification).contentText.toString()).isEqualTo("Converting video… 40%")
        assertThat(notification.actions.single().title.toString()).isEqualTo("Cancel")
        val intent = shadowOf(notification.actions.single().actionIntent).savedIntent
        assertThat(intent.getLongExtra(AndroidSendNotifier.EXTRA_POST_ID, -1)).isEqualTo(5)
        assertThat(notifier.progressNotification(SendProgress()).actions).isNull()
    }

    @Test
    fun thePublisherNotifiesFailuresThroughTheAndroidNotifier() {
        // End to end: a 422 from the server ends as a notification with the server's message.
        SendFixture().use { f ->
            val publisher = Publisher(
                posts = f.posts,
                accounts = f.accounts,
                settings = { f.settings },
                freezer = PlanFreezer(org.evsyukov.post.text.AndroidTextSegmenter()),
                executors = listOf(MastodonExecutor(f.servers.http, f.sessions, f.accounts, f.clock, sleep = {})),
                preparer = f.preparer,
                filesDir = f.filesDir,
                notifier = notifier,
                scheduleRetry = {},
                trigger = {},
                clock = f.clock,
            )
            val server = FakeMastodon(f.servers) { f.clock.now }
            server.statusAnswer = { _, _ -> FakeMastodon.error(422, "Text too long") }
            val alice = f.mastodon()
            f.queue("Hello", listOf(alice))

            blocking { publisher.run() }

            val notification = posted().single()
            assertThat(shadowOf(notification).contentText.toString()).isEqualTo("Text too long")
            assertThat(shadowOf(notification).contentTitle.toString()).isEqualTo("Could not post to alice@mastodon.example")
        }
    }
}
