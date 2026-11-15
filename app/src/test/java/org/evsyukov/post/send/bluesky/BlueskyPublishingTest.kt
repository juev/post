package org.evsyukov.post.send.bluesky

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.evsyukov.post.core.bluesky.ContentLabel
import org.evsyukov.post.core.bluesky.Tid
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.data.accounts.ReplyRule
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.AccountPostOptions
import org.evsyukov.post.data.posts.Attachment
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.PostsRepository
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.send.SendFixture
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/**
 * Publishing to Bluesky end to end on the JVM, with MockWebServer as the PDS, the AppView, the video
 * service and a web page (networks.md N16–N27, product.md R5, R14, R29, R30, formatting.md F11).
 */
@RunWith(AndroidJUnit4::class)
class BlueskyPublishingTest {
    private val f = SendFixture(withBluesky = true)
    private val pds = FakePds(f.servers)
    private val appView = FakeAppView(f.servers, mapOf("bob.example.com" to "did:plc:bob"))

    @After
    fun tearDown() = f.close()

    private fun thread(vararg parts: String) = parts.joinToString("\n$MANUAL_BREAK\n")

    private fun options(account: Long, language: String? = null, block: AccountPostOptions.() -> AccountPostOptions = { this }) =
        PostOptions(language = language, accounts = mapOf(account to AccountPostOptions().block()))

    private fun bytesBefore(text: String, part: String): Int {
        val index = text.indexOf(part)
        check(index >= 0) { "'$part' is not in '$text'" }
        return text.substring(0, index).toByteArray(Charsets.UTF_8).size
    }

    private fun slice(text: String, part: String): Pair<Int, Int> {
        val start = bytesBefore(text, part)
        return start to start + part.toByteArray(Charsets.UTF_8).size
    }

    private fun JsonObject.facets(): List<Triple<Int, Int, JsonObject>> = (this["facets"] as? JsonArray).orEmpty().map {
        val index = it.jsonObject["index"]!!.jsonObject
        Triple(index["byteStart"]!!.jsonPrimitive.int, index["byteEnd"]!!.jsonPrimitive.int, it.jsonObject["features"]!!.jsonArray.single().jsonObject)
    }

