package org.evsyukov.post.share

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.lifecycle.SavedStateHandle
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.AppContainer
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.media.importer.AttachmentImporter
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** The sheet after the death of its process: the saved state brings back the edits and the copies, without a second copy. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ShareModelStateTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val container = AppContainer(context) { FakeCipher() }

    @After
    fun tearDown() {
        if (container.database.isOpen) container.database.close()
    }

    /** Runs the main looper until [condition] holds: the model works on the main thread, the copy on an IO thread. */
    private fun until(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "timed out" }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }

    @Test
    fun theSheetComesBackAfterProcessDeathWithItsEditsAndCopies() {
        val sky = runBlocking {
            container.accounts.add(NewAccount(Network.BLUESKY, "https://pds.example", "sky.bsky.social", "did:plc:sky", "sky", null), BlueskySecret("a", "r"))
        }
        runBlocking { container.settings.setDefaultTargets(listOf(sky)) }
        val file = File(temp.newFolder(), "photo.jpg")
        requireNotNull(javaClass.getResourceAsStream("/media/photo_exif_rot90.jpg")).use { input -> file.outputStream().use { input.copyTo(it) } }
        val shared = SharedContent("Shared", listOf(Uri.fromFile(file)))

        val saved = SavedStateHandle()
        val first = ShareModel(container, saved, shared)
        until { !first.importing && first.targets != null }
        first.editText("Edited")
        first.toggleTarget(sky)
        val copied = first.attachments.single()

        // The process dies: the grant and the source are gone, only the saved state is left.
        assertThat(file.delete()).isTrue()
        val restored = SavedStateHandle(saved.keys().associateWith { saved.get<Any?>(it) })
        val second = ShareModel(container, restored, shared)
        until { second.targets != null }
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(second.importing).isFalse()
        assertThat(second.text).isEqualTo("Edited")
        assertThat(second.targets).isEmpty()
        assertThat(second.attachments).containsExactly(copied)
        assertThat(second.failedFiles).isEqualTo(0)
        assertThat(File(context.filesDir, AttachmentImporter.DIR).list().orEmpty().toList()).containsExactly(File(copied.path).name)
    }
}
