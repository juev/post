package org.evsyukov.post.share

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** What a share intent puts into the draft (product.md R19), and which intents reach the Share activity. */
@RunWith(AndroidJUnit4::class)
class SharedContentTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val photo = Uri.parse("content://sender/photo.jpg")
    private val clip = Uri.parse("content://sender/clip.mp4")

    private fun send(type: String) = Intent(Intent.ACTION_SEND).setType(type)

    @Test
    fun textOnlyFillsTheDraft() {
        val shared = SharedContent.from(send("text/plain").putExtra(Intent.EXTRA_TEXT, "  Hello there  "))
        assertThat(shared.text).isEqualTo("Hello there")
        assertThat(shared.uris).isEmpty()
    }

    @Test
    fun theSubjectGoesFirstWithABlankLine() {
        val intent = send("text/plain").putExtra(Intent.EXTRA_SUBJECT, "A title").putExtra(Intent.EXTRA_TEXT, "https://example.com/a")
        assertThat(SharedContent.from(intent).text).isEqualTo("A title\n\nhttps://example.com/a")
    }

    @Test
    fun aSubjectTheTextAlreadyHoldsIsNotRepeated() {
        val intent = send("text/plain").putExtra(Intent.EXTRA_SUBJECT, "https://example.com/a")
            .putExtra(Intent.EXTRA_TEXT, "Look: https://example.com/a")
        assertThat(SharedContent.from(intent).text).isEqualTo("Look: https://example.com/a")
    }

    @Test
    fun aSubjectWithoutTextIsTheText() {
        assertThat(SharedContent.from(send("text/plain").putExtra(Intent.EXTRA_SUBJECT, "Only a title")).text).isEqualTo("Only a title")
    }

    @Test
    fun oneFileComesFromTheStream() {
        val shared = SharedContent.from(send("image/jpeg").putExtra(Intent.EXTRA_STREAM, photo).putExtra(Intent.EXTRA_TEXT, "caption"))
        assertThat(shared.uris).containsExactly(photo)
        assertThat(shared.text).isEqualTo("caption")
    }

    @Test
    fun severalFilesKeepTheirOrderAndTheClipAddsNoDuplicates() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("*/*")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf(photo, clip))
        intent.clipData = ClipData.newRawUri("", photo).apply { addItem(ClipData.Item(clip)) }
        assertThat(SharedContent.from(intent).uris).containsExactly(photo, clip).inOrder()
    }

    @Test
    fun aFileOnlyInTheClipIsTaken() {
        val intent = send("video/mp4").apply { clipData = ClipData.newRawUri("", clip) }
        assertThat(SharedContent.from(intent).uris).containsExactly(clip)
    }

    @Test
    fun severalTextsOfSendMultipleAreJoined() {
        val intent = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain")
            .putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, arrayListOf<CharSequence>("one", "two"))
        assertThat(SharedContent.from(intent).text).isEqualTo("one\n\ntwo")
    }

    @Test
    fun anotherActionSharesNothing() {
        assertThat(SharedContent.from(Intent(Intent.ACTION_VIEW).putExtra(Intent.EXTRA_TEXT, "x"))).isEqualTo(SharedContent.EMPTY)
    }

    /** R19: SEND and SEND_MULTIPLE of text, images, video and audio reach the Share activity; other types do not. */
    @Test
    fun theShareActivityTakesTextImagesVideoAndAudioOnly() {
        fun targets(action: String, type: String) = context.packageManager.queryIntentActivities(Intent(action).setType(type).setPackage(context.packageName), 0)
            .map { it.activityInfo.name }

        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (type in listOf("text/plain", "image/jpeg", "image/*", "video/mp4", "audio/mpeg")) {
                assertThat(targets(action, type)).containsExactly(ShareActivity::class.java.name)
            }
            assertThat(targets(action, "application/pdf")).isEmpty()
            assertThat(targets(action, "text/html")).isEmpty()
        }
    }
}
