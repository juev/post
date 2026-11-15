package org.evsyukov.post.core.render

import com.google.common.truth.Truth.assertWithMessage
import org.evsyukov.post.core.markdown.BlueskyLinkMode
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.markdown.analyze
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
 * no break inside an atomic range, a grapheme cluster or a code line, no whitespace at the ends of a part,
 * parts that cover each piece with only whitespace between them, numbers that match the parts.
 */
class RenderPropertiesTest {
    private val maxExtraParts = ThreadSplitter.MAX_EXTRA_PARTS


    private val profiles: List<TextProfile> = listOf(
        RenderFixtures.custom(TextFormat.PLAIN, 80, Counting.MASTODON),
        RenderFixtures.custom(TextFormat.PLAIN, 140, Counting.FULL, numberParts = true),
        RenderFixtures.custom(TextFormat.MARKDOWN, 120, Counting.GOTOSOCIAL, contentType = "text/markdown"),
        RenderFixtures.custom(TextFormat.MARKDOWN, 90, Counting.MASTODON, numberParts = true),
        RenderFixtures.custom(TextFormat.HTML, 160, Counting.PLEROMA, numberParts = true, contentType = "text/html"),
        TextProfiles.bluesky(BlueskyLinkMode.FACET_ON_TEXT, numberParts = false),
        TextProfiles.bluesky(BlueskyLinkMode.TEXT_AND_SHORT_URL, numberParts = true),
    )

    private val words = listOf(
        "word", "another", "Привет", "мир", "ёлка", "naïve", "éclair", "👍🏽", "👨‍👩‍👧", "🇺🇦", "漢字", "かな",
        "x", "longerwordhere", "end.", "stop!", "why?", "comma,", "semi;", "colon:", "—", "-", "(paren)", "«цитата»",
        "a b", "1984.", "e.g.", "т. е.",
    )
    private val atoms = listOf(
        "https://example.com", "https://example.com/path/__init__.py?q=1&r=2", "https://example.org/" + "long".repeat(12),
        "@alice", "@alice@example.social", "@bob.bsky.social", "#tag", "#тег", "`code span`", "`a*b*c`",
        "[link text](https://example.com/x)", "[https://a.example](https://a.example)", "<https://auto.example/link>",
        "![alt text](https://img.example/i.png)", "\\*not bold\\*", "&amp;", "<span>",
    )

    private fun sentence(random: Random): String {
        val parts = ArrayList<String>()
        repeat(random.nextInt(1, 14)) {
            parts += if (random.nextInt(5) == 0) atoms.random(random) else words.random(random)
        }
        var text = parts.joinToString(if (random.nextInt(8) == 0) "  " else " ")
        when (random.nextInt(6)) {
            0 -> text = "**$text**"
            1 -> text = "*$text*"
            2 -> text = "~~$text~~"
            3 -> text = "_${text}_"
        }
        return text + listOf(".", "!", "?", "", ",").random(random)
    }

    private fun block(random: Random): String = when (random.nextInt(10)) {
        0 -> "> " + List(random.nextInt(1, 4)) { sentence(random) }.joinToString("\n> ")
        1 -> List(random.nextInt(1, 5)) { "- " + sentence(random) }.joinToString("\n")
        2 -> List(random.nextInt(1, 5)) { "${it + 3}. " + sentence(random) }.joinToString("\n")
        3 -> "```kotlin\n" + List(random.nextInt(1, 8)) { "    line(${it}) // " + words.random(random) }.joinToString("\n") +
            if (random.nextBoolean()) "\n```" else ""
        4 -> "> ```\n" + List(random.nextInt(1, 5)) { "> code $it" }.joinToString("\n") + "\n> ```"
        5 -> MANUAL_BREAK
        6 -> "**" + List(random.nextInt(2, 6)) { sentence(random) }.joinToString(" ") + "**"
        else -> List(random.nextInt(1, 6)) { sentence(random) }.joinToString(" ")
    }

    private fun draft(random: Random): String =
        List(random.nextInt(1, 9)) { block(random) }.joinToString(if (random.nextInt(4) == 0) "\n" else "\n\n")

