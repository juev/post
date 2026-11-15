package org.evsyukov.post.send

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
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
import org.evsyukov.post.data.secrets.MastodonSecret
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Send now on the device, through WorkManager and the real app container, to MockWebServer as a Mastodon
 * instance (product.md R21, R27–R31; networks.md N7–N10): a post with an image as a two-part thread.
 */
@RunWith(AndroidJUnit4::class)
class PublishEndToEndTest {
    private val app = ApplicationProvider.getApplicationContext<PostApplication>()
    private val container = app.container
    private val server = MockWebServer()
    private val statuses = CopyOnWriteArrayList<String>()
    private val uploads = CopyOnWriteArrayList<String>()
    private val keys = CopyOnWriteArrayList<String?>()
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
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.url.encodedPath
                return when {
                    request.method == "POST" && path == "/api/v2/media" -> {
                        uploads += request.body?.utf8().orEmpty()
                        json("""{"id":"m1","type":"image","url":"http://localhost/m1"}""")
                    }
                    request.method == "POST" && path == "/api/v1/statuses" -> {
                        keys += request.headers["Idempotency-Key"]
                        val body = request.body?.utf8().orEmpty()
                        statuses += body
                        val id = "s${statuses.size}"
                        json("""{"id":"$id","uri":"http://localhost/$id","url":"http://localhost/@e2e/$id","created_at":"${Instant.now()}","content":"","visibility":"public"}""")
                    }
                    else -> MockResponse.Builder().code(404).body("""{"error":"not found"}""").build()
                }
            }
        }
        server.start()
        val id = container.accounts.add(
            NewAccount(Network.MASTODON, "http://localhost:${server.port}", "e2e@localhost", "e2e-${System.nanoTime()}", "E2E", null),
            MastodonSecret("e2e-token"),
        )
        accountId = id
        val name = "e2e-${System.nanoTime()}.jpg"
        val file = File(app.filesDir, "attachments/$name").apply { parentFile!!.mkdirs() }
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        val attachment = Attachment(
            kind = MediaKind.IMAGE,
            path = "attachments/$name",
            mime = "image/jpeg",
            sizeBytes = file.length(),
            width = 64,
            height = 48,
            alt = "a red square",
        )

        val postId = container.posts.saveAndQueue(
            DraftInput(text = "first part\n$MANUAL_BREAK\nsecond part", targets = listOf(id), attachments = listOf(attachment)),
            Schedule.Now(Instant.now()),
        )
        this@PublishEndToEndTest.postId = postId
        container.publishTrigger.publishNow()

        withTimeout(60_000) {
            while (container.posts.get(postId)?.state != PostState.SENT) delay(200)
        }

        assertThat(uploads).hasSize(1)
        assertThat(uploads.single()).contains("a red square")
        assertThat(statuses).hasSize(2)
        val first = Json.parseToJsonElement(statuses[0]).jsonObject
        val second = Json.parseToJsonElement(statuses[1]).jsonObject
        assertThat(first["status"]!!.jsonPrimitive.content).isEqualTo("first part")
        assertThat(first["media_ids"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("m1")
        assertThat(second["status"]!!.jsonPrimitive.content).isEqualTo("second part")
        assertThat(second["in_reply_to_id"]!!.jsonPrimitive.content).isEqualTo("s1")
        assertThat(second["visibility"]!!.jsonPrimitive.content).isEqualTo(first["visibility"]!!.jsonPrimitive.content)
        assertThat(keys.toSet()).hasSize(2)
        // R31: the attachment's file is gone once the post is sent.
        assertThat(file.exists()).isFalse()
    }

    private fun json(body: String) = MockResponse.Builder().code(200).addHeader("Content-Type", "application/json").body(body).build()
}
