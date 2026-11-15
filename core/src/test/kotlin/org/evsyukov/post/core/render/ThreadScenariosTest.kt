package org.evsyukov.post.core.render

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.render.RenderFixtures.EN
import org.evsyukov.post.core.render.RenderFixtures.RU
import org.evsyukov.post.core.render.RenderFixtures.assertValid
import org.evsyukov.post.core.render.RenderFixtures.bluesky
import org.evsyukov.post.core.render.RenderFixtures.custom
import org.evsyukov.post.core.render.RenderFixtures.graphemes
import org.evsyukov.post.core.render.RenderFixtures.mastodon
import org.evsyukov.post.core.render.RenderFixtures.render
import org.junit.Test

/** The verification scenarios of threads.md, through the public API. */
class ThreadScenariosTest {

    private val sentences = listOf(
        "Утром над рекой стоял густой туман, и лодки были едва видны.",
        "Рыбаки говорили вполголоса, чтобы не спугнуть рыбу у берега.",
        "К полудню солнце разогнало облака, и вода заблестела.",
        "На пристани собрались дети и смотрели на большие корабли.",
        "Старый смотритель маяка рассказывал им о дальних странах.",
        "Ветер усилился, и чайки поднялись высоко над волнами.",
        "Вечером в деревне зажглись огни, запахло свежим хлебом.",
        "Соседи вышли на крыльцо и долго обсуждали прошедший день.",
        "Ночью над лесом взошла луна, и стало совсем тихо вокруг.",
        "Только сверчки пели в траве до самого рассвета у ручья.",
        "Наутро всё повторилось снова, как и много лет назад.",
        "Жизнь в этих местах текла медленно и спокойно, без спешки.",
    )

    /** Four paragraphs of whole sentences, 290 to 340 graphemes each, about 1260 in all. */
    private fun fourParagraphs(): String {
        var next = 0
        return List(4) {
            val paragraph = StringBuilder()
            while (graphemes(paragraph.toString()) < 290) {
                if (paragraph.isNotEmpty()) paragraph.append(' ')
                paragraph.append(sentences[next++ % sentences.size])
            }
            paragraph.toString()
        }.joinToString("\n\n")
    }

    @Test
    fun `1200 Russian characters in four paragraphs give Mastodon 3 parts and Bluesky 5`() {
        val text = fourParagraphs()
        assertThat(graphemes(text)).isIn(com.google.common.collect.Range.closed(1200, 1300))
        val mastodon = render(text, mastodon(), RU)
        val bluesky = render(text, bluesky(), RU)
        assertValid(text, mastodon)
        assertValid(text, bluesky)
        assertThat(mastodon.parts).hasSize(3)
        assertThat(bluesky.parts).hasSize(5)
        for (result in listOf(mastodon, bluesky)) {
            val average = result.parts.sumOf { it.count }.toDouble() / result.parts.size
            for ((index, part) in result.parts.withIndex()) {
                // Every break falls after a sentence end (a paragraph ends with one too).
                assertThat(text[part.sourceEnd - 1]).isEqualTo('.')
                if (index > 0) assertThat(text[part.sourceStart].isUpperCase()).isTrue()
                assertThat(part.count * 3.0).isAtLeast(average)
            }
        }
    }

    @Test
    fun `a 310-grapheme sentence goes to Bluesky as two even parts split at a comma`() {
        val clauses = listOf(
            "When the long road finally turned toward the sea after many hours of walking",
            "the travellers stopped to rest beside an old stone wall covered in moss",
            "and they shared the last of their bread and water while the sun was setting",
            "before they set off again toward the small harbour town in the distance below",
        )
        val sentence = clauses.joinToString(", ") + "."
        assertThat(graphemes(sentence)).isAtLeast(301)
        assertThat(graphemes(sentence)).isAtMost(320)
        val result = render(sentence, bluesky(), EN)
        assertValid(sentence, result)
        assertThat(result.parts).hasSize(2)
        val (first, second) = result.parts
        assertThat(first.text).endsWith(",")
        // Not 300 + 10: both parts are near the middle.
        assertThat(kotlin.math.abs(first.count - second.count)).isLessThan(90)
    }

    @Test
    fun `a 400-character URL to Bluesky is a conflict and is never split`() {
        val url = "https://example.com/" + "a".repeat(380)
        val text = "Read this: $url and tell me."
        assertThat(url.length).isAtLeast(400)
        val result = render(text, bluesky(), EN)
        val issue = result.issue as TextIssue.TooLongToSplit
        assertThat(result.parts).isEmpty()
        assertThat(issue.sourceStart).isAtMost(text.indexOf(url))
        assertThat(issue.sourceEnd).isAtLeast(text.indexOf(url) + url.length)
        assertThat(issue.piece).isEqualTo(0)
    }

    @Test
    fun `bold text crossing a break is closed and reopened`() {
        val text = "**bold text that crosses the break and goes on for a while**"
        val profile = custom(TextFormat.MARKDOWN, maxCharacters = 40, counting = Counting.GOTOSOCIAL, contentType = "text/markdown")
        val result = render(text, profile)
        assertValid(text, result)
        assertThat(result.parts.size).isAtLeast(2)
        assertThat(result.parts.first().text).endsWith("**")
        assertThat(result.parts.first().text).startsWith("**")
        for (part in result.parts.drop(1)) {
            assertThat(part.text).startsWith("**")
            assertThat(part.text).endsWith("**")
        }
    }

    @Test
    fun `a manual break after the first paragraph gives both networks two parts`() {
        val first = "This is the first paragraph of a short post, and it says hello to everyone here."
        val second = "This is the second paragraph, and it ends the post with a friendly goodbye to all."
        val text = "$first\n\n$MANUAL_BREAK\n\n$second"
        assertThat(first.length + second.length).isAtMost(200)
        for (profile in listOf(mastodon(), bluesky())) {
            val result = render(text, profile)
            assertValid(text, result)
            assertThat(result.parts.map { it.text }).containsExactly(first, second).inOrder()
            assertThat(result.parts.map { it.piece }).containsExactly(0, 1).inOrder()
        }
    }

    @Test
    fun `numbering turns 9 parts into 10 numbered 1 of 10 to 10 of 10`() {
        // Words of 9 letters: 30 fit a 300-grapheme part without numbers, 29 with a number.
        val text = List(265) { "abcdefgh" + ('a' + it % 26) }.joinToString(" ")
        assertThat(render(text, bluesky(numberParts = false)).parts).hasSize(9)
        val result = render(text, bluesky(numberParts = true))
        assertValid(text, result)
        assertThat(result.parts).hasSize(10)
        result.parts.forEachIndexed { index, part ->
            assertThat(part.text).endsWith(" ${index + 1}/10")
            assertThat(part.count).isAtMost(300)
        }
    }
}
