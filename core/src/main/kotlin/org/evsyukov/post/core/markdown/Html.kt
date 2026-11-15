package org.evsyukov.post.core.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Document
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.HtmlInline
import org.commonmark.node.Image
import org.commonmark.node.Link
import org.commonmark.node.LinkReferenceDefinition
import org.commonmark.node.ListBlock
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.evsyukov.post.core.text.TextEntities

/**
 * The Markdown draft [source] as HTML for servers that accept `text/html` but not `text/markdown`
 * (formatting.md, F9).
 *
 * Only these tags are written: `p`, `br`, `strong`, `em`, `del`, `code`, `pre`, `blockquote`, `ul`, `ol`,
 * `li` and `a` with `href` as its only attribute. Everything else is text, with `&`, `<`, `>` and `"`
 * escaped, so inline HTML in the draft reaches the reader as typed.
 *
 * - A paragraph is `<p>`; a line break inside it is `<br>`. Items of a tight list hold their text
 *   without `<p>`.
 * - An inline link or autolink to an `http`, `https` or `mailto` URL is `<a href>`; a link to anything
 *   else becomes the text `text (url)` of F10. Bare `http` and `https` URLs become `<a href>` as well.
 * - A fenced code block is `<pre><code>` without the info string; inline code is `<code>`.
 * - Images, reference links and their definitions, and Markdown outside the subset of F3 are text as typed;
 *   a spaced thematic break such as `* * *` is a paragraph of its own.
 * - A bare URL holding `_`, `*` or `~` is one link, as typed (see [parseMarkdown]).
 * - An ordered list is `<ol>` without `start`, because no attribute but `href` is allowed; its items are
 *   numbered from 1 by the reader.
 * - Manual break lines are removed (threads.md, T13); the pieces around them are joined by a blank line.
 */
fun toHtml(source: String): String {
    val text = withoutManualBreaks(source)
    val out = StringBuilder(text.length + text.length / 4)
    HtmlWriter(text, out).block(parseMarkdown(text))
    return out.toString()
}

private class HtmlWriter(private val source: String, private val out: StringBuilder) {

    fun block(node: Node) {
        when (node) {
            is Document -> children(node)
            is Paragraph -> {
                val tight = (node.parent as? ListItem)?.let { (it.parent as? ListBlock)?.isTight } == true
                if (tight) {
                    inlines(node)
                } else {
                    out.append("<p>")
                    inlines(node)
                    out.append("</p>")
                }
            }
            is BlockQuote -> wrap("blockquote", node)
            is BulletList -> wrap("ul", node)
            is OrderedList -> wrap("ol", node)
            is ListItem -> wrap("li", node)
            is FencedCodeBlock -> {
                out.append("<pre><code>")
                escape(node.literal.orEmpty())
                out.append("</code></pre>")
            }
            is LinkReferenceDefinition, is LiteralLine -> {
                out.append("<p>")
                escape(asTyped(node).trim())
                out.append("</p>")
            }
            else -> {
                // Other blocks outside the subset cannot appear with the parser's settings; their text is kept.
                val typed = asTyped(node)
                if (typed.isNotEmpty()) {
                    out.append("<p>")
                    escape(typed)
                    out.append("</p>")
                }
            }
        }
    }

    private fun children(node: Node) = node.forEachChild { block(it) }

    private fun wrap(tag: String, node: Node) {
        out.append('<').append(tag).append('>')
        children(node)
        out.append("</").append(tag).append('>')
    }

    private fun inlines(parent: Node) = parent.forEachChild { inline(it) }

    private fun inline(node: Node) {
        when (node) {
            is Text -> textWithLinks(node.literal.orEmpty())
            is Code -> {
                out.append("<code>")
                escape(node.literal.orEmpty())
                out.append("</code>")
            }
            is StrongEmphasis -> inlineWrap("strong", node)
            is Emphasis -> inlineWrap("em", node)
            is Strikethrough -> inlineWrap("del", node)
            is SoftLineBreak, is HardLineBreak -> out.append("<br>")
            is Link -> link(node)
            is Image -> escape(asTyped(node))
            is HtmlInline -> escape(node.literal.orEmpty())
            else -> {
                val typed = asTyped(node)
                if (typed.isNotEmpty()) escape(typed) else inlines(node)
            }
        }
    }

    private fun inlineWrap(tag: String, node: Node) {
        out.append('<').append(tag).append('>')
        inlines(node)
        out.append("</").append(tag).append('>')
    }

    private fun link(node: Link) {
        val range = node.range()
        if (range == null || node.form(source, range, 0) == LinkForm.REFERENCE) {
            escape(asTyped(node))
            return
        }
        val destination = node.destination.orEmpty()
        if (!isLinkable(destination, allowMailto = true)) {
            val text = inlinePlainText(node, source)
            escape(if (destination.isEmpty()) text else "$text ($destination)")
            return
        }
        out.append("<a href=\"")
        escape(destination)
        out.append("\">")
        insideLink = true
        if (node.firstChild == null) escape(destination) else inlines(node)
        insideLink = false
        out.append("</a>")
    }

    /** Links cannot nest, so a URL in the text of a link stays text. */
    private var insideLink = false

    private fun textWithLinks(text: String) {
        if (insideLink) {
            escape(text)
            return
        }
        var last = 0
        for (url in TextEntities.urls(text)) {
            escape(text.substring(last, url.start))
            out.append("<a href=\"")
            escape(url.value)
            out.append("\">")
            escape(url.value)
            out.append("</a>")
            last = url.end
        }
        escape(text.substring(last))
    }

    private fun asTyped(node: Node): String = node.range()?.let { source.substring(it.start, it.end) }.orEmpty()

    private fun escape(text: String) {
        for (c in text) {
            when (c) {
                '&' -> out.append("&amp;")
                '<' -> out.append("&lt;")
                '>' -> out.append("&gt;")
                '"' -> out.append("&quot;")
                else -> out.append(c)
            }
        }
    }
}
