package org.evsyukov.post.ui.composer

import org.evsyukov.post.accounts.mastodonCapabilities
import org.evsyukov.post.accounts.textProfile
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.Conflict
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.render.AccountRender
import org.evsyukov.post.core.render.PartAssembler
import org.evsyukov.post.core.render.DraftText
import org.evsyukov.post.core.render.TextIssue
import org.evsyukov.post.core.render.TextRender
import org.evsyukov.post.core.render.TextRenderer
import org.evsyukov.post.core.text.TextSegmenter
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.posts.Attachment
import org.evsyukov.post.data.posts.PollDraft
import org.evsyukov.post.data.settings.Settings
import java.util.Locale

/**
 * The parts of one text profile and the selected accounts that get them (threads.md T3). [assembled] puts the
 * media of the network's plan and the poll on the parts (T14, A11); it is null while the text has an issue
 * that leaves no parts to assemble.
 */
data class ProfileRender(val accounts: List<Account>, val render: TextRender, val assembled: AccountRender? = null) {
    val network: Network get() = render.profile.network

    /** No media conflict of the network waits for a fix. */
    val mediaReady: Boolean get() = assembled?.mediaReady ?: true
}

/**
 * What the counters were computed for, so a render of an older text is recognized: the text, the targets,
 * and what the media plan depends on (attachments, chosen fixes, the poll; media.md A3).
 */
data class RenderInput(
    val text: String,
    val spoilerText: String,
    val language: String,
    val targets: List<Long>,
    val attachments: List<Attachment> = emptyList(),
    val chosenFixes: ChosenFixes = emptyMap(),
    val hasPoll: Boolean = false,
)

/**
 * The renders of [input] for the selected accounts: one per distinct text profile (T3), Mastodon first, then
 * by limit. Each is exactly what [TextRenderer] gives the sender, with the media-only parts of the network's
 * plan in [media] (A11) and the media assembled on the parts by [PartAssembler], so counters, part counts and
 * the preview match what is published (R8, T12, F7, F12).
 */
fun renderTargets(
    input: RenderInput,
    accounts: List<Account>,
    settings: Settings,
    segmenter: TextSegmenter,
    media: List<NetworkMedia> = emptyList(),
): List<ProfileRender> {
    val byId = accounts.associateBy { it.id }
    val selected = input.targets.mapNotNull { byId[it] }
    val draft = DraftText(input.text, input.spoilerText, Locale.forLanguageTag(input.language))
    val plans = media.associate { it.network to it.plan }
    return selected
        .groupBy { it.textProfile(settings).key }
        .map { (_, group) ->
            val profile = group.first().textProfile(settings)
            val plan = plans[profile.network]
            val text = TextRenderer.render(draft, profile, segmenter, PartAssembler.mediaOnlyParts(plan))
            val assembled = if (text.issue == null) PartAssembler.assemble(text, plan, input.hasPoll, input.spoilerText) else null
            ProfileRender(group, text, assembled)
        }
        .sortedWith(compareBy({ it.network.ordinal }, { it.render.limit }))
}

/** The limits a poll must keep for every selected Mastodon account (networks.md N1): the strictest of them. */
data class PollLimits(val maxOptions: Int, val maxCharacters: Int, val minSeconds: Long, val maxSeconds: Long) {
    companion object {
        fun of(accounts: List<Account>): PollLimits {
            val caps = accounts.filter { it.network == Network.MASTODON }.map { it.limits.mastodonCapabilities() }
                .ifEmpty { listOf(MastodonCapabilities()) }
            return PollLimits(
                maxOptions = caps.minOf { it.pollMaxOptions }.coerceAtLeast(MIN_OPTIONS),
                maxCharacters = caps.minOf { it.pollMaxCharactersPerOption }.coerceAtLeast(1),
                minSeconds = caps.maxOf { it.pollMinExpirationSeconds },
                maxSeconds = caps.minOf { it.pollMaxExpirationSeconds },
            )
        }

        const val MIN_OPTIONS = 2
    }
}

/** Why a poll cannot be sent. */
enum class PollProblem { TOO_FEW_OPTIONS, TOO_MANY_OPTIONS, EMPTY_OPTION, OPTION_TOO_LONG, DURATION }

/** The first problem of [poll] under [limits], or null when it can be sent. */
fun pollProblem(poll: PollDraft, limits: PollLimits): PollProblem? = when {
    poll.options.size < PollLimits.MIN_OPTIONS -> PollProblem.TOO_FEW_OPTIONS
    poll.options.size > limits.maxOptions -> PollProblem.TOO_MANY_OPTIONS
    poll.options.any { it.isBlank() } -> PollProblem.EMPTY_OPTION
    poll.options.any { it.length > limits.maxCharacters } -> PollProblem.OPTION_TOO_LONG
    poll.expiresInSeconds !in limits.minSeconds..limits.maxSeconds -> PollProblem.DURATION
    else -> null
}

