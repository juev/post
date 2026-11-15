package org.evsyukov.post.share

import android.app.LocaleManager
import android.app.UiModeManager
import android.graphics.Bitmap
import android.os.LocaleList
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.PostApplication
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.AccountLimits
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.settings.ShareAction
import org.evsyukov.post.data.settings.UnsupportedTypeDefault
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.Locale

/**
 * Screenshots of the Share sheet over another app (the [SenderActivity] of the test APK) for a person to look at: a shared photo with
 * text, a conflict without a default fix, and the same conflict with the default fix listed, in light and dark,
 * English and Russian. The language and theme are set for Post alone and put back at the end, as are the accounts
 * and settings made here.
 */
@RunWith(AndroidJUnit4::class)
class ShareScreenshots {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<PostApplication>()
    private val container = app.container
    private val created = mutableListOf<Long>()

    private val outDir: File by lazy {
        val dir = InstrumentationRegistry.getArguments().getString("additionalTestOutputDir")?.let(::File)
            ?: File(app.getExternalFilesDir(null), "screenshots")
        dir.also { it.mkdirs() }
    }

    @Before
    fun seed() = runBlocking<Unit> {
        val mastodon = container.accounts.add(
            NewAccount(Network.MASTODON, "https://mastodon.social", "denis@mastodon.social", "1", "Denis", null, label = "denis"),
            MastodonSecret("screenshot"),
        )
        container.accounts.updateLimits(mastodon, AccountLimits.Mastodon(MastodonCapabilities()), Instant.now())
        val sky = container.accounts.add(
            NewAccount(Network.BLUESKY, "https://bsky.social", "denis.bsky.social", "did:plc:screenshot", "Denis", null),
            BlueskySecret("a", "r"),
        )
        val work = container.accounts.add(
            NewAccount(Network.MASTODON, "https://fosstodon.org", "work@fosstodon.org", "2", "Work", null, label = "work"),
            MastodonSecret("screenshot"),
        )
        container.accounts.updateLimits(work, AccountLimits.Mastodon(MastodonCapabilities()), Instant.now())
        created += listOf(mastodon, sky, work)
        container.settings.setDefaultTargets(listOf(mastodon, sky))
        container.settings.setShareAction(ShareAction.SEND_NOW)
    }

    @After
    fun cleanUp() = runBlocking<Unit> {
        app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.getEmptyLocaleList()
        app.getSystemService(UiModeManager::class.java).setApplicationNightMode(UiModeManager.MODE_NIGHT_AUTO)
        for (id in created) container.accounts.remove(id)
        container.settings.setDefaultTargets(emptyList())
        container.settings.setShareAction(ShareAction.COMPOSER)
        container.settings.setDefaultFixUnsupportedType(UnsupportedTypeDefault.ASK)
        SenderActivity.close(instrumentation)
    }

    @Test
    fun captureTheSheetOverAnotherAppInLightAndDarkInEnglishAndRussian() {
        for ((locale, dark) in listOf(Locale.ENGLISH to false, Locale.ENGLISH to true, Locale.forLanguageTag("ru") to false, Locale.forLanguageTag("ru") to true)) {
            app.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList(locale)
            app.getSystemService(UiModeManager::class.java)
                .setApplicationNightMode(if (dark) UiModeManager.MODE_NIGHT_YES else UiModeManager.MODE_NIGHT_NO)
            val suffix = "${locale.language}-${if (dark) "dark" else "light"}"
            runBlocking { container.settings.setDefaultFixUnsupportedType(UnsupportedTypeDefault.ASK) }
            shot("share-photo-$suffix", "Morning walk by the river. The fog lifted just before sunrise.", "photo_exif_rot90.jpg")
            shot("share-conflict-$suffix", "A voice note from the walk", "tone.mp3")
            runBlocking { container.settings.setDefaultFixUnsupportedType(UnsupportedTypeDefault.SEND_WITHOUT_FILE) }
            shot("share-default-fix-$suffix", "A voice note from the walk", "tone.mp3")
        }
    }

    /** Has the other app share [text] and the fixture [file], waits until the copy is in, and captures the screen. */
    private fun shot(name: String, text: String, file: String) {
        SenderActivity.share(instrumentation, file, text)
        val end = System.currentTimeMillis() + 10_000
        var sheet: ShareActivity? = null
        while (sheet == null) {
            check(System.currentTimeMillis() < end) { "no Share sheet" }
            Thread.sleep(100)
            instrumentation.runOnMainSync {
                sheet = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<ShareActivity>().firstOrNull()
            }
        }
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag(ShareTags.SEND)).fetchSemanticsNodes().isNotEmpty() &&
                compose.onAllNodes(hasTestTag(ShareTags.COPYING)).fetchSemanticsNodes().isEmpty()
        }
        // The thumbnail and the counters settle.
        Thread.sleep(1_500)
        compose.waitForIdle()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            File(outDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        // Closing the sheet without posting deletes its copies.
        val shown = requireNotNull(sheet)
        instrumentation.runOnMainSync { shown.finish() }
        while (!shown.isDestroyed) Thread.sleep(100)
    }
}
