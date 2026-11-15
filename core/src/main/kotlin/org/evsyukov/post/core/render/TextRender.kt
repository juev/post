package org.evsyukov.post.core.render

import org.evsyukov.post.core.bluesky.LinkSpan
import org.evsyukov.post.core.bluesky.TagSpan
import java.util.Locale

/**
 * The draft as the composer holds it: the Markdown [source] with manual break lines (threads.md, T13), the
 * content warning [spoilerText] (Mastodon only) and the post's language [locale], which decides sentence
 * and word boundaries (T4).
 */
data class DraftText(val source: String, val spoilerText: String = "", val locale: Locale)

/**
 * A Bluesky mention `@handle` over `text[start, end)` of a part, in UTF-16 char offsets. [handle] is
 * lowercased, as atproto handles compare; the sender resolves it to a DID (networks.md, N17).
 */
data class HandleSpan(val start: Int, val end: Int, val handle: String)

/**
 * One part of an account's thread, as the text renderer makes it.
 *
 * @property piece the piece of the draft the part belongs to (T13).
 * @property sourceStart where the part's text starts in [DraftText.source]; with [sourceEnd] it tells the
 *   editor which text went into this part. The text between two parts of a piece is only whitespace (T10).
 * @property text exactly what is sent: converted for the account (formatting.md F8–F11), closed and reopened
 *   markup (T5), and the part number when there is one (T11).
 * @property count the length as the account's server counts it, with the content warning (N3).
 * @property bytes the UTF-8 length for a network with a byte limit (Bluesky), else null.
 * @property links Bluesky link facets: Markdown links and bare URLs (F11). Empty for Mastodon.
 * @property mentions Bluesky mentions by handle, never overlapping a link. Empty for Mastodon.
 * @property tags Bluesky hashtags of at most 64 graphemes and 640 bytes, never overlapping a link. Empty for
 *   Mastodon.
 * @property mediaReply the part is a media-only reply the media plan adds after the first part of its
 *   piece (media.md, A11): it has no text of the draft, only the part number when parts are numbered.
 */
data class TextPart(
    val piece: Int,
    val sourceStart: Int,
    val sourceEnd: Int,
    val text: String,
    val count: Int,
    val bytes: Int?,
    val links: List<LinkSpan>,
    val mentions: List<HandleSpan>,
    val tags: List<TagSpan>,
    val mediaReply: Boolean = false,
)

/** Why an account cannot get its text: the composer shows a conflict for the network (T9, T16, T17). */
sealed interface TextIssue {
    /**
     * A word, URL or other unbreakable run between two candidate breaks does not fit one part even alone
     * (T9). `[sourceStart, sourceEnd)` of the draft source is that run.
     */
    data class TooLongToSplit(val piece: Int, val sourceStart: Int, val sourceEnd: Int) : TextIssue

    /** The account's thread would need [count] parts, more than [TextRenderer.MAX_PARTS] (T16). */
    data class TooManyParts(val count: Int) : TextIssue

    /**
     * The content warning, which goes with every Mastodon part and counts in it (N3), leaves no room: it
     * counts [count] against the limit, and with it the limit is reached before the text, a run of the text
     * that would fit without the warning, or the number of a media-only part (A11).
     */
    data class SpoilerTooLong(val count: Int) : TextIssue
}

/**
 * The text of one account's thread.
 *
 * @property parts the parts in thread order, media-only replies included (A11); empty when [issue] is set.
 * @property manyPartsWarning the thread has [TextRenderer.WARN_PARTS] parts or more (T16).
 * @property limit the account's character limit.
 * @property mediaOnlyParts and [spoilerText] are what the parts were rendered with; [PartAssembler] checks
 *   that the media plan and content warning it gets match them.
 */
data class TextRender(
    val profile: TextProfile,
    val parts: List<TextPart>,
    val issue: TextIssue?,
    val manyPartsWarning: Boolean,
    val limit: Int,
    val mediaOnlyParts: Map<Int, Int> = emptyMap(),
    val spoilerText: String = "",
)