    private fun JsonObject.type(): String = this["\$type"]!!.jsonPrimitive.content

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    @Test
    fun aSinglePostCarriesFacetsWithUtf8ByteOffsets() {
        val alice = f.bluesky()
        val source = "Привет 🌊 @bob.example.com и @ghost.example.com, [статья](https://example.com/page) #тег"
        val id = f.queue(source, listOf(alice), options(alice, language = "ru") { copy(linkCard = false) })

        f.run()

        val post = pds.posts.single()
        val text = post.text
        // F11: the link text carries the link, the address is not in the text.
        assertThat(text).isEqualTo("Привет 🌊 @bob.example.com и @ghost.example.com, статья #тег")
        val facets = post.value.facets()
        assertThat(facets).hasSize(3)
        val (mentionStart, mentionEnd) = slice(text, "@bob.example.com")
        assertThat(facets[0].first).isEqualTo(mentionStart)
        assertThat(facets[0].second).isEqualTo(mentionEnd)
        assertThat(facets[0].third.type()).isEqualTo("app.bsky.richtext.facet#mention")
        assertThat(facets[0].third.string("did")).isEqualTo("did:plc:bob")
        // N17: a handle that does not resolve stays plain text, without a facet.
        val (linkStart, linkEnd) = slice(text, "статья")
        assertThat(facets[1].first).isEqualTo(linkStart)
        assertThat(facets[1].second).isEqualTo(linkEnd)
        assertThat(facets[1].third.type()).isEqualTo("app.bsky.richtext.facet#link")
        assertThat(facets[1].third.string("uri")).isEqualTo("https://example.com/page")
        val (tagStart, tagEnd) = slice(text, "#тег")
        assertThat(facets[2].first to facets[2].second).isEqualTo(tagStart to tagEnd)
        assertThat(facets[2].third.type()).isEqualTo("app.bsky.richtext.facet#tag")
        assertThat(facets[2].third.string("tag")).isEqualTo("тег")
        assertThat(appView.lookups).containsExactly("bob.example.com", "ghost.example.com")
        assertThat(post.value.type()).isEqualTo("app.bsky.feed.post")
        assertThat(post.value["langs"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("ru")
        assertThat(post.value["reply"]).isNull()
        assertThat(post.value["embed"]).isNull()
        // N22: self-labels only go with media.
        assertThat(post.value["labels"]).isNull()
        assertThat(post.createdAt).isEqualTo("2026-11-16T08:00:00.000Z")
        assertThat(Tid.isValid(post.rkey)).isTrue()
        // Only the post: no gates were asked for.
        assertThat(pds.commits.single()).hasSize(1)
        val sent = f.post(id)!!
        assertThat(sent.state).isEqualTo(PostState.SENT)
        val published = sent.deliveries.single().progress.parts.single().published!!
        assertThat(published.uri).isEqualTo("at://did:plc:abc/app.bsky.feed.post/${post.rkey}")
        assertThat(published.cid).isEqualTo(post.cid)
        assertThat(published.url).isEqualTo("https://bsky.app/profile/did:plc:abc/post/${post.rkey}")
        assertThat(pds.requests("com.atproto.repo.applyWrites").single().headers["Authorization"]).isEqualTo("Bearer a")
    }

    @Test
    fun aThreadOfThreeRepliesInOrderWithAThreadgateFirstAndAPostgateOnEveryPart() {
        val alice = f.bluesky()
        val id = f.queue(
            thread("one", "two", "three"),
            listOf(alice),
            options(alice) { copy(replyRules = setOf(ReplyRule.MENTIONED, ReplyRule.FOLLOWING), allowQuotes = false) },
        )

        f.run()

        // N24: one applyWrites per part.
        assertThat(pds.commits).hasSize(3)
        val posts = pds.posts
        assertThat(posts.map { it.text }).containsExactly("one", "two", "three").inOrder()
        val first = posts[0]
        assertThat(pds.commits[0].map { it.collection })
            .containsExactly("app.bsky.feed.post", "app.bsky.feed.threadgate", "app.bsky.feed.postgate").inOrder()
        assertThat(pds.commits[1].map { it.collection }).containsExactly("app.bsky.feed.post", "app.bsky.feed.postgate").inOrder()
        assertThat(pds.commits[2].map { it.collection }).containsExactly("app.bsky.feed.post", "app.bsky.feed.postgate").inOrder()
        for (commit in pds.commits) {
            // Gates share the key of their post and point at it.
            val post = commit.first()
            assertThat(commit.map { it.rkey }.toSet()).containsExactly(post.rkey)
            for (gate in commit.drop(1)) {
                assertThat(gate.value.string("post")).isEqualTo(pds.uriOf(post))
                assertThat(gate.value.type()).isEqualTo(gate.collection)
            }
        }
        val threadgate = pds.commits[0][1].value
        assertThat(threadgate["allow"]!!.jsonArray.map { it.jsonObject.type() })
            .containsExactly("app.bsky.feed.threadgate#mentionRule", "app.bsky.feed.threadgate#followingRule")
        val postgate = pds.commits[1][1].value
        assertThat(postgate["embeddingRules"]!!.jsonArray.single().jsonObject.type()).isEqualTo("app.bsky.feed.postgate#disableRule")
        // Reply references come from the results of the parts before.
        assertThat(first.value["reply"]).isNull()
        for ((index, post) in posts.withIndex().drop(1)) {
            val reply = post.value["reply"]!!.jsonObject
            assertThat(reply["root"]!!.jsonObject.string("uri")).isEqualTo(pds.uriOf(first))
            assertThat(reply["root"]!!.jsonObject.string("cid")).isEqualTo(first.cid)
            assertThat(reply["parent"]!!.jsonObject.string("uri")).isEqualTo(pds.uriOf(posts[index - 1]))
            assertThat(reply["parent"]!!.jsonObject.string("cid")).isEqualTo(posts[index - 1].cid)
        }
        // createdAt grows by a millisecond per part; record keys grow too.
        assertThat(posts.map { it.createdAt })
            .containsExactly("2026-11-16T08:00:00.000Z", "2026-11-16T08:00:00.001Z", "2026-11-16T08:00:00.002Z").inOrder()
        assertThat(posts.map { it.rkey }).isInStrictOrder()
        val delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.progress.parts.map { it.key }).containsExactlyElementsIn(posts.map { it.rkey }).inOrder()
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
    }

    @Test
    fun aPartWhoseAnswerWasLostIsFoundByItsKeyAndNotWrittenTwice() {
        val alice = f.bluesky()
        var drop = true
        f.dropAnswer = { request -> drop && request.url.encodedPath.endsWith("applyWrites") && pds.commits.size == 2 }
        val id = f.queue(thread("one", "two", "three"), listOf(alice), options(alice) { copy(allowQuotes = false) })

        f.run()

        // The second part reached the server, but its answer was lost: the delivery waits for a retry.
        assertThat(pds.commits).hasSize(2)
        val waiting = f.post(id)!!.deliveries.single()
        assertThat(waiting.state).isEqualTo(DeliveryState.WAITING)
        assertThat(waiting.nextAttemptAt).isNotNull()
        // N24: the key was stored before the request.
        assertThat(waiting.progress.parts.first { it.index == 1 }.key).isEqualTo(pds.posts[1].rkey)
        assertThat(waiting.progress.parts.first { it.index == 1 }.published).isNull()

        drop = false
        f.clock.advance(Duration.ofMinutes(5))
        f.run()

        // N25: getRecord found the second part; only the third was written.
        assertThat(pds.posts.map { it.text }).containsExactly("one", "two", "three").inOrder()
        assertThat(pds.commits).hasSize(3)
        val lookups = pds.requests("com.atproto.repo.getRecord").map { it.url.queryParameter("rkey") }
        assertThat(lookups).contains(pds.posts[1].rkey)
        val third = pds.posts[2].value["reply"]!!.jsonObject
        assertThat(third["parent"]!!.jsonObject.string("cid")).isEqualTo(pds.posts[1].cid)
        assertThat(third["root"]!!.jsonObject.string("cid")).isEqualTo(pds.posts[0].cid)
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
    }

    @Test
    fun aResultWithoutUriIsReadBackWithGetRecord() {
        val alice = f.bluesky()
        pds.withResults = false
        val id = f.queue(thread("one", "two"), listOf(alice))

        f.run()

        assertThat(pds.posts[1].value["reply"]!!.jsonObject["parent"]!!.jsonObject.string("cid")).isEqualTo(pds.posts[0].cid)
        assertThat(f.post(id)!!.deliveries.single().progress.parts.map { it.published!!.cid })
            .containsExactly(pds.posts[0].cid, pds.posts[1].cid).inOrder()
    }

    @Test
    fun imagesAreUploadedAndEmbeddedWithAltAspectRatioAndLabels() {
        val alice = f.bluesky()
        val photos = listOf(f.image("a.jpg", alt = "first"), f.image("b.jpg", alt = "second").copy(width = 600, height = 800))
        val id = f.queue("Two photos", listOf(alice), options(alice) { copy(contentLabels = setOf(ContentLabel.NUDITY)) }, attachments = photos)

        f.run()

        assertThat(pds.blobs).hasSize(2)
        assertThat(pds.blobs.map { it.headers["Content-Type"] }).containsExactly("image/jpeg", "image/jpeg")
        val embed = pds.posts.single().value["embed"]!!.jsonObject
        assertThat(embed.type()).isEqualTo("app.bsky.embed.images")
        val images = embed["images"]!!.jsonArray.map { it.jsonObject }
        assertThat(images.map { it.string("alt") }).containsExactly("first", "second").inOrder()
        assertThat(images.map { it["image"]!!.jsonObject["ref"]!!.jsonObject.string("\$link") }).containsExactly("bafkblob1", "bafkblob2").inOrder()
        assertThat(images[0]["aspectRatio"]!!.jsonObject.let { it["width"]!!.jsonPrimitive.int to it["height"]!!.jsonPrimitive.int }).isEqualTo(800 to 600)
        assertThat(images[1]["aspectRatio"]!!.jsonObject.let { it["width"]!!.jsonPrimitive.int to it["height"]!!.jsonPrimitive.int }).isEqualTo(600 to 800)
        val labels = pds.posts.single().value["labels"]!!.jsonObject
        assertThat(labels.type()).isEqualTo("com.atproto.label.defs#selfLabels")
        assertThat(labels["values"]!!.jsonArray.single().jsonObject.string("val")).isEqualTo("nudity")
        val published = f.post(id)!!.deliveries.single().progress.parts.single().published!!
        assertThat(published.media.map { it.id to it.alt }).containsExactly("bafkblob1" to "first", "bafkblob2" to "second").inOrder()
    }

    @Test
    fun sixImagesGoInOneGallery() {
        val alice = f.bluesky()
        val photos = (1..6).map { f.image("p$it.jpg", alt = "photo $it") }
        f.queue("Gallery", listOf(alice), attachments = photos)

        f.run()

        assertThat(pds.posts).hasSize(1)
        val embed = pds.posts.single().value["embed"]!!.jsonObject
        assertThat(embed.type()).isEqualTo("app.bsky.embed.gallery")
        val items = embed["items"]!!.jsonArray.map { it.jsonObject }
        assertThat(items).hasSize(6)
        assertThat(items.map { it.type() }.toSet()).containsExactly("app.bsky.embed.gallery#image")
        assertThat(items.map { it.string("alt") }).isEqualTo((1..6).map { "photo $it" })
        assertThat(items.all { it["aspectRatio"] != null }).isTrue()
    }

    @Test
    fun blobsAreReusedOnAQuickRetryAndUploadedAgainMuchLater() {
        val alice = f.bluesky()
        pds.writeAnswer = { call, _ -> if (call <= 2) FakePds.error(503, "ServiceUnavailable", "busy") else null }
        val id = f.queue("A photo", listOf(alice), attachments = listOf(f.image()))

        f.run()
        assertThat(pds.blobs).hasSize(1)
        f.clock.advance(Duration.ofMinutes(5))
        f.run()
        // N26: a blob uploaded minutes ago is still there.
        assertThat(pds.blobs).hasSize(1)
        f.clock.advance(Duration.ofHours(2))
        f.run()

        assertThat(pds.blobs).hasSize(2)
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
        assertThat(pds.posts.single().value["embed"]!!.jsonObject["images"]!!.jsonArray.single().jsonObject["image"]!!.jsonObject["ref"]!!.jsonObject.string("\$link"))
            .isEqualTo("bafkblob2")
    }

    private fun video(kind: MediaKind = MediaKind.VIDEO, mime: String = "video/mp4", alt: String = "a wave") = Attachment(
        kind = kind,
        path = f.file(if (kind == MediaKind.GIF) "anim.gif" else "clip.mp4", ByteArray(4096) { it.toByte() }),
        mime = mime,
        sizeBytes = 4096,
        width = 640,
        height = 360,
        durationMs = 3000,
        frameRate = 30.0,
        animated = kind == MediaKind.GIF,
        videoCodec = if (kind == MediaKind.VIDEO) "video/avc" else null,
        alt = alt,
    )

    @Test
    fun aVideoGoesThroughTheVideoServiceAndIsPolledUntilItHasABlob() {
        val alice = f.bluesky()
        val service = FakeVideoService(f.servers)
        service.pollsBeforeDone = 2
        val id = f.queue("A clip", listOf(alice), attachments = listOf(video()))

        f.run()

        // N20: limits with their own token, then the multipart upload with a token for the PDS.
        val auths = pds.serviceAuth.map { it.url.queryParameter("aud") to it.url.queryParameter("lxm") }
        assertThat(auths.first()).isEqualTo("did:web:video.bsky.app" to "app.bsky.video.getUploadLimits")
        assertThat(auths.drop(1).toSet()).containsExactly("did:web:pds.example" to "com.atproto.repo.uploadBlob")
        assertThat(service.started).hasSize(1)
        assertThat(service.parts).hasSize(1)
        assertThat(service.polls.get()).isEqualTo(3)
        assertThat(pds.blobs).isEmpty()
        val embed = pds.posts.single().value["embed"]!!.jsonObject
        assertThat(embed.type()).isEqualTo("app.bsky.embed.video")
        assertThat(embed["video"]!!.jsonObject["ref"]!!.jsonObject.string("\$link")).isEqualTo("bafkvideo")
        assertThat(embed.string("alt")).isEqualTo("a wave")
        assertThat(embed["aspectRatio"]!!.jsonObject["width"]!!.jsonPrimitive.int).isEqualTo(640)
        assertThat(embed["presentation"]).isNull()
        val part = f.post(id)!!.deliveries.single().progress.parts.single()
        assertThat(part.videoJobs).isEmpty()
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
    }

    @Test
    fun aGifIsSentAsVideoWithTheGifPresentation() {
        val alice = f.bluesky()
        FakeVideoService(f.servers)
        f.queue("A gif", listOf(alice), attachments = listOf(video(MediaKind.GIF, "image/gif", alt = "dancing")))

        f.run()

        val embed = pds.posts.single().value["embed"]!!.jsonObject
        assertThat(embed.type()).isEqualTo("app.bsky.embed.video")
        assertThat(embed.string("presentation")).isEqualTo("gif")
        assertThat(embed.string("alt")).isEqualTo("dancing")
    }

    @Test
    fun anExhaustedVideoQuotaFailsTheDeliveryWithAClearMessageAndANotification() {
        val alice = f.bluesky()
        val service = FakeVideoService(f.servers)
        service.canUpload = false
        service.limitsMessage = "Daily upload limit reached"
        val id = f.queue("A clip", listOf(alice), attachments = listOf(video()))

        f.run()

        val delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.state).isEqualTo(DeliveryState.FAILED)
        assertThat(delivery.error).isEqualTo("${BlueskyExecutor.QUOTA_MESSAGE}: Daily upload limit reached")
        assertThat(f.notifier.failures.single().third).isEqualTo(delivery.error)
        assertThat(service.started).isEmpty()
        assertThat(pds.commits).isEmpty()
    }

