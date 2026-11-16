package org.evsyukov.post.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import org.evsyukov.post.accounts.FakeServers
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.history.HistoryRecord
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.KeptMedia
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.Schedule
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Editing and deleting published Mastodon posts through the queue (product.md R37–R41; networks.md N28–N31;
 * threads.md T15), with MockWebServer as the server.
 */
@RunWith(AndroidJUnit4::class)
class MastodonEditDeleteTest {
    private val f = SendFixture()
    private val server = FakeMastodon(f.servers) { f.clock.now }
    private val json = Json { ignoreUnknownKeys = true }

    /** `PUT` and `DELETE` requests in the order they came: "PUT s2", "DELETE s3", … */
    private val calls = CopyOnWriteArrayList<String>()
    private val edits = CopyOnWriteArrayList<JsonObject>()
    private val gone = mutableSetOf<String>()

    /** Answers an edit before the server does. */
    private var editAnswer: (String) -> MockResponse? = { null }

    /** Answers a delete before the server does; [call] counts from 1. */
    private var deleteAnswer: (call: Int, id: String) -> MockResponse? = { _, _ -> null }

    init {
        for (n in 1..12) {
            val id = "s$n"
            f.servers.route("PUT", "${server.host}/api/v1/statuses/$id") { request ->
                calls += "PUT $id"
                editAnswer(id)?.let { return@route it }
                val body = json.parseToJsonElement(request.body).jsonObject
                edits += body
                FakeServers.json(
                    """{"id":"$id","uri":"https://${server.host}/users/alice/statuses/$id","url":"https://${server.host}/@alice/$id",""" +
                        """"created_at":"2026-11-16T08:00:00Z","content":"<p>edited</p>"}""",
                )
            }
            f.servers.route("DELETE", "${server.host}/api/v1/statuses/$id") {
                calls += "DELETE $id"
                deleteAnswer(calls.count { it.startsWith("DELETE") }, id)?.let { return@route it }
                if (!gone.add(id)) return@route FakeMastodon.error(404, "Record not found")
                FakeServers.json("""{"id":"$id","text":"source"}""")
            }
        }
    }

    @After
    fun tearDown() = f.close()

    private fun thread(vararg parts: String) = parts.joinToString("\n$MANUAL_BREAK\n")

    /** Publishes [text] to [accounts] as Send now does and returns its history record. */
    private fun published(text: String, accounts: List<Long>, options: PostOptions = PostOptions(), withImage: Boolean = false): HistoryRecord {
        val id = f.queue(text, accounts, options, attachments = if (withImage) listOf(f.image(alt = "old alt")) else emptyList())
        f.run()
        check(f.post(id)!!.state == PostState.SENT)
        return blocking { f.history.observe().first().single() }
    }

    private fun edit(record: HistoryRecord, text: String, targets: List<Long>, options: PostOptions = record.options): Long = blocking {
        f.posts.saveAndQueue(
            DraftInput(text = text, options = options, targets = targets, kind = PostKind.EDIT, historyId = record.id),
            Schedule.Now(f.clock.instant()),
        )
    }

    private fun record(id: Long) = blocking { f.history.get(id) }

    @Test
    fun anEditSendsOnlyTheChangedPartAndKeepsTheOthers() {
        val alice = f.mastodon()
        val record = published(thread("one", "two", "three"), listOf(alice))

        val editId = edit(record, thread("one", "two, better", "three"), listOf(alice))
        f.run()

        // N29: unchanged parts are not sent; the changed one is edited in place.
        assertThat(calls).containsExactly("PUT s2")
        assertThat(edits.single()["status"]!!.jsonPrimitive.content).isEqualTo("two, better")
        assertThat(server.statuses).hasSize(3)
        // The edit leaves the queue, and history has the new text per part.
        assertThat(f.post(editId)).isNull()
        val after = record(record.id)!!
        assertThat(after.text).isEqualTo(thread("one", "two, better", "three"))
        assertThat(after.items.single().parts.map { it.id }).containsExactly("s1", "s2", "s3").inOrder()
        assertThat(after.items.single().parts.map { it.text }).containsExactly("one", "two, better", "three").inOrder()
    }

    @Test
    fun extraPartsArePostedAsRepliesToTheLastRemainingPart() {
        val alice = f.mastodon()
        val record = published(thread("one", "two"), listOf(alice))

        edit(record, thread("one", "two", "three", "four"), listOf(alice))
        f.run()

        assertThat(calls).isEmpty()
        assertThat(server.statuses.drop(2).map { it.text }).containsExactly("three", "four").inOrder()
        assertThat(server.statuses.drop(2).map { it.inReplyTo }).containsExactly("s2", "s3").inOrder()
        assertThat(record(record.id)!!.items.single().parts.map { it.id }).containsExactly("s1", "s2", "s3", "s4").inOrder()
    }

