package org.evsyukov.post.ui.composer

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.R
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.db.PostKind
import org.evsyukov.post.data.history.HistoryItem
import org.evsyukov.post.data.posts.AccountPostOptions
import org.evsyukov.post.data.posts.KeptMedia
import org.evsyukov.post.data.posts.MastodonStatusOptions
import org.evsyukov.post.data.posts.PollDraft
import org.evsyukov.post.data.posts.PostOptions
import org.evsyukov.post.data.posts.PublishedMedia
import org.evsyukov.post.data.posts.PublishedPart
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.theme.PostTheme
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Instant

/** The composer editing a published post (R40, R41; screens.md, Editing a published post; networks.md N28–N32). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h1600dp")
class ComposerPublishedEditTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = ComposerHarness(compose)
    private val alice = h.mastodon("alice")
    private val work = h.mastodon("work")
    private val sky = h.bluesky("sky")
    private val other = h.mastodon("other")

    @After
    fun tearDown() = h.close()

    private fun string(id: Int) = h.context.getString(id)

    private fun exists(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun mastodonItem(account: Long, vararg parts: PublishedPart) =
        HistoryItem(account, Network.MASTODON, "https://x.example", "h$account", parts.toList())

    private fun record(text: String, items: List<HistoryItem>, options: PostOptions = PostOptions()): Long = runBlocking {
        h.container.history.insert(null, Instant.now(), text, options, items)!!
    }

    private fun show(editHistoryId: Long? = null, fromHistoryId: Long? = null) {
        compose.setContent {
            CompositionLocalProvider(LocalAppContainer provides h.container) {
                PostTheme {
                    h.model = viewModel(key = composerModelKey(null, editHistoryId, fromHistoryId)) {
                        ComposerModel(h.container, null, editHistoryId, fromHistoryId)
                    }
                    ComposerScreen(
                        onOpenPosts = {},
                        onOpenSchedule = {},
                        onOpenSettings = {},
                        onExit = { h.exits.incrementAndGet() },
                        editHistoryId = editHistoryId,
                        fromHistoryId = fromHistoryId,
                    )
                }
            }
        }
        h.waitFor(ComposerTags.EDITOR)
        h.waitUntil { h.model.loaded }
    }

    /** The counters render off the main thread after a debounce; the main clock has to move for them. */
    private fun settle(condition: () -> Boolean) {
        val end = System.currentTimeMillis() + 10_000
        while (!condition()) {
            check(System.currentTimeMillis() < end) { "Condition still not satisfied after 10 s" }
            Thread.sleep(10)
            compose.mainClock.advanceTimeBy(200)
            compose.waitForIdle()
        }
    }

    private fun queue() = runBlocking { h.container.posts.observeQueue().first() }

    private fun published() = record(
        "one\n$MANUAL_BREAK\ntwo",
        listOf(
            mastodonItem(alice, PublishedPart(id = "a1", text = "one"), PublishedPart(id = "a2", text = "two")),
            mastodonItem(work, PublishedPart(id = "w1", text = "one"), PublishedPart(id = "w2", text = "two")),
            HistoryItem(sky, Network.BLUESKY, "https://pds.example", "sky.bsky.social", listOf(PublishedPart(uri = "at://did:plc:sky/app.bsky.feed.post/3k"))),
        ),
    )

    @Test
    fun editModeShowsTheHoldersWithMastodonSelectedAndBlueskyLeftUnchanged() {
        val id = published()
        show(editHistoryId = id)

        compose.onNodeWithText(string(R.string.edit_title)).assertExists()
        assertThat(h.text()).isEqualTo("one\n$MANUAL_BREAK\ntwo")
        assertThat(h.model.targets).containsExactly(alice, work)
        // R40: only accounts holding the post are offered; another account cannot become a target.
        assertThat(exists(ComposerTags.target(other))).isFalse()
        h.model.toggleTarget(other)
        assertThat(h.model.targets).doesNotContain(other)
        // N32: Bluesky is left unchanged by default; Send becomes Save and there is no Send later.
        assertThat(exists(ComposerTags.blueskyLeave(sky))).isTrue()
        // This server edits in place, so its copy is a target and gets no repost choice.
        assertThat(exists(ComposerTags.mastodonLeave(alice))).isFalse()
        assertThat(exists(ComposerTags.SEND_LATER)).isFalse()
        compose.onNodeWithContentDescription(string(R.string.edit_save)).assertExists()
    }

    @Test
    fun deleteAndPostAgainMakesBlueskyATargetWithAWarning() {
        val id = published()
        show(editHistoryId = id)

        h.click(ComposerTags.blueskyRepost(sky))

        assertThat(h.model.targets).contains(sky)
        compose.onNodeWithText(string(R.string.edit_bluesky_warning)).assertExists()
        h.click(ComposerTags.blueskyLeave(sky))
        assertThat(h.model.targets).doesNotContain(sky)
    }

    @Test
    fun aMastodonServerThatCannotEditIsLeftUnchangedAndGetsTheRepostChoice() {
        val old = h.mastodon("old", capabilities = MastodonCapabilities(supportsEdit = false))
        val id = record("one", listOf(mastodonItem(old, PublishedPart(id = "o1", text = "one"))))
        show(editHistoryId = id)

        // N31: like a Bluesky copy, it starts unchanged and is deleted and posted again only when chosen.
        assertThat(h.model.targets).doesNotContain(old)
        assertThat(exists(ComposerTags.target(old))).isFalse()
        assertThat(exists(ComposerTags.mastodonLeave(old))).isTrue()
        h.click(ComposerTags.mastodonRepost(old))
        assertThat(h.model.targets).contains(old)
        assertThat(h.model.repostAccounts).containsExactly(old)
        compose.onNodeWithText(string(R.string.edit_mastodon_warning)).assertExists()
        h.click(ComposerTags.mastodonLeave(old))
        assertThat(h.model.targets).doesNotContain(old)
        assertThat(h.model.repostAccounts).isEmpty()
    }

    @Test
    fun theRepostChoiceIsSavedWithTheEdit() {
        val old = h.mastodon("old", capabilities = MastodonCapabilities(supportsEdit = false))
        val id = record("one", listOf(mastodonItem(old, PublishedPart(id = "o1", text = "one"))))
        show(editHistoryId = id)

        h.click(ComposerTags.mastodonRepost(old))
        h.click(ComposerTags.SEND)
        h.waitUntil { queue().isNotEmpty() }

        // N31: the executor deletes the published copy only because the choice travelled with the post.
        assertThat(queue().single().targets).containsExactly(old)
        assertThat(queue().single().options.repostAccounts).containsExactly(old)
    }

    @Test
    fun aMastodonCopyThatCannotBeEditedIsMarkedAsPostedAgainInThePreview() {
        val old = h.mastodon("old", capabilities = MastodonCapabilities(supportsEdit = false))
        val id = record(
            "one\n$MANUAL_BREAK\ntwo",
            listOf(mastodonItem(old, PublishedPart(id = "o1", text = "one"), PublishedPart(id = "o2", text = "two"))),
        )
        show(editHistoryId = id)
        h.click(ComposerTags.mastodonRepost(old))
        settle { h.model.renders?.second?.isNotEmpty() == true }

        val marks = editMarks(h.model.renders!!.second, h.model.editing!!, h.model.keptMedia, h.model.repostAccounts).single()!!

        // N31: the old thread is deleted and every part of the new one is added.
        assertThat(marks.parts).containsExactly(EditMark.ADDED, EditMark.ADDED).inOrder()
        assertThat(marks.deleted).isEqualTo(2)
    }

    @Test
    fun aBlueskyCopyWithFilesRecordedWithoutTheirBlobWarnsTheyAreNotPostedAgain() {
        val sky2 = h.bluesky("sky2")
        val blob = """{"${'$'}type":"blob","ref":{"${'$'}link":"bafk2"},"mimeType":"image/jpeg","size":10}"""
        val id = record(
            "photos",
            listOf(
                HistoryItem(sky, Network.BLUESKY, "https://pds.example", "sky.bsky.social", listOf(PublishedPart(uri = "at://did:plc:sky/app.bsky.feed.post/3k", media = listOf(PublishedMedia("bafk1", "old"))))),
                HistoryItem(sky2, Network.BLUESKY, "https://pds.example", "sky2.bsky.social", listOf(PublishedPart(uri = "at://did:plc:sky2/app.bsky.feed.post/3m", media = listOf(PublishedMedia("bafk2", "new", blob = blob))))),
            ),
        )
        show(editHistoryId = id)

        // N32: a file recorded before history kept its blob reference cannot be embedded again.
        assertThat(filesNotPostedAgain(h.model.editing!!)).containsExactly(sky)
        assertThat(exists(ComposerTags.blueskyFilesLost(sky))).isFalse()
        h.click(ComposerTags.blueskyRepost(sky))
        compose.onNodeWithText(string(R.string.edit_bluesky_files_lost)).assertExists()
    }

    @Test
    fun saveQueuesAnEditOfTheRecordForTheChosenAccounts() {
        val id = published()
        show(editHistoryId = id)

        h.click(ComposerTags.target(work))
        h.type(" more")
        h.click(ComposerTags.SEND)

        h.waitUntil { queue().isNotEmpty() }
        val edit = queue().single()
        // R41: saved locally first as an EDIT of the record, then sent through the queue.
        assertThat(edit.kind).isEqualTo(PostKind.EDIT)
        assertThat(edit.historyId).isEqualTo(id)
        assertThat(edit.targets).containsExactly(alice)
        assertThat(edit.text).isEqualTo("one\n$MANUAL_BREAK\ntwo more")
        h.waitUntil { h.exits.get() == 1 }
        assertThat(h.triggers.get()).isGreaterThan(0)
    }

    @Test
    fun visibilityIsLockedWhileEditing() {
        val id = record("hi", listOf(mastodonItem(alice, PublishedPart(id = "a1", text = "hi"))), PostOptions(accounts = mapOf(alice to AccountPostOptions(visibility = Visibility.UNLISTED))))
        show(editHistoryId = id)

        h.click(ComposerTags.OVERFLOW)
        h.click(ComposerTags.MENU_OPTIONS)
        h.click(ComposerTags.optionsAccount(alice))

        h.waitFor(ComposerTags.VISIBILITY_LOCKED)
        assertThat(exists(AccountOptionsTags.visibility(Visibility.PUBLIC))).isFalse()
    }

    @Test
    fun theLockedVisibilityIsThePublishedOneNotTheCurrentDefault() {
        // Sent followers-only from the defaults, which say Public now.
        val item = mastodonItem(alice, PublishedPart(id = "a1", text = "hi")).copy(mastodon = MastodonStatusOptions(visibility = Visibility.PRIVATE))
        val id = record("hi", listOf(item))
        show(editHistoryId = id)

        val account = runBlocking { h.container.accounts.get(alice) }!!
        assertThat(account.defaults.visibility).isEqualTo(Visibility.PUBLIC)
        assertThat(h.model.optionsFor(account).visibility).isEqualTo(Visibility.PRIVATE)
    }

    @Test
    fun aRemovedPublishedFileAndANewAltAreSavedWithTheEdit() {
        val id = record(
            "photo",
            listOf(
                mastodonItem(
                    alice,
                    PublishedPart(id = "a1", text = "photo", media = listOf(PublishedMedia("m1", "old", attachmentId = 11), PublishedMedia("m2", "second", attachmentId = 12))),
                ),
            ),
        )
        show(editHistoryId = id)

        h.waitFor(ComposerTags.keptMedia(0, 1))
        h.click(ComposerTags.keptMediaToggle(0, 1))
        h.model.setKeptMediaAlt(0, 0, "new alt")
        h.click(ComposerTags.SEND)

        h.waitUntil { queue().isNotEmpty() }
        assertThat(queue().single().options.keptMedia).containsExactly(
            // The attachment names the same file in every copy (#27); the place among all files, in every
            // Mastodon copy whatever its split (T3).
            KeptMedia(0, 0, "new alt", ordinal = 0, attachmentId = 11),
            KeptMedia(0, 1, "second", removed = true, ordinal = 1, attachmentId = 12),
        ).inOrder()
    }

    @Test
    fun aSecondEditStartsFromThePublishedFilesNotFromTheEditsOfTheFirst() {
        // After an edit removed the first file, the second one is at place 0; the old edit must not remove it again.
        val id = record(
            "photo",
            listOf(mastodonItem(alice, PublishedPart(id = "a1", text = "photo", media = listOf(PublishedMedia("m2", "second"))))),
            PostOptions(keptMedia = listOf(KeptMedia(0, 0, "first", removed = true), KeptMedia(0, 1, "second"))),
        )
        show(editHistoryId = id)

        assertThat(h.model.keptMedia).containsExactly(KeptMedia(0, 0, "second", ordinal = 0))
    }

    @Test
    fun thePreviewMarksEditedAddedAndDeletedParts() {
        val id = record(
            "one\n$MANUAL_BREAK\ntwo\n$MANUAL_BREAK\nthree",
            listOf(mastodonItem(alice, PublishedPart(id = "a1", text = "one"), PublishedPart(id = "a2", text = "two"), PublishedPart(id = "a3", text = "three"))),
        )
        show(editHistoryId = id)
        h.model.apply(EditResult("one\n$MANUAL_BREAK\nTWO", 0, 0))
        settle { h.model.renders?.first?.text == "one\n$MANUAL_BREAK\nTWO" }

        val marks = editMarks(h.model.renders!!.second, h.model.editing!!, h.model.keptMedia).single()!!

        assertThat(marks.parts).containsExactly(EditMark.UNCHANGED, EditMark.EDITED).inOrder()
        assertThat(marks.deleted).isEqualTo(1)

        h.model.apply(EditResult("one\n$MANUAL_BREAK\ntwo\n$MANUAL_BREAK\nthree\n$MANUAL_BREAK\nfour", 0, 0))
        settle { h.model.renders?.first?.text?.endsWith("four") == true }
        val added = editMarks(h.model.renders!!.second, h.model.editing!!, h.model.keptMedia).single()!!
        assertThat(added.parts).containsExactly(EditMark.UNCHANGED, EditMark.UNCHANGED, EditMark.UNCHANGED, EditMark.ADDED).inOrder()
        assertThat(added.deleted).isEqualTo(0)

        h.click(ComposerTags.OVERFLOW)
        h.click(ComposerTags.MENU_PREVIEW)
        h.waitFor(ComposerTags.previewMark(0, 3))
    }

    @Test
    fun aPublishedPollWarnsThatChangingItResetsVotes() {
        val id = record("vote", listOf(mastodonItem(alice, PublishedPart(id = "a1", text = "vote"))), PostOptions(poll = PollDraft(listOf("yes", "no"), 86_400)))
        show(editHistoryId = id)

        h.waitFor(ComposerTags.EDIT_POLL_WARNING)
    }

    @Test
    fun aMissingRecordCannotBeEdited() {
        show(editHistoryId = 4242)

        h.waitFor(ComposerTags.EDIT_UNAVAILABLE)
        h.type("anything")
        h.model.toggleTarget(alice)
        h.click(ComposerTags.SEND)
        assertThat(queue()).isEmpty()
    }

    @Test
    fun aNewPostFromASentOneStartsAsAnUnsavedNewDraft() {
        val id = published()
        show(fromHistoryId = id)

        assertThat(h.text()).isEqualTo("one\n$MANUAL_BREAK\ntwo")
        assertThat(h.model.targets).containsExactly(alice, work, sky)
        assertThat(h.model.editing).isNull()
        assertThat(exists(ComposerTags.SEND_LATER)).isTrue()
        // R15: leaving asks whether to keep it.
        assertThat(h.model.hasUnsavedChanges()).isTrue()

        h.click(ComposerTags.SEND)
        h.waitUntil { queue().isNotEmpty() }
        assertThat(queue().single().kind).isEqualTo(PostKind.NEW)
        assertThat(queue().single().historyId).isNull()
    }
}
