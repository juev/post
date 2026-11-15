package org.evsyukov.post.core.markdown

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.node.Block
import org.commonmark.node.BlockQuote
import org.commonmark.node.CustomBlock
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListBlock
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Scanner
import org.commonmark.parser.block.AbstractBlockParser
import org.commonmark.parser.block.AbstractBlockParserFactory
import org.commonmark.parser.block.BlockContinue
import org.commonmark.parser.block.BlockStart
import org.commonmark.parser.block.MatchedBlockParser
import org.commonmark.parser.block.ParserState
import org.evsyukov.post.core.text.TextEntities

/** A range `[start, end)` of char offsets into a source text. */
data class SourceRange(val start: Int, val end: Int) {
    init {
        require(start in 0..end) { "Invalid range [$start, $end)" }
    }

    val length: Int get() = end - start
}

/**
 * [text] parsed as the Markdown subset of formatting.md, F3: block quotes, fenced code blocks and lists are
 * the only blocks; headings, thematic breaks, HTML blocks and indented code stay paragraph text.
 * Strikethrough needs two tildes. Inline HTML and images are parsed, and the converters write them out
 * as typed. Source spans give every node its offsets in [text].
 *
 * Two additions keep text that is not Markdown as typed:
 * - A bare URL, mention or hashtag ([TextEntities]) is one [Text] node: the `_`, `*`, `~`, backticks and
 *   brackets inside it are not markup, so `https://example.com/files/__init__.py` and `@_jane_` stay
 *   whole. Like GFM's extended autolinks, the entity gives up its trailing `*`, `_` and `~`, so markup
 *   around it, as in `**https://example.com**`, still closes. Constructs that start before it, such as a
 *   code span or a link destination, take precedence.
 * - A thematic break that CommonMark would otherwise read as nested list items (`* * *`, `- - -`) is a
 *   [LiteralLine].
 *
 * The nesting limits keep the tree shallow enough for recursive walks on any input; deeper markup stays
 * text. The parser is built per call, because the inline parser reads [text] itself.
 */
internal fun parseMarkdown(text: String): Node = Parser.builder()
    .enabledBlockTypes(setOf(BlockQuote::class.java, FencedCodeBlock::class.java, ListBlock::class.java))
    .extensions(listOf(STRIKETHROUGH))
    .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
    .maxOpenBlockParsers(MAX_BLOCK_NESTING)
    .maxInlineNesting(MAX_INLINE_NESTING)
    .customBlockParserFactory(LiteralLineParser.Factory)
    .customInlineContentParserFactory(EntityParserFactory(text))
    .build()
    .parse(text)

private val STRIKETHROUGH = StrikethroughExtension.builder().requireTwoTildes(true).build()
private const val MAX_BLOCK_NESTING = 32
private const val MAX_INLINE_NESTING = 32

/**
 * The end of the bare URL, mention or hashtag that starts at [start] of [text] and that Markdown must keep
 * as typed, without its trailing `*`, `_` and `~`; -1 when none starts there.
 */
internal fun protectedEntityEnd(text: String, start: Int): Int {
    var end = entityEndAt(text, start)
    while (end > start + 1) {
        var trimmed = end
        while (trimmed > start + 1 && text[trimmed - 1].let { it == '*' || it == '_' || it == '~' }) trimmed--
        if (trimmed == end) break
        // Without the markers it gave up, the entity may end earlier still, as it would before a space:
        // in `*https://example.com/a*.**` the URL is `https://example.com/a`, and the `*` closes the italic.
        val shorter = entityEndAt(text.substring(0, trimmed), start)
        end = if (shorter in start + 2..trimmed) shorter else trimmed
        if (end == trimmed) break
    }
    return if (end > start + 1) end else -1
}

private fun entityEndAt(text: String, start: Int): Int = when (text[start]) {
    'h', 'H' -> TextEntities.urlEndAt(text, start)
    '@' -> maxOf(TextEntities.mastodonMentionEndAt(text, start), TextEntities.blueskyMentionEndAt(text, start))
    '#', '＃' -> TextEntities.hashtagEndAt(text, start)
    else -> -1
}

/** Starts an entity of [protectedEntityEnd] at its first character and returns it as one [Text] node. */
private class EntityParserFactory(private val text: String) : InlineContentParserFactory {
    override fun getTriggerCharacters(): Set<Char> = TRIGGERS

    override fun create(): InlineContentParser = InlineContentParser { state -> parse(state.scanner()) }

    /** The entity at the scanner as a [Text] node; `ParsedInline.none()`, which is null, when none starts there. */
    private fun parse(scanner: Scanner): ParsedInline? {
        val start = scanner.position()
        val first = scanner.peek()
        scanner.next()
        val span = scanner.getSource(start, scanner.position()).sourceSpans.singleOrNull() ?: return ParsedInline.none()
        val offset = span.inputIndex
        if (offset >= text.length || text[offset] != first) return ParsedInline.none()
        val end = protectedEntityEnd(text, offset)
        if (end < 0) return ParsedInline.none()
        // An entity never crosses a line, and within a line the parser's content is the source as typed.
        repeat(end - offset - 1) { scanner.next() }
        val literal = text.substring(offset, end)
        if (scanner.getSource(start, scanner.position()).content != literal) return ParsedInline.none()
        return ParsedInline.of(Text(literal), scanner.position())
    }