    @Test
    fun extraPartsKeepThePublishedVisibilityWhenTheDefaultChangedSince() {
        // N28: a followers-only post sent with the account's default stays followers-only, also in its new parts.
        val alice = f.mastodon()
        blocking { f.accounts.updateDefaults(alice, org.evsyukov.post.data.accounts.AccountOptions(visibility = org.evsyukov.post.core.mastodon.Visibility.PRIVATE)) }
        val record = published("one", listOf(alice))
        assertThat(server.statuses.single().visibility).isEqualTo("private")
        blocking { f.accounts.updateDefaults(alice, org.evsyukov.post.data.accounts.AccountOptions(visibility = org.evsyukov.post.core.mastodon.Visibility.PUBLIC)) }

        edit(record, thread("one", "two"), listOf(alice))
        f.run()

        assertThat(server.statuses.map { it.text to it.visibility }).containsExactly("one" to "private", "two" to "private").inOrder()
    }

    @Test
    fun partsPostedBeforeAFailureStayOutOfHistoryForTheRetryAndGoInWhenTheEditIsDeleted() {
        // #4: the edit adds two parts; the second of them is refused, so "three" is online and not in history.
        val alice = f.mastodon()
        val record = published(thread("one", "two"), listOf(alice))
        server.statusAnswer = { _, request -> if ("four" in request.body) FakeMastodon.error(422, "Validation failed") else null }

        val editId = edit(record, thread("one", "two", "three", "four"), listOf(alice))
        f.run()

        assertThat(f.post(editId)!!.state).isEqualTo(PostState.FAILED)
        assertThat(server.statuses.map { it.id }).containsExactly("s1", "s2", "s3").inOrder()
        // A retry compares its plan with the record, so the record still has the old thread.
        assertThat(record(record.id)!!.items.single().parts.map { it.id }).containsExactly("s1", "s2").inOrder()

        assertThat(blocking { f.posts.deleteUnlessSending(editId) }).isTrue()

        val after = record(record.id)!!.items.single().parts
        assertThat(after.map { it.id }).containsExactly("s1", "s2", "s3").inOrder()
        assertThat(after.last().text).isEqualTo("three")

        // Delete published now removes the part the failed edit left online too.
        blocking { PublishedActions(f.posts, f.history, { }, f.clock).delete(record.id, listOf(alice)) }
        f.run()
        assertThat(calls).containsExactly("DELETE s3", "DELETE s2", "DELETE s1").inOrder()
        assertThat(record(record.id)).isNull()
    }

    @Test
    fun anEditThatSucceedsOnOneAccountIsInHistoryWhileTheOtherFails() {
        val alice = f.mastodon()
        val work = f.mastodon(host = "work.example", remoteId = "202")
        FakeMastodon(f.servers, "work.example") { f.clock.now }
        f.servers.route("PUT", "work.example/api/v1/statuses/s1") { FakeMastodon.error(422, "Validation failed") }
        val record = published("both", listOf(alice, work))

        val editId = edit(record, "both, fixed", listOf(alice, work))
        f.run()

        assertThat(f.post(editId)!!.state).isEqualTo(PostState.FAILED)
        val items = record(record.id)!!.items.associateBy { it.accountId }
        assertThat(items[alice]!!.parts.single().text).isEqualTo("both, fixed")
        assertThat(items[work]!!.parts.single().text).isEqualTo("both")

        // Deleting the failed edit leaves both copies as they are on their servers.
        assertThat(blocking { f.posts.deleteUnlessSending(editId) }).isTrue()
        assertThat(record(record.id)!!.items.associateBy { it.accountId }).isEqualTo(items)
    }

    @Test
    fun aDeletionCutShortKeepsWhatIsLeftWhenItIsDeletedFromTheQueue() {
        val alice = f.mastodon()
        val record = published(thread("one", "two", "three"), listOf(alice))
        deleteAnswer = { call, _ -> if (call == 2) FakeMastodon.error(403, "Forbidden") else null }

        val deleteId = blocking { PublishedActions(f.posts, f.history, { }, f.clock).delete(record.id, listOf(alice)) }
        f.run()
        assertThat(f.post(deleteId)!!.state).isEqualTo(PostState.FAILED)

        assertThat(blocking { f.posts.deleteUnlessSending(deleteId) }).isTrue()

        // s3 is gone; the record names only what is still online.
        assertThat(record(record.id)!!.items.single().parts.map { it.id }).containsExactly("s1", "s2").inOrder()
    }

