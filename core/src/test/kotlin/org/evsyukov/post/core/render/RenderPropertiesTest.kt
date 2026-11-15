package org.evsyukov.post.core.render

import com.google.common.truth.Truth.assertWithMessage
import org.evsyukov.post.core.markdown.BlueskyLinkMode
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.markdown.analyze
import org.evsyukov.post.core.markdown.toHtml
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.core.render.RenderFixtures.segmenter
import org.evsyukov.post.core.thread.SplitPlan
import org.evsyukov.post.core.thread.ThreadSplitter
import org.evsyukov.post.core.thread.pieces
import org.junit.Test
import java.util.Locale
import kotlin.random.Random

/**
 * Properties of every render over random Markdown-like drafts and several profiles: no part over its limits,
 * media-only parts included; no break inside an atomic range, a grapheme cluster or a code line; no
 * whitespace at the ends of a part; parts that cover each piece with only whitespace between them and that
 * read, put together, as the piece reads (T17); numbers that match the parts; and the number of parts of
 * T6 and T8.
 */
class RenderPropertiesTest {
    private val profiles: List<TextProfile> = listOf(
        RenderFixtures.custom(TextFormat.PLAIN, 80, Counting.MASTODON),
        RenderFixtures.custom(TextFormat.PLAIN, 140, Counting.FULL, numberParts = true),
        RenderFixtures.custom(TextFormat.MARKDOWN, 120, Counting.GOTOSOCIAL, contentType = "text/markdown"),
        RenderFixtures.custom(TextFormat.MARKDOWN, 90, Counting.MASTODON, numberParts = true),
        RenderFixtures.custom(TextFormat.HTML, 160, Counting.PLEROMA, numberParts = true, contentType = "text/html"),
        RenderFixtures.custom(TextFormat.HTML, 200, Counting.MASTODON, contentType = "text/html"),
        TextProfiles.bluesky(BlueskyLinkMode.FACET_ON_TEXT, numberParts = false),
        TextProfiles.bluesky(BlueskyLinkMode.TEXT_AND_SHORT_URL, numberParts = true),
    )

    private val words = listOf(
        "word", "another", "Привет", "мир", "ёлка", "naïve", "éclair", "👍🏽", "👨‍👩‍👧", "🇺🇦", "漢字", "かな",
        "x", "longerwordhere", "end.", "stop!", "why?", "comma,", "semi;", "colon:", "—", "-", "(paren)", "«цитата»",
        "a b", "1984.", "e.g.", "т. е.",
        // Text that reads as the start of a block when a part starts with it (T17).
        "+", "*", ">", "5)", "1.", "```js", "~~~", "[ref]:", "\\",
    )
    private val atoms = listOf(
        "https://example.com", "https://example.com/path/__init__.py?q=1&r=2", "https://example.org/" + "long".repeat(12),
        "@alice", "@alice@example.social", "@bob.bsky.social", "#tag", "#тег", "`code span`", "`a*b*c`",
        "[link text](https://example.com/x)", "[https://a.example](https://a.example)", "<https://auto.example/link>",
        "![alt text](https://img.example/i.png)", "\\*not bold\\*", "&amp;", "<span>", "[ spaced ](https://example.com/s)",
    )

    /** A sentence; with [wrap], sometimes all in one emphasis. */
    private fun sentence(random: Random, wrap: Boolean = true): String {
        val parts = ArrayList<String>()
        repeat(random.nextInt(1, 14)) {
            parts += if (random.nextInt(5) == 0) atoms.random(random) else words.random(random)
        }
        var text = parts.joinToString(if (random.nextInt(8) == 0) "  " else " ")
        // A backslash right before the closing markers would escape them: emphasis that does not close.
        if (text.endsWith("\\")) text += "x"
        when (if (wrap) random.nextInt(7) else 6) {
            0 -> text = "**$text**"
            1 -> text = "*$text*"
            2 -> text = "~~$text~~"
            3 -> text = "_${text}_"
        }
        return text + listOf(".", "!", "?", "", ",").random(random)
    }

