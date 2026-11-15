package org.evsyukov.post.share

import android.app.Activity
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.junit4.v2.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.MainActivity
import org.evsyukov.post.PostApplication
import org.evsyukov.post.core.media.MediaKind
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Share menu on a device (R19, R23): another app's content URI with a read grant goes into a draft, and the
 * draft keeps its own copy once the share activity and its grant are gone. Drafts made here are removed at the end.
 */
@RunWith(AndroidJUnit4::class)
class ShareDeviceTest {
    @get:Rule
    val compose = createEmptyComposeRule()

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val app = ApplicationProvider.getApplicationContext<PostApplication>()
    private val container = app.container
    private val created = mutableListOf<Long>()

    @After
    fun cleanUp() {
        SenderActivity.close(instrumentation)
        // Open in composer started the composer in Post's task.
        instrumentation.runOnMainSync {
            for (stage in listOf(Stage.RESUMED, Stage.PAUSED, Stage.STOPPED, Stage.STARTED, Stage.CREATED)) {
                ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(stage).forEach(Activity::finish)
            }
        }
        runBlocking { created.forEach { container.posts.delete(it) } }
    }

    @Test
    fun aFileSharedByAnotherAppIsCopiedIntoTheDraftAndOutlivesTheGrant() {
        val text = "Shared from another app ${System.nanoTime()}"
        val uri = TestShareProvider.uri("photo_exif_rot90.jpg")
        // Post itself may not read the other app's file; only the grant of the share intent lets it.
        assertThrows(SecurityException::class.java) { app.contentResolver.openInputStream(uri)?.close() }
        SenderActivity.share(instrumentation, "photo_exif_rot90.jpg", text)

        val sheet = waitForActivity<ShareActivity>()
        compose.waitUntil(20_000) {
            compose.onAllNodes(hasTestTag(ShareTags.OPEN_COMPOSER).and(isEnabled())).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithTag(ShareTags.OPEN_COMPOSER).performClick()

        val draft = waitForDraft(text)
        created += draft.id
        // The compose test runs the sheet's coroutines while it waits. The system destroys the finished activity
        // once the composer is idle.
        compose.waitUntil(10_000) { sheet.isFinishing || sheet.isDestroyed }

        // The share activity has finished; the draft's copy is the fixture byte for byte and needs no grant.
        val attachment = draft.attachments.single()
        assertThat(attachment.kind).isEqualTo(MediaKind.IMAGE)
        assertThat(attachment.mime).isEqualTo("image/jpeg")
        val expected = instrumentation.context.assets.open("photo_exif_rot90.jpg").use { it.readBytes() }
        assertThat(container.attachmentImporter.file(attachment.path).readBytes()).isEqualTo(expected)

        // The composer opened with the draft.
        val composer = waitForActivity<MainActivity>()
        assertThat(composer.intent.getLongExtra(MainActivity.EXTRA_DRAFT_ID, -1)).isEqualTo(draft.id)
    }

    /** R23: the app publishes its sharing shortcut on start, so the share sheet lists Post in the Direct Share row. */
    @Test
    fun theDirectShareShortcutIsPublished() {
        val end = System.currentTimeMillis() + 10_000
        while (ShortcutManagerCompat.getDynamicShortcuts(app).none { it.id == DirectShare.ID }) {
            check(System.currentTimeMillis() < end) { "no Direct Share shortcut" }
            Thread.sleep(100)
        }
        val shortcut = ShortcutManagerCompat.getDynamicShortcuts(app).single { it.id == DirectShare.ID }
        assertThat(shortcut.categories).containsExactly(DirectShare.CATEGORY)
    }

    private fun waitForDraft(text: String) = run {
        val end = System.currentTimeMillis() + 10_000
        var found = runBlocking { container.posts.observeDrafts().first() }.firstOrNull { it.text == text }
        while (found == null) {
            check(System.currentTimeMillis() < end) { "no draft" }
            Thread.sleep(100)
            found = runBlocking { container.posts.observeDrafts().first() }.firstOrNull { it.text == text }
        }
        found
    }

    private inline fun <reified T : Activity> waitForActivity(): T {
        val end = System.currentTimeMillis() + 10_000
        while (true) {
            var found: T? = null
            instrumentation.runOnMainSync {
                found = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED).filterIsInstance<T>().firstOrNull()
            }
            found?.let { return it }
            check(System.currentTimeMillis() < end) { "no ${T::class.java.simpleName}" }
            Thread.sleep(100)
        }
    }
}