    @Test
    fun surplusPartsAreDeletedLastFirstAfterTheEdit() {
        val alice = f.mastodon()
        val record = published(thread("one", "two", "three", "four"), listOf(alice))

        edit(record, thread("one, all of it"), listOf(alice))
        f.run()

        assertThat(calls).containsExactly("PUT s1", "DELETE s4", "DELETE s3", "DELETE s2").inOrder()
        assertThat(record(record.id)!!.items.single().parts.map { it.id }).containsExactly("s1")
    }

    @Test
    fun aContentWarningChangeEditsEveryPart() {
        val alice = f.mastodon()
        val record = published(thread("one", "two"), listOf(alice))

        edit(record, thread("one", "two"), listOf(alice), record.options.copy(spoilerText = "spoilers"))
        f.run()

        assertThat(calls).containsExactly("PUT s1", "PUT s2").inOrder()
        assertThat(edits.map { it["spoiler_text"]!!.jsonPrimitive.content }.toSet()).containsExactly("spoilers")
    }

    @Test
    fun keptMediaGoByIdWithTheirAltAndRemovedOnesAreLeftOut() {
        val alice = f.mastodon()
        val record = published("A photo", listOf(alice), withImage = true)
        assertThat(record.items.single().parts.single().media.single().id).isEqualTo("m1")

        edit(record, "A photo", listOf(alice), record.options.copy(keptMedia = listOf(KeptMedia(0, 0, "new alt"))))
        f.run()

        // N30: the kept file by its id, its alt text as media_attributes; nothing uploaded again.
        val body = edits.single()
        assertThat((body["media_ids"] as JsonArray).map { it.jsonPrimitive.content }).containsExactly("m1")
        val attribute = (body["media_attributes"] as JsonArray).single().jsonObject
        assertThat(attribute["id"]!!.jsonPrimitive.content).isEqualTo("m1")
        assertThat(attribute["description"]!!.jsonPrimitive.content).isEqualTo("new alt")
        assertThat(server.uploads).hasSize(1)
        assertThat(record(record.id)!!.items.single().parts.single().media.single().alt).isEqualTo("new alt")

        val again = record(record.id)!!
        edit(again, "A photo", listOf(alice), again.options.copy(keptMedia = listOf(KeptMedia(0, 0, "new alt", removed = true))))
        f.run()

        assertThat((edits.last()["media_ids"] as JsonArray)).isEmpty()
        assertThat(record(record.id)!!.items.single().parts.single().media).isEmpty()
    }

    @Test
    fun aRemovedFileGoesFromEveryMastodonCopyWhateverItsSplit() {
        // A (500 characters) splits the long paragraph in two, B (5000) does not: the image is in part 3 on A and
        // in part 2 on B. The composer lists the files of the first copy; the removal is meant for the file itself.
        val alice = f.mastodon()
        val bob = f.mastodon(host = "b.example", remoteId = "202")
        val other = FakeMastodon(f.servers, "b.example") { f.clock.now }
        val bobEdits = CopyOnWriteArrayList<String>()
        for (n in 1..3) {
            f.servers.route("PUT", "b.example/api/v1/statuses/s$n") { request ->
                bobEdits += "PUT s$n " + json.parseToJsonElement(request.body).jsonObject["media_ids"].toString()
                FakeServers.json("""{"id":"s$n","uri":"https://b.example/users/alice/statuses/s$n","created_at":"2026-11-16T08:00:00Z","content":"<p>edited</p>"}""")
            }
        }
        blocking {
            f.accounts.updateLimits(alice, org.evsyukov.post.data.accounts.AccountLimits.Mastodon(org.evsyukov.post.core.mastodon.MastodonCapabilities(maxCharacters = 500)), f.clock.instant())
            f.accounts.updateLimits(bob, org.evsyukov.post.data.accounts.AccountLimits.Mastodon(org.evsyukov.post.core.mastodon.MastodonCapabilities(maxCharacters = 5000)), f.clock.instant())
        }
        val text = thread(List(80) { "Sentence number $it." }.joinToString(" "), "short")
        val id = f.queue(text, listOf(alice, bob), attachments = listOf(f.image().copy(pieceIndex = 1)))
        f.run()
        check(f.post(id)?.state ?: PostState.SENT == PostState.SENT)
        val record = blocking { f.history.observe().first().single() }
        val first = record.items.first { it.network == org.evsyukov.post.core.media.Network.MASTODON }
        val place = first.parts.indexOfFirst { it.media.isNotEmpty() }
        assertThat(record.items.map { item -> item.parts.indexOfFirst { it.media.isNotEmpty() } }.toSet()).hasSize(2)

        // The composer's list: the file of the first copy, by its place there and among all the post's files.
        val removed = KeptMedia(place, 0, "a cat", removed = true, ordinal = 0)
        edit(record, text, listOf(alice, bob), record.options.copy(keptMedia = listOf(removed)))
        f.run()

        assertThat(edits.map { (it["media_ids"] as JsonArray).size }).containsExactly(0)
        assertThat(bobEdits).hasSize(1)
        assertThat(bobEdits.single()).endsWith("[]")
        assertThat(other.statuses).hasSize(2)
    }