    /** A sentence without emphasis around it, ending in a letter so closing markers right after it close. */
    private fun bare(random: Random): String = sentence(random, wrap = false).trimEnd('*', '_', '~', '\\', '.', '!', '?', ',') + "x"

    /**
     * A list with items nested up to three levels, ordered or bulleted at each level with any delimiter or
     * bullet, sometimes with a quote or a code block in an item: a part that starts inside a nested item has
     * no markers for the items around it (T5, T17). Code blocks go only into items of the top level: deeper
     * ones are 4 columns in, where no part may start inside their outermost item (`findBreaks`), which a
     * dedicated test pins; here they would only turn most nested lists into conflicts.
     */
    private fun nestedList(random: Random, depth: Int = 0, indent: String = ""): String {
        val ordered = random.nextBoolean()
        val delimiter = if (random.nextInt(4) == 0) ")" else "."
        val bullet = listOf("-", "*", "+").random(random)
        val first = if (random.nextInt(3) == 0) random.nextInt(2, 9) else 1
        return List(random.nextInt(1, 5)) { k ->
            val marker = if (ordered) "${if (random.nextInt(5) == 0) first else first + k}$delimiter " else "$bullet "
            val inner = indent + " ".repeat(marker.length)
            val item = StringBuilder(indent).append(marker).append(sentence(random))
            when (random.nextInt(8)) {
                0, 1, 2 -> if (depth < 2) item.append('\n').append(nestedList(random, depth + 1, inner))
                3 -> item.append('\n').append(inner).append("> ").append(sentence(random))
                4 -> if (depth == 0) item.append("\n").append(inner).append("```\n").append(inner).append("code ").append(words.random(random))
                    .append('\n').append(inner).append("```")
                5 -> item.append("\n\n").append(inner).append(sentence(random))
            }
            item.toString()
        }.joinToString("\n")
    }

    private fun block(random: Random): String = when (random.nextInt(17)) {
        13, 14, 15 -> nestedList(random)
        0 -> "> " + List(random.nextInt(1, 4)) { sentence(random) }.joinToString("\n> ")
        1 -> List(random.nextInt(1, 5)) { "- " + sentence(random) }.joinToString("\n")
        2 -> List(random.nextInt(1, 5)) { "${it + 3}. " + sentence(random) }.joinToString("\n")
        3 -> "```kotlin\n" + List(random.nextInt(1, 8)) { "    line(${it}) // " + words.random(random) }.joinToString("\n") +
            if (random.nextBoolean()) "\n```" else ""
        4 -> "> ```\n" + List(random.nextInt(1, 5)) { "> code $it" }.joinToString("\n") + "\n> ```"
        5 -> MANUAL_BREAK
        // Bold over several sentences. Bold directly inside bold is left out: Markdown pairs a run of four
        // markers by the text around it, so a part can pair them otherwise than the whole text does.
        6 -> "**" + List(random.nextInt(2, 6)) { sentence(random, wrap = false) }.joinToString(" ") + "**"
        // Lazy numbering: every item typed as 1.
        7 -> List(random.nextInt(2, 9)) { "1. " + sentence(random) }.joinToString("\n")
        8 -> "1. " + sentence(random) + "\n   - " + sentence(random) + "\n   - " + sentence(random) + "\n2. " + sentence(random)
        9 -> "> 1. " + sentence(random) + "\n> 1. " + sentence(random) + "\n" + sentence(random)
        // A hard line break after a word: after `*` or `**` the backslash would make the run pair otherwise.
        10 -> sentence(random) + "\n" + sentence(random, wrap = false).trimEnd('*', '_', '~', '\\') + "x\\\n" + sentence(random)
        // Strikethrough inside strikethrough, the inner span sometimes opening a line (T5, T17).
        11 -> "~~" + bare(random) + (if (random.nextBoolean()) "\n~~" else " ~~") + bare(random) + "~~ " + bare(random) + "~~."
        // Bold inside bold, apart from the outer markers.
        12 -> "**" + bare(random) + " **" + bare(random) + "** " + bare(random) + "**."
        else -> List(random.nextInt(1, 6)) { sentence(random) }.joinToString(" ")
    }

