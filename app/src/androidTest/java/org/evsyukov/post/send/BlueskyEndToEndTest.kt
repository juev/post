package org.evsyukov.post.send

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.evsyukov.post.PostApplication
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.Attachment
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.send.bluesky.AndroidThumbnails
import org.evsyukov.post.send.bluesky.LinkCardFetcher
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Send now on the device, through WorkManager and the real app container, to MockWebServer as a Bluesky
 * PDS (product.md R21, R27–R31; networks.md N19, N24): an image and a two-part thread.
 */
@RunWith(AndroidJUnit4::class)
class BlueskyEndToEndTest {
    private val app = ApplicationProvider.getApplicationContext<PostApplication>()
    private val container = app.container
    private val server = MockWebServer()
    private val writes = CopyOnWriteArrayList<JsonObject>()
    private val blobs = CopyOnWriteArrayList<String>()
    private var accountId: Long? = null
    private var postId: Long? = null

    @After
    fun tearDown() = runBlocking {
        container.history.observe().first()
            .filter { record -> record.items.any { it.accountId == accountId } }
            .forEach { container.history.delete(it.id) }
        postId?.let { container.posts.delete(it) }
        accountId?.let { container.accounts.remove(it) }
        server.close()
    }

    @Test
    fun sendNowPublishesAnImageAndATwoPartThread() = runBlocking<Unit> {
        val did = "did:plc:e2e${System.nanoTime()}"
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                if (request.headers["Authorization"] != null && request.headers["Authorization"] != "Bearer e2e-access") {
                    return json("""{"error":"InvalidToken"}""", 400)
                }
                return when (path) {
                    "/xrpc/com.atproto.repo.uploadBlob" -> {
                        blobs += request.headers["Content-Type"].orEmpty()
                        json("""{"blob":{"${'$'}type":"blob","ref":{"${'$'}link":"bafkblob${blobs.size}"},"mimeType":"image/jpeg","size":${request.bodySize}}}""")
                    }
                    "/xrpc/com.atproto.repo.applyWrites" -> {
                        val body = Json.parseToJsonElement(request.body?.utf8().orEmpty()).jsonObject
                        writes += body
                        val rkey = body["writes"]!!.jsonArray[0].jsonObject["rkey"]!!.jsonPrimitive.content
                        json("""{"results":[{"uri":"at://$did/app.bsky.feed.post/$rkey","cid":"bafyrei${writes.size}"}]}""")
                    }
                    "/xrpc/com.atproto.repo.getRecord" -> json("""{"error":"RecordNotFound"}""", 400)
                    else -> json("""{"error":"NotFound"}""", 404)
                }
            }
        }
        server.start()
        val id = container.accounts.add(
            NewAccount(Network.BLUESKY, "http://localhost:${server.port}", "e2e.example.com", did, "E2E", null),
            BlueskySecret("e2e-access", "e2e-refresh"),
        )
        accountId = id
        val name = "e2e-${System.nanoTime()}.jpg"
        val file = File(app.filesDir, "attachments/$name").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val attachment = Attachment(
            kind = MediaKind.IMAGE,
            path = "attachments/$name",
            mime = "image/jpeg",
            sizeBytes = file.length(),
            width = 64,
            height = 48,
            alt = "a blue square",
        )

        val postId = container.posts.saveAndQueue(
            DraftInput(text = "first part\n$MANUAL_BREAK\nsecond part", targets = listOf(id), attachments = listOf(attachment)),
            Schedule.Now(Instant.now()),
        )
        this@BlueskyEndToEndTest.postId = postId
        container.publishTrigger.publishNow()

        withTimeout(60_000) {
            while (container.posts.get(postId)?.state != PostState.SENT) delay(200)
        }

        assertThat(blobs).containsExactly("image/jpeg")
        assertThat(writes).hasSize(2)
        val first = writes[0]["writes"]!!.jsonArray.single().jsonObject["value"]!!.jsonObject
        val second = writes[1]["writes"]!!.jsonArray.single().jsonObject["value"]!!.jsonObject
        assertThat(first["text"]!!.jsonPrimitive.content).isEqualTo("first part")
        val image = first["embed"]!!.jsonObject["images"]!!.jsonArray.single().jsonObject
        assertThat(image["alt"]!!.jsonPrimitive.content).isEqualTo("a blue square")
        assertThat(image["image"]!!.jsonObject["ref"]!!.jsonObject["\$link"]!!.jsonPrimitive.content).isEqualTo("bafkblob1")
        assertThat(second["text"]!!.jsonPrimitive.content).isEqualTo("second part")
        val rkey = writes[0]["writes"]!!.jsonArray.single().jsonObject["rkey"]!!.jsonPrimitive.content
        val reply = second["reply"]!!.jsonObject
        assertThat(reply["parent"]!!.jsonObject["uri"]!!.jsonPrimitive.content).isEqualTo("at://$did/app.bsky.feed.post/$rkey")
        assertThat(reply["parent"]!!.jsonObject["cid"]!!.jsonPrimitive.content).isEqualTo("bafyrei1")
        assertThat(reply["root"]!!.jsonObject["cid"]!!.jsonPrimitive.content).isEqualTo("bafyrei1")
        val record = container.history.observe().first().first { r -> r.items.any { it.accountId == id } }
        assertThat(record.items.single().parts.map { it.url })
            .containsExactly("https://bsky.app/profile/$did/post/$rkey", "https://bsky.app/profile/$did/post/${writes[1]["writes"]!!.jsonArray.single().jsonObject["rkey"]!!.jsonPrimitive.content}")
            .inOrder()
        // R31: the attachment's file is gone once the post is sent.
        assertThat(file.exists()).isFalse()
    }

    @Test
    fun aLargePreviewImageBecomesASmallJpegThumbnail() {
        val bitmap = Bitmap.createBitmap(3000, 2000, Bitmap.Config.ARGB_8888)
        for (x in 0 until 3000 step 7) for (y in 0 until 2000 step 5) bitmap.setPixel(x, y, Color.rgb(x % 256, y % 256, (x * y) % 256))
        val png = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

        val thumb = AndroidThumbnails.jpeg(png)!!

        assertThat(thumb.size).isAtMost(LinkCardFetcher.MAX_THUMB_BYTES)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(thumb, 0, thumb.size, bounds)
        assertThat(bounds.outMimeType).isEqualTo("image/jpeg")
        // 3000 × 2000 keeps its shape: 1000 × 666.7, rounded.
        assertThat(bounds.outWidth).isEqualTo(AndroidThumbnails.MAX_SIDE)
        assertThat(bounds.outHeight).isEqualTo(667)
    }

    private fun json(body: String, code: Int = 200) =
        MockResponse.Builder().code(code).addHeader("Content-Type", "application/json").body(body).build()
}
