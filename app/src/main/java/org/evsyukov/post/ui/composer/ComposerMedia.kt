package org.evsyukov.post.ui.composer

import org.evsyukov.post.accounts.mediaRules
import org.evsyukov.post.core.markdown.isManualBreakLine
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.FixKind
import org.evsyukov.post.core.media.MediaPlan
import org.evsyukov.post.core.media.MediaPlanner
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.media.NetworkMediaRules
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.posts.Attachment
import org.evsyukov.post.data.settings.Settings

/** The fixes chosen for media conflicts, per network and conflict kind (`PostOptions.chosenFixes`). */
typealias ChosenFixes = Map<Network, Map<ConflictKind, FixKind>>

/**
 * The media plan of one selected network (media.md A2, A10): the strictest rules of its selected accounts
 * and the plan built with them.
 */
data class NetworkMedia(val network: Network, val accounts: List<Account>, val rules: NetworkMediaRules, val plan: MediaPlan) {
    /** Conflicts that hold the post until a fix is chosen. */
    val blocking get() = plan.conflicts.filter { it.blocking }
}

/** The number of pieces of [text]: one more than its manual break lines (threads.md T13). */
fun pieceCount(text: String): Int = 1 + text.lineSequence().count { isManualBreakLine(it) }

/** The piece of [text] that [offset] is in: the number of manual break lines that start before it (T14). */
fun pieceAt(text: String, offset: Int): Int {
    val end = offset.coerceIn(0, text.length)
    var piece = 0
    var start = 0
    while (start < end) {
        val newline = text.indexOf('\n', start).let { if (it < 0) text.length else it }
        if (isManualBreakLine(text.substring(start, newline))) piece++
        start = newline + 1
    }
    return piece
}

/**
 * The piece [attachment] goes with: its own, or the last piece when manual breaks were removed since it
 * was added, so no file is left without a part.
 */
fun effectivePiece(attachment: Attachment, pieces: Int): Int = attachment.pieceIndex.coerceIn(0, maxOf(pieces, 1) - 1)

/**
 * The fixes the planner uses for [network]: the defaults of the settings (A12) with the post's chosen fixes
 * over them.
 */
fun fixesFor(network: Network, settings: Settings, chosen: ChosenFixes): Map<ConflictKind, FixKind> =
    settings.defaultFixes() + chosen[network].orEmpty()

/**
 * The media plan of every selected network (media.md A2–A12), Mastodon first. Each network gets the
 * strictest rules of its selected accounts (A10), the post's chosen fixes over the default fixes of the
 * settings (A12), and strips image metadata unless the settings keep it (A6). Attachments are planned with
 * their keys as ids and with the piece they go with ([effectivePiece]).
 */
fun planMedia(
    attachments: List<Attachment>,
    pieces: Int,
    selected: List<Account>,
    settings: Settings,
    chosen: ChosenFixes,
    hasPoll: Boolean,
): List<NetworkMedia> {
    val items = attachments.map { it.toMediaItem().copy(pieceIndex = effectivePiece(it, pieces)) }
    return selected.groupBy { it.network }.toSortedMap().map { (network, accounts) ->
        val rules = NetworkMediaRules.strictest(accounts.map { it.mediaRules() })
        val plan = MediaPlanner.plan(
            items = items,
            rules = rules,
            fixes = fixesFor(network, settings, chosen),
            hasPoll = hasPoll,
            stripMetadata = !settings.keepImageMetadata,
        )
        NetworkMedia(network, accounts, rules, plan)
    }
}

/** The ids of attachments in a conflict of any network that still waits for the user (the strip's warning badge). */
fun conflictedIds(media: List<NetworkMedia>): Set<Long> =
    media.flatMap { network -> network.plan.conflicts.flatMap { it.itemIds } }.toSet()

/** Alt text length as the networks count it (Unicode code points, A9). */
fun altLength(alt: String): Int = alt.codePointCount(0, alt.length)
