package org.evsyukov.post.ui.composer

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.markdown.HighlightSpan
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.data.posts.PollDraft
import org.junit.Test

/** The edits of the formatting buttons (F5) and breaks (T13), the highlight styles (F4) and the poll rules. */
class MarkdownEditsTest {
    @Test
    fun wrapKeepsTheWrappedTextSelected() {
        assertThat(MarkdownEdits.wrap("a word b", 2, 6, "**")).isEqualTo(EditResult("a **word** b", 4, 8))
        // A selection made backwards is wrapped the same way.
        assertThat(MarkdownEdits.wrap("a word b", 6, 2, "~~")).isEqualTo(EditResult("a ~~word~~ b", 4, 8))
    }

    @Test
    fun wrapWithoutSelectionPutsTheCursorInsideThePair() {
        assertThat(MarkdownEdits.wrap("ab", 1, 1, "`")).isEqualTo(EditResult("a``b", 2, 2))
    }

    @Test
    fun aPrefixIsAddedToLinesThatLackItAndRemovedWhenAllHaveIt() {
        assertThat(MarkdownEdits.toggleLinePrefix("one\ntwo", 0, 7, "> ")).isEqualTo(EditResult("> one\n> two", 2, 11))
        assertThat(MarkdownEdits.toggleLinePrefix("> one\n> two", 2, 11, "> ")).isEqualTo(EditResult("one\ntwo", 0, 7))
        // Mixed lines all get it first.
        assertThat(MarkdownEdits.toggleLinePrefix("- one\ntwo", 0, 9, "- ").text).isEqualTo("- one\n- two")
    }

    @Test
    fun blankLinesInsideASelectionKeepNoPrefix() {
        assertThat(MarkdownEdits.toggleLinePrefix("one\n\ntwo", 0, 8, "> ").text).isEqualTo("> one\n\n> two")
    }

    @Test
    fun aSelectionEndingAfterALineBreakLeavesTheNextLineAlone() {
        assertThat(MarkdownEdits.toggleLinePrefix("one\ntwo", 0, 4, "- ").text).isEqualTo("- one\ntwo")
    }

    @Test
    fun aCursorOnALineTogglesThatLineOnly() {
        assertThat(MarkdownEdits.toggleLinePrefix("a\nbc\nd", 3, 3, "> ")).isEqualTo(EditResult("a\n> bc\nd", 5, 5))
        // A cursor inside the removed prefix lands at the line start.
        assertThat(MarkdownEdits.toggleLinePrefix("a\n> bc", 3, 3, "> ")).isEqualTo(EditResult("a\nbc", 2, 2))
    }

    @Test
    fun aLinkUsesTheSelectionAsItsText() {
        assertThat(MarkdownEdits.link("see docs", 4, 8, " https://e.com ")).isEqualTo(EditResult("see [docs](https://e.com)", 25, 25))
        assertThat(MarkdownEdits.link("see ", 4, 4, "https://e.com")).isEqualTo(EditResult("see [](https://e.com)", 5, 5))
    }

    @Test
    fun aBreakGoesOnALineOfItsOwn() {
        assertThat(MarkdownEdits.insertBreak("ab", 1)).isEqualTo(EditResult("a\n$MANUAL_BREAK\nb", 4))
        assertThat(MarkdownEdits.insertBreak("a\n", 2)).isEqualTo(EditResult("a\n$MANUAL_BREAK\n", 4))
        assertThat(MarkdownEdits.insertBreak("a\nb", 1)).isEqualTo(EditResult("a\n$MANUAL_BREAK\nb", 4))
        assertThat(MarkdownEdits.insertBreak("", 0)).isEqualTo(EditResult("$MANUAL_BREAK\n", 2))
    }

    @Test
    fun removingABreakUndoesInsertingIt() {
        val inserted = MarkdownEdits.insertBreak("first second", 5).text
        val line = inserted.indexOf(MANUAL_BREAK)
        assertThat(MarkdownEdits.removeBreak(inserted, line, line + 1).text).isEqualTo("first\n second")
        // The last line of the text has no line break after it: the one before goes.
        assertThat(MarkdownEdits.removeBreak("a\n$MANUAL_BREAK", 2, 3)).isEqualTo(EditResult("a", 1))
    }

