package org.evsyukov.post.core.schedule

import java.time.Duration
import java.time.Instant

/** The late threshold offered under Settings, Sending (Q4). */
enum class LateThreshold(val duration: Duration) {
    MINUTES_15(Duration.ofMinutes(15)),
    MINUTES_30(Duration.ofMinutes(30)),
    HOUR_1(Duration.ofHours(1)),
    HOURS_3(Duration.ofHours(3)),
    ;

    companion object {
        /** The threshold of a new install (Q4, R26a). */
        val DEFAULT = MINUTES_30
    }
}

/**
 * Whether a post due at [sendAt] is late at [now] (Q4): its send time passed more than [threshold] ago.
 * A post exactly [threshold] past its time is due but not late and is sent as usual.
 */
fun isLate(sendAt: Instant, now: Instant, threshold: Duration): Boolean =
    Duration.between(sendAt, now) > threshold

/** [isLate] with a threshold preset. */
fun isLate(sendAt: Instant, now: Instant, threshold: LateThreshold): Boolean =
    isLate(sendAt, now, threshold.duration)