    @Test
    fun aVideoUploadResumesItsStoredJobAndStartsOverWhenTheServiceForgotIt() {
        val alice = f.bluesky()
        val service = FakeVideoService(f.servers)
        service.finishAnswer = { call ->
            when (call) {
                1 -> FakePds.error(503, "ServiceOverloaded", "try later")
                2 -> FakePds.error(400, "UploadExpired", "the upload expired")
                else -> null
            }
        }
        val id = f.queue("A clip", listOf(alice), attachments = listOf(video()))

        f.run()

        val waiting = f.post(id)!!.deliveries.single()
        assertThat(waiting.state).isEqualTo(DeliveryState.WAITING)
        assertThat(waiting.progress.parts.single().videoJobs.values).containsExactly("job1")

        f.clock.advance(Duration.ofMinutes(5))
        f.run()

        // The stored job was asked about and resumed; its finish said it expired, so a new upload started.
        assertThat(service.requests("getUploadStatus").map { it.url.queryParameter("jobId") }).containsExactly("job1")
        assertThat(service.started).hasSize(2)
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
        assertThat(pds.posts).hasSize(1)
    }

    @Test
    fun aLinkCardIsBuiltFromThePageWhenThePartHasNoMedia() {
        val alice = f.bluesky()
        val page = """<html><head><title>Fallback</title>
            <meta property="og:title" content="Статья">
            <meta property="og:description" content="Про волны">
            <meta property="og:image" content="https://example.com/cover.png"></head><body>text</body></html>"""
        f.servers.route("GET", "example.com/page") { html(page) }
        f.servers.route("GET", "example.com/cover.png") {
            mockwebserver3.MockResponse.Builder().code(200).addHeader("Content-Type", "image/png").body(okio.Buffer().write(ByteArray(3000) { 7 })).build()
        }
        f.linkCards = LinkCardFetcher(f.servers.http) { bytes -> bytes.copyOf(100) }
        f.queue("Read https://example.com/page", listOf(alice))

        f.run()

        val embed = pds.posts.single().value["embed"]!!.jsonObject
        assertThat(embed.type()).isEqualTo("app.bsky.embed.external")
        val external = embed["external"]!!.jsonObject
        assertThat(external.string("uri")).isEqualTo("https://example.com/page")
        assertThat(external.string("title")).isEqualTo("Статья")
        assertThat(external.string("description")).isEqualTo("Про волны")
        assertThat(external["thumb"]!!.jsonObject["ref"]!!.jsonObject.string("\$link")).isEqualTo("bafkblob1")
        assertThat(pds.blobs.single().headers["Content-Type"]).isEqualTo("image/jpeg")
        assertThat(pds.blobs.single().headers["Content-Length"]).isEqualTo("100")
        // R34: only the page and its image were contacted for the card.
        assertThat(f.servers.seen.filter { it.host == "example.com" }.map { it.path }).containsExactly("/page", "/cover.png").inOrder()
    }

