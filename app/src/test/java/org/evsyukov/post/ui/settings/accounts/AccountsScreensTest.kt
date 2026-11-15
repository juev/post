package org.evsyukov.post.ui.settings.accounts

import androidx.activity.ComponentActivity
import androidx.annotation.StringRes
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.R
import org.evsyukov.post.accounts.SignInProblem
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.AccountLimits
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.accounts.ReplyRule
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.oauth.MastodonFlowState
import org.evsyukov.post.ui.theme.PostTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Settings → Accounts, the add forms and the account details (screens.md; R1, R2, R5, R17, R33). */
@RunWith(AndroidJUnit4::class)
class AccountsScreensTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private fun account(
        id: Long,
        network: Network,
        name: String,
        handle: String,
        label: String = "",
        state: AccountState = AccountState.OK,
        limits: AccountLimits? = null,
        defaults: AccountOptions = AccountOptions(),
    ) = Account(
        id = id, network = network, server = "https://s.example", handle = handle, remoteId = "r$id", displayName = name,
        avatarUrl = null, label = label, position = 0, state = state, limits = limits,
        limitsFetchedAt = limits?.let { Instant.parse("2026-11-16T08:00:00Z") }, defaults = defaults, createdAt = Instant.EPOCH,
    )

    private val list = listOf(
        account(1, Network.MASTODON, "Me", "me@mastodon.example", label = "personal"),
        account(2, Network.MASTODON, "Work", "work@fosstodon.example", state = AccountState.NEEDS_SIGN_IN),
        account(3, Network.BLUESKY, "Alice", "alice.example.com"),
    )

    // ---- Accounts list ----

    @Test
    fun accountsAreGroupedByNetworkWithNameHandleLabelAndTheSignInMark() {
        compose.setContent { PostTheme { AccountsContent(list, {}, {}, { _, _ -> }, {}) } }

        compose.onNodeWithTag(AccountsTags.header(Network.MASTODON)).assertIsDisplayed()
        compose.onNodeWithTag(AccountsTags.header(Network.BLUESKY)).assertIsDisplayed()
        compose.onNodeWithText("Me").assertIsDisplayed()
        compose.onNodeWithText("@me@mastodon.example").assertIsDisplayed()
        compose.onNodeWithText("personal").assertIsDisplayed()
        compose.onNodeWithText("@alice.example.com").assertIsDisplayed()
        // R5: only the rejected account carries the mark.
        compose.onNodeWithTag(AccountsTags.row(2)).assert(androidx.compose.ui.test.hasText(string(R.string.accounts_needs_sign_in)))
        compose.onNodeWithTag(AccountsTags.row(1)).assert(androidx.compose.ui.test.hasText(string(R.string.accounts_needs_sign_in)).not())
    }

    @Test
    fun theRowMenuMovesAnAccountWithinItsNetwork() {
        val moves = mutableListOf<Pair<Long, Boolean>>()
        compose.setContent { PostTheme { AccountsContent(list, {}, {}, { id, up -> moves += id to up }, {}) } }

        // R33: the menu button is labelled with the account.
        compose.onNodeWithContentDescription(string(R.string.account_menu, "Me")).performClick()
        compose.onNodeWithTag(AccountsTags.MOVE_UP).assertIsNotEnabled()
        compose.onNodeWithTag(AccountsTags.MOVE_DOWN).assertIsEnabled().performClick()

        assertThat(moves).containsExactly(1L to false)
    }

    @Test
    fun movedOrderSwapsWithinTheNetworkOnly() {
        assertThat(movedOrder(list, 1, up = false)).containsExactly(2L, 1L).inOrder()
        assertThat(movedOrder(list, 2, up = true)).containsExactly(2L, 1L).inOrder()
        assertThat(movedOrder(list, 1, up = true)).isNull()
        assertThat(movedOrder(list, 3, up = true)).isNull()
        assertThat(movedOrder(list, 3, up = false)).isNull()
    }

    @Test
    fun addAccountAsksForTheNetworkFirst() {
        val chosen = mutableListOf<Network>()
        compose.setContent { PostTheme { AccountsContent(emptyList(), {}, {}, { _, _ -> }, { chosen += it }) } }

        compose.onNodeWithTag(AccountsTags.EMPTY).assertIsDisplayed()
        compose.onNodeWithTag(AccountsTags.ADD).performClick()
        compose.onNodeWithText(string(R.string.accounts_choose_network)).assertIsDisplayed()
        compose.onNodeWithTag(AccountsTags.CHOOSE_BLUESKY).performClick()

        assertThat(chosen).containsExactly(Network.BLUESKY)
        compose.onNodeWithText(string(R.string.accounts_choose_network)).assertDoesNotExist()
    }

    @Test
    fun tappingAnAccountOpensIt() {
        val opened = mutableListOf<Long>()
        compose.setContent { PostTheme { AccountsContent(list, {}, { opened += it }, { _, _ -> }, {}) } }

        compose.onNodeWithTag(AccountsTags.row(3)).performClick()

        assertThat(opened).containsExactly(3L)
    }

    // ---- Add Mastodon account ----

    @Test
    fun theInstanceFieldIsCheckedBeforeContinue() {
        var address by mutableStateOf("")
        var continued = 0
        compose.setContent {
            PostTheme {
                AddMastodonContent(address, { address = it }, addressValid = address == "mastodon.social", MastodonFlowState.Idle, false, { continued++ }, {}, {})
            }
        }

        compose.onNodeWithTag(AddMastodonTags.CONTINUE).assertIsNotEnabled()
        compose.onNodeWithTag(AddMastodonTags.ADDRESS).performTextInput("not a server")
        compose.onNodeWithText(string(R.string.problem_invalid_address)).assertIsDisplayed()
        compose.onNodeWithTag(AddMastodonTags.CONTINUE).assertIsNotEnabled()

        address = "mastodon.social"
        compose.onNodeWithText(string(R.string.problem_invalid_address)).assertDoesNotExist()
        compose.onNodeWithTag(AddMastodonTags.CONTINUE).assertIsEnabled().performClick()
        assertThat(continued).isEqualTo(1)
    }

    @Test
    fun whileTheBrowserIsOpenTheFormWaitsAndCanBeCancelled() {
        var cancelled = 0
        compose.setContent {
            PostTheme { AddMastodonContent("mastodon.social", {}, true, MastodonFlowState.InBrowser, false, {}, { cancelled++ }, {}) }
        }

        compose.onNodeWithText(string(R.string.add_mastodon_in_browser)).assertIsDisplayed()
        compose.onNodeWithTag(AddMastodonTags.CONTINUE).assertDoesNotExist()
        compose.onNodeWithTag(AddMastodonTags.CANCEL).performClick()
        assertThat(cancelled).isEqualTo(1)
    }

    @Test
    fun aFailedSignInShowsWhyAndOffersARetry() {
        compose.setContent {
            PostTheme { AddMastodonContent("mastodon.social", {}, true, MastodonFlowState.Failed(SignInProblem.NOT_MASTODON), false, {}, {}, {}) }
        }

        compose.onNodeWithText(string(R.string.problem_not_mastodon)).assertIsDisplayed()
        compose.onNodeWithText(string(R.string.try_again)).assertIsDisplayed().assertIsEnabled()
    }

    // ---- Add Bluesky account ----

    @Test
    fun theBlueskyFormChecksTheHandleMasksThePasswordAndWarnsWithoutBlocking() {
        var handle by mutableStateOf("")
        var password by mutableStateOf("")
        var signIns = 0
        compose.setContent {
            PostTheme {
                AddBlueskyContent(handle, { handle = it }, password, { password = it }, "", {}, BlueskyFormState.Idle, false, { signIns++ }, {})
            }
        }

        compose.onNodeWithTag(AddBlueskyTags.SIGN_IN).assertIsNotEnabled()
        compose.onNodeWithTag(AddBlueskyTags.HANDLE).performTextInput("not a handle")
        compose.onNodeWithText(string(R.string.problem_invalid_handle)).assertIsDisplayed()

        handle = "@alice.bsky.social"
        compose.onNodeWithText(string(R.string.problem_invalid_handle)).assertDoesNotExist()
        compose.onNodeWithTag(AddBlueskyTags.PASSWORD).performTextInput("my-real-password")
        // Masked, and announced as a password field.
        compose.onNodeWithTag(AddBlueskyTags.PASSWORD).assert(SemanticsMatcher.keyIsDefined(SemanticsProperties.Password))
        // A warning, not a block.
        compose.onNodeWithTag(AddBlueskyTags.PASSWORD_WARNING, useUnmergedTree = true).assertExists()
        compose.onNodeWithTag(AddBlueskyTags.SIGN_IN).assertIsEnabled().performClick()
        assertThat(signIns).isEqualTo(1)

        password = "abcd-efgh-ijkl-mnop"
        compose.onNodeWithTag(AddBlueskyTags.PASSWORD_WARNING, useUnmergedTree = true).assertDoesNotExist()
        // R33: the show/hide button is labelled.
        compose.onNodeWithContentDescription(string(R.string.show_password)).performClick()
        compose.onNodeWithContentDescription(string(R.string.hide_password)).assertExists()
        compose.onNodeWithText(string(R.string.add_bluesky_password_link)).assertExists()
    }

    @Test
    fun whenThePdsAsksForTheEmailCodeTheFormWaitsForIt() {
        var code by mutableStateOf("")
        compose.setContent {
            PostTheme {
                AddBlueskyContent("alice.bsky.social", {}, "abcd-efgh-ijkl-mnop", {}, code, { code = it }, BlueskyFormState.NeedsCode, false, {}, {})
            }
        }

        compose.onNodeWithText(string(R.string.add_bluesky_code_hint)).assertExists()
        compose.onNodeWithTag(AddBlueskyTags.SIGN_IN).performScrollTo().assertIsNotEnabled()
        compose.onNodeWithTag(AddBlueskyTags.CODE).performTextInput("ABCDE-12345")
        compose.onNodeWithTag(AddBlueskyTags.SIGN_IN).performScrollTo().assertIsEnabled()
    }

    @Test
    fun appPasswordShapeAndHandleChecks() {
        assertThat(looksLikeAppPassword("abcd-efgh-ijkl-mnop")).isTrue()
        assertThat(looksLikeAppPassword("hunter2")).isFalse()
        assertThat(isHandleOrDid("@Alice.bsky.social")).isTrue()
        assertThat(isHandleOrDid("did:plc:ewvi7nxzyoun6zhxrhs64oiz")).isTrue()
        assertThat(isHandleOrDid("alice")).isFalse()
    }

    // ---- Account details ----

    @Test
    fun mastodonDetailsEditTheDefaultsAndShowTheServerLimits() {
        val changes = mutableListOf<AccountOptions>()
        val labels = mutableListOf<String>()
        val caps = MastodonCapabilities(maxCharacters = 5000, maxMediaAttachments = 6, postFormats = setOf("text/plain", "text/markdown"))
        compose.setContent {
            PostTheme {
                AccountDetailsContent(
                    account(1, Network.MASTODON, "Me", "me@mastodon.example", limits = AccountLimits.Mastodon(caps)),
                    {}, { labels += it }, { changes += it }, {}, {},
                )
            }
        }

        compose.onNodeWithText("@me@mastodon.example").assertIsDisplayed()
        compose.onNodeWithTag(AccountDetailsTags.LABEL).performTextInput("work")
        assertThat(labels.last()).isEqualTo("work")
        compose.onNodeWithTag(AccountDetailsTags.visibility(Visibility.PUBLIC)).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(AccountDetailsTags.visibility(Visibility.PRIVATE)).performScrollTo().performClick()
        assertThat(changes.last().visibility).isEqualTo(Visibility.PRIVATE)
        compose.onNodeWithTag(AccountDetailsTags.SENSITIVE).performScrollTo().performClick()
        assertThat(changes.last().sensitive).isTrue()
        assertThat(changes.last().visibility).isEqualTo(Visibility.PRIVATE)
        compose.onNodeWithTag(AccountDetailsTags.SENSITIVE).assertIsOn()
        // N1, F2: the limits and the formats the server reported.
        compose.onNodeWithText(string(R.string.limits_characters, 5000)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(string(R.string.limits_media, 6)).assertExists()
        compose.onNodeWithText(string(R.string.limits_post_formats, "text/plain, text/markdown")).assertExists()
    }

    @Test
    fun blueskyDetailsEditWhoCanReplyAndQuotes() {
        val changes = mutableListOf<AccountOptions>()
        compose.setContent {
            PostTheme { AccountDetailsContent(account(3, Network.BLUESKY, "Alice", "alice.example.com"), {}, {}, { changes += it }, {}, {}) }
        }

        compose.onNodeWithTag(AccountDetailsTags.replyMode("everyone")).performScrollTo().assertIsSelected()
        compose.onNodeWithTag(AccountDetailsTags.replyMode("nobody")).performScrollTo().performClick()
        assertThat(changes.last().replyRules).isEmpty()
        compose.onNodeWithTag(AccountDetailsTags.replyMode("some")).performScrollTo().performClick()
        assertThat(changes.last().replyRules).containsExactly(ReplyRule.FOLLOWING)
        compose.onNodeWithTag(AccountDetailsTags.replyRule(ReplyRule.MENTIONED)).performScrollTo().performClick()
        assertThat(changes.last().replyRules).containsExactly(ReplyRule.FOLLOWING, ReplyRule.MENTIONED)
        compose.onNodeWithTag(AccountDetailsTags.ALLOW_QUOTES).performScrollTo().performClick()
        assertThat(changes.last().allowQuotes).isFalse()
        compose.onNodeWithText(string(R.string.limits_bluesky_fixed)).performScrollTo().assertIsDisplayed()
    }

    @Test
    fun removeAsksForConfirmationFirst() {
        var removed = 0
        var signInAgain = 0
        compose.setContent {
            PostTheme {
                AccountDetailsContent(account(1, Network.MASTODON, "Me", "me@mastodon.example", state = AccountState.NEEDS_SIGN_IN), {}, {}, {}, { signInAgain++ }, { removed++ })
            }
        }

        compose.onNodeWithText(string(R.string.accounts_needs_sign_in)).assertIsDisplayed()
        compose.onNodeWithTag(AccountDetailsTags.SIGN_IN_AGAIN).performScrollTo().performClick()
        assertThat(signInAgain).isEqualTo(1)
        compose.onNodeWithTag(AccountDetailsTags.REMOVE).performScrollTo().performClick()
        compose.onNodeWithText(string(R.string.remove_account_title)).assertIsDisplayed()
        assertThat(removed).isEqualTo(0)
        compose.onNodeWithTag(AccountDetailsTags.CONFIRM_REMOVE).performClick()
        assertThat(removed).isEqualTo(1)
    }

    private fun string(@StringRes id: Int, vararg args: Any) = compose.activity.getString(id, *args)
}
