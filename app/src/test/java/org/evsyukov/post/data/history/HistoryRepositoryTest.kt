package org.evsyukov.post.data.history

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.RecordingFiles
import org.evsyukov.post.data.posts.AccountPostOptions
import org.evsyukov.post.data.inMemoryDatabase
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.PublishedMedia
import org.evsyukov.post.data.posts.PublishedPart
import org.evsyukov.post.data.posts.RoomPostsRepository
import org.evsyukov.post.data.settings.HistoryRetention
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant

/** The Sent tab (R31): retention from the settings, newest first, purge of expired records, R38 ids. */
@RunWith(AndroidJUnit4::class)
class HistoryRepositoryTest {
    private val db = inMemoryDatabase()
    private var retention = HistoryRetention.DAYS_30
    private val repo = RoomHistoryRepository(db) { retention }
    private val sentAt = Instant.parse("2026-11-16T07:00:00Z")

    @After
    fun tearDown() = db.close()

    @Test
    fun aRecordExpiresAfterTheRetentionPeriodOfTheSettings() = runBlocking<Unit> {
        for ((setting, days) in listOf(HistoryRetention.DAYS_7 to 7L, HistoryRetention.DAYS_30 to 30L, HistoryRetention.DAYS_90 to 90L)) {
            retention = setting
            val id = repo.insert(null, sentAt, "text", PostOptions(), emptyList())!!
            assertThat(repo.get(id)!!.expiresAt).isEqualTo(sentAt.plus(Duration.ofDays(days)))
        }
    }

    @Test
    fun withHistoryOffNothingIsKept() = runBlocking<Unit> {
        retention = HistoryRetention.OFF

        assertThat(repo.insert(null, sentAt, "text", PostOptions(), emptyList())).isNull()
        assertThat(repo.observe().first()).isEmpty()
    }

    @Test
    fun recordsKeepTheIdsToEditAndDeleteEachCopy() = runBlocking<Unit> {
        // R38: per account and part, the server ids, the source text, the options and Mastodon media with alt text.
        val items = listOf(
            HistoryItem(
                accountId = 1,
                network = Network.MASTODON,
                server = "https://mastodon.social",
                handle = "alice",
                parts = listOf(
                    PublishedPart(id = "110", url = "https://mastodon.social/@alice/110", media = listOf(PublishedMedia("m1", "A cat"))),
                    PublishedPart(id = "111", url = "https://mastodon.social/@alice/111"),
                ),
            ),
            HistoryItem(
                accountId = 2,
                network = Network.BLUESKY,
                server = "https://pds.example",
                handle = "alice.bsky.social",
                parts = listOf(PublishedPart(uri = "at://did:plc:a/app.bsky.feed.post/3k", cid = "bafyrei", url = "https://bsky.app/profile/a/post/3k")),
            ),
        )
        val options = PostOptions(language = "de", accounts = mapOf(7L to AccountPostOptions(visibility = Visibility.UNLISTED)))

        val postId = RoomPostsRepository(db, RecordingFiles(), { retention }).saveDraft(DraftInput(text = "Source"))
        val id = repo.insert(postId, sentAt, "Source *text*\n\n⸻\n\nsecond", options, items)!!

        val record = repo.get(id)!!
        assertThat(record.postId).isEqualTo(postId)
        assertThat(record.sentAt).isEqualTo(sentAt)
        assertThat(record.text).isEqualTo("Source *text*\n\n⸻\n\nsecond")
        assertThat(record.options).isEqualTo(options)
        assertThat(record.items).isEqualTo(items)
    }

    @Test
    fun recordsAreListedNewestFirstAndCanBeDeleted() = runBlocking<Unit> {
        val old = repo.insert(null, sentAt, "old", PostOptions(), emptyList())!!
        val newest = repo.insert(null, sentAt.plusSeconds(60), "newest", PostOptions(), emptyList())!!
        val middle = repo.insert(null, sentAt.plusSeconds(30), "middle", PostOptions(), emptyList())!!

        assertThat(repo.observe().first().map { it.id }).containsExactly(newest, middle, old).inOrder()

        assertThat(repo.delete(middle)).isTrue()
        assertThat(repo.delete(middle)).isFalse()
        assertThat(repo.observe().first().map { it.id }).containsExactly(newest, old).inOrder()
    }

    @Test
    fun purgeRemovesTheRecordsWhoseTimeHasCome() = runBlocking<Unit> {
        retention = HistoryRetention.DAYS_7
        val expired = repo.insert(null, sentAt, "expired", PostOptions(), emptyList())!!
        val dueNow = repo.insert(null, sentAt.plusSeconds(60), "due now", PostOptions(), emptyList())!!
        val kept = repo.insert(null, sentAt.plusSeconds(61), "kept", PostOptions(), emptyList())!!
        val now = sentAt.plusSeconds(60).plus(Duration.ofDays(7))

        assertThat(repo.purgeExpired(now)).isEqualTo(2)

        assertThat(repo.get(expired)).isNull()
        assertThat(repo.get(dueNow)).isNull()
        assertThat(repo.get(kept)).isNotNull()
        assertThat(repo.purgeExpired(now)).isEqualTo(0)
    }

    @Test
    fun aChangedRetentionAppliesToTheRecordsAlreadyKept() = runBlocking<Unit> {
        retention = HistoryRetention.DAYS_90
        val old = repo.insert(null, sentAt, "old", PostOptions(), emptyList())!!
        val recent = repo.insert(null, sentAt.plus(Duration.ofDays(5)), "recent", PostOptions(), emptyList())!!

        // screens.md, Sent: records older than the retention period are deleted, whatever it was before.
        retention = HistoryRetention.DAYS_7
        assertThat(repo.purgeExpired(sentAt.plus(Duration.ofDays(8)))).isEqualTo(1)
        assertThat(repo.get(old)).isNull()
        assertThat(repo.get(recent)!!.expiresAt).isEqualTo(sentAt.plus(Duration.ofDays(12)))

        // A longer period keeps a record longer than it was first set to.
        retention = HistoryRetention.DAYS_30
        assertThat(repo.purgeExpired(sentAt.plus(Duration.ofDays(13)))).isEqualTo(0)
        assertThat(repo.get(recent)!!.expiresAt).isEqualTo(sentAt.plus(Duration.ofDays(35)))
    }

    @Test
    fun turningHistoryOffDeletesEveryRecordAtTheNextPurge() = runBlocking<Unit> {
        repo.insert(null, sentAt, "one", PostOptions(), emptyList())
        repo.insert(null, sentAt.plusSeconds(60), "two", PostOptions(), emptyList())

        retention = HistoryRetention.OFF

        // R37: with history off the app keeps no ids.
        assertThat(repo.purgeExpired(sentAt.plusSeconds(120))).isEqualTo(2)
        assertThat(repo.observe().first()).isEmpty()
    }
}
