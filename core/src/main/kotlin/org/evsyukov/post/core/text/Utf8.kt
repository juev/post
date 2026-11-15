package org.evsyukov.post.core.text

/**
 * The number of bytes [text] takes in UTF-8, as `text.toByteArray(Charsets.UTF_8).size` counts them,
 * without allocating the bytes.
 *
 * A surrogate pair takes 4 bytes. An unpaired surrogate takes 1 byte, because the JVM encoder, which
 * also encodes the request body sent to the server, replaces it with `?`.
 */
fun utf8Length(text: String): Int = utf8ByteOffset(text, text.length)

/**
 * The UTF-8 byte offset of the char offset [charIndex] in [text]: the UTF-8 length of
 * `text.substring(0, charIndex)`. Bluesky facets use these offsets (networks.md, N17).
 *
 * @throws IndexOutOfBoundsException when [charIndex] is outside `0..text.length`.
 */
fun utf8ByteOffset(text: String, charIndex: Int): Int {
    if (charIndex < 0 || charIndex > text.length) {
        throw IndexOutOfBoundsException("charIndex $charIndex is outside 0..${text.length}")
    }
    var bytes = 0
    var i = 0
    while (i < charIndex) {
        val c = text[i]
        when {
            c.code < 0x80 -> bytes += 1
            c.code < 0x800 -> bytes += 2
            c.isHighSurrogate() && i + 1 < charIndex && text[i + 1].isLowSurrogate() -> {
                bytes += 4
                i++
            }
            c.isSurrogate() -> bytes += 1
            else -> bytes += 3
        }
        i++
    }
    return bytes
}