    @Test
    fun aServerWithoutEditFailsTheEditWithAMessageOfferingDeleteAndPostAgain() {
        val alice = f.mastodon()
        val record = published("old text", listOf(alice))
        editAnswer = { FakeMastodon.error(405, "Method Not Allowed") }

        val editId = edit(record, "new text", listOf(alice))
        f.run()

        // N31: the edit fails, stays in the Failed group of the queue, and history keeps the old copy.
        val post = f.post(editId)!!
        assertThat(post.state).isEqualTo(PostState.FAILED)
        assertThat(post.deliveries.single().error).isEqualTo(MastodonExecutor.EDIT_UNSUPPORTED)
        assertThat(f.notifier.failures.single().third).isEqualTo(MastodonExecutor.EDIT_UNSUPPORTED)
        assertThat(blocking { f.posts.observeQueue().first() }.map { it.id }).contains(editId)
        assertThat(record(record.id)!!.text).isEqualTo("old text")
    }

    @Test
    fun aClearedAccountKeepsItsCopyUnchanged() {
        val alice = f.mastodon()
        val work = f.mastodon(host = "work.example", remoteId = "202")
        val other = FakeMastodon(f.servers, "work.example") { f.clock.now }
        val record = published("same text", listOf(alice, work))
        check(other.statuses.size == 1)

        edit(record, "new text", listOf(alice))
        f.run()

        assertThat(calls).containsExactly("PUT s1")
        val items = record(record.id)!!.items.associateBy { it.accountId }
        assertThat(items[alice]!!.parts.single().text).isEqualTo("new text")
        assertThat(items[work]!!.parts.single().text).isEqualTo("same text")
    }

    @Test
    fun deleteRemovesEveryPartLastFirstAndCountsAGonePartAsDeleted() {
        val alice = f.mastodon()
        val record = published(thread("one", "two", "three"), listOf(alice))
        gone += "s2"

        val deleteId = blocking { PublishedActions(f.posts, f.history, { }, f.clock).delete(record.id, listOf(alice)) }
        f.run()

        assertThat(calls).containsExactly("DELETE s3", "DELETE s2", "DELETE s1").inOrder()
        // R39: the record leaves history with the sent post; the DELETE post leaves the queue.
        assertThat(record(record.id)).isNull()
        assertThat(f.post(record.postId!!)).isNull()
        assertThat(f.post(deleteId)).isNull()
    }

    @Test
    fun aRateLimitedDeleteWaitsAndResumesWithoutDeletingTwice() {
        val alice = f.mastodon()
        val record = published(thread("one", "two", "three"), listOf(alice))
        deleteAnswer = { call, _ -> if (call == 2) FakeMastodon.error(429, "Too many requests", mapOf("Retry-After" to "600")) else null }

        val deleteId = blocking { PublishedActions(f.posts, f.history, { }, f.clock).delete(record.id, listOf(alice)) }
        f.run()

        // N31: the queue waits on 429; the deletion was saved first and stays queued (R41).
        val waiting = f.post(deleteId)!!
        assertThat(waiting.deliveries.single().state).isEqualTo(DeliveryState.WAITING)
        assertThat(waiting.deliveries.single().progress.deleted).containsExactly("s3")
        assertThat(f.retries.single()).isEqualTo(f.clock.now.plusSeconds(600))
        assertThat(record(record.id)).isNotNull()

        f.clock.advance(Duration.ofMinutes(11))
        f.run()

        assertThat(calls).containsExactly("DELETE s3", "DELETE s2", "DELETE s2", "DELETE s1").inOrder()
        assertThat(record(record.id)).isNull()
    }

    @Test
    fun deletingFromOneAccountKeepsTheRecordForTheOther() {
        val alice = f.mastodon()
        val work = f.mastodon(host = "work.example", remoteId = "202")
        FakeMastodon(f.servers, "work.example") { f.clock.now }
        val record = published("both", listOf(alice, work))

        blocking { PublishedActions(f.posts, f.history, { }, f.clock).delete(record.id, listOf(alice)) }
        f.run()

        assertThat(calls).containsExactly("DELETE s1")
        val after = record(record.id)!!
        assertThat(after.items.map { it.accountId }).containsExactly(work)
        assertThat(f.post(record.postId!!)!!.state).isEqualTo(PostState.SENT)
    }
}
