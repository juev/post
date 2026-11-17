package org.evsyukov.post.send.bluesky

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.data.accounts.ReplyRule
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.history.HistoryRecord
import org.evsyukov.post.data.posts.AccountPostOptions
import org.evsyukov.post.data.posts.Attachment
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.KeptMedia
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.send.PublishedActions
import org.evsyukov.post.send.SendFixture
import org.evsyukov.post.send.blocking
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Deleting a published Bluesky thread and "delete and post again" (product.md R39–R41; networks.md N32, N33),
 * with MockWebServer as the PDS.
 */
@RunWith(AndroidJUnit4::class)
class BlueskyEditDeleteTest {
    private val f = SendFixture(withBluesky = true)
    private val pds = FakePds(f.servers)

    @After
    fun tearDown() = f.close()

    private fun thread(vararg parts: String) = parts.joinToString("\n$MANUAL_BREAK\n")

    /** A thread published with a threadgate on its first part and a postgate on every part. */
    private fun published(account: Long, vararg parts: String): HistoryRecord {
        val options = PostOptions(accounts = mapOf(account to AccountPostOptions(replyRules = setOf(ReplyRule.FOLLOWERS), allowQuotes = false)))
        val id = f.queue(thread(*parts), listOf(account), options)
        f.run()
        check(f.post(id)!!.state == PostState.SENT)
        return blocking { f.history.observe().first().single() }
    }

    private fun rkeys(record: HistoryRecord) = record.items.single().parts.map { it.uri!!.substringAfterLast('/') }

    @Test
    fun deleteRemovesEveryPartAndItsGatesInOneApplyWrites() {
        val alice = f.bluesky()
        val record = published(alice, "one", "two", "three")
        val keys = rkeys(record)
        assertThat(pds.records).hasSize(3 + 1 + 3)

        blocking { PublishedActions(f.posts, f.history, { }, f.clock).delete(record.id, listOf(alice)) }
        f.run()

        // N33: one call; parts last first, the threadgate, every postgate.
        val deleted = pds.deleteCommits.single()
        assertThat(deleted.filter { it.startsWith("app.bsky.feed.post/") })
            .containsExactlyElementsIn(keys.reversed().map { "app.bsky.feed.post/$it" }).inOrder()
        assertThat(deleted).contains("app.bsky.feed.threadgate/${keys.first()}")
        assertThat(deleted.filter { it.startsWith("app.bsky.feed.postgate/") }).hasSize(3)
        assertThat(pds.records).isEmpty()
        // R39: the record leaves history.
        assertThat(blocking { f.history.get(record.id) }).isNull()
    }

    @Test
    fun aPartAlreadyGoneIsLeftOutOfTheDeletion() {
        val alice = f.bluesky()
        val record = published(alice, "one", "two")
        val keys = rkeys(record)
        pds.records.remove("app.bsky.feed.post/${keys[1]}")
        pds.records.remove("app.bsky.feed.postgate/${keys[1]}")

        blocking { PublishedActions(f.posts, f.history, { }, f.clock).delete(record.id, listOf(alice)) }
        f.run()

        assertThat(pds.deleteCommits.single()).containsExactly(
            "app.bsky.feed.post/${keys[0]}",
            "app.bsky.feed.threadgate/${keys[0]}",
            "app.bsky.feed.postgate/${keys[0]}",
        )
        assertThat(blocking { f.history.get(record.id) }).isNull()
    }

    private fun edit(record: HistoryRecord, account: Long, text: String, options: PostOptions = record.options): Long = blocking {
        f.posts.saveAndQueue(
            DraftInput(text = text, options = options, targets = listOf(account), kind = PostKind.EDIT, historyId = record.id),
            Schedule.Now(f.clock.instant()),
        )
    }

    /** What each `applyWrites` call did, in order. */
    private fun writeCalls() = pds.requests("com.atproto.repo.applyWrites").map { if (it.body.contains("applyWrites#delete")) "delete" else "create" }

