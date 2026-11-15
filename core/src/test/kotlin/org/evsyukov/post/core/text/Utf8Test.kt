package org.evsyukov.post.core.text

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class Utf8Test {

    @Test
    fun `length counts 1 to 4 bytes per code point`() {
        assertThat(utf8Length("")).isEqualTo(0)
        assertThat(utf8Length("abc")).isEqualTo(3)
        assertThat(utf8Length("Привет")).isEqualTo(12)
        assertThat(utf8Length("你好")).isEqualTo(6)
        assertThat(utf8Length("👍")).isEqualTo(4)
        // Four emoji of 4 bytes and three ZWJ of 3 bytes.
        assertThat(utf8Length("👨‍👩‍👧‍👦")).isEqualTo(25)
    }

    @Test
    fun `byte offset of each char offset in a mixed text`() {
        val text = "aП👍b"
        assertThat((0..text.length).map { utf8ByteOffset(text, it) }).containsExactly(0, 1, 3, 4, 7, 8).inOrder()
    }

    @Test
    fun `byte offsets of a facet after multibyte text`() {
        val text = "Привет @alice.bsky.social"
        val start = text.indexOf('@')
        assertThat(utf8ByteOffset(text, start)).isEqualTo(13)
        assertThat(utf8ByteOffset(text, text.length)).isEqualTo(13 + "@alice.bsky.social".length)
    }

    @Test
    fun `matches the JVM encoder on random text with lone surrogates`() {
        val random = Random(20261115)
        val alphabet = listOf('a', 'я', '中', '\uD83D', '\uDE00', 'é', '\n', '\uDBFF', '\uDC00', 'z')
        repeat(500) {
            val text = buildString { repeat(random.nextInt(0, 30)) { append(alphabet[random.nextInt(alphabet.size)]) } }
            assertThat(utf8Length(text)).isEqualTo(text.toByteArray(Charsets.UTF_8).size)
            for (i in 0..text.length) {
                assertThat(utf8ByteOffset(text, i)).isEqualTo(text.substring(0, i).toByteArray(Charsets.UTF_8).size)
            }
        }
    }

    @Test
    fun `offset outside the text throws`() {
        assertThrows(IndexOutOfBoundsException::class.java) { utf8ByteOffset("abc", 4) }
        assertThrows(IndexOutOfBoundsException::class.java) { utf8ByteOffset("abc", -1) }
    }
}
