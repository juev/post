package org.evsyukov.post.core.media

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** The network a media plan is built for. */
@Serializable
enum class Network { MASTODON, BLUESKY }

/**
 * What an attachment is (product.md R9). A GIF is its own kind: Bluesky takes it as video (media.md A8).
 * [GIF] is an animated GIF, which the app never converts; a GIF of one frame is an [IMAGE] with
 * `image/gif`, which the app may convert like any image (social-app classifies GIFs the same way).
 */
@Serializable
enum class MediaKind { IMAGE, GIF, VIDEO, AUDIO }

/**
 * One attachment as the planner sees it: the original file copied into app storage (media.md A1).
 *
 * @property pieceIndex the piece of the text the attachment belongs to; a manual break starts a new
 *   piece and attachments added below it belong there (threads.md T13, T14).
 * @property hasTransparency the image has an alpha channel, so a conversion keeps PNG (A5).
 * @property frameRate frames per second of a video, when the app could read it; used against the
 *   network's frame rate limit.
 * @property alt the alt text, shared by all networks (A9).
 */
@Serializable
data class MediaItem(
    val id: Long,
    val kind: MediaKind,
    val mime: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
    val durationMs: Long?,
    val pieceIndex: Int,
    val hasTransparency: Boolean = false,
    val frameRate: Double? = null,
    val alt: String = "",
)

/** The conflicts of the media.md table, plus the alt text mark of A9. */
@Serializable
enum class ConflictKind(
    /** The fixes media.md offers for this conflict, in the order of the table. */
    val fixes: List<FixKind>,
    /** A blocking conflict holds the post until a fix is chosen; the alt text mark does not. */
    val blocking: Boolean = true,
) {
    /** More files than the network allows in one post (A11). */
    TOO_MANY_FILES(listOf(FixKind.MOVE_TO_REPLY, FixKind.REMOVE_FILES_FOR_NETWORK, FixKind.REMOVE_ACCOUNT)),

    /** A type the network does not take and the app cannot convert to one it takes, such as audio for Bluesky. */
    UNSUPPORTED_TYPE(listOf(FixKind.SEND_WITHOUT_FILE, FixKind.REMOVE_ACCOUNT)),

    /** A video, an audio file or (for Bluesky) a GIF together with images. */
    MIXED_TYPES(listOf(FixKind.MOVE_VIDEO_TO_REPLY, FixKind.REMOVE_VIDEO, FixKind.REMOVE_IMAGES)),

    /** A video longer than the network allows; the app does not trim video. */
    VIDEO_TOO_LONG(listOf(FixKind.REMOVE_VIDEO, FixKind.REMOVE_ACCOUNT)),

    /** The daily video quota of the network is used up (Bluesky `getUploadLimits`). */
    VIDEO_QUOTA(listOf(FixKind.SEND_LATER, FixKind.SEND_WITHOUT_FILE, FixKind.REMOVE_ACCOUNT)),

    /** A poll and media in one Mastodon status. */
    POLL_WITH_MEDIA(listOf(FixKind.REMOVE_POLL, FixKind.REMOVE_MEDIA, FixKind.MOVE_MEDIA_TO_REPLY)),

    /**
     * The file cannot be adapted to the limit without losing content, such as a 40-minute video for
     * 99 MB, an animated GIF over 1280×720 for Mastodon, or a file whose conversion gives a type the
     * network does not take.
     */
    CANNOT_FIT(listOf(FixKind.REMOVE_VIDEO, FixKind.REMOVE_ACCOUNT)),

    /** Alt text over the network's limit (A9): the composer marks it and the user shortens it. */
    ALT_TOO_LONG(emptyList(), blocking = false),
}

/**
 * A fix for a conflict. A fix applies to one network: to every account of that network among the
 * targets (A10). Fixes that remove files remove the files of the conflict for this network only.
 */
@Serializable
enum class FixKind {
    /** Put the files over the limit into media-only replies after their part, in order (A11). */
    MOVE_TO_REPLY,

    /** Leave the files over the limit out for this network. */
    REMOVE_FILES_FOR_NETWORK,

    /** Take the account (the whole network) out of the targets. */
    REMOVE_ACCOUNT,

    /** Send the post to this network without the conflicting file. */
    SEND_WITHOUT_FILE,

    /** Put every video (audio file, Bluesky GIF) of a mixed part into its own media-only reply. */
    MOVE_VIDEO_TO_REPLY,

    /** Leave the conflicting video (or the audio file, GIF, other file that cannot fit) out for this network. */
    REMOVE_VIDEO,

    /** Leave the images of a mixed part out for this network. */
    REMOVE_IMAGES,

    /** Send the post without its poll to this network. */
    REMOVE_POLL,

    /** Leave out the media of the part that carries the poll. */
    REMOVE_MEDIA,

