package org.evsyukov.post.core.render

import org.evsyukov.post.core.bluesky.LinkSpan
import org.evsyukov.post.core.bluesky.MAX_TAG_BYTES
import org.evsyukov.post.core.bluesky.MAX_TAG_GRAPHEMES
import org.evsyukov.post.core.bluesky.TagSpan
import org.evsyukov.post.core.markdown.toBlueskyText
import org.evsyukov.post.core.markdown.toHtml
import org.evsyukov.post.core.markdown.toMarkdownDialect
import org.evsyukov.post.core.markdown.toPlainText
import org.evsyukov.post.core.text.TextEntities
import org.evsyukov.post.core.text.TextSegmenter
import org.evsyukov.post.core.text.graphemeCount
import org.evsyukov.post.core.text.utf8Length
import org.evsyukov.post.core.thread.PartFragment

/** A part converted for the account, numbered and counted exactly. */
internal class ConvertedPart(
    val text: String,
    val count: Int,
    val bytes: Int?,
    val links: List<LinkSpan>,
    val mentions: List<HandleSpan>,
    val tags: List<TagSpan>,
) {
    fun overshoot(profile: TextProfile): Int = count - profile.maxCharacters

    fun byteOvershoot(profile: TextProfile): Int = if (bytes == null || profile.maxBytes == null) 0 else bytes - profile.maxBytes

    fun fits(profile: TextProfile): Boolean = overshoot(profile) <= 0 && byteOvershoot(profile) <= 0
}

/**
 * Turns the Markdown of a part into what one account receives (formatting.md F8–F11), adds the part number
 * (threads.md, T11) and counts the result as the account's server does (N3, N16, F12).
 */
