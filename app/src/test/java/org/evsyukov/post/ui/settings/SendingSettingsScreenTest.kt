package org.evsyukov.post.ui.settings

import android.content.Context
import android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
import android.provider.Settings.EXTRA_APP_PACKAGE
import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.AppContainer
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.schedule.LateThreshold
import org.evsyukov.post.core.schedule.isLate
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.history.HistoryItem
import org.evsyukov.post.data.posts.DeliveryPlan
import org.evsyukov.post.data.posts.DeliveryProgress
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.PartProgress
import org.evsyukov.post.data.posts.PlannedPart
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.PublishedPart
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.settings.HistoryRetention
import org.evsyukov.post.data.settings.Settings
import org.evsyukov.post.data.settings.VideoQuality
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.theme.PostTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import java.time.Duration
import java.time.Instant

/** Settings → Sending on the app's settings (screens.md; R31, R32, Q4, T11, A6). */
@RunWith(AndroidJUnit4::class)
class SendingSettingsScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val container = AppContainer(ApplicationProvider.getApplicationContext<Context>()) { FakeCipher() }

    @After
    fun tearDown() {
        // Ends the activity first, so no screen still reads the database when it closes (#15).
        runCatching { compose.activityRule.scenario.close() }
        if (container.database.isOpen) container.database.close()
    }

    private fun show() {
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides container) { PostTheme { SendingSettingsScreen(onBack = {}) } }
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag(SendingTags.LIST)).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun node(tag: String): SemanticsNodeInteraction {
        compose.onNodeWithTag(SendingTags.LIST).performScrollToNode(hasTestTag(tag))
        return compose.onNodeWithTag(tag)
    }

    private fun option(group: String, value: Enum<*>) = node(SettingsSectionTags.option(group, value))

    private fun stored(): Settings = runBlocking { container.settings.current() }

    private fun waitStored(check: (Settings) -> Boolean) = compose.waitUntil(5_000) { check(stored()) }

    @Test
    fun aNewInstallShowsTheDefaultsOfTheSpec() {
        show()

        node(SendingTags.SUCCESS_NOTIFICATIONS).assertIsOff()
        option(SendingTags.HISTORY, HistoryRetention.DAYS_30).assertIsSelected()
        option(SendingTags.HISTORY, HistoryRetention.OFF).assertIsNotSelected()
        option(SendingTags.LATE_THRESHOLD, LateThreshold.MINUTES_30).assertIsSelected()
        node(SendingTags.NUMBER_PARTS).assertIsOff()
        option(SendingTags.VIDEO_QUALITY, VideoQuality.STANDARD).assertIsSelected()
        node(SendingTags.KEEP_METADATA).assertIsOff()
    }

    @Test
    fun storedValuesAreShown() {
        runBlocking {
            container.settings.setSuccessNotifications(true)
            container.settings.setHistoryRetention(HistoryRetention.DAYS_90)
            container.settings.setLateThreshold(LateThreshold.HOURS_3)
            container.settings.setNumberThreadParts(true)
            container.settings.setVideoQuality(VideoQuality.HIGH)
            container.settings.setKeepImageMetadata(true)
        }
        show()

        node(SendingTags.SUCCESS_NOTIFICATIONS).assertIsOn()
        option(SendingTags.HISTORY, HistoryRetention.DAYS_90).assertIsSelected()
        option(SendingTags.HISTORY, HistoryRetention.DAYS_30).assertIsNotSelected()
        option(SendingTags.LATE_THRESHOLD, LateThreshold.HOURS_3).assertIsSelected()
        node(SendingTags.NUMBER_PARTS).assertIsOn()
        option(SendingTags.VIDEO_QUALITY, VideoQuality.HIGH).assertIsSelected()
        node(SendingTags.KEEP_METADATA).assertIsOn()
    }

    @Test
    fun switchesAreSaved() {
        show()

        node(SendingTags.SUCCESS_NOTIFICATIONS).performClick()
        waitStored { it.successNotifications }
        node(SendingTags.NUMBER_PARTS).performClick()
        waitStored { it.numberThreadParts }
        node(SendingTags.KEEP_METADATA).performClick()
        waitStored { it.keepImageMetadata }
        node(SendingTags.SUCCESS_NOTIFICATIONS).assertIsOn()
        node(SendingTags.NUMBER_PARTS).assertIsOn()
        node(SendingTags.KEEP_METADATA).assertIsOn()

        node(SendingTags.SUCCESS_NOTIFICATIONS).performClick()
        node(SendingTags.NUMBER_PARTS).performClick()
        node(SendingTags.KEEP_METADATA).performClick()
        waitStored { !it.successNotifications && !it.numberThreadParts && !it.keepImageMetadata }
    }

    @Test
    fun everyRetentionLateThresholdAndVideoQualityIsSaved() {
        show()

        for (value in HistoryRetention.entries.reversed()) {
            option(SendingTags.HISTORY, value).performClick()
            waitStored { it.historyRetention == value }
            option(SendingTags.HISTORY, value).assertIsSelected()
        }
        for (value in LateThreshold.entries.reversed()) {
            option(SendingTags.LATE_THRESHOLD, value).performClick()
            waitStored { it.lateThreshold == value }
            option(SendingTags.LATE_THRESHOLD, value).assertIsSelected()
        }
        for (value in VideoQuality.entries.reversed()) {
            option(SendingTags.VIDEO_QUALITY, value).performClick()
            waitStored { it.videoQuality == value }
            option(SendingTags.VIDEO_QUALITY, value).assertIsSelected()
        }
    }

    @Test
    fun systemNotificationSettingsOpenThePageOfTheApp() {
        show()

        node(SendingTags.SYSTEM_NOTIFICATIONS).performClick()

        val intent = shadowOf(compose.activity).nextStartedActivity
        assertThat(intent.action).isEqualTo(ACTION_APP_NOTIFICATION_SETTINGS)
        assertThat(intent.getStringExtra(EXTRA_APP_PACKAGE)).isEqualTo(compose.activity.packageName)
    }

    // Effects where the consumer exists.

    @Test
    fun aShorterRetentionRemovesOlderRecordsAndOffRemovesAll() {
        val now = Instant.now()
        val old = runBlocking { container.history.insert(null, now.minus(Duration.ofDays(10)), "old", PostOptions(), emptyList())!! }
        val recent = runBlocking { container.history.insert(null, now.minus(Duration.ofDays(2)), "recent", PostOptions(), emptyList())!! }
        show()

        option(SendingTags.HISTORY, HistoryRetention.DAYS_7).performClick()
        compose.waitUntil(5_000) { runBlocking { container.history.get(old) == null } }
        assertThat(runBlocking { container.history.get(recent) }).isNotNull()

        option(SendingTags.HISTORY, HistoryRetention.OFF).performClick()
        compose.waitUntil(5_000) { runBlocking { container.history.observe().first().isEmpty() } }
    }

    @Test
    fun theRetentionChosenHereIsUsedWhenASentPostIsRecorded() {
        show()
        option(SendingTags.HISTORY, HistoryRetention.DAYS_7).performClick()
        waitStored { it.historyRetention == HistoryRetention.DAYS_7 }

        val record = runBlocking { container.history.get(sendPost("seven days")!!)!! }
        assertThat(Duration.between(record.sentAt, record.expiresAt)).isEqualTo(Duration.ofDays(7))

        option(SendingTags.HISTORY, HistoryRetention.OFF).performClick()
        waitStored { it.historyRetention == HistoryRetention.OFF }

        assertThat(sendPost("no history")).isNull()
    }

    @Test
    fun theLateThresholdChosenHereDecidesWhetherAPostIsLate() {
        show()
        val now = Instant.parse("2026-11-16T10:00:00Z")
        val sendAt = now.minus(Duration.ofMinutes(20))

        option(SendingTags.LATE_THRESHOLD, LateThreshold.MINUTES_15).performClick()
        waitStored { it.lateThreshold == LateThreshold.MINUTES_15 }
        assertThat(isLate(sendAt, now, stored().lateThreshold)).isTrue()

        option(SendingTags.LATE_THRESHOLD, LateThreshold.HOUR_1).performClick()
        waitStored { it.lateThreshold == LateThreshold.HOUR_1 }
        assertThat(isLate(sendAt, now, stored().lateThreshold)).isFalse()
    }

    /** Sends a post to one account through the posts repository; the id of its history record. */
    private fun sendPost(text: String): Long? = runBlocking {
        val account = container.accounts.add(
            NewAccount(Network.MASTODON, "https://mastodon.example", "$text@mastodon.example", text, text, null),
            MastodonSecret("t"),
        )
        val id = container.posts.saveDraft(DraftInput(text = text, targets = listOf(account)))
        container.posts.queue(id, Schedule.Now(Instant.now()), listOf(account))
        for (delivery in container.posts.get(id)!!.deliveries) {
            container.posts.startDelivery(delivery.id, DeliveryPlan(listOf(PlannedPart(text))))
            container.posts.deliverySucceeded(delivery.id, DeliveryProgress(listOf(PartProgress(0, published = PublishedPart(id = "1")))))
        }
        container.posts.markSent(id, listOf(HistoryItem(account, Network.MASTODON, "https://mastodon.example", text, emptyList())))
    }
}
