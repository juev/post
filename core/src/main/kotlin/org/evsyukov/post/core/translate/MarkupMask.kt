package org.evsyukov.post.core.translate

import org.evsyukov.post.core.markdown.HighlightSpan
import org.evsyukov.post.core.markdown.highlight

/**
 * A piece of the draft a translation must carry through unchanged: a Markdown marker, a link address, a
 * code span, a mention, a hashtag, a URL or a manual thread break.
 *
 * [gluedBefore] and [gluedAfter] say whether the draft had text touching the piece on that side. A
 * translation engine may add whitespace around its placeholders; without the flags, `**bold**` could come
 * back as `** bold **`, which Markdown no longer reads as bold (formatting.md, F3).
 */
data class MaskToken(val text: String, val gluedBefore: Boolean, val gluedAfter: Boolean)

/** The draft as a translation engine gets it: [text] holds placeholder tokens where [tokens] stood. */
class MaskedText(
    val text: String,
    val tokens: List<MaskToken>,
    internal val open: String,
    internal val close: String,
)

/**
 * The masking that lets a translation engine see only the prose of the draft (screens.md, Composer):
 * Markdown markup, code, link addresses, mentions, hashtags, URLs and manual break markers are replaced by
 * placeholders, translated text is mapped back onto them, and the spacing around them is put right.
 *
 * A result whose placeholders do not come back exactly once each is refused ([unmask] gives null): a
 * translation that loses a marker would damage the draft, and the composer then leaves the text as it was.
 */
object MarkupMask {
    /** Delimiter pairs for placeholders, the first one the text does not hold itself. */
    private val DELIMITERS = listOf("{{" to "}}", "[[" to "]]", "<%" to "%>", "«%" to "%»")

    private val URL = Regex("""(?i)\bhttps?://[^\s<>]+""")
    private val MENTION = Regex("""@[A-Za-z0-9_]+(?:@[A-Za-z0-9.-]+)?""")
    private val HASHTAG = Regex("""#[\p{L}\p{N}_]+""")

    /** The kinds of [highlight] spans that a translation must not touch. */
    private val PROTECTED_KINDS = setOf(
        HighlightSpan.Kind.SYNTAX,
        HighlightSpan.Kind.CODE,
        HighlightSpan.Kind.CODE_BLOCK,
        HighlightSpan.Kind.LINK_URL,
        HighlightSpan.Kind.BREAK_MARKER,
    )

    fun mask(source: String): MaskedText {
        val (open, close) = DELIMITERS.firstOrNull { (start, _) -> start !in source } ?: DELIMITERS.first()
        val ranges = protectedRanges(source)
        val text = StringBuilder(source.length)
        val tokens = ArrayList<MaskToken>()
        var at = 0
        for (range in ranges) {
            text.append(source, at, range.start)
            val gluedBefore = range.start > 0 && !source[range.start - 1].isWhitespace()
            val gluedAfter = range.endInclusive + 1 < source.length && !source[range.endInclusive + 1].isWhitespace()
            tokens += MaskToken(source.substring(range.start, range.endInclusive + 1), gluedBefore, gluedAfter)
            text.append(open).append(tokens.size - 1).append(close)
            at = range.endInclusive + 1
        }
        text.append(source, at, source.length)
        return MaskedText(text.toString(), tokens, open, close)
    }

    /**
     * [translated] with the placeholders replaced by the pieces of [masked], or null when a placeholder is
     * missing or appears twice, which means the engine did not keep the draft intact.
     */
    fun unmask(masked: MaskedText, translated: String): String? {
        val matches = Regex(Regex.escape(masked.open) + "(\\d{1,6})" + Regex.escape(masked.close))
            .findAll(translated)
            .toList()
        val indices = matches.map { match -> match.groupValues[1].toIntOrNull() ?: return null }
        if (indices.sorted() != masked.tokens.indices.toList()) return null

        val out = StringBuilder(translated.length)
        var at = 0
        var previous: MaskToken? = null
        matches.forEachIndexed { position, match ->
            var segment = translated.substring(at, match.range.first)
            segment = after(previous, segment)
            val token = masked.tokens[indices[position]]
            if (token.gluedBefore) {
                segment = segment.trimEnd(' ', '\t')
            } else if (segment.isNotEmpty() && !segment.last().isWhitespace()) {
                segment += " "
            }
            out.append(segment).append(token.text)
            previous = token
            at = match.range.last + 1
        }
        out.append(after(previous, translated.substring(at)))
        return out.toString()
    }

    /** [segment] adjusted to follow [previous]: glued to it, or set apart by a space as in the draft. */
    private fun after(previous: MaskToken?, segment: String): String = when {
        previous == null -> segment
        previous.gluedAfter -> segment.trimStart(' ', '\t')
        segment.isNotEmpty() && !segment.first().isWhitespace() -> " $segment"
        else -> segment
    }

    /** The ranges of [source] a translation must not touch, sorted and merged where they overlap. */
    private fun protectedRanges(source: String): List<IntRange> {
        val masked = ArrayList<IntRange>()
        for (span in highlight(source)) {
            if (span.kind in PROTECTED_KINDS) masked += span.start until span.end
        }
        val found = ArrayList<IntRange>(masked)
        var gap = 0
        for (range in masked.sortedBy { it.start } + listOf(source.length until source.length)) {
            if (gap < range.start) {
                val text = source.substring(gap, range.start)
                for (pattern in listOf(URL, MENTION, HASHTAG)) {
                    for (match in pattern.findAll(text)) {
                        val start = gap + match.range.first
                        val end = gap + trimmedEnd(match.value, text, match.range.last + 1)
                        if (start < end) found += start until end
                    }
                }
            }
            gap = maxOf(gap, range.endInclusive + 1)
        }
        return merged(found)
    }

    /**
     * Where the URL that ends at [end] really ends in [gapText]: a sentence's punctuation and an unbalanced
     * closing bracket are not part of it.
     */
    private fun trimmedEnd(value: String, gapText: String, end: Int): Int {
        var stop = end
        while (stop > 0 && gapText[stop - 1] in ".,;:!?…") stop--
        val url = gapText.substring(end - value.length, stop)
        for ((opening, closing) in listOf('(' to ')', '[' to ']', '{' to '}')) {
            if (url.lastOrNull() == closing && url.count { it == closing } > url.count { it == opening }) stop--
        }
        return stop
    }

    private fun merged(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.sortedWith(compareBy({ it.start }, { it.endInclusive }))
        val result = ArrayList<IntRange>(sorted.size)
        for (range in sorted) {
            val last = result.lastOrNull()
            // Touching ranges stay apart: `**` ends where the bold text begins, and that text is translated.
            if (last != null && range.start <= last.endInclusive) {
                result[result.size - 1] = last.start..maxOf(last.endInclusive, range.endInclusive)
            } else {
                result += range
            }
        }
        return result
    }
}
