package org.evsyukov.post.data.history

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.posts.DeliveryPlan
import org.evsyukov.post.data.posts.DeliveryProgress
import org.evsyukov.post.data.posts.MastodonStatusOptions
import org.evsyukov.post.data.posts.PartProgress
import org.evsyukov.post.data.posts.PlannedPart
import org.evsyukov.post.data.posts.PublishedPart
import org.junit.Test

/** #4: what an account's copy is after a delivery of an edit or a deletion acted on it, finished or cut short. */
class HistoryChangesTest {
    private fun bluesky(vararg keys: String) = HistoryItem(
        accountId = 1,
        network = Network.BLUESKY,
        server = "https://pds.example",
        handle = "bob.example",
        parts = keys.map { PublishedPart(uri = "at://did:plc:bob/app.bsky.feed.post/$it", cid = "c$it", text = "old $it") },
    )

    private fun plan(vararg texts: String, mastodon: MastodonStatusOptions? = null) =
        DeliveryPlan(parts = texts.map { PlannedPart(text = it) }, mastodon = mastodon)

    @Test
    fun aRepostCutShortAfterTheOldThreadWentNamesOnlyTheNewPartsPosted() {
        val old = bluesky("a", "b")
        val progress = DeliveryProgress(
            parts = listOf(PartProgress(0, published = PublishedPart(uri = "at://did:plc:bob/app.bsky.feed.post/n", cid = "cn"))),
            deleted = old.parts.map { it.uri!! },
            oldRemoved = true,
        )

        val after = old.after(PostKind.EDIT, plan("new one", "new two"), progress, succeeded = false)!!

        assertThat(after.parts.map { it.uri }).containsExactly("at://did:plc:bob/app.bsky.feed.post/n")
        assertThat(after.parts.single().text).isEqualTo("new one")
    }

    @Test
    fun aRepostCutShortBeforeTheOldThreadWentKeepsTheOldThread() {
        // N32: the new thread goes up first; while the old one is online the record names it, not a mix of both.
        val old = bluesky("a", "b")
        val progress = DeliveryProgress(
            parts = listOf(PartProgress(0, key = "n", published = PublishedPart(uri = "at://did:plc:bob/app.bsky.feed.post/n", cid = "cn"))),
        )

        assertThat(old.after(PostKind.EDIT, plan("new one", "new two"), progress, succeeded = false)).isEqualTo(old)
    }

    @Test
    fun aRepostThatDidNotStartLeavesTheCopyAsItWas() {
        val old = bluesky("a", "b")

        assertThat(old.after(PostKind.EDIT, plan("new"), DeliveryProgress(), succeeded = false)).isEqualTo(old)
    }

    @Test
    fun aDeletionThatRemovedEveryPartLeavesNoCopy() {
        val old = bluesky("a")

        assertThat(old.after(PostKind.DELETE, null, DeliveryProgress(deleted = listOf(old.parts.single().uri!!)), succeeded = true)).isNull()
    }

    @Test
    fun onlyAFinishedEditTakesTheNewMastodonOptions() {
        val before = MastodonStatusOptions(sensitive = false)
        val now = MastodonStatusOptions(sensitive = true)
        val old = HistoryItem(1, Network.MASTODON, "https://m.example", "me", listOf(PublishedPart(id = "s1"), PublishedPart(id = "s2")), before)
        val progress = DeliveryProgress(parts = listOf(PartProgress(0, published = PublishedPart(id = "s1"))))

        // Part 2 still has the old options, so the next edit must send them again.
        assertThat(old.after(PostKind.EDIT, plan("one", "two", mastodon = now), progress, succeeded = false)!!.mastodon).isEqualTo(before)
        val done = progress.copy(parts = progress.parts + PartProgress(1, published = PublishedPart(id = "s2")))
        assertThat(old.after(PostKind.EDIT, plan("one", "two", mastodon = now), done, succeeded = true)!!.mastodon).isEqualTo(now)
    }
}
