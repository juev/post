package org.evsyukov.post.core.media

import org.evsyukov.post.core.media.Conversion.TranscodeVideo

/**
 * The transcoding target of a video that must shrink to a size limit (media.md A7).
 *
 * The output is MP4 with H.264 video and AAC audio at [AUDIO_BITRATE]. The bitrate comes from the
 * duration and the size limit:
 *
 * 1. The budget is [BUDGET_PERCENT] % of the limit. The rest covers the MP4 container (sample
 *    tables, `moov`) and the encoder overshooting its requested bitrate; the converter still
 *    checks the output against [TranscodeVideo.maxBytes].
 * 2. The video bitrate is the budget in bits divided by the duration, minus the audio bitrate.
 * 3. The output keeps 1080 px on the short side when that bitrate reaches [MIN_BITRATE_1080];
 *    otherwise it goes down to 720 px, which needs [MIN_BITRATE_720]. Below that floor H.264
 *    turns into blocks, so the video cannot fit ([ConflictKind.CANNOT_FIT]).
 * 4. The bitrate is capped at [MAX_BITRATE_1080] or [MAX_BITRATE_720]: a short clip gains nothing
 *    from more, and the file stays small.
 * 5. With the source size known, the bitrate is also capped at the source's own bitrate (its size
 *    in bits over its duration, audio included), but not below the floor of the step. A file
 *    transcoded for its type, frame rate or resolution gains no detail from more bits, so it does
 *    not grow beyond what it was, except up to the floor.
 *
 * A source with a short side of 720 px or less keeps its size and uses the 720 floor and cap.
 * A source between 720 and 1080 is not scaled up; it uses the 1080 floor. With a pixel limit
 * (Mastodon `video_matrix_limit`), a step whose output exceeds it is skipped.
 */
object VideoTargets {
    const val TARGET_MIME = "video/mp4"

    /** AAC stereo, bits per second. */
    const val AUDIO_BITRATE = 128_000

    /** Share of the size limit given to the streams, in percent. */
    const val BUDGET_PERCENT = 90

    const val FULL_HD_SHORT_SIDE = 1080
    const val HD_SHORT_SIDE = 720

    /** Lowest video bitrate for 1080p, bits per second. */
    const val MIN_BITRATE_1080 = 2_500_000

    /** Lowest video bitrate for 720p, bits per second. */
    const val MIN_BITRATE_720 = 1_000_000

    const val MAX_BITRATE_1080 = 8_000_000
    const val MAX_BITRATE_720 = 5_000_000

    private class Step(val shortSide: Int, val minBitrate: Int, val maxBitrate: Int)

    private val steps = listOf(
        Step(FULL_HD_SHORT_SIDE, MIN_BITRATE_1080, MAX_BITRATE_1080),
        Step(HD_SHORT_SIDE, MIN_BITRATE_720, MAX_BITRATE_720),
    )

    /**
     * The target for a video of [durationMs] and [width] × [height] that must fit [maxBytes], or
     * null when even 720p at [MIN_BITRATE_720] does not fit (or the duration is not positive).
     * An unknown size is passed as 0 × 0 and is treated as a large source. [sourceBytes] is the
     * size of the source file; null or not positive when unknown, and then it caps nothing.
     */
    fun target(
        durationMs: Long,
        maxBytes: Long,
        width: Int,
        height: Int,
        maxPixels: Long? = null,
        sourceBytes: Long? = null,
    ): TranscodeVideo? {
        if (durationMs <= 0 || maxBytes <= 0) return null
        val budgetBits = maxBytes * 8 * BUDGET_PERCENT / 100
        val totalBitrate = budgetBits * 1000 / durationMs
        val videoBitrate = totalBitrate - AUDIO_BITRATE
        val sourceBitrate = sourceBytes?.takeIf { it > 0 }?.let { it * 8 * 1000 / durationMs }
        val sourceShortSide = minOf(width, height)
        for (step in steps) {
            // A source that is already small keeps its size and is judged by the 720 step.
            if (step.shortSide == FULL_HD_SHORT_SIDE && sourceShortSide in 1..HD_SHORT_SIDE) continue
            if (maxPixels != null && outputPixels(width, height, step.shortSide) > maxPixels) continue
            if (videoBitrate < step.minBitrate) continue
            val sourceCap = sourceBitrate?.coerceAtLeast(step.minBitrate.toLong()) ?: Long.MAX_VALUE
            return TranscodeVideo(
                targetMime = TARGET_MIME,
                maxShortSide = step.shortSide,
                videoBitrate = minOf(videoBitrate, step.maxBitrate.toLong(), sourceCap).toInt(),
                audioBitrate = AUDIO_BITRATE,
                maxBytes = maxBytes,
            )
        }
        return null
    }

    /** Pixels of the output when the short side is scaled down to [shortSide] (never up). */
    internal fun outputPixels(width: Int, height: Int, shortSide: Int): Long {
        val short = minOf(width, height).toLong()
        val long = maxOf(width, height).toLong()
        if (short <= shortSide) return short * long
        val scaledLong = (long * shortSide + short - 1) / short
        return shortSide.toLong() * scaledLong
    }
}
