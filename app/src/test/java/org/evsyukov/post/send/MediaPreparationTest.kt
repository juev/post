package org.evsyukov.post.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.evsyukov.post.core.media.Conversion
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.Attachment
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.media.convert.ConversionException
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration

/**
 * The preparation of queued media (media.md A4): it runs when the post is queued, in the foreground worker
 * with progress and Cancel, not at the first attempt to send.
 */
@RunWith(AndroidJUnit4::class)
class MediaPreparationTest {
    private val f = SendFixture()
    private val server = FakeMastodon(f.servers) { f.clock.now }

    @After
    fun tearDown() = f.close()

    private fun bigVideo() = Attachment(
        kind = MediaKind.VIDEO,
        path = f.file("clip.mp4"),
        mime = "video/mp4",
        sizeBytes = 300L * 1024 * 1024,
        width = 1920,
        height = 1080,
        durationMs = 60_000,
        frameRate = 30.0,
        videoCodec = "video/avc",
    )

    /** A post queued for tomorrow: nothing sends it, so only the preparation touches it. */
    private fun scheduled(text: String, account: Long, attachments: List<Attachment>): Long = runBlocking {
        f.posts.saveAndQueue(
            DraftInput(text = text, targets = listOf(account), attachments = attachments),
            Schedule.Explicit(f.clock.instant().plus(Duration.ofHours(9))),
        )
    }

    private fun prepare() = runBlocking { f.publisher.prepareQueued { f.foregrounds += it } }

    @Test
    fun aScheduledPostIsConvertedWhenItIsQueuedAndTheRunOnlyUploadsIt() {
        val alice = f.mastodon()
        f.converter.onConvert = { _, progress -> progress(40) }
        val id = scheduled("Clip", alice, listOf(bigVideo()))

        prepare()

        assertThat(f.converter.conversions.single()).isInstanceOf(Conversion.TranscodeVideo::class.java)
        val queued = f.post(id)!!
        assertThat(queued.state).isEqualTo(PostState.QUEUED)
        assertThat(queued.deliveries.single().plan).isNotNull()
        assertThat(queued.deliveries.single().attempts).isEqualTo(0)
        assertThat(f.servers.seen).isEmpty()
        // The conversion shows its progress, and once done the worker says it is still preparing the queue.
        assertThat(f.foregrounds.any { it.transcodingPostId == id }).isTrue()
        assertThat(f.foregrounds.last()).isEqualTo(SendProgress(preparing = true))

        f.clock.advance(Duration.ofHours(9))
        f.run()

        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
        assertThat(server.statuses).hasSize(1)
        // A4: the send uploads the variant made when the post was queued; it does not convert it again.
        assertThat(f.converter.conversions).hasSize(1)
    }

    @Test
    fun aConversionThatFailsWhenThePostIsQueuedFailsTheDeliveryRightAway() {
        val alice = f.mastodon()
        f.converter.onConvert = { _, _ -> throw ConversionException.TranscodeFailed("ERROR_CODE") }
        val id = scheduled("Clip", alice, listOf(bigVideo()))

        prepare()

        val delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.state).isEqualTo(DeliveryState.FAILED)
        assertThat(delivery.error).isEqualTo("the video could not be converted")
        assertThat(f.post(id)!!.state).isEqualTo(PostState.FAILED)
        assertThat(f.notifier.failures).hasSize(1)
        assertThat(f.servers.seen).isEmpty()
    }

    @Test
    fun cancellingThePreparationOfAScheduledPostReturnsItToTheDrafts() = runBlocking<Unit> {
        val alice = f.mastodon()
        val started = CompletableDeferred<Unit>()
        f.converter.onConvert = { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        val id = scheduled("Clip", alice, listOf(bigVideo()))
        val path = f.post(id)!!.attachments.single().path

        val preparation = launch(Dispatchers.IO) { f.publisher.prepareQueued() }
        withTimeout(10_000) { started.await() }
        f.publisher.cancelPreparation(id)
        withTimeout(10_000) { preparation.join() }

        val post = f.post(id)!!
        assertThat(post.state).isEqualTo(PostState.DRAFT)
        assertThat(post.deliveries).isEmpty()
        assertThat(post.attachments).hasSize(1)
        assertThat(File(f.filesDir, path).exists()).isTrue()
        assertThat(f.servers.seen).isEmpty()
        assertThat(f.notifier.failures).isEmpty()
    }
}
