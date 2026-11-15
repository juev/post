package org.evsyukov.post.core.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.Block
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlInline
import org.commonmark.node.Link
import org.commonmark.node.Node
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text

/**
 * The Markdown draft [source] rewritten for servers that accept `text/markdown` (formatting.md, F8):
 * - italic is always `*x*`: `_x_` becomes `*x*`, because glitch-soc renders `_x_` as underline;
 * - bold is always `**x**`: `__x__` becomes `**x**`;
 * - strikethrough is `~~x~~`, the only form the editor parses;
 * - italic directly inside italic (`*_x_*`, `_*x*_`) loses the inner markers, which would otherwise turn
 *   into `**x**`, that is bold;
 * - manual break lines are removed (threads.md, T13); the pieces around them are joined by a blank line;
 * - everything else stays exactly as typed, byte for byte.
 *
 * A replacement must not change what the reader sees. Next to a literal `*` a new `*` could join it into
 * another delimiter run (`*_a_` is not `**a*`), so the result is parsed again and compared with the
 * source; when they differ, the replacements are taken one by one, each only if it keeps the text and
 * its styles, and the rest keep their underscores.
 */
fun toMarkdownDialect(source: String): String {
    val text = withoutManualBreaks(source)
    val replacements = ArrayList<List<Edit>>()
    collectDialectEdits(markdownParser.parse(text), replacements)
    if (replacements.isEmpty()) return text
    val expected = readerView(text)
    val all = applyEdits(text, replacements.flatten()).text
    if (readerView(all) == expected) return all
    var accepted = emptyList<Edit>()
    for (replacement in replacements) {
        val trial = accepted + replacement
        if (readerView(applyEdits(text, trial).text) == expected) accepted = trial
    }
    return applyEdits(text, accepted).text
}

/** The replacements, one list of edits per emphasis node, so they can be taken or left together. */
private fun collectDialectEdits(node: Node, replacements: MutableList<List<Edit>>) {
    when (node) {
        is Emphasis -> {
            val range = node.range()
            val parentRange = (node.parent as? Emphasis)?.range()
            if (range != null && range.length >= 2) {
                if (parentRange != null && (range.start == parentRange.start + 1 || range.end == parentRange.end - 1)) {
                    replacements += listOf(Edit(range.start, range.start + 1, ""), Edit(range.end - 1, range.end, ""))
                } else if (node.openingDelimiter == "_") {
                    replacements += listOf(Edit(range.start, range.start + 1, "*"), Edit(range.end - 1, range.end, "*"))
                }
            }
        }
        is StrongEmphasis -> {
            val range = node.range()
            if (range != null && range.length >= 4 && node.openingDelimiter == "__") {
                replacements += listOf(Edit(range.start, range.start + 2, "**"), Edit(range.end - 2, range.end, "**"))
            }
        }
    }
    node.forEachChild { collectDialectEdits(it, replacements) }
}

/**
 * What a reader sees in [markdown]: the blocks, the text with its styles and the links. Italic inside
 * italic is just italic, so `*_x_*` and `*x*` look the same.
 */
private fun readerView(markdown: String): String {
    val out = StringBuilder()
    // Adjacent text with the same styles reads the same however the parser cut it into nodes.
    var lastStyle: String? = null
    fun walk(node: Node, italic: Boolean, bold: Boolean, strike: Boolean) {
        fun children(i: Boolean = italic, b: Boolean = bold, s: Boolean = strike) =
            node.forEachChild { walk(it, i, b, s) }
        if (node !is Text && node !is Emphasis && node !is StrongEmphasis && node !is Strikethrough) lastStyle = null
        when (node) {
            is Text -> {
                val style = "${if (italic) 'i' else '-'}${if (bold) 'b' else '-'}${if (strike) 's' else '-'}"
                if (style != lastStyle) out.append('\u0000').append(style)
                out.append(node.literal)
                lastStyle = style
            }
            is Code -> out.append("code:").append(node.literal).append('\u0000')
            is Emphasis -> children(i = true)
            is StrongEmphasis -> children(b = true)
            is Strikethrough -> children(s = true)
            is SoftLineBreak, is HardLineBreak -> out.append('\n')
            is HtmlInline -> out.append("html:").append(node.literal).append('\u0000')
            is FencedCodeBlock -> out.append("fence:").append(node.literal).append('\u0000')
            is Link -> {
                out.append("link(").append(node.destination).append(")[")
                children()
                out.append(']')
            }
            is Block -> {
                out.append('<').append(node.javaClass.simpleName).append('>')
                children()
                out.append("</>")
            }
            else -> {
                out.append(node.javaClass.simpleName).append('(')
                children()
                out.append(')')
            }
        }
    }
    walk(markdownParser.parse(markdown), italic = false, bold = false, strike = false)
    return out.toString()
}
