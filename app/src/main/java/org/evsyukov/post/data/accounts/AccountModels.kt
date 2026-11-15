package org.evsyukov.post.data.accounts

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import org.evsyukov.post.core.bluesky.ContentLabel
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.mastodon.Visibility
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.db.AccountState
import java.time.Instant

/** Who may reply to a Bluesky post: one allow rule of the threadgate (networks.md, Reply control). */
@Serializable
enum class ReplyRule { MENTIONED, FOLLOWERS, FOLLOWING }

/** Mastodon `quote_approval_policy` (4.5+, product.md R13). */
@Serializable
enum class QuotePolicy { PUBLIC, FOLLOWERS, NOBODY }

/**
 * The options of a post for one account (R13), also stored as the account's defaults (R17).
 * Each network reads its own fields and ignores the rest.
 *
 * @property languages BCP 47 codes; Mastodon takes the first, Bluesky up to three (N18).
 * @property contentWarning Mastodon `spoiler_text`; Bluesky has none.
 * @property sensitive Mastodon `sensitive`.
 * @property contentLabels Bluesky self-labels for media (N22).
 * @property replyRules the Bluesky threadgate: null lets anyone reply, an empty set nobody, otherwise
 *   the people of these rules.
 * @property quotePolicy who may quote the post on Mastodon 4.5+.
 * @property allowQuotes false adds a Bluesky postgate that disables quoting (N24).
 */
@Serializable
data class AccountOptions(
    val visibility: Visibility = Visibility.PUBLIC,
    val languages: List<String> = emptyList(),
    val contentWarning: String = "",
    val sensitive: Boolean = false,
    val contentLabels: Set<ContentLabel> = emptySet(),
    val replyRules: Set<ReplyRule>? = null,
    val quotePolicy: QuotePolicy = QuotePolicy.PUBLIC,
    val allowQuotes: Boolean = true,
)

/** What the account's server accepts, read on sign-in and once a day (networks.md N1). */
@Serializable
sealed interface AccountLimits {
    /** Limits, formats and API version of a Mastodon-API server. */
    @Serializable
    @SerialName("mastodon")
    data class Mastodon(val capabilities: MastodonCapabilities) : AccountLimits
}

/**
 * A signed-in account without its secret, which only [AccountsRepository.readSecret] opens.
 *
 * @property server the origin of the Mastodon instance or the Bluesky PDS.
 * @property remoteId the Mastodon account id or the DID.
 */
data class Account(
    val id: Long,
    val network: Network,
    val server: String,
    val handle: String,
    val remoteId: String,
    val displayName: String,
    val avatarUrl: String?,
    val label: String,
    val position: Int,
    val state: AccountState,
    val limits: AccountLimits?,
    val limitsFetchedAt: Instant?,
    val defaults: AccountOptions,
    val createdAt: Instant,
)

/** The profile of an account that has just signed in. */
data class NewAccount(
    val network: Network,
    val server: String,
    val handle: String,
    val remoteId: String,
    val displayName: String,
    val avatarUrl: String?,
    val label: String = "",
)

/** No account has this id; it may have been removed. */
class AccountNotFound(val id: Long) : Exception("no account $id")
