package org.evsyukov.post.ui.composer

import org.evsyukov.post.accounts.mastodonCapabilities
import org.evsyukov.post.accounts.textProfile
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.render.DraftText
import org.evsyukov.post.core.render.TextIssue
import org.evsyukov.post.core.render.TextRender
import org.evsyukov.post.core.render.TextRenderer
import org.evsyukov.post.core.text.TextSegmenter
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.posts.PollDraft
import org.evsyukov.post.data.settings.Settings
import java.util.Locale

/** The parts of one text profile and the selected accounts that get them (threads.md T3). */
data class ProfileRender(val accounts: List<Account>, val render: TextRender) {
    val network: Network get() = render.profile.network
}

/** What the counters were computed for, so a render of an older text is recognized. */
data class RenderInput(val text: String, val spoilerText: String, val language: String, val targets: List<Long>)

/**
 * The renders of [input] for the selected accounts: one per distinct text profile (T3), Mastodon first, then
 * by limit. Each is exactly what [TextRenderer] gives the sender, so counters, part counts and the preview
 * match what is published (R8, T12, F7, F12).
 */
fun renderTargets(input: RenderInput, accounts: List<Account>, settings: Settings, segmenter: TextSegmenter): List<ProfileRender> {
    val byId = accounts.associateBy { it.id }
    val selected = input.targets.mapNotNull { byId[it] }
    val draft = DraftText(input.text, input.spoilerText, Locale.forLanguageTag(input.language))
    return selected
        .groupBy { it.textProfile(settings).key }
        .map { (_, group) -> ProfileRender(group, TextRenderer.render(draft, group.first().textProfile(settings), segmenter)) }
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
}

/** Everything that blocks sending [text] with [poll] to [targets], given the current [renders]. */
fun blockers(targets: List<Long>, text: String, renders: List<ProfileRender>, poll: PollDraft?, pollLimits: PollLimits): List<Blocker> = buildList {
    if (targets.isEmpty()) add(Blocker.NoTarget)
    if (text.isBlank() && poll == null) add(Blocker.EmptyPost)
    for (render in renders) render.render.issue?.let { add(Blocker.Text(render, it)) }
    if (poll != null) pollProblem(poll, pollLimits)?.let { add(Blocker.Poll(it)) }
}

/** The fragment of [source] a [TextIssue.TooLongToSplit] names, shortened for the details sheet. */
fun tooLongFragment(source: String, issue: TextIssue.TooLongToSplit, max: Int = 80): String {
    val start = issue.sourceStart.coerceIn(0, source.length)
    val end = issue.sourceEnd.coerceIn(start, source.length)
    val fragment = source.substring(start, end)
    return if (fragment.length <= max) fragment else fragment.take(max - 1) + "…"
}