    @Test
    fun theHighlightStylesBoldCodeAndDimsMarkup() {
        val colors = HighlightColors(Color.Gray, Color.Blue, Color.DarkGray, Color.Red, Color.LightGray)
        val spans = orderedHighlight("**b** `c`\n$MANUAL_BREAK")

        val bold = spans.single { it.kind == HighlightSpan.Kind.BOLD }
        assertThat(bold.start to bold.end).isEqualTo(2 to 3)
        assertThat(bold.kind.style(colors).fontWeight).isEqualTo(FontWeight.Bold)
        assertThat(HighlightSpan.Kind.CODE.style(colors).fontFamily).isEqualTo(FontFamily.Monospace)
        assertThat(HighlightSpan.Kind.SYNTAX.style(colors).color).isEqualTo(Color.Gray)
        assertThat(HighlightSpan.Kind.BREAK_MARKER.style(colors).color).isEqualTo(Color.Transparent)
        // Markup is styled after what contains it, so its dim color wins.
        assertThat(spans.indexOfLast { it.kind == HighlightSpan.Kind.SYNTAX }).isGreaterThan(spans.indexOf(bold))
        assertThat(breakLines("a\n$MANUAL_BREAK\nb").single().start).isEqualTo(2)
        assertThat(hasFormatting("plain\n$MANUAL_BREAK\ntext")).isFalse()
        assertThat(hasFormatting("**x**")).isTrue()
    }

    @Test
    fun aPollTakesTheStrictestLimitsOfTheServers() {
        val limits = PollLimits(maxOptions = 3, maxCharacters = 5, minSeconds = 300, maxSeconds = 3600)
        assertThat(pollProblem(PollDraft(listOf("a", "b"), 600), limits)).isNull()
        assertThat(pollProblem(PollDraft(listOf("a"), 600), limits)).isEqualTo(PollProblem.TOO_FEW_OPTIONS)
        assertThat(pollProblem(PollDraft(listOf("a", "b", "c", "d"), 600), limits)).isEqualTo(PollProblem.TOO_MANY_OPTIONS)
        assertThat(pollProblem(PollDraft(listOf("a", " "), 600), limits)).isEqualTo(PollProblem.EMPTY_OPTION)
        assertThat(pollProblem(PollDraft(listOf("a", "bbbbbb"), 600), limits)).isEqualTo(PollProblem.OPTION_TOO_LONG)
        assertThat(pollProblem(PollDraft(listOf("a", "b"), 7200), limits)).isEqualTo(PollProblem.DURATION)
        assertThat(pollProblem(PollDraft(listOf("a", "b"), 60), limits)).isEqualTo(PollProblem.DURATION)

        // Without a Mastodon target the vanilla limits apply.
        val vanilla = PollLimits.of(emptyList())
        assertThat(vanilla.maxOptions).isEqualTo(MastodonCapabilities.Defaults.POLL_MAX_OPTIONS)
    }

    @Test
    fun theClipboardOffersOnlyAWebAddress() {
        assertThat(clipboardUrl(" https://e.com/x ")).isEqualTo("https://e.com/x")
        assertThat(clipboardUrl("http://e.com")).isEqualTo("http://e.com")
        assertThat(clipboardUrl("see https://e.com")).isNull()
        assertThat(clipboardUrl("ftp://e.com")).isNull()
        assertThat(clipboardUrl(null)).isNull()
    }

    @Test
    fun sendIsBlockedWithoutTargetsOrText() {
        val limits = PollLimits.of(emptyList())
        assertThat(blockers(emptyList(), "hi", emptyList(), null, limits)).containsExactly(Blocker.NoTarget)
        assertThat(blockers(listOf(1L), " ", emptyList(), null, limits)).containsExactly(Blocker.EmptyPost)
        assertThat(blockers(listOf(1L), "hi", emptyList(), null, limits)).isEmpty()
        // A poll alone is something to send; an invalid one blocks.
        assertThat(blockers(listOf(1L), "", emptyList(), PollDraft(listOf("a", "b"), 86_400), limits)).isEmpty()
        assertThat(blockers(listOf(1L), "q", emptyList(), PollDraft(listOf("a", ""), 86_400), limits))
            .containsExactly(Blocker.Poll(PollProblem.EMPTY_OPTION))
    }
}
