package org.evsyukov.post.core.thread

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.markdown.MANUAL_BREAK
import org.evsyukov.post.core.markdown.splitManualBreaks
import org.junit.Test

/** T13: manual breaks split the draft into pieces before anything else. */
class PiecesTest {
    private fun texts(source: String) = pieces(source).map { source.substring(it.start, it.end) }

    @Test
    fun `a draft without manual breaks is one piece without whitespace at its ends`() {
        val source = "\n\n  Hello world.  \n"
        assertThat(pieces(source)).containsExactly(Piece(0, 4, 16))
        assertThat(texts(source)).containsExactly("Hello world.")
    }

    @Test
    fun `manual break lines split the draft and are never part of a piece`() {
        val source = "First.\n\n$MANUAL_BREAK\n\nSecond.\n  $MANUAL_BREAK  \nThird."
        assertThat(texts(source)).containsExactly("First.", "Second.", "Third.").inOrder()
        assertThat(pieces(source).map { it.index }).containsExactly(0, 1, 2).inOrder()
    }

    @Test
    fun `empty pieces keep their index, as attachments refer to it`() {
        val source = "$MANUAL_BREAK\nA\n$MANUAL_BREAK\n$MANUAL_BREAK\nB\n$MANUAL_BREAK"
        val pieces = pieces(source)
        assertThat(pieces.map { it.isEmpty }).containsExactly(true, false, true, false, true).inOrder()
        assertThat(texts(source)).containsExactly("", "A", "", "B", "").inOrder()
    }

    @Test
    fun `pieces are numbered as splitManualBreaks numbers them`() {
        val source = "  a\n\n$MANUAL_BREAK\n\n\n$MANUAL_BREAK\n  b c\n\n"
        assertThat(texts(source)).isEqualTo(splitManualBreaks(source).map { it.trim() })
    }
}
