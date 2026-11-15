package org.evsyukov.post.core.bluesky

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** atproto.com/specs/tid (`atproto-website` `59daa1ff`), examples copied from the spec. */
class TidTest {
    @Test
    fun specValidExamplesAreAccepted() {
        for (tid in listOf("3jzfcijpj2z2a", "7777777777777", "3zzzzzzzzzzzz", "2222222222222")) {
            assertThat(Tid.isValid(tid)).isTrue()
        }
    }

    @Test
    fun specInvalidExamplesAreRejected() {
        val invalid = listOf(
            "3jzfcijpj2z21", "0000000000000", // not base32
            "3JZFCIJPJ2Z2A", // case-sensitive
            "3jzfcijpj2z2aa", "3jzfcijpj2z2", "222", // too long or short
            "3jzf-cij-pj2z-2a", // legacy dashes
            "zzzzzzzzzzzzz", "kjzfcijpj2z2a", // high bit set
        )
        for (tid in invalid) assertThat(Tid.isValid(tid)).isFalse()
    }

    @Test
    fun integerZeroIsAllTwos() {
        assertThat(Tid.encode(0, 0)).isEqualTo("2222222222222")
    }

    @Test
    fun encodingPutsMicrosecondsAboveTheTenBitClockId() {
        val micros = 1_763_200_000_123_456L // 2025-11-15, microseconds
        val tid = Tid.encode(micros, 1023)
        assertThat(tid).hasLength(13)
        assertThat(Tid.isValid(tid)).isTrue()
        assertThat(Tid.timestampMicros(tid)).isEqualTo(micros)
        assertThat(Tid.clockId(tid)).isEqualTo(1023)
        // The last two characters hold the low 10 bits: 1023 = 31,31 → "zz".
        assertThat(tid.takeLast(2)).isEqualTo("zz")
        assertThat(Tid.encode(micros, 0).takeLast(2)).isEqualTo("22")
    }

    @Test
    fun largestTimestampKeepsTheTopBitClear() {
        // 2^63 - 1: 13 characters carry 65 bits, so the first one holds bits 64..60 = 0b00111 → 'b'.
        val tid = Tid.encode((1L shl 53) - 1, 1023)
        assertThat(tid).isEqualTo("bzzzzzzzzzzzz")
        assertThat(Tid.isValid(tid)).isTrue()
    }

    @Test
    fun specExampleDecodesToItsTimestamp() {
        val tid = "3jzfcijpj2z2a"
        assertThat(Tid.encode(Tid.timestampMicros(tid), Tid.clockId(tid))).isEqualTo(tid)
    }

    @Test
    fun valuesOutOfRangeAreRefused() {
        assertThat(runCatching { Tid.encode(1L shl 53, 0) }.isFailure).isTrue()
        assertThat(runCatching { Tid.encode(0, 1024) }.isFailure).isTrue()
        assertThat(runCatching { Tid.encode(-1, 0) }.isFailure).isTrue()
    }

    @Test
    fun generatorIsStrictlyIncreasingWhenTheClockStandsStill() {
        val generator = Tid.Generator(clockId = 5) { 1_763_200_000_000_000L }
        val tids = List(1000) { generator.next() }
        assertThat(tids.zipWithNext().all { (a, b) -> a < b }).isTrue()
        assertThat(tids.toSet()).hasSize(1000)
        assertThat(Tid.timestampMicros(tids.last())).isEqualTo(1_763_200_000_000_999L)
    }

    @Test
    fun generatorIsStrictlyIncreasingWhenTheClockGoesBack() {
        var now = 1_763_200_000_000_000L
        val generator = Tid.Generator(clockId = 7) { now }
        val first = generator.next()
        now -= 5_000_000 // the clock is set back five seconds
        val second = generator.next()
        assertThat(second).isGreaterThan(first)
        now += 60_000_000
        val third = generator.next()
        assertThat(Tid.timestampMicros(third)).isEqualTo(now)
    }

    @Test
    fun processTidsAreValidAndIncreasing() {
        val tids = List(200) { Tid.next() }
        assertThat(tids.all { Tid.isValid(it) }).isTrue()
        assertThat(tids.zipWithNext().all { (a, b) -> a < b }).isTrue()
        // The timestamp is the wall clock in microseconds (milliseconds padded by 1000).
        val nowMicros = System.currentTimeMillis() * 1000
        assertThat(Tid.timestampMicros(tids.first())).isAtMost(nowMicros + 1000)
        assertThat(Tid.timestampMicros(tids.first())).isAtLeast(nowMicros - 60_000_000)
    }
}
