package org.evsyukov.post.data.posts

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.evsyukov.post.core.bluesky.ContentLabel
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.FixKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.accounts.AccountOptions
import org.evsyukov.post.data.accounts.QuotePolicy
import org.evsyukov.post.data.accounts.ReplyRule
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.db.DbJson
import org.junit.Test
import java.time.Instant

/** The post options model (R13, R17): the JSON the composer stores in `posts.optionsJson` and the sender reads. */
class PostOptionsTest {
    private val full = PostOptions(
        language = "ru",
        spoilerText = "spoilers",
        sensitive = true,
        accounts = mapOf(
            1L to AccountPostOptions(visibility = Visibility.UNLISTED, quotePolicy = QuotePolicy.NOBODY, sensitive = true),
            2L to AccountPostOptions(
                replyRules = setOf(ReplyRule.FOLLOWING, ReplyRule.MENTIONED),
                allowQuotes = false,
                contentLabels = setOf(ContentLabel.NUDITY),
                linkCard = false,
            ),
            3L to AccountPostOptions(replyRules = emptySet()),
        ),
        poll = PollDraft(listOf("Yes", "No", "Maybe"), expiresInSeconds = 3_600, multiple = true, hideTotals = true),
        chosenFixes = mapOf(Network.BLUESKY to mapOf(ConflictKind.TOO_MANY_FILES to FixKind.MOVE_TO_REPLY)),
    )

    private fun encode(options: PostOptions) = DbJson.encodeToString(PostOptions.serializer(), options)

    private fun decode(json: String) = DbJson.decodeFromString(PostOptions.serializer(), json)

    @Test
    fun everyFieldSurvivesTheRoundTrip() {
        assertThat(decode(encode(full))).isEqualTo(full)
    }

    @Test
    fun nobodyAndAnyoneMayReplyStayDifferent() {
        // An empty set (nobody) and null (anyone) are different threadgates; the JSON keeps them apart.
        val decoded = decode(encode(full))
        assertThat(decoded.accounts.getValue(3L).replyRules).isEmpty()
        assertThat(decoded.accounts.getValue(1L).replyRules).isNull()
    }

    @Test
    fun theStoredKeysAreTheDocumentedOnes() {
        val json = Json.parseToJsonElement(encode(full)).jsonObject
        assertThat(json.keys).containsExactly("language", "spoilerText", "sensitive", "accounts", "poll", "chosenFixes")
        val bluesky = (json.getValue("accounts") as JsonObject).getValue("2").jsonObject
        assertThat(bluesky.keys).containsAtLeast("replyRules", "allowQuotes", "contentLabels", "linkCard")
    }

    @Test
    fun anEmptyObjectAndUnknownKeysReadAsTheDefaults() {
        assertThat(decode("{}")).isEqualTo(PostOptions())
        assertThat(decode("""{"language":"de","later":{"x":1}}""")).isEqualTo(PostOptions(language = "de"))
        val defaults = PostOptions()
        assertThat(defaults.language).isNull()
        assertThat(defaults.spoilerText).isEmpty()
        assertThat(defaults.sensitive).isFalse()
        assertThat(defaults.poll).isNull()
    }

    @Test
    fun anAccountWithoutAnEntryGetsItsDefaultsAndTheLinkCard() {
        val defaults = AccountOptions(
            visibility = Visibility.PRIVATE,
            languages = listOf("en", "de"),
            sensitive = true,
            replyRules = setOf(ReplyRule.FOLLOWERS),
            quotePolicy = QuotePolicy.FOLLOWERS,
            allowQuotes = false,
            contentLabels = setOf(ContentLabel.PORN),
        )
        val account = account(9, defaults)

        val options = PostOptions().forAccount(account)

        assertThat(options).isEqualTo(
            AccountPostOptions(
                visibility = Visibility.PRIVATE,
                quotePolicy = QuotePolicy.FOLLOWERS,
                sensitive = true,
                replyRules = setOf(ReplyRule.FOLLOWERS),
                allowQuotes = false,
                contentLabels = setOf(ContentLabel.PORN),
                linkCard = true,
            ),
        )
        // The entry of the post wins over the defaults.
        val own = AccountPostOptions(visibility = Visibility.DIRECT)
        assertThat(PostOptions(accounts = mapOf(9L to own)).forAccount(account)).isEqualTo(own)
    }

    @Test
    fun theLanguageOfThePostGoesToEveryAccountElseItsDefaults() {
        val account = account(1, AccountOptions(languages = listOf("en", "de")))
        assertThat(PostOptions(language = "ru").languagesFor(account)).containsExactly("ru")
        assertThat(PostOptions().languagesFor(account)).containsExactly("en", "de").inOrder()
    }

    @Test
    fun mediaIsSensitiveWhenThePostOrTheAccountSaysSo() {
        val plain = account(1, AccountOptions())
        val marked = account(2, AccountOptions(sensitive = true))
        assertThat(PostOptions().sensitiveFor(plain)).isFalse()
        assertThat(PostOptions().sensitiveFor(marked)).isTrue()
        assertThat(PostOptions(sensitive = true).sensitiveFor(plain)).isTrue()
        assertThat(PostOptions(accounts = mapOf(1L to AccountPostOptions(sensitive = true))).sensitiveFor(plain)).isTrue()
    }

    private fun account(id: Long, defaults: AccountOptions) = Account(
        id = id, network = Network.MASTODON, server = "https://m.example", handle = "a", remoteId = "1", displayName = "A", avatarUrl = null,
        label = "", position = 0, state = AccountState.OK, limits = null, limitsFetchedAt = null, defaults = defaults, createdAt = Instant.EPOCH,
    )
}
