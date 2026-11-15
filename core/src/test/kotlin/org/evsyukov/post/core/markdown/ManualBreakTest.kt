package org.evsyukov.post.core.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** threads.md T13: a line of only U+2E3B is a manual break that splits the text and is never published. */
class ManualBreakTest {

    @Test
    fun `break line is the marker alone with optional surrounding spaces`() {
        assertThat(MANUAL_BREAK).isEqualTo("⸻")
        assertThat(isManualBreakLine("⸻")).isTrue()
        assertThat(isManualBreakLine("  ⸻ \t")).isTrue()
        assertThat(isManualBreakLine("⸻\r")).isTrue()
    }

    @Test
    fun `other lines are not breaks`() {
        assertThat(isManualBreakLine("")).isFalse()
        assertThat(isManualBreakLine("⸻⸻")).isFalse()
        assertThat(isManualBreakLine("a ⸻")).isFalse()
        assertThat(isManualBreakLine("> ⸻")).isFalse()
        assertThat(isManualBreakLine("—")).isFalse()
        assertThat(isManualBreakLine("---")).isFalse()
    }

    @Test
    fun `pieces between breaks lose blank lines at their edges`() {
        assertThat(splitManualBreaks("First paragraph.\n\n⸻\n\nSecond one.\nStill second."))
            .containsExactly("First paragraph.", "Second one.\nStill second.").inOrder()
    }

    @Test
    fun `lines inside a piece stay as typed`() {
        assertThat(splitManualBreaks("  - item\n\n  more  \n⸻\n> quote"))
            .containsExactly("  - item\n\n  more  ", "> quote").inOrder()
    }

    @Test
    fun `text without breaks is one piece`() {
        assertThat(splitManualBreaks("\n  \nhello\nworld\n\n")).containsExactly("hello\nworld")
        assertThat(splitManualBreaks("")).containsExactly("")
    }

    @Test
    fun `empty pieces are kept so piece indexes match the breaks`() {
        assertThat(splitManualBreaks("⸻\nb")).containsExactly("", "b").inOrder()
        assertThat(splitManualBreaks("a\n⸻\n⸻\nb")).containsExactly("a", "", "b").inOrder()
        assertThat(splitManualBreaks("a\n⸻")).containsExactly("a", "").inOrder()
    }

    @Test
    fun `windows and old mac line breaks`() {
        assertThat(splitManualBreaks("a\r\n⸻\r\nb")).containsExactly("a", "b").inOrder()
        assertThat(splitManualBreaks("a\r ⸻ \rb")).containsExactly("a", "b").inOrder()
    }
}