    private fun draft(random: Random): String =
        List(random.nextInt(1, 9)) { block(random) }.joinToString(if (random.nextInt(4) == 0) "\n" else "\n\n")

    @Test
    fun `renders of random drafts keep every invariant`() {
        val random = Random(20261115)
        var split = 0
        var issues = 0
        var media = 0
        repeat(3000) { iteration ->
            val source = draft(random)
            val profile = profiles[iteration % profiles.size]
            val spoiler = when (random.nextInt(8)) {
                0 -> "CW " + words.random(random)
                1 -> "w".repeat(random.nextInt(profile.maxCharacters - 12, profile.maxCharacters))
                else -> ""
            }
            val mediaOnly = if (random.nextInt(4) == 0) {
                val count = pieces(source).size
                mapOf(random.nextInt(count + 1) to random.nextInt(0, 3))
            } else {
                emptyMap()
            }
            val locale = if (random.nextBoolean()) Locale.ENGLISH else Locale.forLanguageTag("ru")
            val result = TextRenderer.render(DraftText(source, spoiler, locale), profile, segmenter, mediaOnly)
            check(source, spoiler, mediaOnly, profile, result)
            if (result.parts.size > 1) split++
            if (result.issue != null) issues++
            if (result.parts.any { it.mediaReply }) media++
        }
        println("RenderPropertiesTest: split=$split issues=$issues media=$media of 3000")
        // The inputs are long enough to exercise the splitter, not only single parts.
        assertWithMessage("renders with more than one part").that(split).isGreaterThan(1500)
        assertWithMessage("renders with an issue").that(issues).isLessThan(900)
        assertWithMessage("renders with media-only parts").that(media).isGreaterThan(200)
    }

    @Test
    fun `T6 T8 the number of parts is the smallest that fits unless its split leaves a short tail`() {
        // Against the greedy split by exact counts (T6): more parts only when that split breaks the tail rule (T8).
        val random = Random(6)
        var renders = 0
        var tails = 0
        repeat(2400) { iteration ->
            val source = draft(random).replace(MANUAL_BREAK, "")
            val profile = profiles[iteration % profiles.size]
            val piece = pieces(source).singleOrNull()?.takeIf { !it.isEmpty } ?: return@repeat
            val result = TextRenderer.render(DraftText(source, "", Locale.ENGLISH), profile, segmenter)
            if (result.issue != null) return@repeat
            val count = result.parts.size
            val label = if (profile.numberParts && count >= 2) widestLabel(digitsOf(count)) else null
            val model = PieceModel(piece, source, profile, segmenter, PartConverter(profile, segmenter, ""), Locale.ENGLISH)
            val greedy = model.exactGreedy(label) as SplitPlan.Parts
            renders++
            val message = "profile ${profile.key}, render ${result.parts.map { it.count }}, greedy ${greedy.ends}, draft:\n$source"
            // Fewer parts than the greedy split is fine: closing markers can make a longer part fit where a
            // shorter one does not, which the greedy bisection does not see. Every part fits all the same.
            if (count <= greedy.count) return@repeat
            assertWithMessage(message).that(count - greedy.count).isAtMost(ThreadSplitter.MAX_EXTRA_PARTS)
            var from = 0
            val lengths = greedy.ends.map { to -> model.exact(from, to, null).count.also { from = to } }
            assertWithMessage("$message\ngreedy lengths $lengths").that(lengths.last() * 3.0).isLessThan(lengths.average())
            tails++
        }
        println("RenderPropertiesTest T6: renders=$renders more parts by the tail rule=$tails")
        assertWithMessage("renders compared").that(renders).isGreaterThan(1600)
    }

