package org.evsyukov.post.media.convert

/**
 * Mastodon keeps an H.264 video as it is, but transcodes any other one itself and cuts the result
 * at [MAX_FRAMES] frames. A video the plan sends as it is must then be transcoded by the app (A7),
 * so the whole video arrives; the sender asks for a `TranscodeVideo` when [mustTranscode] says so.
 */
object MastodonVideoCheck {
    /** Frames Mastodon keeps of a video it transcodes. */
    const val MAX_FRAMES = 36_000

    /** The frame rate assumed when the file does not give one: the highest common one, so a long video is not missed. */
    const val ASSUMED_FRAME_RATE = 60.0

    private const val H264 = "video/avc"

    /**
     * Whether a video Mastodon would take as it is must still be transcoded: its codec is not H.264
     * (an unknown codec counts as not H.264) and it has more than [MAX_FRAMES] frames. A video of
     * unknown duration is not transcoded for this reason.
     */
    fun mustTranscode(videoCodec: String?, durationMs: Long?, frameRate: Double?): Boolean {
        if (videoCodec != null && normalizeMime(videoCodec) == H264) return false
        if (durationMs == null || durationMs <= 0) return false
        val rate = frameRate?.takeIf { it > 0 } ?: ASSUMED_FRAME_RATE
        return durationMs / 1000.0 * rate > MAX_FRAMES
    }
}