/** The poll durations the composer offers, in seconds; only those within the server limits are shown. */
val POLL_DURATIONS = listOf(5 * 60L, 30 * 60L, 3600L, 6 * 3600L, 86_400L, 3 * 86_400L, 7 * 86_400L)

/** What keeps Send disabled (R7, R8, screens.md Composer); a tap on Send then opens the details sheet. */
sealed interface Blocker {
    /** No account is selected (R7). */
    data object NoTarget : Blocker

    /** Neither text nor anything else to publish. */
    data object EmptyPost : Blocker

    /** The text cannot be made into parts for these accounts (T9, T16, T17). */
    data class Text(val render: ProfileRender, val issue: TextIssue) : Blocker

    /** The poll breaks a limit of a selected server. */
    data class Poll(val problem: PollProblem) : Blocker

    /** A media conflict of [network] waits for a fix (R11, media.md). */
    data class Media(val network: Network, val conflict: Conflict) : Blocker

    /** "Require alt text" is on and [ids] have none (R10). */
    data class AltMissing(val ids: List<Long>) : Blocker

    /** The alt text of [ids] is over the limit of a selected Mastodon server, which rejects the upload (A9). */
    data class AltTooLong(val ids: List<Long>, val limit: Int) : Blocker

    /** Picked files are still being copied (R9); sending now would leave them out of the post. */
    data object Importing : Blocker

    /**
     * The media plan converts [ids] and this device cannot decode them (media.md A4). The probe runs when
     * Send is tapped, so the post cannot be queued until the files are removed or replaced.
     */
    data class MediaUnreadable(val ids: List<Long>) : Blocker
}

/**
 * Everything that blocks sending [text] with [poll] and [attachments] to [targets], given the current
 * [renders] and media plans ([media]). Alt text over Bluesky's limit is only marked (A9): the limit is the
 * official app's, not the server's. While [importing], the post is not complete yet, so it waits too.
 */
fun blockers(
    targets: List<Long>,
    text: String,
    renders: List<ProfileRender>,
    poll: PollDraft?,
    pollLimits: PollLimits,
    attachments: List<Attachment> = emptyList(),
    media: List<NetworkMedia> = emptyList(),
    requireAltText: Boolean = false,
    importing: Boolean = false,
): List<Blocker> = buildList {
    if (targets.isEmpty()) add(Blocker.NoTarget)
    if (importing) add(Blocker.Importing)
    // A network that gets no file (all left out by a fix) and no poll (Bluesky has none) would get an empty post.
    val emptyForANetwork = media.any { network ->
        val pollGoes = poll != null && network.network == Network.MASTODON
        !pollGoes && attachments.none { it.id !in network.plan.excluded }
    }
    if (text.isBlank() && !importing && ((poll == null && attachments.isEmpty()) || emptyForANetwork)) add(Blocker.EmptyPost)
    for (render in renders) render.render.issue?.let { add(Blocker.Text(render, it)) }
    if (poll != null) pollProblem(poll, pollLimits)?.let { add(Blocker.Poll(it)) }
    for (network in media) network.blocking.forEach { add(Blocker.Media(network.network, it)) }
    if (requireAltText) attachments.filter { it.alt.isBlank() }.map { it.id }.takeIf { it.isNotEmpty() }?.let { add(Blocker.AltMissing(it)) }
    media.firstOrNull { it.network == Network.MASTODON }?.let { mastodon ->
        val limit = mastodon.rules.maxAltLength
        val sent = mastodon.plan.excluded.keys
        attachments.filter { it.id !in sent && altLength(it.alt) > limit }.map { it.id }
            .takeIf { it.isNotEmpty() }?.let { add(Blocker.AltTooLong(it, limit)) }
    }
}

/**
 * The blocker for the files the last probe found unreadable (media.md A4), while [attachments] still hold
 * them: a file removed or replaced stops blocking the send.
 */
fun unreadableBlocker(unreadable: List<Long>, attachments: List<Attachment>): List<Blocker> =
    attachments.map { it.id }.filter { it in unreadable }.takeIf { it.isNotEmpty() }
        ?.let { listOf(Blocker.MediaUnreadable(it)) }
        .orEmpty()

/** The fragment of [source] a [TextIssue.TooLongToSplit] names, shortened for the details sheet. */
fun tooLongFragment(source: String, issue: TextIssue.TooLongToSplit, max: Int = 80): String {
    val start = issue.sourceStart.coerceIn(0, source.length)
    val end = issue.sourceEnd.coerceIn(start, source.length)
    val fragment = source.substring(start, end)
    return if (fragment.length <= max) fragment else fragment.take(max - 1) + "…"
}