    private fun JsonObject.string(key: String): String = this[key]!!.jsonPrimitive.content

    private fun JsonObject.link(): String = this["ref"]!!.jsonObject.string("\$link")

    private fun JsonObject.aspect(): Pair<Int, Int> = this["aspectRatio"]!!.jsonObject.let { it["width"]!!.jsonPrimitive.int to it["height"]!!.jsonPrimitive.int }

    @Test
    fun deleteAndPostAgainPublishesTheNewThreadThenDeletesTheOldOne() {
        val alice = f.bluesky()
        val record = published(alice, "one", "two")
        val oldKeys = rkeys(record)

        val editId = edit(record, alice, thread("one", "two", "three"))
        f.run()

        // N32: the new thread goes up first, part by part; the old one is deleted after it, in one call.
        assertThat(writeCalls()).containsExactly("create", "create", "create", "create", "create", "delete").inOrder()
        assertThat(pds.deleteCommits.single()).hasSize(2 + 1 + 2)
        assertThat(pds.posts.drop(2).map { it.text }).containsExactly("one", "two", "three").inOrder()
        assertThat(f.post(editId)).isNull()
        // History keeps the new copy: new keys, new text.
        val after = blocking { f.history.get(record.id) }!!
        val newKeys = rkeys(after)
        assertThat(newKeys).hasSize(3)
        assertThat(newKeys).containsNoneIn(oldKeys)
        assertThat(after.items.single().parts.map { it.text }).containsExactly("one", "two", "three").inOrder()
        assertThat(after.text).isEqualTo(thread("one", "two", "three"))
    }

    @Test
    fun aRepostEmbedsTheKeptImagesByTheirBlobsWithTheEditedAltText() {
        val alice = f.bluesky()
        pds.keepsBlobs = true
        val photos = listOf(f.image("a.jpg", "first"), f.image("b.jpg", "second"), f.image("c.jpg", "third"))
        val id = f.queue("Photos", listOf(alice), attachments = photos)
        f.run()
        check(f.post(id)!!.state == PostState.SENT)
        val record = blocking { f.history.observe().first().single() }
        val oldKey = rkeys(record).single()
        // R38: history keeps each file's full blob reference and aspect ratio.
        val published = record.items.single().parts.single().media
        assertThat(published.map { it.id }).containsExactly("bafkblob1", "bafkblob2", "bafkblob3").inOrder()
        val firstBlob = Json.parseToJsonElement(published.first().blob!!).jsonObject
        assertThat(firstBlob.string("\$type")).isEqualTo("blob")
        assertThat(firstBlob.link()).isEqualTo("bafkblob1")
        assertThat(firstBlob.string("mimeType")).isEqualTo("image/jpeg")
        assertThat(firstBlob["size"]!!.jsonPrimitive.long).isGreaterThan(0L)
        assertThat(published.first().width to published.first().height).isEqualTo(800 to 600)

        val edits = listOf(KeptMedia(0, 1, "second, fixed", ordinal = 1), KeptMedia(0, 2, "third", removed = true, ordinal = 2))
        val editId = edit(record, alice, "Photos, fixed", record.options.copy(keptMedia = edits))
        f.run()

        assertThat(f.post(editId)).isNull()
        // N32: nothing is uploaded again; the new post embeds the old blobs with the edited alt text, without the removed one.
        assertThat(pds.blobs).hasSize(3)
        val repost = pds.posts.last()
        assertThat(repost.text).isEqualTo("Photos, fixed")
        val images = repost.value["embed"]!!.jsonObject["images"]!!.jsonArray.map { it.jsonObject }
        assertThat(images.map { it["image"]!!.jsonObject.link() }).containsExactly("bafkblob1", "bafkblob2").inOrder()
        assertThat(images.map { it.string("alt") }).containsExactly("first", "second, fixed").inOrder()
        assertThat(images.map { it.aspect() }).containsExactly(800 to 600, 800 to 600)
        // The old post is gone; the PDS keeps the blobs the new post refers to and drops the removed one.
        assertThat(pds.records.keys).doesNotContain("app.bsky.feed.post/$oldKey")
        assertThat(pds.storedBlobs).containsExactly("bafkblob1", "bafkblob2")
        // History names the new copy with the files it embeds, ready for the next repost.
        val after = blocking { f.history.get(record.id) }!!.items.single().parts.single().media
        assertThat(after.map { it.id to it.alt }).containsExactly("bafkblob1" to "first", "bafkblob2" to "second, fixed").inOrder()
        assertThat(after.map { it.blob }).containsExactly(published[0].blob, published[1].blob).inOrder()
    }