    private fun check(source: String, spoiler: String, mediaOnly: Map<Int, Int>, profile: TextProfile, result: TextRender) {
        val message = "profile ${profile.key}, spoiler ${spoiler.length}, media $mediaOnly, draft:\n$source"
        val issue = result.issue
        if (issue != null) {
            assertWithMessage(message).that(result.parts).isEmpty()
            when (issue) {
                is TextIssue.TooLongToSplit -> {
                    assertWithMessage(message).that(issue.sourceStart).isLessThan(issue.sourceEnd)
                    assertWithMessage(message).that(source.substring(issue.sourceStart, issue.sourceEnd).isNotBlank()).isTrue()
                }
                is TextIssue.SpoilerTooLong -> assertWithMessage(message).that(spoiler).isNotEmpty()
                is TextIssue.TooManyParts -> assertWithMessage(message).that(issue.count).isGreaterThan(TextRenderer.MAX_PARTS)
            }
            return
        }
        val n = result.parts.size
        val sentSpoiler = if (profile.network == Network.BLUESKY) "" else spoiler
        result.parts.forEachIndexed { index, part ->
            val partMessage = "$message\npart $index <${part.text}>"
            assertWithMessage(partMessage).that(part.count).isAtMost(profile.maxCharacters)
            assertWithMessage(partMessage).that(part.count).isEqualTo(profile.count(part.text, sentSpoiler, segmenter))
            profile.maxBytes?.let { assertWithMessage(partMessage).that(part.bytes!!).isAtMost(it) }
            if (part.text.isNotEmpty()) {
                assertWithMessage(partMessage).that(part.text.first().isWhitespace()).isFalse()
                assertWithMessage(partMessage).that(part.text.last().isWhitespace()).isFalse()
            }
            val label = "${index + 1}/$n"
            if (profile.numberParts && n >= 2) {
                assertWithMessage(partMessage).that(part.text.removeSuffix("</p>")).endsWith(label)
            } else {
                assertWithMessage(partMessage).that(part.text).doesNotContainMatch("^\\d+/\\d+$")
            }
            if (part.mediaReply || part.sourceStart == part.sourceEnd) {
                // A part without text of the draft holds its number at most.
                assertWithMessage(partMessage).that(part.text).isEqualTo(if (profile.numberParts && n >= 2) label else "")
            } else if (visible(PartConverter(profile, segmenter, "").convert(source.substring(part.sourceStart, part.sourceEnd)), profile.format).isNotEmpty()) {
                // A part whose text of the draft shows anything shows it (T17). Only text that shows nothing
                // anyway, such as the info string of an empty code block, makes an empty part.
                assertWithMessage(partMessage).that(visible(part.text, profile.format).removeSuffix(label.takeIf { profile.numberParts && n >= 2 }.orEmpty()))
                    .isNotEmpty()
            }
        }
        for ((piece, replies) in mediaOnly) {
            assertWithMessage(message).that(result.parts.count { it.piece == piece && it.mediaReply }).isEqualTo(replies)
        }
        for (piece in pieces(source)) {
            val parts = result.parts.filter { it.piece == piece.index && !it.mediaReply && it.sourceStart < it.sourceEnd }
            // A piece without text, or one that shows nothing once converted, has no part of its own.
            if (piece.isEmpty || PartConverter(profile, segmenter, "").convert(source.substring(piece.start, piece.end)).isBlank()) {
                assertWithMessage(message).that(parts).isEmpty()
                continue
            }
            assertWithMessage(message).that(parts).isNotEmpty()
            assertWithMessage(message).that(parts.first().sourceStart).isEqualTo(piece.start)
            assertWithMessage(message).that(parts.last().sourceEnd).isEqualTo(piece.end)
            val text = source.substring(piece.start, piece.end)
            val structure = analyze(text)
            val clusters = segmenter.graphemeBoundaries(text).toHashSet()
            for ((before, after) in parts.zipWithNext()) {
                val end = before.sourceEnd - piece.start
                val start = after.sourceStart - piece.start
                val gap = text.substring(end, start)
                // Only whitespace and the markers of blank quote lines are dropped at a break (T10).
                assertWithMessage("$message\ngap <$gap>").that(gap.all { it.isWhitespace() || it == '>' }).isTrue()
                assertWithMessage(message).that(end in clusters && start in clusters).isTrue()
                for (atom in structure.atomicRanges) {
                    val cut = (atom.start < end && end < atom.end) || (atom.start < start && start < atom.end)
                    assertWithMessage("$message\ncut $atom").that(cut).isFalse()
                }
                for (block in structure.codeBlocks) {
                    if (block.range.start < end && end < block.range.end) {
                        assertWithMessage("$message\ncode line cut").that(gap.contains('\n') || gap.contains('\r')).isTrue()
                    }
                }
            }
            // T17: put together, the parts read as the whole piece converted for the account, but for the
            // whitespace at the breaks, the reopened markup and the part numbers.
            val whole = visible(PartConverter(profile, segmenter, "").convert(text), profile.format)
            val joined = parts.joinToString("") { part ->
                val index = result.parts.indexOf(part)
                visible(withoutLabel(part.text, if (profile.numberParts && n >= 2) "${index + 1}/$n" else null), profile.format)
            }
            assertWithMessage("$message\nparts:\n${parts.joinToString("\n---\n") { it.text }}").that(joined).isEqualTo(whole)
        }
    }