internal class PartConverter(
    private val profile: TextProfile,
    private val segmenter: TextSegmenter,
    private val spoilerText: String,
) {
    /** The whole Markdown [source] in the account's format, as typed at the ends; for estimates. */
    fun convert(source: String): String = when (profile.format) {
        TextFormat.PLAIN -> toPlainText(source)
        TextFormat.MARKDOWN -> toMarkdownDialect(source)
        TextFormat.HTML -> toHtml(source)
        TextFormat.BLUESKY -> toBlueskyText(source, profile.blueskyLinkMode).text
    }

    /** The length [text] adds as the server counts it, without the content warning. */
    fun units(text: String): Int = profile.count(text, "", segmenter)

    /**
     * The part [fragment] converted, without whitespace at its ends (T10), with [label] (`i/n`) appended
     * when it is numbered, and counted with the content warning.
     */
    fun part(fragment: PartFragment, label: String?): ConvertedPart {
        var links = emptyList<LinkSpan>()
        val body: String
        when (profile.format) {
            TextFormat.PLAIN -> body = toPlainText(fragment.markdown).trim()
            TextFormat.MARKDOWN -> body = toMarkdownDialect(fragment.markdown).trim()
            TextFormat.HTML -> body = toHtml(fragment.markdown).trim()
            TextFormat.BLUESKY -> {
                val bluesky = toBlueskyText(fragment.markdown, profile.blueskyLinkMode)
                val raw = bluesky.text
                var start = 0
                while (start < raw.length && raw[start].isWhitespace()) start++
                var end = raw.length
                while (end > start && raw[end - 1].isWhitespace()) end--
                body = raw.substring(start, end)
                // A link keeps its facet on its text without the whitespace at its ends, which the part may
                // have dropped (T10); a link of only whitespace became its URL, so it is never empty here.
                links = bluesky.links.mapNotNull { link ->
                    var linkStart = maxOf(link.start, start)
                    var linkEnd = minOf(link.end, end)
                    while (linkStart < linkEnd && raw[linkStart].isWhitespace()) linkStart++
                    while (linkEnd > linkStart && raw[linkEnd - 1].isWhitespace()) linkEnd--
                    if (linkStart < linkEnd) LinkSpan(linkStart - start, linkEnd - start, link.url) else null
                }
            }
        }
        val text = withLabel(body, label, fragment.endsWithCodeBlock)
        return counted(text, links)
    }

    /** A part without text of the draft: empty, or only its number. */
    fun bare(label: String?): ConvertedPart = counted(label.orEmpty(), emptyList())

    private fun withLabel(body: String, label: String?, endsWithCodeBlock: Boolean): String = when {
        label == null -> body
        body.isEmpty() -> label
        profile.format == TextFormat.MARKDOWN && endsWithCodeBlock -> "$body\n$label"
        // The server reads Markdown beyond the subset of F3, and the number would change what such a line is.
        profile.format == TextFormat.MARKDOWN && body.substringAfterLast('\n').let { TABLE_ROW.matches(it) } -> "$body\n\n$label"
        profile.format == TextFormat.MARKDOWN && body.substringAfterLast('\n').let { line -> LABEL_BREAKERS.any { it.containsMatchIn(line) } } ->
            "$body\n$label"
        profile.format == TextFormat.HTML ->
            if (body.endsWith("</p>")) body.dropLast(4) + " " + label + "</p>" else "$body<p>$label</p>"
        else -> "$body $label"
    }

    private fun counted(text: String, links: List<LinkSpan>): ConvertedPart {
        val count = profile.count(text, spoilerText, segmenter)
        val bytes = if (profile.maxBytes != null) utf8Length(text) else null
        if (profile.format != TextFormat.BLUESKY) return ConvertedPart(text, count, bytes, emptyList(), emptyList(), emptyList())
        fun overlapsLink(start: Int, end: Int) = links.any { it.start < end && start < it.end }
        val mentions = TextEntities.blueskyMentions(text)
            .filter { !overlapsLink(it.start, it.end) }
            .map { HandleSpan(it.start, it.end, it.value.lowercase()) }
        val tags = TextEntities.hashtags(text)
            .filter { tag ->
                !overlapsLink(tag.start, tag.end) && mentions.none { it.start < tag.end && tag.start < it.end } &&
                    segmenter.graphemeCount(tag.value) <= MAX_TAG_GRAPHEMES && utf8Length(tag.value) <= MAX_TAG_BYTES
            }
            .map { TagSpan(it.start, it.end, it.value) }
        return ConvertedPart(text, count, bytes, links, mentions, tags)
    }
}

/**
 * Last lines of a Markdown part that a number appended to them would turn into something else on a server
 * that reads full Markdown (outside the subset of F3, so they are sent as typed): a setext underline or a
 * thematic break (`---`, `===`, `* * *`), a link reference definition (`[ref]: url`), and an ATX heading
 * with a closing sequence (`# Title #`). The number goes on a line of its own after them.
 */
private val LABEL_BREAKERS = listOf(
    Regex("""^[ >]*(?:=+|-+|(?:[-*_][ \t]*){3,})[ \t]*$"""),
    Regex("""^[ >]*\[(?:[^\]\\]|\\.)+]:"""),
    Regex("""^[ >]*#{1,6}[ \t].*[ \t]#+[ \t]*$"""),
)

/** A line that could be a table row: on a line of its own after it, the number would be a row too. */
private val TABLE_ROW = Regex("""^[ >]*\|.*|.*\|[ \t]*$""")

/** The width a part number ` n/n` (or `<p>n/n</p>` in HTML) of [digits] digits takes; 0 without numbers. */
internal fun numberReserve(format: TextFormat, digits: Int): Int = when {
    digits == 0 -> 0
    format == TextFormat.HTML -> 2 * digits + 8
    else -> 2 * digits + 2
}

/** The widest number label of [digits] digits, such as `99/99`, for checks before the count is known. */
internal fun widestLabel(digits: Int): String? = if (digits == 0) null else "9".repeat(digits) + "/" + "9".repeat(digits)

/** The number of decimal digits of [value]. */
internal fun digitsOf(value: Int): Int = value.toString().length
