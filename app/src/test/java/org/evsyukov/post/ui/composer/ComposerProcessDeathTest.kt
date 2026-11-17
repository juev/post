package org.evsyukov.post.ui.composer

import android.net.Uri
import android.content.Context
import android.os.Looper
import androidx.compose.foundation.text.input.setTextAndPlaceCursorAtEnd
import androidx.compose.runtime.snapshots.Snapshot
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.AppContainer
import org.evsyukov.post.PublishTrigger
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.secrets.MastodonSecret
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Android kills the app in the background while the composer has unsaved changes (R15, R27): when the user comes
 * back, the composer shows what it showed, with the files already copied (R9), and still asks before leaving.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ComposerProcessDeathTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container = AppContainer(context, publishTrigger = PublishTrigger {}) { FakeCipher() }
    private val alice: Long = runBlocking {
        container.accounts.add(
            NewAccount(Network.MASTODON, "https://alice.example", "alice@alice.example", "r-alice", "alice", null),
            MastodonSecret("t-alice"),
        )
    }

    @After
    fun tearDown() {
        if (container.database.isOpen) container.database.close()
    }

    /**
     * Runs the main looper, where the models' coroutines run, until [condition] holds. With no composition on
     * screen nothing tells the snapshot observers of a change, so this does, as the frame clock would.
     */
    private fun waitUntil(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!condition()) {
            Snapshot.sendApplyNotifications()
            check(System.currentTimeMillis() < end) { "condition still not satisfied after 5 s" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun model(draftId: Long?, state: SavedStateHandle) = ComposerModel(container, draftId, savedState = state).also { model ->
        waitUntil { model.loaded }
    }

    /** The saved state as Android hands it to the new process: the same values in a new handle. */
    private fun afterProcessDeath(state: SavedStateHandle) = SavedStateHandle(state.keys().associateWith { state.get<Any?>(it) })

    private fun source(name: String): Uri {
        val file = File(temp.newFolder(), name)
        requireNotNull(javaClass.getResourceAsStream("/media/$name")).use { input -> file.outputStream().use { input.copyTo(it) } }
        return Uri.fromFile(file)
    }

    @Test
    fun aNewUnsavedPostComesBackWithItsTextOptionsAndFiles() {
        runBlocking { container.settings.setDefaultTargets(listOf(alice)) }
        val state = SavedStateHandle()
        val before = model(null, state)
        before.text.setTextAndPlaceCursorAtEnd("written before the process died")
        before.showSpoiler(true)
        before.spoiler.setTextAndPlaceCursorAtEnd("spoilers")
        var failed = -1
        before.viewModelScope.launch { failed = before.addAttachments(listOf(source("alpha.png"))) }
        waitUntil { before.attachments.size == 1 }
        before.setAlt(before.attachments.single().id, "a transparent square")
        waitUntil { true }
        assertThat(failed).isEqualTo(0)

        val after = model(null, afterProcessDeath(state))
        waitUntil { after.text.text.toString() == "written before the process died" }

        assertThat(after.draftId).isNull()
        assertThat(after.spoilerShown).isTrue()
        assertThat(after.spoiler.text.toString()).isEqualTo("spoilers")
        assertThat(after.targets).containsExactly(alice)
        val file = after.attachments.single()
        assertThat(file.path).isEqualTo(before.attachments.single().path)
        assertThat(file.alt).isEqualTo("a transparent square")
        assertThat(file.id).isLessThan(0)
        assertThat(container.attachmentImporter.file(file.path).isFile).isTrue()
        // R15: leaving still asks whether to keep it.
        assertThat(after.hasUnsavedChanges()).isTrue()
    }

    @Test
    fun anUnsavedFileWhoseCopyIsGoneIsDroppedAndCounted() {
        val state = SavedStateHandle()
        val before = model(null, state)
        before.text.setTextAndPlaceCursorAtEnd("with two files")
        before.viewModelScope.launch { before.addAttachments(listOf(source("alpha.png"), source("photo_exif_rot90.jpg"))) }
        waitUntil { before.attachments.size == 2 }
        val (gone, kept) = before.attachments
        waitUntil { true }
        // media.md A1: the sweep deleted one copy while the process was gone.
        assertThat(container.attachmentImporter.file(gone.path).delete()).isTrue()

        val after = model(null, afterProcessDeath(state))
        waitUntil { after.text.text.toString() == "with two files" }

        assertThat(after.attachments.map { it.path }).containsExactly(kept.path)
        assertThat(after.missingFiles).isEqualTo(1)
        after.missingFilesShown()
        assertThat(after.missingFiles).isEqualTo(0)
    }

    @Test
    fun anOpenComposerHoldsItsFilesAgainstTheSweep() {
        val model = model(null, SavedStateHandle())
        model.viewModelScope.launch { model.addAttachments(listOf(source("alpha.png"))) }
        waitUntil { model.attachments.size == 1 }

        assertThat(container.filesInUse.paths()).containsExactly(model.attachments.single().path)
    }

    @Test
    fun aSavedDraftComesBackWithTheEditsMadeSinceItWasSaved() {
        val id = runBlocking { container.posts.saveDraft(DraftInput(text = "saved text", targets = listOf(alice))) }
        val state = SavedStateHandle()
        val before = model(id, state)
        before.text.setTextAndPlaceCursorAtEnd("saved text, then edited")
        waitUntil { true }

        val after = model(id, afterProcessDeath(state))
        waitUntil { after.text.text.toString() == "saved text, then edited" }

        assertThat(after.draftId).isEqualTo(id)
        assertThat(after.hasUnsavedChanges()).isTrue()
    }

    @Test
    fun withoutASavedStateTheComposerStartsAsBefore() {
        val after = model(null, SavedStateHandle())

        assertThat(after.text.text.toString()).isEmpty()
        assertThat(after.hasUnsavedChanges()).isFalse()
    }
}
