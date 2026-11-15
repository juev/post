package org.evsyukov.post.ui.composer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.R
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.AccountPostOptions
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.PollDraft
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.data.posts.PostOptions
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Drafts (R15): save, open by id, the question on leaving with unsaved changes, and Clear. */
@RunWith(AndroidJUnit4::class)
class ComposerDraftsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var h: ComposerHarness

    @Before
    fun setUp() {
        h = ComposerHarness(compose)
    }

    @After
    fun tearDown() = h.close()

    private fun drafts(): List<Post> = runBlocking { h.container.posts.observeDrafts().first() }

    private fun absent(tag: String) = assertThat(compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes()).isEmpty()

    @Test
    fun saveDraftKeepsTextTargetsAndOptions() {
        val alice = h.mastodon("alice")
        h.defaultTargets(alice)
        h.show()
        h.type("Draft text")
        h.click(ComposerTags.CW)
        h.node(ComposerTags.SPOILER).performTextInput("spoiler")

        h.menu(ComposerTags.MENU_SAVE)

        h.waitUntil { drafts().isNotEmpty() }
        val draft = drafts().single()
        assertThat(draft.state).isEqualTo(PostState.DRAFT)
        assertThat(draft.text).isEqualTo("Draft text")
        assertThat(draft.targets).containsExactly(alice)
        assertThat(draft.options.spoilerText).isEqualTo("spoiler")
        // Saved: leaving now asks nothing.
        h.click(ComposerTags.POSTS)
        absent(ComposerTags.LEAVE_KEEP)
        assertThat(h.postsOpened.get()).isEqualTo(1)
    }

    @Test
    fun aDraftOpensByIdWithEverythingItHad() {
        val alice = h.mastodon("alice")
        val bob = h.mastodon("bob")
        h.defaultTargets(alice)
        val options = PostOptions(
            language = "ru",
            spoilerText = "cw",
            accounts = mapOf(bob to AccountPostOptions(visibility = Visibility.DIRECT)),
            poll = PollDraft(listOf("A", "B"), expiresInSeconds = 86_400),
        )
        val id = runBlocking { h.container.posts.saveDraft(DraftInput(text = "Saved **text**", options = options, targets = listOf(bob))) }

        h.show(draftId = id)

        h.waitUntil { h.text() == "Saved **text**" }
        h.node(ComposerTags.target(bob)).assertIsSelected()
        h.node(ComposerTags.target(alice)).assertIsNotSelected()
        h.node(ComposerTags.SPOILER).assertIsDisplayed()
        h.node(ComposerTags.POLL).assertExists()
        compose.onNodeWithContentDescription(h.string(R.string.composer_language_description, "Russian")).assertExists()

        // Nothing changed, so nothing is asked; an edit saved again updates the same draft.
        h.type(" more")
        h.menu(ComposerTags.MENU_SAVE)
        h.waitUntil { drafts().single().text == "Saved **text** more" }
        assertThat(drafts().single().id).isEqualTo(id)
        assertThat(drafts().single().options.accounts.getValue(bob).visibility).isEqualTo(Visibility.DIRECT)
        assertThat(drafts().single().options.language).isEqualTo("ru")
    }

    @Test
    fun openingPostsWithUnsavedTextAsksAndCancelStays() {
        h.show()
        h.type("unsaved")

        h.click(ComposerTags.POSTS)
        h.waitFor(ComposerTags.LEAVE_KEEP)
        h.click(ComposerTags.LEAVE_CANCEL)

        assertThat(h.postsOpened.get()).isEqualTo(0)
        assertThat(h.text()).isEqualTo("unsaved")
        assertThat(drafts()).isEmpty()
    }

    @Test
    fun keepDraftSavesAndOpensSettings() {
        h.show()
        h.type("keep me")

        h.click(ComposerTags.SETTINGS)
        h.waitFor(ComposerTags.LEAVE_KEEP)
        h.click(ComposerTags.LEAVE_KEEP)

        h.waitUntil { h.settingsOpened.get() == 1 }
        assertThat(drafts().single().text).isEqualTo("keep me")
    }

    @Test
    fun discardDropsTheTextAndOpensPosts() {
        h.show()
        h.type("drop me")

        h.click(ComposerTags.POSTS)
        h.waitFor(ComposerTags.LEAVE_DISCARD)
        h.click(ComposerTags.LEAVE_DISCARD)

        h.waitUntil { h.postsOpened.get() == 1 }
        assertThat(drafts()).isEmpty()
        h.waitUntil { h.text().isEmpty() }
    }

    @Test
    fun theBackThatWouldExitAsksAndKeepSavesBeforeLeaving() {
        h.show()
        h.type("before exit")

        compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
        h.waitFor(ComposerTags.LEAVE_KEEP)
        assertThat(h.exits.get()).isEqualTo(0)
        h.click(ComposerTags.LEAVE_KEEP)

        h.waitUntil { h.exits.get() == 1 }
        assertThat(drafts().single().text).isEqualTo("before exit")
    }

    @Test
    fun theBackAsksNothingRightAfterAnOpenedDraftIsSavedAgain() {
        val id = runBlocking { h.container.posts.saveDraft(DraftInput(text = "Saved", options = PostOptions(), targets = emptyList())) }
        h.show(draftId = id)
        h.waitUntil { h.text() == "Saved" }
        h.type(" again")
        h.waitUntil { backIsCaught() }

        h.menu(ComposerTags.MENU_SAVE)
        h.waitUntil { drafts().single().text == "Saved again" }

        // R15: nothing is unsaved, so the back is not held for a question.
        h.waitUntil { !backIsCaught() }
    }

    private fun backIsCaught(): Boolean {
        var caught = false
        compose.activityRule.scenario.onActivity { caught = it.onBackPressedDispatcher.hasEnabledCallbacks() }
        return caught
    }

    @Test
    fun anEmptyComposerLeavesWithoutAQuestion() {
        h.show()

        h.click(ComposerTags.POSTS)

        absent(ComposerTags.LEAVE_KEEP)
        assertThat(h.postsOpened.get()).isEqualTo(1)
    }

    @Test
    fun clearAsksAndStartsAnEmptyDraft() {
        h.show()
        h.type("to clear")

        h.menu(ComposerTags.MENU_CLEAR)
        h.waitFor(ComposerTags.CLEAR_CONFIRM)
        assertThat(h.text()).isEqualTo("to clear")
        h.click(ComposerTags.CLEAR_CONFIRM)

        h.waitUntil { h.text().isEmpty() }
    }
}