    private companion object {
        val TRIGGERS = setOf('h', 'H', '@', '#', '＃')
    }
}

/**
 * A line that CommonMark reads as a thematic break and that would otherwise become nested list items
 * (`* * *`, `- - -`). Thematic breaks are outside the subset of F3, so the converters keep the line as
 * typed and the editor does not highlight it.
 */
internal class LiteralLine : CustomBlock()

private class LiteralLineParser : AbstractBlockParser() {
    private val block = LiteralLine()

    override fun getBlock(): Block = block

    override fun tryContinue(state: ParserState): BlockContinue? = BlockContinue.none()

    object Factory : AbstractBlockParserFactory() {
        override fun tryStart(state: ParserState, matchedBlockParser: MatchedBlockParser): BlockStart? {
            if (state.indent >= 4) return BlockStart.none()
            val line = state.line.content
            if (!isSpacedThematicBreak(line, state.nextNonSpaceIndex)) return BlockStart.none()
            return BlockStart.of(LiteralLineParser()).atIndex(line.length)
        }
    }
}

/** `-` or `*` followed by a space or tab (a list marker), and at least three of it with only spaces and tabs between. */
private fun isSpacedThematicBreak(line: CharSequence, start: Int): Boolean {
    if (start + 1 >= line.length) return false
    val marker = line[start]
    if ((marker != '-' && marker != '*') || (line[start + 1] != ' ' && line[start + 1] != '\t')) return false
    var count = 0
    for (i in start until line.length) {
        when (line[i]) {
            marker -> count++
            ' ', '\t' -> Unit
            else -> return false
        }
    }
    return count >= 3
}

/** The range a node covers in the parsed text, from its first span to the end of its last; null without spans. */
internal fun Node.range(offset: Int = 0): SourceRange? {
    val spans = sourceSpans
    if (spans.isEmpty()) return null
    var start = Int.MAX_VALUE
    var end = Int.MIN_VALUE
    for (span in spans) {
        start = minOf(start, span.inputIndex)
        end = maxOf(end, span.inputIndex + span.length)
    }
    return SourceRange(start + offset, end + offset)
}

/** The range of a block with all its descendants, which reaches lazy continuation lines. */
internal fun Node.deepRange(offset: Int = 0): SourceRange? {
    var result = range(offset)
    var child = firstChild
    while (child != null) {
        val childRange = child.deepRange(offset)
        if (childRange != null) {
            result = if (result == null) {
                childRange
            } else {
                SourceRange(minOf(result.start, childRange.start), maxOf(result.end, childRange.end))
            }
        }
        child = child.next
    }
    return result
}

internal inline fun Node.forEachChild(action: (Node) -> Unit) {
    var child = firstChild
    while (child != null) {
        val next = child.next
        action(child)
        child = next
    }
}

/**
 * How a [Link] node was written. Only inline links `[text](url)` and autolinks `<url>` belong to the
 * subset of F3; a reference link `[text][label]` stays text as typed.
 */
internal enum class LinkForm { INLINE, AUTOLINK, REFERENCE }

internal fun Link.form(source: String, range: SourceRange, offset: Int): LinkForm = when {
    source[range.start] == '<' -> LinkForm.AUTOLINK
    source[range.end - 1] == ')' && inlineLinkTextEnd(source, range, offset) >= 0 -> LinkForm.INLINE
    else -> LinkForm.REFERENCE
}

/**
 * The offset of the `]` that closes the text of the inline link at [range], or -1. The text ends where
 * its last child ends; that is where `](` must follow. [offset] maps the node's spans into [source].
 */
internal fun Link.inlineLinkTextEnd(source: String, range: SourceRange, offset: Int): Int {
    val textEnd = lastChild?.range(offset)?.end ?: (range.start + 1)
    if (textEnd + 1 < range.end && source[textEnd] == ']' && source[textEnd + 1] == '(') return textEnd
    // Fallback for a last child without spans: the first "](" after the opening bracket.
    val found = source.indexOf("](", range.start + 1)
    return if (found in range.start + 1 until range.end - 1) found else -1
}

/** Whether a link destination may become a link in HTML or a Bluesky facet: `http`, `https`, or `mailto` when allowed. */
internal fun isLinkable(destination: String, allowMailto: Boolean): Boolean {
    val lower = destination.lowercase()
    return (lower.startsWith("http://") && lower.length > 7) ||
        (lower.startsWith("https://") && lower.length > 8) ||
        (allowMailto && lower.startsWith("mailto:") && lower.length > 7)
}