    @Test
    fun aRepostKeepsTheGifAsAVideoWithoutUploadingItAgain() {
        val alice = f.bluesky()
        pds.keepsBlobs = true
        val service = FakeVideoService(f.servers)
        pds.storedBlobs += "bafkvideo"
        val gif = Attachment(
            kind = MediaKind.GIF,
            path = f.file("anim.gif", ByteArray(4096) { it.toByte() }),
            mime = "image/gif",
            sizeBytes = 4096,
            width = 640,
            height = 360,
            durationMs = 3000,
            frameRate = 30.0,
            animated = true,
            alt = "dancing",
        )
        val id = f.queue("A gif", listOf(alice), attachments = listOf(gif))
        f.run()
        check(f.post(id)!!.state == PostState.SENT)
        val record = blocking { f.history.observe().first().single() }

        val editId = edit(record, alice, "A gif, fixed")
        f.run()

        assertThat(f.post(editId)).isNull()
        assertThat(service.started).hasSize(1)
        val embed = pds.posts.last().value["embed"]!!.jsonObject
        assertThat(embed.string("\$type")).isEqualTo("app.bsky.embed.video")
        assertThat(embed["video"]!!.jsonObject.link()).isEqualTo("bafkvideo")
        assertThat(embed.string("alt")).isEqualTo("dancing")
        assertThat(embed.aspect()).isEqualTo(640 to 360)
        assertThat(embed.string("presentation")).isEqualTo("gif")
        assertThat(pds.storedBlobs).contains("bafkvideo")
    }

    @Test
    fun aRepostTheServerRefusesIsDeletedAgainAndTheOldThreadStays() {
        val alice = f.bluesky()
        val record = published(alice, "one", "two")
        val oldKeys = rkeys(record)
        // The second part of the new thread is refused for good.
        var creates = 0
        pds.writeAnswer = { _, request ->
            if (request.body.contains("applyWrites#create") && ++creates == 2) FakePds.error(400, "InvalidRequest", "Invalid app.bsky.feed.post record") else null
        }

        val editId = edit(record, alice, thread("one, fixed", "two, fixed"))
        f.run()

        val failed = f.post(editId)!!
        assertThat(failed.state).isEqualTo(PostState.FAILED)
        assertThat(failed.deliveries.single().error).contains("Invalid app.bsky.feed.post record")
        // N32: the new first part goes again with its threadgate and postgate; the old thread is untouched.
        val newKey = pds.posts.drop(2).single().rkey
        assertThat(pds.deleteCommits.single()).containsExactly(
            "app.bsky.feed.post/$newKey",
            "app.bsky.feed.threadgate/$newKey",
            "app.bsky.feed.postgate/$newKey",
        )
        assertThat(pds.records.keys.filter { it.startsWith("app.bsky.feed.post/") }).containsExactlyElementsIn(oldKeys.map { "app.bsky.feed.post/$it" })
        assertThat(failed.deliveries.single().progress.parts).isEmpty()
        assertThat(blocking { f.history.get(record.id) }).isEqualTo(record)

        // Deleting the failed edit leaves the record naming the old thread.
        assertThat(blocking { f.posts.deleteUnlessSending(editId) }).isTrue()
        assertThat(blocking { f.history.get(record.id) }).isEqualTo(record)
    }

