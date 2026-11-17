package org.evsyukov.post.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.markdown.BlueskyLinkMode
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.FixKind
import org.evsyukov.post.core.schedule.LateThreshold
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.time.Duration
import java.time.Instant

/** Settings in DataStore: the defaults of the spec and values that survive reopening the file. */
class AppSettingsTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val scopes = mutableListOf<CoroutineScope>()
    private val file: File by lazy { File(temp.root, "settings.preferences_pb") }

    @After
    fun tearDown() = runBlocking {
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
    }

    private fun open(): AppSettings = AppSettings(newScope()) { file }

    private fun newScope() = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }

    /** Closes every open store, so the file can be opened again in this process. */
    private suspend fun closeAll() {
        scopes.forEach { it.coroutineContext[Job]!!.cancelAndJoin() }
        scopes.clear()
    }

    @Test
    fun aNewInstallHasTheDefaultsOfTheSpec() = runBlocking<Unit> {
        val settings = open().current()

        assertThat(settings.defaultTargets).isEmpty() // R16: chosen by the user
        assertThat(settings.shareAction).isEqualTo(ShareAction.COMPOSER) // R18
        assertThat(settings.requireAltText).isFalse() // R10: off by default
        assertThat(settings.defaultFixTooManyFiles).isEqualTo(TooManyFilesDefault.ASK) // A12
        assertThat(settings.defaultFixUnsupportedType).isEqualTo(UnsupportedTypeDefault.ASK) // A12
        assertThat(settings.blueskyLinkMode).isEqualTo(BlueskyLinkMode.FACET_ON_TEXT) // F11
        assertThat(settings.successNotifications).isFalse() // R32: off by default
        assertThat(settings.historyRetention).isEqualTo(HistoryRetention.DAYS_30) // R31: 30 days by default
        assertThat(settings.lateThreshold).isEqualTo(LateThreshold.MINUTES_30) // Q4, R26a: 30 minutes
        assertThat(settings.lateThreshold.duration).isEqualTo(Duration.ofMinutes(30))
        assertThat(settings.numberThreadParts).isFalse() // T11: off by default
        assertThat(settings.videoQuality).isEqualTo(VideoQuality.STANDARD)
        assertThat(settings.keepImageMetadata).isFalse() // A6: metadata removed unless kept
        assertThat(settings.notificationPermissionAsked).isFalse()
        assertThat(settings.translateTo).isNull() // R13: translation is off by default
        assertThat(settings.lastLimitsRefresh).isNull()
        assertThat(settings.defaultFixes()).isEmpty()
        assertThat(settings).isEqualTo(Settings())
    }

    @Test
    fun theOptionsOfTheSpecAreOffered() {
        // screens.md, Sending: history off, 7, 30, 90 days; late threshold 15 minutes, 30 minutes, 1 hour, 3 hours.
        assertThat(HistoryRetention.entries.map { it.days }).containsExactly(null, 7L, 30L, 90L).inOrder()
        assertThat(LateThreshold.entries.map { it.duration })
            .containsExactly(Duration.ofMinutes(15), Duration.ofMinutes(30), Duration.ofHours(1), Duration.ofHours(3)).inOrder()
        // R18: open the composer, send now, or send later.
        assertThat(ShareAction.entries).hasSize(3)
    }

    @Test
    fun everySettingSurvivesReopeningTheFile() = runBlocking<Unit> {
        val changed = Settings(
            defaultTargets = listOf(3, 1, 2),
            shareAction = ShareAction.SEND_LATER,
            requireAltText = true,
            defaultFixTooManyFiles = TooManyFilesDefault.MOVE_TO_REPLY,
            defaultFixUnsupportedType = UnsupportedTypeDefault.SEND_WITHOUT_FILE,
            blueskyLinkMode = BlueskyLinkMode.TEXT_AND_SHORT_URL,
            successNotifications = true,
            historyRetention = HistoryRetention.OFF,
            lateThreshold = LateThreshold.HOURS_3,
            numberThreadParts = true,
            videoQuality = VideoQuality.HIGH,
            keepImageMetadata = true,
            notificationPermissionAsked = true,
            translateTo = "de",
            lastLimitsRefresh = Instant.parse("2026-11-16T07:00:00Z"),
        )
        open().apply {
            setDefaultTargets(changed.defaultTargets)
            setShareAction(changed.shareAction)
            setRequireAltText(changed.requireAltText)
            setDefaultFixTooManyFiles(changed.defaultFixTooManyFiles)
            setDefaultFixUnsupportedType(changed.defaultFixUnsupportedType)
            setBlueskyLinkMode(changed.blueskyLinkMode)
            setSuccessNotifications(changed.successNotifications)
            setHistoryRetention(changed.historyRetention)
            setLateThreshold(changed.lateThreshold)
            setNumberThreadParts(changed.numberThreadParts)
            setVideoQuality(changed.videoQuality)
            setKeepImageMetadata(changed.keepImageMetadata)
            setNotificationPermissionAsked(changed.notificationPermissionAsked)
            setTranslateTo(changed.translateTo)
            setLastLimitsRefresh(changed.lastLimitsRefresh!!)
        }
        closeAll()

        val reopened = open().current()

        assertThat(reopened).isEqualTo(changed)
        // A12: the defaults reach the media planner as its fixes.
        assertThat(reopened.defaultFixes()).containsExactly(
            ConflictKind.TOO_MANY_FILES, FixKind.MOVE_TO_REPLY,
            ConflictKind.UNSUPPORTED_TYPE, FixKind.SEND_WITHOUT_FILE,
        )
    }

    @Test
    fun aValueThisVersionDoesNotKnowFallsBackToTheDefault() = runBlocking<Unit> {
        val raw = PreferenceDataStoreFactory.create(scope = newScope()) { file }
        raw.edit {
            it[stringPreferencesKey("share_action")] = "TELEPATHY"
            it[stringPreferencesKey("late_threshold")] = "MINUTES_45"
            it[stringPreferencesKey("default_targets")] = "not json"
        }
        closeAll()

        val settings = open().current()

        assertThat(settings.shareAction).isEqualTo(ShareAction.COMPOSER)
        assertThat(settings.lateThreshold).isEqualTo(LateThreshold.MINUTES_30)
        assertThat(settings.defaultTargets).isEmpty()
    }

    @Test
    fun aFileThatCannotBeReadGivesTheDefaultsInsteadOfFailing() = runBlocking<Unit> {
        // A directory in place of the file: every read is an IOException, not a damaged file.
        assertThat(file.mkdirs()).isTrue()

        assertThat(open().current()).isEqualTo(Settings())
    }

    @Test
    fun aDamagedFileGivesTheDefaultsInsteadOfFailing() = runBlocking<Unit> {
        file.writeBytes(byteArrayOf(0x0A, 0x7F, 0x01, 0x02, 0x03))

        val settings = open()
        assertThat(settings.current()).isEqualTo(Settings())

        settings.setShareAction(ShareAction.SEND_NOW)
        assertThat(settings.current().shareAction).isEqualTo(ShareAction.SEND_NOW)
    }
}
