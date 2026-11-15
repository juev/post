package org.evsyukov.post.core.bluesky

import java.security.SecureRandom

/**
 * AT Protocol Timestamp Identifier, used as the record key of posts, threadgates and postgates.
 *
 * Spec: atproto.com/specs/tid (`atproto-website` `59daa1ff`, `specs/tid/en.mdx`): a 64-bit integer
 * with the top bit 0, 53 bits of microseconds since the UNIX epoch and a 10-bit clock identifier,
 * written as 13 characters of base32-sortable (`234567abcdefghijklmnopqrstuvwxyz`). Because the
 * alphabet is in ASCII order, string order equals numeric order.
 *
 * The device clock has millisecond precision, so the timestamp is padded by multiplying by 1000, as
 * the spec suggests; the reference generator does the same (`bluesky-social/atproto` `7dd086d4de`,
 * `packages/common-web/src/tid.ts:24-44`). That one picks its clock id from 0–31; this one uses the
 * whole 10-bit range.
 */
object Tid {
    private const val ALPHABET = "234567abcdefghijklmnopqrstuvwxyz"
    private const val LENGTH = 13
    private const val MAX_TIMESTAMP = (1L shl 53) - 1
    private val SYNTAX = Regex("^[234567abcdefghij][234567abcdefghijklmnopqrstuvwxyz]{12}$")

    private val generator = Generator(clockId = SecureRandom().nextInt(1024)) { System.currentTimeMillis() * 1000 }

    /** A new TID, strictly greater than every TID this process generated before. */
    fun next(): String = generator.next()

    /** The spec's reference regex: 13 characters, base32-sortable, top bit clear. */
    fun isValid(value: String): Boolean = SYNTAX.matches(value)

    /** Encodes [timestampMicros] (53 bits) and [clockId] (10 bits). */
    fun encode(timestampMicros: Long, clockId: Int): String {
        require(timestampMicros in 0..MAX_TIMESTAMP) { "timestamp out of 53-bit range" }
        require(clockId in 0..1023) { "clock id out of 10-bit range" }
        var value = (timestampMicros shl 10) or clockId.toLong()
        val chars = CharArray(LENGTH)
        for (i in LENGTH - 1 downTo 0) {
            chars[i] = ALPHABET[(value and 31).toInt()]
            value = value ushr 5
        }
        return String(chars)
    }

    /** Microseconds since the epoch encoded in a valid [tid]. */
    fun timestampMicros(tid: String): Long = decode(tid) ushr 10

    /** Clock identifier encoded in a valid [tid]. */
    fun clockId(tid: String): Int = (decode(tid) and 1023).toInt()

    private fun decode(tid: String): Long {
        require(isValid(tid)) { "not a TID" }
        return tid.fold(0L) { acc, c -> (acc shl 5) or ALPHABET.indexOf(c).toLong() }
    }

    /**
     * Generator with an injectable clock. Each value is above the previous one even when the clock
     * stands still or goes back: the timestamp becomes `max(clock, previous + 1)`.
     */
    internal class Generator(private val clockId: Int, private val clockMicros: () -> Long) {
        private var last = -1L

        @Synchronized
        fun next(): String {
            last = maxOf(clockMicros(), last + 1)
            return encode(last, clockId)
        }
    }
}