    @Test
    fun `renders of random drafts keep every invariant`() {
        val random = Random(20261115)
        var split = 0
        var issues = 0
        repeat(3000) { iteration ->
            val source = draft(random)
            val profile = profiles[iteration % profiles.size]
            val spoiler = if (random.nextInt(5) == 0) "CW " + words.random(random) else ""
            val locale = if (random.nextBoolean()) Locale.ENGLISH else Locale.forLanguageTag("ru")
            val result = TextRenderer.render(DraftText(source, spoiler, locale), profile, segmenter)
            check(source, spoiler, profile, result)
            if (result.parts.size > 1) split++
            if (result.issue != null) issues++
        }
        println("RenderPropertiesTest: split=$split issues=$issues of 3000")
        // The inputs are long enough to exercise the splitter, not only single parts.
        assertWithMessage("renders with more than one part").that(split).isGreaterThan(1500)
        assertWithMessage("renders with an issue").that(issues).isLessThan(600)
    }

    @Test
    fun `T6 the number of parts stays at the smallest that fits`() {
        // Against the greedy split by exact counts: more parts only by the tail rule (T8) or a rough estimate.
        val random = Random(6)
        val measured = profiles.filter { !it.numberParts }
        var renders = 0
        var above = 0
        repeat(1500) { iteration ->
            val source = draft(random).replace(MANUAL_BREAK, "")
            val profile = measured[iteration % measured.size]
            val piece = pieces(source).singleOrNull()?.takeIf { !it.isEmpty } ?: return@repeat
            val result = TextRenderer.render(DraftText(source, "", Locale.ENGLISH), profile, segmenter)
            val model = PieceModel(piece, source, profile, segmenter, PartConverter(profile, segmenter, ""), Locale.ENGLISH)
            val exact = model.plan(digits = 0, spoilerUnits = 0, slack = 0, byteSlack = 0, exactOnly = true)
            if (result.issue != null || exact !is SplitPlan.Parts) return@repeat
            renders++
            val extra = result.parts.size - exact.count
            assertWithMessage("profile ${profile.key}, draft:\n$source").that(extra).isAtMost(maxExtraParts)
            if (extra > 0) above++
        }
        assertWithMessage("renders compared").that(renders).isGreaterThan(1000)
        assertWithMessage("renders with more parts than the exact greedy split, of $renders").that(above).isLessThan(renders / 20)
    }

    private fun check(source: String, spoiler: String, profile: TextProfile, result: TextRender) {
        val message = "profile ${profile.key}, draft:\n$source"
        val issue = result.issue
        if (issue != null) {
            assertWithMessage(message).that(result.parts).isEmpty()
            if (issue is TextIssue.TooLongToSplit) {
                assertWithMessage(message).that(issue.sourceStart).isLessThan(issue.sourceEnd)
                assertWithMessage(message).that(source.substring(issue.sourceStart, issue.sourceEnd).isNotBlank()).isTrue()
            }
            return
        }
        val n = result.parts.size
        val sentSpoiler = if (profile.network == Network.BLUESKY) "" else spoiler
        result.parts.forEachIndexed { index, part ->
            assertWithMessage(message).that(part.count).isAtMost(profile.maxCharacters)
            assertWithMessage(message).that(part.count).isEqualTo(profile.count(part.text, sentSpoiler, segmenter))
            profile.maxBytes?.let { assertWithMessage(message).that(part.bytes!!).isAtMost(it) }
            if (part.text.isNotEmpty()) {
                assertWithMessage("$message\npart <${part.text}>").that(part.text.first().isWhitespace()).isFalse()
                assertWithMessage("$message\npart <${part.text}>").that(part.text.last().isWhitespace()).isFalse()
            }
            if (profile.numberParts && n >= 2) {
                assertWithMessage(message).that(part.text.removeSuffix("</p>")).endsWith("${index + 1}/$n")
            } else {
                assertWithMessage(message).that(part.text).doesNotContainMatch("^\\d+/\\d+$")
            }
        }
        for (piece in pieces(source)) {
            val parts = result.parts.filter { it.piece == piece.index && !it.mediaReply && it.sourceStart < it.sourceEnd }
            if (piece.isEmpty) {
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
        }
    }
}
