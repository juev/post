package org.evsyukov.post.send

import org.evsyukov.post.accounts.mastodonCapabilities
import org.evsyukov.post.accounts.mediaRules
import org.evsyukov.post.accounts.textProfile
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.Conversion
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.core.media.MediaPlan
import org.evsyukov.post.core.media.MediaPlanner
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.media.NetworkMediaRules
import org.evsyukov.post.core.media.VideoTargets
import org.evsyukov.post.core.render.DraftText
import org.evsyukov.post.core.render.PartAssembler
import org.evsyukov.post.core.render.TextIssue
import org.evsyukov.post.core.render.TextRenderer
import org.evsyukov.post.core.text.TextSegmenter
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.posts.DeliveryPlan
import org.evsyukov.post.data.posts.MastodonStatusOptions
import org.evsyukov.post.data.posts.PlannedPart
import org.evsyukov.post.data.posts.Post
import org.evsyukov.post.data.settings.Settings
import org.evsyukov.post.media.convert.MastodonVideoCheck
import java.util.Locale
import java.util.UUID

/** What freezing a delivery's plan gave: the plan, or why the post cannot go to the account. */
sealed interface FreezeResult {
    data class Ready(val plan: DeliveryPlan) : FreezeResult

    /** A blocking conflict or a text that does not fit: the delivery fails with [message] (R29). */
    data class Blocked(val message: String) : FreezeResult
}

/**
 * Freezes the plan of a delivery at its first attempt (R30): the account's parts as the composer showed them
 * (the account's text profile, threads.md T3), the network's media plan with the post's chosen fixes over the
 * default fixes (media.md A10, A12), a stable `Idempotency-Key` per part (N11) and the status options.
 */
class PlanFreezer(
    private val segmenter: TextSegmenter,
    private val newKey: () -> String = { UUID.randomUUID().toString() },
) {
    /**
     * @param networkAccounts the post's target accounts of [account]'s network: the media plan follows the
     *   strictest of their rules (A10).
     */
    fun freeze(post: Post, account: Account, networkAccounts: List<Account>, settings: Settings): FreezeResult {
        val network = account.network
        val options = post.options
        val media = mediaPlan(post, network, networkAccounts.ifEmpty { listOf(account) }, settings)
        if (media != null) {
            media.conflicts.firstOrNull { it.blocking }?.let { return FreezeResult.Blocked(conflictMessage(it.kind)) }
            if (media.accountRemoved) return FreezeResult.Blocked("the account was taken out of the post by a media fix")
        }
        val profile = account.textProfile(settings)
        val locale = Locale.forLanguageTag(options.language ?: account.defaults.languages.firstOrNull() ?: Locale.getDefault().toLanguageTag())
        val draft = DraftText(post.text, options.spoilerText, locale)
        val text = TextRenderer.render(draft, profile, segmenter, PartAssembler.mediaOnlyParts(media))
        val render = PartAssembler.assemble(text, media, hasPoll = options.poll != null, spoilerText = options.spoilerText)
        render.textIssue?.let { return FreezeResult.Blocked(issueMessage(it)) }
        val conversions = HashMap<Long, Conversion>()
        val byId = post.attachments.associateBy { it.id }
        val rules = media?.let { rulesOf(network, networkAccounts.ifEmpty { listOf(account) }) }
        for (id in render.parts.flatMap { it.mediaItemIds }) {
            var conversion = media?.conversions?.get(id) ?: Conversion.AsIs
            val attachment = byId[id]
            // A7: Mastodon cuts a video it transcodes itself after 36 000 frames; transcode it here instead.
            if (network == Network.MASTODON && conversion == Conversion.AsIs && attachment != null &&
                attachment.kind == MediaKind.VIDEO && rules != null &&
                MastodonVideoCheck.mustTranscode(attachment.videoCodec, attachment.durationMs, attachment.frameRate)
            ) {
                conversion = VideoTargets.target(
                    durationMs = attachment.durationMs ?: 0,
                    maxBytes = rules.maxVideoBytes,
                    width = attachment.width ?: 0,
                    height = attachment.height ?: 0,
                    maxPixels = rules.maxVideoPixels,
                    sourceBytes = attachment.sizeBytes,
                )?.copy(maxFrameRate = rules.maxVideoFrameRate)
                    ?: return FreezeResult.Blocked(conflictMessage(ConflictKind.CANNOT_FIT))
            }
            conversions[id] = conversion
        }
        val mastodon = if (network == Network.MASTODON) {
            val caps = account.limits.mastodonCapabilities()
            val accountOptions = options.forAccount(account)
            MastodonStatusOptions(
                visibility = accountOptions.visibility,
                sensitive = options.sensitiveFor(account),
                language = options.languagesFor(account).firstOrNull(),
                quoteApprovalPolicy = accountOptions.quotePolicy.name.lowercase().takeIf { caps.supportsQuotePolicy },
                poll = options.poll.takeIf { render.parts.any { it.carriesPoll } },
            )
        } else {
            null
        }
        val parts = render.parts.map { part ->
            PlannedPart(
                text = part.text,
                attachmentIds = part.mediaItemIds,
                key = newKey(),
                contentType = part.contentType,
                spoilerText = part.spoilerText,
                carriesPoll = part.carriesPoll,
            )
        }
        return FreezeResult.Ready(DeliveryPlan(parts, conversions, mastodon))
    }

    private fun mediaPlan(post: Post, network: Network, accounts: List<Account>, settings: Settings): MediaPlan? {
        if (post.attachments.isEmpty()) return null
        return MediaPlanner.plan(
            items = post.attachments.map { it.toMediaItem() },
            rules = rulesOf(network, accounts),
            fixes = settings.defaultFixes() + post.options.chosenFixes[network].orEmpty(),
            hasPoll = post.options.poll != null,
            stripMetadata = !settings.keepImageMetadata,
        )
    }

    private fun rulesOf(network: Network, accounts: List<Account>): NetworkMediaRules =
        NetworkMediaRules.strictest(accounts.filter { it.network == network }.map { it.mediaRules() })

    companion object {
        /** The reason a delivery fails with when a media conflict has no fix (shown in the failure notification). */
        fun conflictMessage(kind: ConflictKind): String = when (kind) {
            ConflictKind.TOO_MANY_FILES -> "too many files for this server"
            ConflictKind.UNSUPPORTED_TYPE -> "a file type this server does not accept"
            ConflictKind.MIXED_TYPES -> "a video or audio file together with images"
            ConflictKind.VIDEO_TOO_LONG -> "a video longer than this server allows"
            ConflictKind.VIDEO_QUOTA -> "the daily video quota is used up"
            ConflictKind.POLL_WITH_MEDIA -> "a poll together with media"
            ConflictKind.CANNOT_FIT -> "a file cannot be made to fit this server's limits"
            ConflictKind.ALT_TOO_LONG -> "an alt text is longer than this server allows"
        }

        fun issueMessage(issue: TextIssue): String = when (issue) {
            is TextIssue.TooLongToSplit -> "a word or link is too long for one part"
            is TextIssue.TooManyParts -> "the thread would need ${issue.count} parts"
            is TextIssue.SpoilerTooLong -> "the content warning leaves no room for the text"
        }
    }
}
