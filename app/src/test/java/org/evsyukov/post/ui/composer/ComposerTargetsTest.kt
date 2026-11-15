package org.evsyukov.post.ui.composer

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.R
import org.evsyukov.post.accounts.textProfile
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.core.render.DraftText
import org.evsyukov.post.core.render.TextRenderer
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.accounts.QuotePolicy
import org.evsyukov.post.data.accounts.ReplyRule
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.AccountPostOptions
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.settings.Settings
import org.evsyukov.post.text.AndroidTextSegmenter
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

/** Targets (R7, R16, R17), counters and the status line (R8, T12), Send rules and Send now (R27, R32), polls (R13). */
@RunWith(AndroidJUnit4::class)
class ComposerTargetsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private lateinit var h: ComposerHarness

    @Before
    fun setUp() {
        h = ComposerHarness(compose)
    }

    @After
    fun tearDown() = h.close()

    private fun queued(): List<Post> = runBlocking { h.container.posts.observeQueue().first() }


    private fun waitStatus(index: Int, text: String) =
        h.waitUntil { compose.onAllNodes(hasTestTag(ComposerTags.statusEntry(index)) and hasText(text), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

    // Targets

    @Test
    fun aNewDraftSelectsTheDefaultTargetsAndATapToggles() {
        val alice = h.mastodon("alice")
        val bob = h.mastodon("bob")
        val sky = h.bluesky("sky")
        h.defaultTargets(sky, 999, alice)
        h.show()
        h.waitFor(ComposerTags.target(sky))

        h.node(ComposerTags.target(alice)).assertIsSelected()
        h.node(ComposerTags.target(sky)).assertIsSelected()
        h.node(ComposerTags.target(bob)).assertIsNotSelected()

        h.click(ComposerTags.target(bob))
        h.click(ComposerTags.target(alice))

        h.node(ComposerTags.target(bob)).assertIsSelected()
        h.node(ComposerTags.target(alice)).assertIsNotSelected()
    }

    @Test
    fun aLongPressOpensTheAccountOptionsPrefilledFromItsDefaults() {
        val quotes = MastodonCapabilities(mastodonApiVersion = 7, supportsQuotePolicy = true)
        val alice = h.mastodon("alice", AccountOptions(visibility = Visibility.PRIVATE, quotePolicy = QuotePolicy.FOLLOWERS, sensitive = true), quotes)
        h.defaultTargets(alice)
        h.show()
        h.waitFor(ComposerTags.target(alice))

        h.node(ComposerTags.target(alice)).performTouchInput { longClick() }
        h.waitFor(ComposerTags.ACCOUNT_OPTIONS)

        h.node(AccountOptionsTags.visibility(Visibility.PRIVATE)).assertIsSelected()
        h.node(AccountOptionsTags.quote(QuotePolicy.FOLLOWERS)).assertIsSelected()
        h.node(AccountOptionsTags.SENSITIVE).performScrollTo().assertIsOn()

        h.click(AccountOptionsTags.visibility(Visibility.UNLISTED))
        h.node(AccountOptionsTags.visibility(Visibility.UNLISTED)).assertIsSelected()
    }

    @Test
    fun theQuotePolicyIsOfferedOnlyWhenTheServerSupportsIt() {
        val old = h.mastodon("old")
        h.show()
        h.waitFor(ComposerTags.target(old))

        h.node(ComposerTags.target(old)).performTouchInput { longClick() }
        h.waitFor(ComposerTags.ACCOUNT_OPTIONS)

        assertThat(compose.onAllNodes(hasTestTag(AccountOptionsTags.quote(QuotePolicy.PUBLIC))).fetchSemanticsNodes()).isEmpty()
    }

    @Test
    fun blueskyOptionsArePrefilledAndTheLinkCardIsOnByDefault() {
        val sky = h.bluesky("sky", AccountOptions(replyRules = emptySet(), allowQuotes = false))
        h.show()
        h.waitFor(ComposerTags.target(sky))

        h.node(ComposerTags.target(sky)).performTouchInput { longClick() }
        h.waitFor(ComposerTags.ACCOUNT_OPTIONS)

        h.node(AccountOptionsTags.reply("nobody")).assertIsSelected()
        h.node(AccountOptionsTags.reply("everyone")).assertIsNotSelected()
        h.node(AccountOptionsTags.ALLOW_QUOTES).performScrollTo().assertIsOff()
        h.node(AccountOptionsTags.LINK_CARD).performScrollTo().assertIsOn()
    }

    // Counters and the status line

    @Test
    fun oneShortPartShowsTheCountAndTheLimit() {
        val alice = h.mastodon("alice")
        h.defaultTargets(alice)
        h.show()

        h.type("Hello")

        waitStatus(0, "Mastodon 5/500")
        h.node(ComposerTags.SEND).assertIsEnabled()
    }

    @Test
    fun aLongTextShowsThePartsOfTheBlueskyThreadAsCoreRendersThem() {
        val alice = h.mastodon("alice")
        val sky = h.bluesky("sky")
        h.defaultTargets(alice, sky)
        h.show()
        val text = (1..7).joinToString(" ") { "Sentence number $it is here to fill the post with some words." }

        h.type(text)

        val account = runBlocking { h.container.accounts.get(sky)!! }
        val profile = account.textProfile(Settings())
        val expected = TextRenderer.render(DraftText(text, "", Locale.forLanguageTag(Locale.getDefault().language)), profile, AndroidTextSegmenter())
        assertThat(expected.parts.size).isGreaterThan(1)
        waitStatus(0, "Mastodon ${text.length}/500")
        waitStatus(1, h.plural(R.plurals.composer_status_parts, expected.parts.size, "Bluesky", expected.parts.size))
        h.node(ComposerTags.SEND).assertIsEnabled()

        h.menu(ComposerTags.MENU_PREVIEW)
        h.waitFor(ComposerTags.PREVIEW)
        // The preview shows each part exactly as it is sent.
        for ((index, part) in expected.parts.withIndex()) {
            h.node(ComposerTags.PREVIEW).performScrollToNode(hasTestTag(ComposerTags.previewPart(1, index)))
            compose.onNodeWithText(part.text, useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun aWordThatCannotBeSplitBlocksSendingAndTheDetailsQuoteIt() {
        val sky = h.bluesky("sky")
        h.defaultTargets(sky)
        h.show()
        val word = "x".repeat(320)

        h.type("Start $word")

        waitStatus(0, h.string(R.string.composer_status_issue, "Bluesky"))
        h.node(ComposerTags.SEND).assertIsNotEnabled()

        h.click(ComposerTags.SEND)
        h.waitFor(ComposerTags.DETAILS)
        h.waitUntil { compose.onAllNodes(hasTextPart(word.take(40))).fetchSemanticsNodes().isNotEmpty() }
        assertThat(queued()).isEmpty()
    }

    @Test
    fun theDetailsSayWhenNoSelectedAccountKeepsFormatting() {
        val alice = h.mastodon("alice")
        h.defaultTargets(alice)
        h.show()
        h.type("**bold** text")
        // A plain-text server counts the text without the markup (F12).
        waitStatus(0, "Mastodon 9/500")

        h.click(ComposerTags.STATUS)

        h.waitFor(ComposerTags.DETAILS)
        compose.onNodeWithText(h.string(R.string.composer_formatting_removed)).assertExists()
    }

    // Send

    @Test
    fun sendIsDisabledWithoutATargetAndATapOpensTheDetails() {
        h.mastodon("alice")
        h.show()
        h.type("Hello")

        h.node(ComposerTags.SEND).assertIsNotEnabled()
        h.click(ComposerTags.SEND)

        h.waitFor(ComposerTags.DETAILS)
        compose.onNodeWithText(h.string(R.string.composer_blocker_no_target)).assertExists()
        assertThat(queued()).isEmpty()
        assertThat(h.triggers.get()).isEqualTo(0)
    }

    @Test
    fun sendIsDisabledForAnEmptyPost() {
        val alice = h.mastodon("alice")
        h.defaultTargets(alice)
        h.show()
        waitStatus(0, "Mastodon 0/500")

        h.node(ComposerTags.SEND).assertIsNotEnabled()
    }

    @Test
    fun sendNowQueuesThePostWithItsTargetsAndOptionsCallsTheTriggerAndStartsANewDraft() {
        val alice = h.mastodon("alice", AccountOptions(languages = listOf("de")))
        val sky = h.bluesky("sky")
        h.defaultTargets(alice, sky)
        h.show()
        h.type("Hello *world*")
        h.click(ComposerTags.CW)
        h.node(ComposerTags.SPOILER).performTextInput("cw")
        h.node(ComposerTags.target(sky)).performTouchInput { longClick() }
        h.waitFor(ComposerTags.ACCOUNT_OPTIONS)
        h.click(AccountOptionsTags.reply("nobody"))

        h.click(ComposerTags.SEND)

        h.waitUntil { queued().isNotEmpty() }
        val post = queued().single()
        assertThat(post.state).isEqualTo(PostState.QUEUED)
        assertThat(post.schedule).isInstanceOf(Schedule.Now::class.java)
        assertThat(post.text).isEqualTo("Hello *world*")
        assertThat(post.targets).containsExactly(alice, sky).inOrder()
        assertThat(post.deliveries.map { it.state }).containsExactly(DeliveryState.WAITING, DeliveryState.WAITING)
        assertThat(post.options.language).isEqualTo("de")
        assertThat(post.options.spoilerText).isEqualTo("cw")
        assertThat(post.options.accounts.getValue(sky).replyRules).isEmpty()
        h.waitUntil { h.triggers.get() == 1 }
        // A new empty draft, with the default targets again; R32: the permission was asked once.
        h.waitUntil { h.text().isEmpty() }
        h.node(ComposerTags.target(alice)).assertIsSelected()
        compose.onNodeWithText(h.plural(R.plurals.composer_sending_to, 2, 2)).assertExists()
        h.waitUntil { runBlocking { h.container.settings.current().notificationPermissionAsked } }
    }

    @Test
    fun theQueueCountShowsOnPosts() {
        val alice = h.mastodon("alice")
        h.defaultTargets(alice)
        h.show()
        h.type("one")
        waitStatus(0, "Mastodon 3/500")

        h.click(ComposerTags.SEND)

        h.waitUntil {
            compose.onAllNodes(androidx.compose.ui.test.hasContentDescription(h.plural(R.plurals.composer_posts_queued, 1, 1)))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    // Poll

    @Test
    fun aPollKeepsTheOptionLimitsAndDurationsOfTheServer() {
        val caps = MastodonCapabilities(
            pollMaxOptions = 3,
            pollMaxCharactersPerOption = 10,
            pollMinExpirationSeconds = 3600,
            pollMaxExpirationSeconds = 86_400,
        )
        val alice = h.mastodon("alice", capabilities = caps)
        h.defaultTargets(alice)
        h.show()
        h.type("Vote")

        h.menu(ComposerTags.MENU_POLL)
        h.waitFor(ComposerTags.POLL)

        h.node(ComposerTags.pollOption(0)).assertExists()
        h.node(ComposerTags.pollOption(1)).assertExists()
        // Durations outside the server's range are not offered.
        assertThat(compose.onAllNodes(hasTestTag(ComposerTags.pollDuration(5 * 60L))).fetchSemanticsNodes()).isEmpty()
        assertThat(compose.onAllNodes(hasTestTag(ComposerTags.pollDuration(7 * 86_400L))).fetchSemanticsNodes()).isEmpty()
        h.node(ComposerTags.pollDuration(3600L)).assertExists()
        h.node(ComposerTags.pollDuration(86_400L)).assertIsSelected()

        h.click(ComposerTags.POLL_ADD)
        h.node(ComposerTags.pollOption(2)).assertExists()
        assertThat(compose.onAllNodes(hasTestTag(ComposerTags.POLL_ADD)).fetchSemanticsNodes()).isEmpty()

        // Empty options block sending; filled ones within the limit allow it; a long one blocks again.
        waitStatus(0, "Mastodon 4/500")
        h.node(ComposerTags.SEND).assertIsNotEnabled()
        h.node(ComposerTags.pollOption(0)).performScrollTo().performTextInput("Yes")
        h.node(ComposerTags.pollOption(1)).performScrollTo().performTextInput("No")
        h.node(ComposerTags.pollOption(2)).performScrollTo().performTextInput("Maybe")
        h.waitUntil { runCatching { h.node(ComposerTags.SEND).assertIsEnabled() }.isSuccess }
        h.node(ComposerTags.pollOption(2)).performScrollTo().performTextInput(" later on")
        h.waitUntil { runCatching { h.node(ComposerTags.SEND).assertIsNotEnabled() }.isSuccess }
    }
}
