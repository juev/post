package org.evsyukov.post

import android.Manifest
import android.app.Application
import android.app.NotificationManager
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.send.AndroidSendNotifier
import org.evsyukov.post.send.schedule.AndroidLateNotifier
import org.evsyukov.post.share.ShareTestApplication
import org.evsyukov.post.ui.composer.ComposerTags
import org.evsyukov.post.ui.posts.PostsTags
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * A notification tapped while the composer holds an unsaved draft (R5, R15, R32, Q6): the app opens the screen the
 * notification names over the composer, and the draft is still there on the way back.
 */
@RunWith(AndroidJUnit4::class)
@Config(application = ShareTestApplication::class, qualifiers = "w411dp-h891dp")
class NotificationTapTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val app: ShareTestApplication = ApplicationProvider.getApplicationContext()
    private val manager = app.getSystemService(NotificationManager::class.java)
    private var scenario: ActivityScenario<MainActivity>? = null
    private val account = Account(
        id = 7, network = Network.MASTODON, server = "https://mastodon.example", handle = "alice@mastodon.example",
        remoteId = "101", displayName = "Alice", avatarUrl = null, label = "", position = 0, state = AccountState.OK,
        limits = null, limitsFetchedAt = null, defaults = AccountOptions(), createdAt = Instant.EPOCH,
    )

    @After
    fun tearDown() {
        scenario?.close()
        if (app.container.database.isOpen) app.container.database.close()
    }

    private fun assertReachesTheRunningApp(intent: Intent) {
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK).isEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP).isNotEqualTo(0)
        assertThat(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP).isNotEqualTo(0)
    }

    @Test
    fun theNotificationsOfTheSenderReachTheRunningAppInsteadOfClearingIt() {
        shadowOf(app as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        AndroidSendNotifier(app).deliveryFailed(account, postId = 3, message = "no")
        AndroidSendNotifier(app).needsSignIn(account)
        AndroidLateNotifier(app).showLate(1, canReschedule = false)

        val notifications = shadowOf(manager).allNotifications
        assertThat(notifications).hasSize(3)
        notifications.forEach { assertReachesTheRunningApp(shadowOf(it.contentIntent).savedIntent) }
    }

    @Test
    fun aTappedNotificationOpensTheQueueOverTheComposerAndKeepsTheDraft() {
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(ComposerTags.EDITOR)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(ComposerTags.EDITOR).performTextInput("not saved yet")
        compose.waitForIdle()

        // A copy of the launch intent with the notification's extra: ActivityScenario finds its activity by it.
        scenario!!.onActivity { it.onNewIntent(Intent(it.intent).putExtra(AndroidSendNotifier.EXTRA_OPEN, AndroidSendNotifier.OPEN_QUEUE)) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(PostsTags.SCREEN)).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(PostsTags.SCREEN).assertIsDisplayed()

        compose.onNodeWithContentDescription(app.getString(R.string.back)).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(PostsTags.SCREEN)).fetchSemanticsNodes().isEmpty() }
        val text = compose.onNodeWithTag(ComposerTags.EDITOR).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.EditableText].text
        assertThat(text).isEqualTo("not saved yet")
    }
}
