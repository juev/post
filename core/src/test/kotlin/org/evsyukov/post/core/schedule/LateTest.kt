package org.evsyukov.post.core.schedule

import com.google.common.truth.Truth.assertThat
import java.time.Duration
import org.junit.Test

/** Q4: a post is late when its time passed more than the threshold ago. */
class LateTest {

    private val sendAt = at("2026-11-16T08:00:00Z")

    @Test
    fun `Q4 the default threshold is 30 minutes and the presets are 15m, 30m, 1h, 3h`() {
        assertThat(LateThreshold.DEFAULT).isEqualTo(LateThreshold.MINUTES_30)
        assertThat(LateThreshold.entries.map { it.duration }).containsExactly(
            Duration.ofMinutes(15),
            Duration.ofMinutes(30),
            Duration.ofHours(1),
            Duration.ofHours(3),
        ).inOrder()
    }

    @Test
    fun `Q4 a post exactly at the threshold is due, not late, and a second more makes it late`() {
        for (threshold in LateThreshold.entries) {
            val boundary = sendAt.plus(threshold.duration)
            assertThat(isLate(sendAt, boundary, threshold)).isFalse()
            assertThat(isLate(sendAt, boundary.plusSeconds(1), threshold)).isTrue()
            assertThat(isLate(sendAt, boundary.plusNanos(1), threshold.duration)).isTrue()
        }
    }

    @Test
    fun `Q4 the two-hour case is late at 30 minutes and 1 hour but not at 3 hours`() {
        val twoHoursLater = sendAt.plus(Duration.ofHours(2))
        assertThat(isLate(sendAt, twoHoursLater, LateThreshold.MINUTES_30)).isTrue()
        assertThat(isLate(sendAt, twoHoursLater, LateThreshold.HOUR_1)).isTrue()
        assertThat(isLate(sendAt, twoHoursLater, LateThreshold.HOURS_3)).isFalse()
    }

    @Test
    fun `Q4 a post that is not yet due or due right now is not late`() {
        assertThat(isLate(sendAt, sendAt.minusSeconds(60), LateThreshold.MINUTES_15)).isFalse()
        assertThat(isLate(sendAt, sendAt, LateThreshold.MINUTES_15)).isFalse()
        // Ten minutes without network is not late at the default threshold (M6).
        assertThat(isLate(sendAt, sendAt.plus(Duration.ofMinutes(10)), LateThreshold.DEFAULT)).isFalse()
    }
}
