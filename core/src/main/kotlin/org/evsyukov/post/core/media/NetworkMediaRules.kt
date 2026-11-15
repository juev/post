package org.evsyukov.post.core.media

import kotlinx.serialization.Serializable

/**
 * What one network (or the strictest of its target accounts, A10) accepts as attachments.
 * Values come from networks.md; a Mastodon account maps the limits read from its server (N1) into
 * this type, and [mastodonDefaults] holds the vanilla values for servers that report none. The
 * mapping starts from [mastodonDefaults] with `copy`, so a limit the server does not report
 * ([maxGifPixels]) keeps its vanilla value.
 *
 * A null limit means the network has none. MIME types are compared in lower case without parameters.
 *
 * @property maxAttachments files in one post; for Bluesky the image limit of `app.bsky.embed.images`.
 *   A network with 0 takes no files at all.
 * @property maxImagePixels width × height (Mastodon `image_matrix_limit`).
 * @property maxImageLongSide the long side social-app downscales Bluesky images to (N19).
 * @property maxVideoBytes the limit for video, and for audio where audio is allowed (Mastodon uses
 *   one limit for both).
 * @property maxVideoPixels width × height (Mastodon `video_matrix_limit`).
 * @property audioMimes audio types the network takes; empty with [audioAllowed] means any audio type.
 * @property gifIsVideo a GIF goes to the video service and counts as a video (Bluesky, A8, N21).
 * @property videoMustBeAlone a video (audio file, video GIF) cannot share a post with other files:
 *   Mastodon takes one video or one audio file alone, Bluesky images or one video.
 * @property galleryMax up to this many images go in one gallery (Bluesky `app.bsky.embed.gallery`, N19);
 *   null when the network has no gallery.
 * @property maxAltLength alt text limit in characters (Unicode code points).
 * @property pollAllowedWithMedia a status may carry a poll and media together; Mastodon forbids it.
 *   Bluesky has no polls, so a poll never conflicts there.
 * @property maxGifPixels width × height of an `image/gif` upload. Mastodon rejects a larger GIF with
 *   422 (`Attachmentable::GIF_MATRIX_LIMIT`, 1280×720) and does not list this limit in
 *   `/api/v2/instance`. A GIF the network converts on its side is checked against this limit; a GIF
 *   the app converts to JPEG or PNG is not.
 */
