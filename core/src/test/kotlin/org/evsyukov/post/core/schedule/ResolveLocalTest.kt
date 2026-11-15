package org.evsyukov.post.core.schedule

import com.google.common.truth.Truth.assertThat
import java.time.ZonedDateTime
import org.junit.Test

/** S2: local slot times in the device zone, across daylight-saving changes. */
class ResolveLocalTest {

    @Test
    fun `S2 a slot inside the Berlin spring gap moves to the first minute after the gap`() {
        // 2026-03-29: 02:00 CET jumps to 03:00 CEST, at 01:00 UTC.
        assertThat(resolveLocal(local("2026-03-29T02:30"), BERLIN)).isEqualTo(at("2026-03-29T01:00:00Z"))
        assertThat(resolveLocal(local("2026-03-29T02:00"), BERLIN)).isEqualTo(at("2026-03-29T01:00:00Z"))
        assertThat(resolveLocal(local("2026-03-29T02:59"), BERLIN)).isEqualTo(at("2026-03-29T01:00:00Z"))
    }

    @Test
    fun `S2 the gap is not resolved the way ZonedDateTime of does it`() {
        // ZonedDateTime.of shifts 02:30 by the length of the gap, to 03:30; S2 wants 03:00.
        val shifted = ZonedDateTime.of(local("2026-03-29T02:30"), BERLIN).toInstant()
        assertThat(shifted).isEqualTo(at("2026-03-29T01:30:00Z"))
        assertThat(resolveLocal(local("2026-03-29T02:30"), BERLIN)).isNotEqualTo(shifted)
    }

    @Test
    fun `S2 times around the Berlin spring gap keep their meaning`() {
        assertThat(resolveLocal(local("2026-03-29T01:59"), BERLIN)).isEqualTo(at("2026-03-29T00:59:00Z"))
        assertThat(resolveLocal(local("2026-03-29T03:00"), BERLIN)).isEqualTo(at("2026-03-29T01:00:00Z"))
        assertThat(resolveLocal(local("2026-03-29T03:01"), BERLIN)).isEqualTo(at("2026-03-29T01:01:00Z"))
    }

    @Test
    fun `S2 a slot inside the Berlin autumn overlap uses its first occurrence`() {
        // 2026-10-25: 03:00 CEST falls back to 02:00 CET; 02:00-03:00 happens twice.
        assertThat(resolveLocal(local("2026-10-25T02:30"), BERLIN)).isEqualTo(at("2026-10-25T00:30:00Z"))
        assertThat(resolveLocal(local("2026-10-25T02:00"), BERLIN)).isEqualTo(at("2026-10-25T00:00:00Z"))
        assertThat(resolveLocal(local("2026-10-25T03:00"), BERLIN)).isEqualTo(at("2026-10-25T02:00:00Z"))
    }

    @Test
    fun `S2 New York spring gap and autumn overlap`() {
        // 2026-03-08: 02:00 EST jumps to 03:00 EDT, at 07:00 UTC.
        assertThat(resolveLocal(local("2026-03-08T02:30"), NEW_YORK)).isEqualTo(at("2026-03-08T07:00:00Z"))
        // 2026-11-01: 02:00 EDT falls back to 01:00 EST; 01:30 first happens at 05:30 UTC (EDT).
        assertThat(resolveLocal(local("2026-11-01T01:30"), NEW_YORK)).isEqualTo(at("2026-11-01T05:30:00Z"))
    }

    @Test
    fun `S2 a zone without daylight saving maps every local time by its fixed offset`() {
        assertThat(resolveLocal(local("2026-03-29T02:30"), MOSCOW)).isEqualTo(at("2026-03-28T23:30:00Z"))
        assertThat(resolveLocal(local("2026-10-25T02:30"), MOSCOW)).isEqualTo(at("2026-10-24T23:30:00Z"))
        assertThat(resolveLocal(local("2026-11-16T09:00"), MOSCOW)).isEqualTo(at("2026-11-16T06:00:00Z"))
    }

    @Test
    fun `S2 a slot post follows the device to a new zone, an explicit time does not`() {
        val slot = SendTime.Slot(local("2026-11-16T09:00"))
        assertThat(slot.toInstant(BERLIN)).isEqualTo(at("2026-11-16T08:00:00Z"))
        assertThat(slot.toInstant(MOSCOW)).isEqualTo(at("2026-11-16T06:00:00Z"))
        assertThat(slot.toInstant(NEW_YORK)).isEqualTo(at("2026-11-16T14:00:00Z"))

        val explicit = SendTime.Explicit(at("2026-11-16T08:00:00Z"))
        assertThat(explicit.toInstant(BERLIN)).isEqualTo(at("2026-11-16T08:00:00Z"))
        assertThat(explicit.toInstant(MOSCOW)).isEqualTo(at("2026-11-16T08:00:00Z"))
    }

    @Test
    fun `S2 a slot send time inside a gap resolves through the transition`() {
        assertThat(SendTime.Slot(local("2026-03-29T02:30")).toInstant(BERLIN)).isEqualTo(at("2026-03-29T01:00:00Z"))
        assertThat(SendTime.Slot(local("2026-10-25T02:30")).toInstant(BERLIN)).isEqualTo(at("2026-10-25T00:30:00Z"))
    }
}