    @Test
    fun aCardThatCannotBeBuiltLeavesThePostWithoutOne() {
        val alice = f.bluesky()
        f.servers.route("GET", "example.com/page") { FakePds.error(500, "x", "down") }
        f.linkCards = LinkCardFetcher(f.servers.http) { it }
        val id = f.queue("Read https://example.com/page", listOf(alice))

        f.run()

        assertThat(pds.posts.single().value["embed"]).isNull()
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
        assertThat(f.notifier.failures).isEmpty()
    }

    @Test
    fun noCardIsFetchedWhenTheOptionsTurnItOff() {
        val alice = f.bluesky()
        var fetched = 0
        f.linkCards = LinkCardSource { fetched++; LinkCard(it, "t", "d", null) }
        f.queue("Read https://example.com/page", listOf(alice), options(alice) { copy(linkCard = false) })

        f.run()

        assertThat(fetched).isEqualTo(0)
        assertThat(pds.posts.single().value["embed"]).isNull()
    }

    @Test
    fun anExpiredTokenIsRefreshedAndTheCallRepeated() {
        val alice = f.bluesky()
        pds.accessJwt = "a2"
        pds.refreshJwt = "r"
        val id = f.queue("Hello", listOf(alice))

        f.run()

        assertThat(pds.requests("com.atproto.server.refreshSession").single().headers["Authorization"]).isEqualTo("Bearer r")
        assertThat(pds.posts).hasSize(1)
        assertThat(f.post(id)!!.state).isEqualTo(PostState.SENT)
        val stored = runBlocking { f.accounts.readSecret(alice) } as BlueskySecret
        assertThat(stored.accessJwt).isEqualTo("a2+")
        assertThat(stored.refreshJwt).isEqualTo("r+")
    }