@Serializable
data class NetworkMediaRules(
    val network: Network,
    val maxAttachments: Int,
    val imageMimes: Set<String>,
    val maxImageBytes: Long,
    val maxImagePixels: Long?,
    val maxImageLongSide: Int?,
    val videoMimes: Set<String>,
    val maxVideoBytes: Long,
    val maxVideoPixels: Long?,
    val maxVideoFrameRate: Int?,
    val maxVideoDurationMs: Long?,
    val audioAllowed: Boolean,
    val gifIsVideo: Boolean,
    val videoMustBeAlone: Boolean,
    val galleryMax: Int?,
    val maxAltLength: Int,
    val pollAllowedWithMedia: Boolean,
    val audioMimes: Set<String> = emptySet(),
    val maxGifPixels: Long? = null,
) {
    /** The most images one part takes: a gallery where the network has one. */
    val maxImagesPerPart: Int get() = maxOf(maxAttachments, galleryMax ?: 0)

    internal fun acceptsImage(mime: String): Boolean = normalizeMime(mime) in imageMimes.normalized()

    internal fun acceptsVideo(mime: String): Boolean = normalizeMime(mime) in videoMimes.normalized()

    internal fun acceptsAudio(mime: String): Boolean =
        audioAllowed && (audioMimes.isEmpty() || normalizeMime(mime) in audioMimes.normalized())

    companion object {
        private const val MIB = 1024L * 1024

        /**
         * Vanilla Mastodon (networks.md table; `MediaAttachment` of mastodon/mastodon main): 4 files,
         * JPEG, PNG, GIF, WebP up to 16 MB and 7680×4320, a GIF up to 1280×720
         * (`Attachmentable::GIF_MATRIX_LIMIT`, not in the networks.md table); MP4, WebM, QuickTime,
         * Ogg up to 99 MB, 3840×2160 and 120 fps; audio up to 99 MB; one video or one audio file
         * alone; alt text 10 000 characters; no poll with media.
         */
        fun mastodonDefaults(): NetworkMediaRules = NetworkMediaRules(
            network = Network.MASTODON,
            maxAttachments = 4,
            imageMimes = setOf("image/jpeg", "image/png", "image/gif", "image/webp"),
            maxImageBytes = 16 * MIB,
            maxImagePixels = 7680L * 4320,
            maxImageLongSide = null,
            videoMimes = setOf("video/mp4", "video/webm", "video/quicktime", "video/ogg"),
            maxVideoBytes = 99 * MIB,
            maxVideoPixels = 3840L * 2160,
            maxVideoFrameRate = 120,
            maxVideoDurationMs = null,
            audioAllowed = true,
            gifIsVideo = false,
            videoMustBeAlone = true,
            galleryMax = null,
            maxAltLength = 10_000,
            pollAllowedWithMedia = false,
            audioMimes = setOf(
                "audio/wave", "audio/wav", "audio/x-wav", "audio/x-pn-wave", "audio/vnd.wave", "audio/ogg",
                "audio/vorbis", "audio/mpeg", "audio/mp3", "audio/webm", "audio/flac", "audio/aac", "audio/m4a",
                "audio/x-m4a", "audio/mp4", "audio/3gpp",
            ),
            maxGifPixels = 1280L * 720,
        )

        /**
         * Bluesky (networks.md table, N19–N21): `app.bsky.embed.images` takes 4 images of 2 000 000
         * bytes, `app.bsky.embed.gallery` up to 10 (the soft limit the lexicon asks for); images are
         * downscaled to 4000 px on the long side as social-app does. Video goes to the video service:
         * MP4, MPEG, WebM, QuickTime up to 300 000 000 bytes (`app.bsky.embed.video`) and 10 minutes
         * (social-app `VIDEO_MAX_DURATION_MS`); a GIF goes there too. No audio; alt text 2000
         * characters (social-app `MAX_ALT_TEXT`).
         */
        fun bluesky(): NetworkMediaRules = NetworkMediaRules(
            network = Network.BLUESKY,
            maxAttachments = 4,
            imageMimes = setOf("image/jpeg", "image/png", "image/webp"),
            maxImageBytes = 2_000_000,
            maxImagePixels = null,
            maxImageLongSide = 4000,
            videoMimes = setOf("video/mp4", "video/mpeg", "video/webm", "video/quicktime"),
            maxVideoBytes = 300_000_000,
            maxVideoPixels = null,
            maxVideoFrameRate = null,
            maxVideoDurationMs = 10 * 60 * 1000L,
            audioAllowed = false,
            gifIsVideo = true,
            videoMustBeAlone = true,
            galleryMax = 10,
            maxAltLength = 2000,
            pollAllowedWithMedia = true,
        )

        /**
         * The strictest rules of several accounts of one network (A10): the smallest limits, the
         * types every account takes, and a restriction when any account has it.
         */
        fun strictest(rules: List<NetworkMediaRules>): NetworkMediaRules {
            require(rules.isNotEmpty()) { "no rules to merge" }
            val network = rules.first().network
            require(rules.all { it.network == network }) { "rules of different networks cannot be merged" }
            if (rules.size == 1) return rules.single()
            val audioMimes = mergeAudioMimes(rules)
            return NetworkMediaRules(
                network = network,
                maxAttachments = rules.minOf { it.maxAttachments },
                imageMimes = rules.map { it.imageMimes.normalized() }.reduce { a, b -> a intersect b },
                maxImageBytes = rules.minOf { it.maxImageBytes },
                maxImagePixels = minOfLimits(rules.map { it.maxImagePixels }),
                maxImageLongSide = minOfLimits(rules.map { it.maxImageLongSide }),
                videoMimes = rules.map { it.videoMimes.normalized() }.reduce { a, b -> a intersect b },
                maxVideoBytes = rules.minOf { it.maxVideoBytes },
                maxVideoPixels = minOfLimits(rules.map { it.maxVideoPixels }),
                maxVideoFrameRate = minOfLimits(rules.map { it.maxVideoFrameRate }),
                maxVideoDurationMs = minOfLimits(rules.map { it.maxVideoDurationMs }),
                // Lists without a common type leave no audio file that every account takes.
                audioAllowed = rules.all { it.audioAllowed } && audioMimes != null,
                gifIsVideo = rules.any { it.gifIsVideo },
                videoMustBeAlone = rules.any { it.videoMustBeAlone },
                // No gallery on one account means no gallery for the network.
                galleryMax = if (rules.any { it.galleryMax == null }) null else rules.minOf { it.galleryMax!! },
                maxAltLength = rules.minOf { it.maxAltLength },
                pollAllowedWithMedia = rules.all { it.pollAllowedWithMedia },
                audioMimes = audioMimes.orEmpty(),
                maxGifPixels = minOfLimits(rules.map { it.maxGifPixels }),
            )
        }

        /** The smallest limit; null (no limit) only when no account has one. */
        private fun <T : Comparable<T>> minOfLimits(limits: List<T?>): T? = limits.filterNotNull().minOrNull()

        /**
         * The audio types every account takes. An empty list means any type, so it does not narrow
         * the others. Null when the lists have no type in common.
         */
        private fun mergeAudioMimes(rules: List<NetworkMediaRules>): Set<String>? {
            val lists = rules.filter { it.audioMimes.isNotEmpty() }.map { it.audioMimes.normalized() }
            if (lists.isEmpty()) return emptySet()
            return lists.reduce { a, b -> a intersect b }.ifEmpty { null }
        }
    }
}

/** Lower case, without parameters such as `; codecs=…`; `image/jpg` is read as `image/jpeg`. */
internal fun normalizeMime(mime: String): String {
    val bare = mime.substringBefore(';').trim().lowercase()
    return if (bare == "image/jpg") "image/jpeg" else bare
}

private fun Set<String>.normalized(): Set<String> = mapTo(LinkedHashSet()) { normalizeMime(it) }
