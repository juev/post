package org.evsyukov.post.core.markdown

import org.evsyukov.post.core.text.TextEntities

/**
 * The plain text of the Markdown draft [source] for servers that accept only plain text (formatting.md, F10):
 * - bold, italic, strikethrough and code markers go, their text stays; backslash escapes and entities
 *   become the characters they stand for;
 * - a block quote keeps its `> ` prefix;
 * - list items become `• ` lines, or `1. `, `2. `… lines numbered from the list's first number;
 * - a link `[text](url)` becomes `text (url)`, or only the URL when the text is the URL, also without
 *   its scheme (`[example.com](https://example.com)`); an autolink `<url>` becomes the URL; an e-mail
 *   autolink becomes the address;
 * - the fence lines of a code block go, the code stays as typed;
 * - images, inline HTML, reference links and Markdown outside the subset of F3 stay as typed, and so do
 *   line breaks, blank lines and indentation;
 * - manual break lines are removed (threads.md, T13); the pieces around them are joined by a blank line.
 */
fun toPlainText(source: String): String = rewritePlain(withoutManualBreaks(source)) { range, text, destination ->
    Edit(range.start, range.end, plainLink(text, destination))
}.text

/** How a Markdown link reaches Bluesky, by the setting "Bluesky: Markdown links" (formatting.md, F11). */
enum class BlueskyLinkMode {
    /** The default: the link text with a link facet on it; the URL is not in the text. */
    FACET_ON_TEXT,

    /** The link text, then the shortened address in parentheses with a link facet on it. */
    TEXT_AND_SHORT_URL,
}

/** A link facet: `[start, end)` of [BlueskyText.text], in UTF-16 char offsets, links to [url]. */
data class LinkSpan(val start: Int, val end: Int, val url: String)

/** The text of a Bluesky post and its link facets, sorted by start, in UTF-16 char offsets. */
data class BlueskyText(val text: String, val links: List<LinkSpan>)

/**
 * The Bluesky text of the Markdown draft [source] (formatting.md, F11): the plain text of [toPlainText],
 * with these differences:
 * - an inline link to an `http` or `https` URL becomes, in [BlueskyLinkMode.FACET_ON_TEXT], its text with a
 *   [LinkSpan] over it; in [BlueskyLinkMode.TEXT_AND_SHORT_URL], `text (short)` with the [LinkSpan] over
 *   `short`, the address shortened as social-app shortens it ([toShortUrl]);
 * - a link whose text is the URL, also without the scheme, or is empty, keeps the visible text (the URL
 *   when the text is empty) with a [LinkSpan] over it, in both modes; an autolink `<url>` becomes the URL
 *   with a [LinkSpan];
 * - a link to any other destination (relative, `mailto:`, other schemes) becomes plain text as in F10,
 *   without a facet;
 * - bare `http` and `https` URLs stay as typed and get a [LinkSpan] each ([TextEntities.urls]), unless
 *   they overlap a link span already made.
 *
 * Mentions and hashtags are left to the code that builds facets, since a mention needs its DID.
 * Offsets are char offsets into [BlueskyText.text]; facets need them as UTF-8 byte offsets later.
 */
fun toBlueskyText(source: String, mode: BlueskyLinkMode): BlueskyText {
    val rewritten = rewritePlain(withoutManualBreaks(source)) { range, text, destination ->
        blueskyLink(range, text, destination, mode)
    }
    val links = rewritten.links.toMutableList()
    for (url in TextEntities.urls(rewritten.text)) {
        if (links.none { it.start < url.end && url.start < it.end }) links += LinkSpan(url.start, url.end, url.value)
    }
    links.sortBy { it.start }
    return BlueskyText(rewritten.text, links)
}

private fun rewritePlain(
    source: String,
    linkEdit: (range: SourceRange, text: String, destination: String) -> Edit,
): Rewritten {
    val rewriter = PlainRewriter(source, linkEdit)
    rewriter.visit(markdownParser.parse(source))
    return applyEdits(source, rewriter.edits)
}

private fun plainLink(text: String, destination: String): String = when {
    destination.isEmpty() -> text
    text.isBlank() -> destination
    textShowsUrl(text, destination) -> if (destination.startsWith("mailto:", ignoreCase = true)) text else destination
    else -> "$text ($destination)"
}

private fun blueskyLink(range: SourceRange, text: String, destination: String, mode: BlueskyLinkMode): Edit {
    if (!isLinkable(destination, allowMailto = false)) return Edit(range.start, range.end, plainLink(text, destination))
    if (text.isBlank()) return Edit(range.start, range.end, destination, destination, 0, destination.length)
    if (textShowsUrl(text, destination) || mode == BlueskyLinkMode.FACET_ON_TEXT) {
        return Edit(range.start, range.end, text, destination, 0, text.length)
    }
    val short = toShortUrl(destination)
    val replacement = "$text ($short)"
    return Edit(range.start, range.end, replacement, destination, text.length + 2, text.length + 2 + short.length)
}
