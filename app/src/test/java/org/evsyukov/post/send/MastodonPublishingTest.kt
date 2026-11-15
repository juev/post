package org.evsyukov.post.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.MockResponse
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.core.media.Conversion
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.AccountPostOptions
import org.evsyukov.post.data.posts.Attachment
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.PostsRepository
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.settings.HistoryRetention
import org.evsyukov.post.core.media.Network
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Duration

/**
 * Publishing to Mastodon end to end on the JVM, with MockWebServer as the server (product.md R5, R27–R31;
 * networks.md N7–N12; threads.md T15).
 */
@RunWith(AndroidJUnit4::class)
class MastodonPublishingTest {
    private val f = SendFixture()
    private val server = FakeMastodon(f.servers) { f.clock.now }

    @After
    fun tearDown() = f.close()

    private fun thread(vararg parts: String) = parts.joinToString("\n$MANUAL_BREAK\n")

    @Test
    fun aSinglePostIsPublishedWithItsKeyAndBecomesSent() {
        val alice = f.mastodon()
        val id = f.queue("Hello, Mastodon", listOf(alice))

        f.run()

        assertThat(server.statuses).hasSize(1)
        val status = server.statuses.single()
        assertThat(status.text).isEqualTo("Hello, Mastodon")
        assertThat(status.visibility).isEqualTo("public")
        assertThat(status.inReplyTo).isNull()
        assertThat(status.key).isNotEmpty()
        val request = server.requests("/api/v1/statuses").single()
        assertThat(request.headers["Authorization"]).isEqualTo("Bearer token-mastodon.example")
        val post = f.post(id)!!
        assertThat(post.state).isEqualTo(PostState.SENT)
        assertThat(post.deliveries.single().state).isEqualTo(DeliveryState.SENT)
        // R30: the key sent is the one frozen with the plan.
        assertThat(post.deliveries.single().plan!!.parts.single().key).isEqualTo(status.key)
        assertThat(f.notifier.failures).isEmpty()
        assertThat(f.retries).isEmpty()
    }

