package org.evsyukov.post.ui.composer

import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import org.evsyukov.post.R
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.FixKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.media.importer.AttachmentImporter
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Attachments in the composer (R9–R11, media.md, threads.md T14; screens.md attachment strip and sheet): the
 * strip of the current piece with its badges, the attachment sheet, conflicts and fixes in the details sheet,
 * Send enabling, and the files of attachments that are removed or discarded.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerAttachmentsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var h: ComposerHarness

    @Before
    fun setUp() {
        h = ComposerHarness(compose)
    }

    @After
    fun tearDown() = h.close()

    private fun uris(name: String, count: Int = 1): List<Uri> = (1..count).map { index ->
        val file = File(temp.newFolder(), "$index-$name")
        requireNotNull(javaClass.getResourceAsStream("/media/$name")).use { input -> file.outputStream().use { input.copyTo(it) } }
        Uri.fromFile(file)
    }

    /** Adds [uris] as the attach menu does: on the composer's own scope, while the test waits. */
    private fun add(uris: List<Uri>) {
        val before = h.model.attachments.size
        val failed = AtomicInteger(-1)
        h.model.viewModelScope.launch { failed.set(h.model.addAttachments(uris)) }
        settle { failed.get() >= 0 }
        assertThat(failed.get()).isEqualTo(0)
        assertThat(h.model.attachments).hasSize(before + uris.size)
        compose.waitForIdle()
    }

    /**
     * Waits for [condition] while work that left the main thread (the import on IO, the render on Default)
     * comes back to it: the test dispatcher runs those resumptions when the test idles, which a plain
     * `waitUntil` does not do.
     */
    private fun settle(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Condition still not satisfied after 10 s" }
            Thread.sleep(10)
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
        }
    }

    private fun queued(): List<Post> = runBlocking { h.container.posts.observeQueue().first() }

    private fun drafts(): List<Post> = runBlocking { h.container.posts.observeDrafts().first() }

    private fun present(tag: String) = compose.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun stored(): List<String> = File(h.context.filesDir, AttachmentImporter.DIR).list().orEmpty().toList()

    private fun ids() = h.model.attachments.map { it.id }

    @Test
    fun sixImagesGoAsABlueskyGalleryAndMastodonAsksThenMovesTwoToAReply() {
        val alice = h.mastodon("alice")
        val sky = h.bluesky("sky")
        h.defaultTargets(alice, sky)
        h.show()
        h.type("Six photos")
        add(uris("photo_exif_rot90.jpg", 6))

        val six = ids()
        assertThat(six).hasSize(6)
        six.forEach { h.waitFor(AttachmentTags.item(it)) }
        // The two over Mastodon's four carry the warning; none has alt text yet.
        assertThat(present(AttachmentTags.conflictBadge(six[4]))).isTrue()
        assertThat(present(AttachmentTags.conflictBadge(six[5]))).isTrue()
        assertThat(present(AttachmentTags.conflictBadge(six[0]))).isFalse()
        six.forEach { assertThat(present(AttachmentTags.altBadge(it))).isTrue() }
        h.node(ComposerTags.SEND).assertIsNotEnabled()

        // Send in this state opens the details: Mastodon asks, Bluesky does not.
        h.click(ComposerTags.SEND)
        h.waitFor(ComposerTags.DETAILS)
        assertThat(present(AttachmentTags.conflict(Network.MASTODON, ConflictKind.TOO_MANY_FILES))).isTrue()
        assertThat(present(AttachmentTags.conflict(Network.BLUESKY, ConflictKind.TOO_MANY_FILES))).isFalse()
        for (fix in ConflictKind.TOO_MANY_FILES.fixes) assertThat(present(AttachmentTags.fix(Network.MASTODON, ConflictKind.TOO_MANY_FILES, fix))).isTrue()
        assertThat(queued()).isEmpty()

        h.click(AttachmentTags.fix(Network.MASTODON, ConflictKind.TOO_MANY_FILES, FixKind.MOVE_TO_REPLY))

        h.waitFor(AttachmentTags.resolved(Network.MASTODON, ConflictKind.TOO_MANY_FILES))
        assertThat(present(AttachmentTags.undo(Network.MASTODON, ConflictKind.TOO_MANY_FILES))).isTrue()
        assertThat(present(AttachmentTags.conflictBadge(six[4]))).isFalse()
        settle { runCatching { h.node(ComposerTags.SEND).assertIsEnabled() }.isSuccess }

        // The counters and the preview see the real parts: Mastodon four images and a reply with two, Bluesky a gallery.
        settle { h.model.renders?.second?.firstOrNull { it.network == Network.MASTODON }?.render?.parts?.size == 2 }
        val renders = h.model.renders!!.second
        val mastodon = renders.single { it.network == Network.MASTODON }.assembled!!
        assertThat(mastodon.parts.map { it.mediaItemIds }).containsExactly(six.take(4), six.drop(4)).inOrder()
        assertThat(mastodon.parts[0].text).isEqualTo("Six photos")
        assertThat(mastodon.parts[1].mediaOnly).isTrue()
        val bluesky = renders.single { it.network == Network.BLUESKY }.assembled!!
        assertThat(bluesky.parts.map { it.mediaItemIds }).containsExactly(six)
        settle {
            compose.onAllNodes(hasTestTag(ComposerTags.statusEntry(0)) and hasText(h.plural(R.plurals.composer_status_parts, 2, "Mastodon", 2)), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        h.click(ComposerTags.SEND)

        settle { queued().isNotEmpty() }
        val post = queued().single()
        assertThat(post.options.chosenFixes).containsExactly(Network.MASTODON, mapOf(ConflictKind.TOO_MANY_FILES to FixKind.MOVE_TO_REPLY))
        assertThat(post.attachments).hasSize(6)
        assertThat(post.attachments.map { it.id }.all { it > 0 }).isTrue()
        // The queued post owns the files; the new empty draft has none, and nothing was deleted.
        post.attachments.forEach { assertThat(File(h.context.filesDir, it.path).isFile).isTrue() }
        settle { h.model.attachments.isEmpty() }
        assertThat(present(AttachmentTags.STRIP)).isFalse()
    }

    @Test
    fun undoBringsTheConflictBack() {
        val alice = h.mastodon("alice")
        h.defaultTargets(alice)
        h.show()
        add(uris("alpha.png", 5))
        h.click(ComposerTags.STATUS)
        h.waitFor(ComposerTags.DETAILS)
        h.click(AttachmentTags.fix(Network.MASTODON, ConflictKind.TOO_MANY_FILES, FixKind.REMOVE_FILES_FOR_NETWORK))
        h.waitFor(AttachmentTags.undo(Network.MASTODON, ConflictKind.TOO_MANY_FILES))
        assertThat(h.model.chosenFixes).containsExactly(Network.MASTODON, mapOf(ConflictKind.TOO_MANY_FILES to FixKind.REMOVE_FILES_FOR_NETWORK))

        h.click(AttachmentTags.undo(Network.MASTODON, ConflictKind.TOO_MANY_FILES))

        h.waitFor(AttachmentTags.conflict(Network.MASTODON, ConflictKind.TOO_MANY_FILES))
        assertThat(h.model.chosenFixes).isEmpty()
        h.node(ComposerTags.SEND).assertIsNotEnabled()
    }

    @Test
    fun removingTheAccountFixTakesTheNetworkOutOfTheTargets() {
        val alice = h.mastodon("alice")
        val sky = h.bluesky("sky")
        h.defaultTargets(alice, sky)
        h.show()
        h.type("song")
        add(uris("tone.mp3"))
        h.click(ComposerTags.STATUS)
        h.waitFor(ComposerTags.DETAILS)
        // An MP3: Mastodon takes it; Bluesky does not.
        assertThat(present(AttachmentTags.conflict(Network.BLUESKY, ConflictKind.UNSUPPORTED_TYPE))).isTrue()
        assertThat(present(AttachmentTags.conflict(Network.MASTODON, ConflictKind.UNSUPPORTED_TYPE))).isFalse()

        h.click(AttachmentTags.fix(Network.BLUESKY, ConflictKind.UNSUPPORTED_TYPE, FixKind.REMOVE_ACCOUNT))

        settle { h.model.targets == listOf(alice) }
        settle { runCatching { h.node(ComposerTags.SEND).assertIsEnabled() }.isSuccess }
    }

    @Test
    fun attachmentsGoToThePieceTheCursorIsInAndTheStripShowsThatPiece() {
        val sky = h.bluesky("sky")
        h.defaultTargets(sky)
        h.show()
        val text = "First piece\n$MANUAL_BREAK\nSecond piece"
        h.type(text)
        // The cursor is at the end: the second piece.
        add(uris("alpha.png"))
        val second = ids().single()
        assertThat(h.model.attachments.single().pieceIndex).isEqualTo(1)
        h.waitFor(AttachmentTags.item(second))

        h.select(0, 0)
        add(uris("still.gif"))
        val first = ids().last()
        assertThat(h.model.attachments.last().pieceIndex).isEqualTo(0)
        h.waitFor(AttachmentTags.item(first))
        assertThat(present(AttachmentTags.item(second))).isFalse()

        // Each piece's part carries its own file (T14).
        settle { h.model.renders?.second?.singleOrNull()?.assembled?.parts?.sumOf { it.mediaItemIds.size } == 2 }
        val parts = h.model.renders!!.second.single().assembled!!.parts
        assertThat(parts.map { it.mediaItemIds }).containsExactly(listOf(first), listOf(second)).inOrder()
    }

    @Test
    fun theSheetEditsAltTextAgainstEachNetworksLimitAndSetsTheFocus() {
        val alice = h.mastodon("alice", capabilities = MastodonCapabilities(descriptionLimit = 20))
        val sky = h.bluesky("sky")
        h.defaultTargets(alice, sky)
        h.show()
        h.type("cat")
        add(uris("photo_exif_rot90.jpg"))
        val id = ids().single()
        h.waitFor(AttachmentTags.item(id))
        assertThat(present(AttachmentTags.altBadge(id))).isTrue()
        compose.onAllNodes(hasContentDescription(h.string(R.string.media_no_alt), substring = true)).fetchSemanticsNodes().let { assertThat(it).isNotEmpty() }

        h.click(AttachmentTags.item(id))
        h.waitFor(AttachmentTags.SHEET)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()

        // A tap on the top-left quarter of the image puts the focus there: x < 0, y > 0 on Mastodon's axes.
        compose.onNodeWithTag(AttachmentTags.PREVIEW).performTouchInput { click(androidx.compose.ui.geometry.Offset(width * 0.25f, height * 0.25f)) }
        compose.waitForIdle()
        val focused = h.model.attachments.single()
        assertThat(focused.focusX!!).isWithin(0.05f).of(-0.5f)
        assertThat(focused.focusY!!).isWithin(0.05f).of(0.5f)
        assertThat(present(AttachmentTags.FOCUS)).isTrue()

        h.node(AttachmentTags.ALT).performTextInput("A grey cat")
        compose.waitForIdle()
        assertThat(h.model.attachments.single().alt).isEqualTo("A grey cat")
        settle {
            compose.onAllNodes(hasTestTag(AttachmentTags.ALT_COUNTER) and hasText("Mastodon 10/20 · Bluesky 10/2000"), useUnmergedTree = true)
                .fetchSemanticsNodes().isNotEmpty()
        }

        // Over Mastodon's limit: marked in the sheet, and Send waits (A9).
        h.node(AttachmentTags.ALT).performTextReplacement("A grey cat asleep on a sunny sill")
        compose.waitForIdle()
        settle { compose.onAllNodes(hasText(h.string(R.string.media_alt_over, "Mastodon", 20)), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
        assertThat(h.model.currentBlockers()).contains(Blocker.AltTooLong(listOf(id), 20))

        h.node(AttachmentTags.ALT).performTextReplacement("A grey cat")
        compose.waitForIdle()
        assertThat(h.model.currentBlockers()).isEmpty()
    }

    @Test
    fun requireAltTextHoldsSendUntilTheAttachmentIsDescribed() {
        val sky = h.bluesky("sky")
        h.defaultTargets(sky)
        runBlocking { h.container.settings.setRequireAltText(true) }
        h.show()
        add(uris("alpha.png"))
        val id = ids().single()
        settle { h.model.currentBlockers() == listOf(Blocker.AltMissing(listOf(id))) }
        h.node(ComposerTags.SEND).assertIsNotEnabled()

        h.click(AttachmentTags.item(id))
        h.waitFor(AttachmentTags.SHEET)
        h.node(AttachmentTags.ALT).performTextInput("Red square")
        compose.waitForIdle()

        // An attachment alone is a post: nothing else holds it.
        settle { runCatching { h.node(ComposerTags.SEND).assertIsEnabled() }.isSuccess }
        assertThat(present(AttachmentTags.altBadge(id))).isFalse()
    }

    @Test
    fun removingANewAttachmentDeletesItsFile() {
        val sky = h.bluesky("sky")
        h.defaultTargets(sky)
        h.show()
        add(uris("alpha.png"))
        val attachment = h.model.attachments.single()
        val file = File(h.context.filesDir, attachment.path)
        assertThat(file.isFile).isTrue()

        h.click(AttachmentTags.item(attachment.id))
        h.waitFor(AttachmentTags.SHEET)
        h.click(AttachmentTags.REMOVE)

        settle { h.model.attachments.isEmpty() }
        settle { !file.exists() }
        assertThat(present(AttachmentTags.STRIP)).isFalse()
    }

    @Test
    fun aSavedAttachmentKeepsItsFileUntilTheDraftIsSavedWithoutIt() {
        val sky = h.bluesky("sky")
        h.defaultTargets(sky)
        h.show()
        h.type("draft")
        add(uris("alpha.png"))
        runBlocking { h.model.saveDraft() }
        val saved = h.model.attachments.single()
        assertThat(saved.id).isGreaterThan(0L)
        val file = File(h.context.filesDir, saved.path)

        runBlocking { h.model.removeAttachment(saved.id) }
        assertThat(file.isFile).isTrue()
        // Discarding the change brings it back with its file.
        runBlocking { h.model.discardChanges() }
        assertThat(h.model.attachments.map { it.id }).containsExactly(saved.id)
        assertThat(file.isFile).isTrue()

        runBlocking {
            h.model.removeAttachment(saved.id)
            h.model.saveDraft()
        }
        assertThat(file.exists()).isFalse()
        assertThat(drafts().single().attachments).isEmpty()
    }

    @Test
    fun discardingOrClearingDeletesTheFilesAddedSinceTheLastSave() {
        val sky = h.bluesky("sky")
        h.defaultTargets(sky)
        h.show()
        h.type("draft")
        add(uris("alpha.png"))
        runBlocking { h.model.saveDraft() }
        val kept = File(h.context.filesDir, h.model.attachments.single().path)
        add(uris("still.gif"))
        val added = File(h.context.filesDir, h.model.attachments.last().path)
        assertThat(stored()).hasSize(2)

        // Leaving with unsaved changes asks; Discard drops the new file and keeps the saved one.
        assertThat(h.model.hasUnsavedChanges()).isTrue()
        runBlocking { h.model.discardChanges() }
        assertThat(added.exists()).isFalse()
        assertThat(kept.isFile).isTrue()

        add(uris("animated.gif"))
        val cleared = File(h.context.filesDir, h.model.attachments.last().path)
        h.menu(ComposerTags.MENU_CLEAR)
        h.click(ComposerTags.CLEAR_CONFIRM)
        settle { h.model.attachments.isEmpty() }
        settle { !cleared.exists() }
        // The saved draft stays in Drafts with its file.
        assertThat(kept.isFile).isTrue()
        assertThat(drafts().single().attachments).hasSize(1)
    }

    @Test
    fun aDraftKeepsItsAttachmentsAltFocusAndFixes() {
        val alice = h.mastodon("alice")
        h.defaultTargets(alice)
        h.show()
        h.type("five")
        add(uris("alpha.png", 5))
        val first = ids().first()
        h.model.setAlt(first, "red")
        h.model.setFocus(first, 0.25f, -0.5f)
        h.model.chooseFix(Network.MASTODON, ConflictKind.TOO_MANY_FILES, FixKind.MOVE_TO_REPLY)
        val id = runBlocking { h.model.saveDraft() }
        assertThat(h.model.hasUnsavedChanges()).isFalse()

        val post = runBlocking { h.container.posts.get(id) }!!
        assertThat(post.attachments).hasSize(5)
        assertThat(post.attachments.first().alt).isEqualTo("red")
        assertThat(post.attachments.first().focusX).isEqualTo(0.25f)
        assertThat(post.attachments.first().focusY).isEqualTo(-0.5f)
        assertThat(post.options.chosenFixes).containsExactly(Network.MASTODON, mapOf(ConflictKind.TOO_MANY_FILES to FixKind.MOVE_TO_REPLY))

        // Saving again adds no rows: the composer now holds the saved ids.
        runBlocking { h.model.saveDraft() }
        assertThat(runBlocking { h.container.posts.get(id) }!!.attachments.map { it.id }).isEqualTo(post.attachments.map { it.id })
    }

    @Test
    fun theAttachMenuOffersThePhotoPickerAndFiles() {
        val sky = h.bluesky("sky")
        h.defaultTargets(sky)
        h.show()
        h.click(ComposerTags.ATTACH)
        h.waitFor(AttachmentTags.ATTACH_MEDIA)
        compose.onNodeWithTag(AttachmentTags.ATTACH_MEDIA).assertExists()
        compose.onNodeWithTag(AttachmentTags.ATTACH_FILES).assertExists()
        assertThat(compose.onAllNodes(hasText(h.string(R.string.media_attach_media))).fetchSemanticsNodes()).isNotEmpty()
    }
}