    /** Move the media of the part that carries the poll into media-only replies after it. */
    MOVE_MEDIA_TO_REPLY,

    /** Keep the files and send the post after the quota resets (the next free slot). */
    SEND_LATER,
}

/** A conflict of one network: what is wrong, with which files, and how it can be fixed. */
@Serializable
data class Conflict(
    val kind: ConflictKind,
    val itemIds: List<Long>,
    val fixes: List<FixKind> = kind.fixes,
) {
    val blocking: Boolean get() = kind.blocking
}

/** A conflict that a chosen fix resolved; the composer and the Share sheet list what will change (A12). */
@Serializable
data class ResolvedConflict(val conflict: Conflict, val fix: FixKind)

/**
 * The media of one thread part.
 *
 * @property partOffset 0 is the part the piece starts with; 1 and up are media-only replies the
 *   plan adds after that part, in this order (A11).
 */
@Serializable
data class PartMedia(val partOffset: Int, val itemIds: List<Long>)

/** What is uploaded instead of the original file (A2): the original itself or a converted variant. */
@Serializable
sealed interface Conversion {

    /** The original file goes as it is. */
    @Serializable
    @SerialName("as_is")
    data object AsIs : Conversion

    /**
     * An image decoded, with its orientation applied, downscaled to fit [maxLongSide] and [maxPixels]
     * when they are set, and encoded as [targetMime] until the file fits [maxBytes] (A5, A6).
     *
     * @property targetMime `image/jpeg`, or `image/png` for an image with transparency; a pass that
     *   only removes metadata from a JPEG, PNG or WebP keeps its type and has no size targets.
     * @property stripMetadata remove EXIF, GPS and other metadata (A6).
     */
    @Serializable
    @SerialName("convert_image")
    data class ConvertImage(
        val targetMime: String,
        val maxLongSide: Int?,
        val maxPixels: Long?,
        val maxBytes: Long,
        val stripMetadata: Boolean,
    ) : Conversion

    /**
     * A video transcoded on the device to MP4, H.264 and AAC (A7), as [VideoTargets] chose.
     *
     * @property maxShortSide the short side of the output; a smaller source is not scaled up.
     * @property videoBitrate and [audioBitrate] in bits per second.
     * @property maxBytes the network's limit the output must fit.
     * @property maxFrameRate the network's frame rate limit; a faster source drops frames.
     */
    @Serializable
    @SerialName("transcode_video")
    data class TranscodeVideo(
        val targetMime: String,
        val maxShortSide: Int,
        val videoBitrate: Int,
        val audioBitrate: Int,
        val maxBytes: Long,
        val maxFrameRate: Int? = null,
    ) : Conversion

    /** Bluesky: the GIF goes to the video service as it is, `image/gif`, with `presentation: gif` (A8, N21). */
    @Serializable
    @SerialName("gif_as_video")
    data object GifAsVideo : Conversion
}

/**
 * The media plan of one network (A2, A3).
 *
 * Every attachment ends up in exactly one place: in a part of [partsByPiece], in [excluded] because
 * a chosen fix left it out, or in a blocking conflict of [conflicts] that still waits for a fix.
 *
 * @property partsByPiece for each piece of the text, its parts with media, by [PartMedia.partOffset].
 * @property conversions the variant to upload for every placed attachment.
 * @property conflicts conflicts without a chosen fix; blocking ones hold the post.
 * @property notes short notes on the files that are converted (A3), one per file.
 * @property resolved conflicts a chosen fix resolved, with that fix.
 * @property excluded attachments a chosen fix left out for this network, with that fix.
 */
@Serializable
data class MediaPlan(
    val network: Network,
    val partsByPiece: Map<Int, List<PartMedia>>,
    val conversions: Map<Long, Conversion>,
    val conflicts: List<Conflict>,
    val notes: List<String>,
    val resolved: List<ResolvedConflict> = emptyList(),
    val excluded: Map<Long, FixKind> = emptyMap(),
) {
    /** The post can go to this network: no blocking conflict waits for a fix. */
    val ready: Boolean get() = conflicts.none { it.blocking }

    /** A chosen fix took the account (the network) out of the targets. */
    val accountRemoved: Boolean get() = resolved.any { it.fix == FixKind.REMOVE_ACCOUNT }

    /** A chosen fix drops the poll for this network. */
    val pollRemoved: Boolean get() = resolved.any { it.fix == FixKind.REMOVE_POLL }

    /** A chosen fix keeps the video and sends the post after the quota resets. */
    val sendLater: Boolean get() = resolved.any { it.fix == FixKind.SEND_LATER }

    /** Ids of every attachment placed in a part. */
    val placedItemIds: List<Long> get() = partsByPiece.values.flatMap { parts -> parts.flatMap { it.itemIds } }
}
