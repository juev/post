package org.evsyukov.post.core.render

import com.google.common.truth.Truth.assertWithMessage
import org.evsyukov.post.core.markdown.BlueskyLinkMode
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.render.RenderFixtures.assertValid
import org.evsyukov.post.core.render.RenderFixtures.segmenter
import org.junit.Test
import java.util.Locale

/**
 * The renderer runs on every keystroke of the composer (T12), so a long draft must render fast. The bound
 * is generous for slow CI machines; the measured time is printed.
 */
class RenderPerformanceTest {

    private fun mixedMarkdown(): String {
        val blocks = listOf(
            "Это **важный** абзац с [ссылкой](https://example.com/page) и упоминанием @alice@example.social. " +
                "Второе предложение идёт следом, а третье — с `кодом` и #хэштегом. Ещё одно, короткое.",
            "> A quoted line with *emphasis* and a bare URL https://example.org/a/b?c=d.\n> And a second quoted line, " +
                "longer than the first one, with ~~struck~~ words.",
            "- first item with a 👍🏽 emoji\n- second item, with **bold** text\n- third item that is a little longer than the others",
            "```kotlin\nfun main() {\n    println(\"hello\")\n    val list = listOf(1, 2, 3)\n}\n```",
            "Plain English sentences follow here. They are short. Some are longer, with commas, semicolons; and dashes — " +
                "like this one, which keeps going for a while before it finally ends.",
        )
        val text = StringBuilder()
        var i = 0
        while (text.length < 5000) {
            if (text.isNotEmpty()) text.append("\n\n")
            text.append(blocks[i++ % blocks.size])
        }
        return text.toString()
    }

    @Test
    fun `a 5000-character mixed Markdown draft renders fast for each profile`() {
        val source = mixedMarkdown()
        val profiles = mapOf(
            "bluesky" to TextProfiles.bluesky(BlueskyLinkMode.FACET_ON_TEXT, numberParts = true),
            "mastodon" to TextProfiles.mastodon(MastodonCapabilities(softwareName = "mastodon"), numberParts = true),
            "gotosocial markdown" to TextProfiles.mastodon(
                MastodonCapabilities(postFormats = setOf("text/markdown"), postFormatsDeclared = true, softwareName = "gotosocial"),
                numberParts = false,
            ),
            "pleroma html" to TextProfiles.mastodon(
                MastodonCapabilities(postFormats = setOf("text/html"), postFormatsDeclared = true, softwareName = "pleroma", maxCharacters = 500),
                numberParts = true,
            ),
        )
        val draft = DraftText(source, "", Locale.forLanguageTag("ru"))
        for ((name, profile) in profiles) {
            repeat(5) { TextRenderer.render(draft, profile, segmenter) } // JIT warm-up
            val times = List(9) {
                val start = System.nanoTime()
                val result = TextRenderer.render(draft, profile, segmenter)
                val elapsed = (System.nanoTime() - start) / 1_000_000.0
                assertValid(source, result)
                elapsed
            }.sorted()
            val median = times[times.size / 2]
            val parts = TextRenderer.render(draft, profile, segmenter).parts.size
            println("RenderPerformanceTest: $name, ${source.length} chars, $parts parts, median %.1f ms".format(Locale.ROOT, median))
            assertWithMessage("median render time of $name, ms").that(median).isLessThan(500.0)
        }
    }
}