    /** [text] without the part number [label], the way [PartConverter] appends it. */
    private fun withoutLabel(text: String, label: String?): String = when {
        label == null -> text
        text == label -> ""
        text.endsWith("<p>$label</p>") -> text.removeSuffix("<p>$label</p>")
        text.endsWith(" $label</p>") -> text.removeSuffix(" $label</p>") + "</p>"
        text.endsWith("\n$label") -> text.removeSuffix("\n$label")
        else -> text.removeSuffix(" $label")
    }

    /**
     * What a reader sees of a part in [format]: plain text as it is, HTML as its text with list numbers and
     * bullets, and Markdown as HTML the way a server renders it. Whitespace (dropped at breaks), quote
     * markers (reopened) and the emphasis characters `*` and `_` are left out: a part reopens and closes
     * emphasis (T5), and whether a stray `*` among others pairs depends on Markdown's delimiter rules over
     * the whole paragraph, which is formatting rather than content. A `~` stays: strikethrough pairs only
     * runs of two, so a tilde shows in a part exactly when it shows in the whole text, and a reopened `~~`
     * that runs into another tilde shows up here.
     */
    private fun visible(text: String, format: TextFormat): String {
        val shown = when (format) {
            TextFormat.MARKDOWN -> htmlText(toHtml(text))
            TextFormat.HTML -> htmlText(text)
            TextFormat.PLAIN, TextFormat.BLUESKY -> text
        }
        return shown.filterNot { it.isWhitespace() || it == '>' || it == '*' || it == '_' }
    }

    /** The text of [html] with `n.` for the items of ordered lists, counted from their `start`, and `•` for bullets. */
    private fun htmlText(html: String): String {
        val out = StringBuilder()
        val lists = ArrayDeque<Int>()
        var i = 0
        while (i < html.length) {
            if (html[i] != '<') {
                out.append(html[i++])
                continue
            }
            val close = html.indexOf('>', i)
            val tag = html.substring(i + 1, close)
            when {
                tag == "ul" -> lists.addLast(-1)
                tag.startsWith("ol") -> lists.addLast(Regex("start=\"(\\d+)\"").find(tag)?.groupValues?.get(1)?.toInt() ?: 1)
                tag == "/ul" || tag == "/ol" -> lists.removeLast()
                tag == "li" -> {
                    val number = lists.last()
                    if (number < 0) {
                        out.append('•')
                    } else {
                        out.append(number).append('.')
                        lists[lists.lastIndex] = number + 1
                    }
                }
            }
            i = close + 1
        }
        return out.toString()
    }
}