    @Test
    fun aRetryAfterTheNewThreadWentUpDoesNotPostItAgain() {
        val alice = f.bluesky()
        val record = published(alice, "one")
        // The deletion of the old thread fails with a server error; the new thread is up already.
        var deletes = 0
        pds.writeAnswer = { _, request ->
            if (request.body.contains("applyWrites#delete") && deletes++ == 0) FakePds.error(502, "BadGateway", "upstream") else null
        }

        val editId = edit(record, alice, "new one")
        f.run()

        val waiting = f.post(editId)!!.deliveries.single()
        assertThat(waiting.state).isEqualTo(DeliveryState.WAITING)
        assertThat(waiting.progress.oldRemoved).isFalse()
        assertThat(waiting.progress.parts.single().published).isNotNull()
        // While the old thread is online, the record names it.
        assertThat(blocking { f.history.get(record.id) }).isEqualTo(record)

        f.clock.advance(java.time.Duration.ofMinutes(5))
        f.run()

        assertThat(pds.posts.map { it.text }).containsExactly("one", "new one").inOrder()
        assertThat(pds.deleteCommits).hasSize(1)
        assertThat(f.post(editId)).isNull()
        assertThat(blocking { f.history.get(record.id) }!!.items.single().parts.single().text).isEqualTo("new one")
    }

    @Test
    fun aRepostThatSucceedsIsInHistoryAtOnceSoDeletePublishedReachesItAfterTheFailedEditIsDeleted() {
        // #4: Mastodon A and Bluesky B; B is posted again, A's edit fails, the user deletes the edit from the queue.
        val alice = f.mastodon()
        val mastodon = org.evsyukov.post.send.FakeMastodon(f.servers) { f.clock.now }
        f.servers.route("PUT", "${mastodon.host}/api/v1/statuses/s1") { org.evsyukov.post.send.FakeMastodon.error(422, "Validation failed") }
        val bob = f.bluesky()
        val id = f.queue("one", listOf(alice, bob))
        f.run()
        check(f.post(id)!!.state == PostState.SENT)
        val record = blocking { f.history.observe().first().single() }
        val oldKeys = record.items.first { it.accountId == bob }.parts.map { it.uri!!.substringAfterLast('/') }

        val editId = blocking {
            f.posts.saveAndQueue(
                DraftInput(text = "one, fixed", options = record.options, targets = listOf(alice, bob), kind = PostKind.EDIT, historyId = record.id),
                Schedule.Now(f.clock.instant()),
            )
        }
        f.run()

        assertThat(f.post(editId)!!.state).isEqualTo(PostState.FAILED)
        // B's new thread is in the record already; A keeps its old copy.
        val during = blocking { f.history.get(record.id) }!!.items.associateBy { it.accountId }
        val newKeys = during[bob]!!.parts.map { it.uri!!.substringAfterLast('/') }
        assertThat(newKeys).containsNoneIn(oldKeys)
        assertThat(pds.records.keys).contains("app.bsky.feed.post/${newKeys.single()}")
        assertThat(during[alice]!!.parts.single().id).isEqualTo("s1")

        assertThat(blocking { f.posts.deleteUnlessSending(editId) }).isTrue()
        blocking { PublishedActions(f.posts, f.history, { }, f.clock).delete(record.id, listOf(bob)) }
        f.run()

        // Delete published reaches the new thread, not the gone one.
        assertThat(pds.deleteCommits.last()).contains("app.bsky.feed.post/${newKeys.single()}")
        assertThat(pds.records.keys.filter { it.startsWith("app.bsky.feed.post/") }).isEmpty()
        assertThat(blocking { f.history.get(record.id) }!!.items.map { it.accountId }).containsExactly(alice)
    }
}