    @Test
    fun aRejectedRefreshStopsTheDeliveryForSignIn() {
        val alice = f.bluesky()
        pds.accessJwt = "other"
        pds.refreshJwt = "other"
        val id = f.queue("Hello", listOf(alice))

        f.run()

        val delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.state).isEqualTo(DeliveryState.FAILED)
        assertThat(delivery.error).isEqualTo(PostsRepository.NEEDS_SIGN_IN)
        assertThat(f.notifier.signIns).containsExactly(alice)
        assertThat(f.account(alice).state).isEqualTo(AccountState.NEEDS_SIGN_IN)
        assertThat(pds.posts).isEmpty()
    }

    @Test
    fun aRateLimitWaitsUntilTheReset() {
        val alice = f.bluesky()
        val reset = f.clock.now.plusSeconds(600).epochSecond
        pds.writeAnswer = { call, _ -> if (call == 1) FakePds.error(429, "RateLimitExceeded", "slow down", mapOf("ratelimit-reset" to "$reset")) else null }
        val id = f.queue("Hello", listOf(alice))

        f.run()

        val delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.state).isEqualTo(DeliveryState.WAITING)
        assertThat(delivery.nextAttemptAt).isEqualTo(Instant.ofEpochSecond(reset))
        assertThat(f.notifier.failures).isEmpty()
    }

    @Test
    fun aValidationErrorFailsTheDeliveryWithTheServersMessage() {
        val alice = f.bluesky()
        pds.writeAnswer = { _, _ -> FakePds.error(400, "InvalidRequest", "Invalid app.bsky.feed.post record") }
        val id = f.queue("Hello", listOf(alice))

        f.run()

        val delivery = f.post(id)!!.deliveries.single()
        assertThat(delivery.state).isEqualTo(DeliveryState.FAILED)
        assertThat(delivery.error).isEqualTo("InvalidRequest: Invalid app.bsky.feed.post record")
        assertThat(f.notifier.failures.single().third).isEqualTo(delivery.error)
    }

    private fun html(body: String) =
        mockwebserver3.MockResponse.Builder().code(200).addHeader("Content-Type", "text/html; charset=utf-8").body(body).build()
}