    @Test
    fun aThreadOfThreeRepliesToEachPartWithTheSameVisibility() {
        val alice = f.mastodon()
        val options = PostOptions(accounts = mapOf(alice to AccountPostOptions(visibility = Visibility.UNLISTED)), spoilerText = "cw")
        val id = f.queue(thread("one", "two", "three"), listOf(alice), options)

        f.run()

        assertThat(server.statuses.map { it.text }).containsExactly("one", "two", "three").inOrder()
        assertThat(server.statuses.map { it.inReplyTo }).containsExactly(null, "s1", "s2").inOrder()
        // N10: the same visibility on every part, passed explicitly; the content warning on every part (N3).
        assertThat(server.statuses.map { it.visibility }.toSet()).containsExactly("unlisted")
        assertThat(server.statuses.map { it.body["spoiler_text"].toString() }.toSet()).containsExactly("\"cw\"")
        // N11: one stable key per part.
        assertThat(server.statuses.map { it.key }.toSet()).hasSize(3)
        val delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.progress.parts.map { it.published!!.id }).containsExactly("s1", "s2", "s3").inOrder()
    }

    @Test
    fun mediaIsUploadedWithAltAndWaitedForBeforeTheStatus() {
        val alice = f.mastodon()
        server.uploadCode = 202
        server.processingPolls = 2
        val id = f.queue("A photo", listOf(alice), attachments = listOf(f.image(alt = "a cat on a mat").copy(focusX = 0.5f, focusY = -0.25f)))

        f.run()

        // N7: 202, then polled until ready, then the status with the media id.
        assertThat(server.uploads).hasSize(1)
        assertThat(server.uploads.single().body).contains("a cat on a mat")
        assertThat(server.uploads.single().body).contains("0.50,-0.25")
        assertThat(server.polls.get()).isEqualTo(3)
        assertThat(server.statuses.single().mediaIds).containsExactly("m1")
        val requests = f.servers.seen.map { "${it.method} ${it.path}" }
        assertThat(requests.indexOf("POST /api/v1/statuses")).isGreaterThan(requests.lastIndexOf("GET /api/v1/media/m1"))
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
    }

    @Test
    fun aFailedMediaProcessingFailsTheDelivery() {
        val alice = f.mastodon()
        server.uploadCode = 202
        val id = f.queue("A photo", listOf(alice), attachments = listOf(f.image()))
        server.processingError = "Error processing thumbnail"

        f.run()

        val delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.state).isEqualTo(DeliveryState.FAILED)
        assertThat(delivery.error).isEqualTo("Error processing thumbnail")
        assertThat(server.statuses).isEmpty()
    }

    @Test
    fun aTransientErrorRetriesWithTheSameKeyAndBackoffFromThirtySeconds() {
        val alice = f.mastodon()
        server.statusAnswer = { attempt, _ -> if (attempt == 1) FakeMastodon.error(503, "Service Unavailable") else null }
        val id = f.queue("Hello", listOf(alice))

        f.run()

        var delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.state).isEqualTo(DeliveryState.WAITING)
        assertThat(delivery.attempts).isEqualTo(1)
        assertThat(delivery.nextAttemptAt).isEqualTo(f.clock.now.plusSeconds(30))
        assertThat(f.retries).containsExactly(f.clock.now.plusSeconds(30))
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENDING)
        assertThat(f.notifier.failures).isEmpty()

        // Not yet due: the run leaves it alone.
        f.run()
        assertThat(server.keys).hasSize(1)

        f.clock.advance(Duration.ofSeconds(30))
        f.run()

        assertThat(server.keys).hasSize(2)
        assertThat(server.keys.toSet()).hasSize(1)
        delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.attempts).isEqualTo(2)
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
        assertThat(server.statuses).hasSize(1)
    }

    @Test
    fun theBackoffDoublesWithEveryAttempt() {
        assertThat(MastodonExecutor.backoff(1)).isEqualTo(Duration.ofSeconds(30))
        assertThat(MastodonExecutor.backoff(2)).isEqualTo(Duration.ofSeconds(60))
        assertThat(MastodonExecutor.backoff(3)).isEqualTo(Duration.ofSeconds(120))
        assertThat(MastodonExecutor.backoff(40)).isEqualTo(Duration.ofHours(1))
    }

    @Test
    fun aRateLimitRetriesAfterRetryAfter() {
        val alice = f.mastodon()
        server.statusAnswer = { _, _ -> FakeMastodon.error(429, "Too many requests", mapOf("Retry-After" to "120")) }
        val id = f.queue("Hello", listOf(alice))

        f.run()

        val delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.state).isEqualTo(DeliveryState.WAITING)
        assertThat(delivery.nextAttemptAt).isEqualTo(f.clock.now.plusSeconds(120))
        assertThat(f.retries).containsExactly(f.clock.now.plusSeconds(120))
        assertThat(f.notifier.failures).isEmpty()
    }

    @Test
    fun aValidationErrorFailsTheDeliveryWithTheServersMessageAndNotifies() {
        val alice = f.mastodon()
        server.statusAnswer = { _, _ -> FakeMastodon.error(422, "Validation failed: Text character limit of 500 exceeded") }
        val id = f.queue("Hello", listOf(alice))

        f.run()

        val post = f.post(id)!!
        assertThat(post.state).isEqualTo(PostState.FAILED)
        assertThat(post.deliveries.single().state).isEqualTo(DeliveryState.FAILED)
        assertThat(post.deliveries.single().error).isEqualTo("Validation failed: Text character limit of 500 exceeded")
        assertThat(f.notifier.failures).containsExactly(Triple(alice, id, "Validation failed: Text character limit of 500 exceeded"))
        assertThat(f.retries).isEmpty()
        // Another run does not try it again: only a retry does.
        f.run()
        assertThat(server.keys).hasSize(1)
    }

    @Test
    fun anOtherClientErrorFailsTheDelivery() {
        val alice = f.mastodon()
        server.statusAnswer = { _, _ -> FakeMastodon.error(404, "Record not found") }
        val id = f.queue("Hello", listOf(alice))

        f.run()

        assertThat(f.post(id)!!.deliveries.single().state).isEqualTo(DeliveryState.FAILED)
        assertThat(f.notifier.failures.single().third).isEqualTo("Record not found")
    }

    @Test
    fun aFileTheClientRefusesFailsTheDelivery() {
        val alice = f.mastodon()
        // uploadMedia refuses a focus point out of range before any request: retrying cannot fix it.
        val id = f.queue("Photo", listOf(alice), attachments = listOf(f.image().copy(focusX = 3f, focusY = 0f)))

        f.run()

        val delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.state).isEqualTo(DeliveryState.FAILED)
        assertThat(server.uploads).isEmpty()
        assertThat(f.notifier.failures).hasSize(1)
    }

    @Test
    fun rejectedCredentialsMarkTheAccountAndSigningInAgainResumes() {
        val alice = f.mastodon()
        server.statusAnswer = { attempt, _ -> if (attempt == 1) FakeMastodon.error(401, "The access token is invalid") else null }
        val id = f.queue("Hello", listOf(alice))

        f.run()

        assertThat(f.account(alice).state).isEqualTo(AccountState.NEEDS_SIGN_IN)
        var delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.state).isEqualTo(DeliveryState.FAILED)
        assertThat(delivery.error).isEqualTo(PostsRepository.NEEDS_SIGN_IN)
        assertThat(f.notifier.signIns).containsExactly(alice)
        assertThat(f.notifier.failures).isEmpty()

        // A post queued meanwhile stops for the same reason without a request.
        val second = f.queue("Second", listOf(alice))
        f.run()
        assertThat(server.keys).hasSize(1)
        assertThat(f.post(second)!!.deliveries.single().error).isEqualTo(PostsRepository.NEEDS_SIGN_IN)

        // R5: signing in again resumes both.
        blocking {
            f.accounts.add(NewAccount(Network.MASTODON, "https://mastodon.example", "alice@mastodon.example", "101", "Alice", null), MastodonSecret("token-new"))
            f.publisher.onSignedIn(alice)
        }
        assertThat(f.triggers.get()).isEqualTo(1)
        delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.state).isEqualTo(DeliveryState.WAITING)
        f.run()

        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
        assertThat(f.post(second)!!.state).isEqualTo(PostState.SENT)
        assertThat(server.requests("/api/v1/statuses").last().headers["Authorization"]).isEqualTo("Bearer token-new")
    }

    @Test
    fun signingInWithoutStoppedDeliveriesDoesNotStartTheSender() {
        val alice = f.mastodon()
        blocking { f.publisher.onSignedIn(alice) }
        assertThat(f.triggers.get()).isEqualTo(0)
    }

    @Test
    fun deliveriesAreIndependent() {
        val failing = f.mastodon(host = "a.example")
        val working = f.mastodon(host = "b.example", remoteId = "202")
        FakeMastodon(f.servers, "a.example") { f.clock.now }.statusAnswer = { _, _ -> FakeMastodon.error(422, "Nope") }
        val b = FakeMastodon(f.servers, "b.example") { f.clock.now }
        val id = f.queue("Hello both", listOf(failing, working))

        f.run()

        val post = f.post(id)!!
        assertThat(post.deliveries.map { it.accountId to it.state })
            .containsExactly(failing to DeliveryState.FAILED, working to DeliveryState.SENT).inOrder()
        assertThat(b.statuses.single().text).isEqualTo("Hello both")
        assertThat(post.state).isEqualTo(PostState.FAILED)

        // A retry of the failed delivery runs it again; the other one is not sent twice.
        assertThat(blocking { f.publisher.retryDelivery(post.deliveries.first().id) }).isTrue()
        assertThat(f.triggers.get()).isEqualTo(1)
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENDING)
        f.run()
        assertThat(b.statuses).hasSize(1)
        assertThat(f.post(id)!!.state).isEqualTo(PostState.FAILED)
    }

    @Test
    fun aCrashDuringPartThreeResumesAtPartThreeWithoutDuplicates() = runBlocking<Unit> {
        val alice = f.mastodon()
        val id = f.queue(thread("one", "two", "three"), listOf(alice))
        lateinit var run: Job
        // The process dies while the third status is being created: the server made it, the app never heard.
        server.afterCreate = { created -> if (created.text == "three" && server.keys.size == 3) run.cancel() }
        run = launch(kotlinx.coroutines.Dispatchers.IO) { f.publisher.run() }
        run.join()
        val left = f.post(id)!!.deliveries.single()
        assertThat(left.state).isEqualTo(DeliveryState.SENDING)
        assertThat(left.progress.firstUnpublished(3)).isEqualTo(2)

        f.run()

        // Parts one and two are not sent again; part three goes with its key, and the server answers with s3.
        assertThat(server.keys).hasSize(4)
        assertThat(server.keys[3]).isEqualTo(server.keys[2])
        assertThat(server.statuses.map { it.text }).containsExactly("one", "two", "three").inOrder()
        assertThat(server.statuses.last().inReplyTo).isEqualTo("s2")
        val delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.progress.parts.map { it.published!!.id }).containsExactly("s1", "s2", "s3").inOrder()
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
    }

    @Test
    fun anUnknownOutcomeIsRetriedWithTheSameKeyWithinTheHour() {
        val alice = f.mastodon()
        // The status is made, but the answer is not what the API documents.
        server.statusAnswer = { attempt, _ -> if (attempt == 1) MockResponse.Builder().code(200).body("{").build() else null }
        val id = f.queue("Hello", listOf(alice))
        f.run()
        assertThat(f.post(id)!!.deliveries.single().state).isEqualTo(DeliveryState.WAITING)
        assertThat(f.post(id)!!.deliveries.single().progress.parts.single().unknownSince).isNotNull()

        f.clock.advance(Duration.ofMinutes(10))
        f.run()

        assertThat(server.keys).hasSize(2)
        assertThat(server.keys.toSet()).hasSize(1)
        assertThat(f.servers.requests("mastodon.example/api/v1/accounts/101/statuses")).isEmpty()
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
    }

    @Test
    fun anUnknownOutcomeOlderThanAnHourIsFoundAmongTheLatestStatusesAndNotPostedAgain() {
        val alice = f.mastodon()
        // The server made the status (s1) but the answer was lost.
        server.statusAnswer = { attempt, request ->
            if (attempt == 1) {
                server.statuses += FakeMastodon.Created("s1", kotlinx.serialization.json.Json.parseToJsonElement(request.body).let { it as kotlinx.serialization.json.JsonObject }, request.headers["Idempotency-Key"], f.clock.now)
                MockResponse.Builder().code(200).body("not json").build()
            } else {
                null
            }
        }
        server.listStatuses("101")
        val id = f.queue("Hello again", listOf(alice))
        f.run()
        assertThat(f.post(id)!!.deliveries.single().state).isEqualTo(DeliveryState.WAITING)

        f.clock.advance(Duration.ofMinutes(61))
        f.run()

        assertThat(f.servers.requests("mastodon.example/api/v1/accounts/101/statuses")).hasSize(1)
        assertThat(server.keys).hasSize(1)
        assertThat(server.statuses).hasSize(1)
        val post = f.post(id)!!
        assertThat(post.state).isEqualTo(PostState.SENT)
        assertThat(post.deliveries.single().progress.parts.single().published!!.id).isEqualTo("s1")
    }

    @Test
    fun anUnknownOutcomeOlderThanAnHourWithNoMatchingStatusIsPostedAgain() {
        val alice = f.mastodon()
        server.statusAnswer = { attempt, _ -> if (attempt == 1) MockResponse.Builder().code(200).body("{").build() else null }
        server.listStatuses("101")
        val id = f.queue("Hello there", listOf(alice))
        f.run()
        // The status listed is not ours: other text.
        server.statuses.clear()
        server.statuses += FakeMastodon.Created("s9", kotlinx.serialization.json.buildJsonObject {
            put("status", JsonPrimitiveOf("Something else"))
            put("visibility", JsonPrimitiveOf("public"))
        }, null, f.clock.now)

        f.clock.advance(Duration.ofMinutes(61))
        f.run()

        assertThat(server.keys).hasSize(2)
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
    }

    @Test
    fun aBlueskyDeliveryWaitsUntouchedWithoutAnExecutor() {
        val alice = f.mastodon()
        val bob = f.bluesky()
        val id = f.queue("Both networks", listOf(alice, bob))

        f.run()

        val post = f.post(id)!!
        val bluesky = post.deliveries.first { it.accountId == bob }
        assertThat(bluesky.state).isEqualTo(DeliveryState.WAITING)
        assertThat(bluesky.attempts).isEqualTo(0)
        assertThat(bluesky.error).isNull()
        assertThat(post.deliveries.first { it.accountId == alice }.state).isEqualTo(DeliveryState.SENT)
        assertThat(post.state).isEqualTo(PostState.SENDING)
    }

    @Test
    fun aPostThatCannotBeSplitFailsWithoutARequest() {
        val alice = f.mastodon()
        val id = f.queue("x".repeat(600), listOf(alice))

        f.run()

        val delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.state).isEqualTo(DeliveryState.FAILED)
        assertThat(delivery.plan).isNull()
        assertThat(delivery.attempts).isEqualTo(1)
        assertThat(server.keys).isEmpty()
        assertThat(f.notifier.failures.single().third).isEqualTo("a word or link is too long for one part")
    }

    @Test
    fun aPostNotYetDueIsNotSent() {
        val alice = f.mastodon()
        val id = blocking {
            f.posts.saveAndQueue(
                org.evsyukov.post.data.posts.DraftInput(text = "Later", targets = listOf(alice)),
                org.evsyukov.post.data.posts.Schedule.Explicit(f.clock.now.plusSeconds(3600)),
            )
        }
        f.run()
        assertThat(server.keys).isEmpty()
        assertThat(f.post(id)!!.state).isEqualTo(PostState.QUEUED)
    }

    @Test
    fun postsDueTogetherGoOutInQueueOrder() {
        val alice = f.mastodon()
        val first = f.queue("first", listOf(alice))
        val second = f.queue("second", listOf(alice))
        f.run()
        assertThat(server.statuses.map { it.text }).containsExactly("first", "second").inOrder()
        assertThat(f.post(first)!!.state).isEqualTo(PostState.SENT)
        assertThat(f.post(second)!!.state).isEqualTo(PostState.SENT)
    }

    // History and files (R31, R38)

    @Test
    fun aSentPostIsRecordedInHistoryAndItsFilesGo() {
        val alice = f.mastodon()
        val options = PostOptions(language = "en", spoilerText = "")
        val id = f.queue(thread("first part", "second part"), listOf(alice), options, attachments = listOf(f.image(alt = "a cat")))
        val attachmentPath = f.post(id)!!.attachments.single().path

        f.run()

        val records = blocking { f.history.observe().first() }
        val record = records.single()
        assertThat(record.postId).isEqualTo(id)
        assertThat(record.text).isEqualTo(thread("first part", "second part"))
        assertThat(record.options).isEqualTo(options)
        assertThat(record.expiresAt).isEqualTo(record.sentAt.plus(Duration.ofDays(30)))
        val item = record.items.single()
        assertThat(item.accountId).isEqualTo(alice)
        assertThat(item.network).isEqualTo(Network.MASTODON)
        assertThat(item.handle).isEqualTo("alice@mastodon.example")
        assertThat(item.parts.map { it.id }).containsExactly("s1", "s2").inOrder()
        assertThat(item.parts.map { it.url }).containsExactly("https://mastodon.example/@alice/s1", "https://mastodon.example/@alice/s2").inOrder()
        assertThat(item.parts.first().media.map { it.id to it.alt }).containsExactly("m1" to "a cat")
        // R31: attachment and variant files are gone.
        assertThat(File(f.filesDir, attachmentPath).exists()).isFalse()
        assertThat(File(f.filesDir, "variants/$id").exists()).isFalse()
        assertThat(f.converter.conversions).isNotEmpty()
        assertThat(f.post(id)!!.attachments).isEmpty()
    }

    @Test
    fun withHistoryOffNothingIsRecordedAndThePostGoes() {
        f.settings = f.settings.copy(historyRetention = HistoryRetention.OFF)
        val alice = f.mastodon()
        val id = f.queue("Hello", listOf(alice), attachments = listOf(f.image()))
        val attachmentPath = f.post(id)!!.attachments.single().path

        f.run()

        assertThat(blocking { f.history.observe().first() }).isEmpty()
        assertThat(f.post(id)).isNull()
        assertThat(File(f.filesDir, attachmentPath).exists()).isFalse()
    }

    @Test
    fun theSuccessSummaryIsSentOnlyWhenTheSettingIsOn() {
        val alice = f.mastodon()
        f.queue("Quiet", listOf(alice))
        f.run()
        assertThat(f.notifier.successes).isEmpty()

        f.settings = f.settings.copy(successNotifications = true)
        val loud = f.queue("Loud", listOf(alice))
        f.run()
        assertThat(f.notifier.successes).containsExactly(loud to 1)
    }

    // Media preparation (media.md A4, A7)

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

    @Test
    fun aVideoOverTheLimitIsTranscodedInTheForegroundBeforeUpload() {
        val alice = f.mastodon()
        f.converter.onConvert = { _, progress -> progress(50) }
        val id = f.queue("Clip", listOf(alice), attachments = listOf(bigVideo()))

        f.run()

        assertThat(f.converter.conversions.single()).isInstanceOf(Conversion.TranscodeVideo::class.java)
        assertThat(f.foregrounds.first()).isEqualTo(SendProgress())
        assertThat(f.foregrounds.any { it.transcodingPostId == id }).isTrue()
        assertThat(server.uploads.single().body).contains("video/mp4")
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
    }

    @Test
    fun cancellingTheConversionPutsThePostBackToTheDraftsWithItsFiles() = runBlocking<Unit> {
        val alice = f.mastodon()
        val started = CompletableDeferred<Unit>()
        f.converter.onConvert = { _, _ ->
            started.complete(Unit)
            awaitCancellation()
        }
        val id = f.queue("Clip", listOf(alice), attachments = listOf(bigVideo()))
        val path = f.post(id)!!.attachments.single().path

        val run = launch(kotlinx.coroutines.Dispatchers.IO) { f.publisher.run() }
        withTimeout(10_000) { started.await() }
        f.publisher.cancelPreparation(id)
        withTimeout(10_000) { run.join() }

        val post = f.post(id)!!
        assertThat(post.state).isEqualTo(PostState.DRAFT)
        assertThat(post.deliveries).isEmpty()
        assertThat(post.attachments).hasSize(1)
        assertThat(File(f.filesDir, path).exists()).isTrue()
        assertThat(server.uploads).isEmpty()
        assertThat(f.notifier.failures).isEmpty()
    }

    @Test
    fun aConversionThatCannotFitFailsTheDelivery() {
        val alice = f.mastodon()
        f.converter.onConvert = { conversion, _ ->
            throw org.evsyukov.post.media.convert.ConversionException.CannotFit((conversion as Conversion.TranscodeVideo).maxBytes, Long.MAX_VALUE)
        }
        val id = f.queue("Clip", listOf(alice), attachments = listOf(bigVideo()))

        f.run()

        val delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.state).isEqualTo(DeliveryState.FAILED)
        assertThat(delivery.error).isEqualTo("a file cannot be made to fit this server's limits")
        assertThat(f.notifier.failures).hasSize(1)
    }

    @Test
    fun anUploadOlderThanADayIsMadeAgain() {
        val alice = f.mastodon()
        // The image uploads, then the status fails for a while.
        server.statusAnswer = { attempt, _ -> if (attempt == 1) FakeMastodon.error(503, "down") else null }
        val id = f.queue("Photo", listOf(alice), attachments = listOf(f.image()))
        f.run()
        assertThat(server.uploads).hasSize(1)

        f.clock.advance(Duration.ofHours(25))
        f.run()

        // N8: the server dropped the unattached media; the retry uploads it again.
        assertThat(server.uploads).hasSize(2)
        assertThat(server.statuses.single().mediaIds).containsExactly("m2")
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
    }

    @Test
    fun aRetrySoonAfterReusesTheUploadedMedia() {
        val alice = f.mastodon()
        server.statusAnswer = { attempt, _ -> if (attempt == 1) FakeMastodon.error(503, "down") else null }
        f.queue("Photo", listOf(alice), attachments = listOf(f.image()))
        f.run()
        f.clock.advance(Duration.ofMinutes(1))
        f.run()
        assertThat(server.uploads).hasSize(1)
        assertThat(server.statuses.single().mediaIds).containsExactly("m1")
    }

    @Test
    fun aPollGoesWithTheFirstPartOnly() {
        val alice = f.mastodon()
        val poll = org.evsyukov.post.data.posts.PollDraft(listOf("yes", "no"), 3600, multiple = true)
        f.queue(thread("Vote?", "Details"), listOf(alice), PostOptions(poll = poll))
        f.run()
        assertThat(server.statuses[0].body["poll"].toString()).contains("\"yes\"")
        assertThat(server.statuses[1].body["poll"]).isNull()
    }
}

private fun JsonPrimitiveOf(value: String) = kotlinx.serialization.json.JsonPrimitive(value)
