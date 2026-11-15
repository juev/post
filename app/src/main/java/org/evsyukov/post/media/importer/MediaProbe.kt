package org.evsyukov.post.media.importer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import androidx.exifinterface.media.ExifInterface
import org.evsyukov.post.core.media.MediaKind
import java.io.File

/**
 * What the importer learns about one file (media.md A1): the numbers the media planner checks against the
 * limits of every network.
 *
 * @property width and [height] as the file is shown: an image turned by its EXIF orientation, a video by
 *   its rotation, so 90 and 270 degrees swap them.
 * @property rotation the rotation of a video in degrees, 0 for anything else.
 * @property videoCodec the MIME type of the video track, such as `video/avc`.
 * @property animated an image with more than one frame (GIF, APNG, animated WebP).
 */
data class ProbedMedia(
    val kind: MediaKind,
    val mime: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val frameRate: Double? = null,
    val rotation: Int = 0,
    val videoCodec: String? = null,
    val animated: Boolean = false,
    val hasTransparency: Boolean = false,
)

/**
 * Reads the metadata of a file in app storage (R9, media.md A1). Images: size from the header with the
 * EXIF orientation applied, animation from the container ([MediaSniffer.isAnimated]), transparency from a
 * small decoded sample. Video: duration, size, rotation and frame rate from [MediaMetadataRetriever], the
 * codec and the declared frame rate from [MediaExtractor]. Audio: duration.
 *
 * A GIF is [MediaKind.GIF] only when it is animated; a GIF of one frame is an image with `image/gif`
 * (plan, step 7 notes). A value the platform cannot read stays null; the planner treats it as unknown.
 */
class MediaProbe {

    /** The metadata of [file], whose type is [mime]; null when [mime] is not an image, a video or audio. */
    fun probe(file: File, mime: String): ProbedMedia? {
        val type = mime.substringBefore(';').trim().lowercase()
        val size = file.length()
        return when {
            type.startsWith("image/") -> image(file, type, size)
            type.startsWith("video/") -> video(file, type, size)
            type.startsWith("audio/") -> ProbedMedia(MediaKind.AUDIO, type, size, durationMs = retrieve(file) { duration() })
            else -> null
        }
    }

    private fun image(file: File, mime: String, size: Long): ProbedMedia {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.path, bounds) }
        var width = bounds.outWidth.takeIf { it > 0 }
        var height = bounds.outHeight.takeIf { it > 0 }
        val rotation = runCatching { ExifInterface(file).rotationDegrees }.getOrDefault(0)
        if (rotation == 90 || rotation == 270) width = height.also { height = width }
        val animated = MediaSniffer.isAnimated(file, mime)
        val kind = if (mime == "image/gif" && animated) MediaKind.GIF else MediaKind.IMAGE
        return ProbedMedia(
            kind = kind,
            mime = mime,
            sizeBytes = size,
            width = width,
            height = height,
            animated = animated,
            hasTransparency = mime != "image/jpeg" && hasTransparency(file, maxOf(bounds.outWidth, bounds.outHeight)),
        )
    }

    /** Decodes a sample of at most about [SAMPLE_SIDE] pixels and looks for a pixel that is not opaque. */
    private fun hasTransparency(file: File, longSide: Int): Boolean {
        var sample = 1
        while (longSide / (sample * 2) >= SAMPLE_SIDE) sample *= 2
        val bitmap = runCatching {
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 })
        }.getOrNull() ?: return false
        try {
            if (!bitmap.hasAlpha()) return false
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            return pixels.any { (it ushr 24) != 0xFF }
        } finally {
            bitmap.recycle()
        }
    }

    private fun video(file: File, mime: String, size: Long): ProbedMedia {
        class Read(val duration: Long?, val width: Int?, val height: Int?, val rotation: Int, val frames: Int?)
        val read = retrieve(file) {
            Read(
                duration = duration(),
                width = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull(),
                height = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull(),
                rotation = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0,
                frames = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toIntOrNull(),
            )
        }
        val track = videoTrack(file)
        val rotation = ((read?.rotation ?: 0) % 360 + 360) % 360
        var width = read?.width ?: track?.getIntOrNull(MediaFormat.KEY_WIDTH)
        var height = read?.height ?: track?.getIntOrNull(MediaFormat.KEY_HEIGHT)
        if (rotation == 90 || rotation == 270) width = height.also { height = width }
        val duration = read?.duration ?: track?.takeIf { it.containsKey(MediaFormat.KEY_DURATION) }?.getLong(MediaFormat.KEY_DURATION)?.div(1000)
        val declared = track?.getNumberOrNull(MediaFormat.KEY_FRAME_RATE)?.toDouble()?.takeIf { it > 0 }
        val counted = read?.frames?.takeIf { it > 0 }?.let { frames -> duration?.takeIf { it > 0 }?.let { frames * 1000.0 / it } }
        return ProbedMedia(
            kind = MediaKind.VIDEO,
            mime = mime,
            sizeBytes = size,
            width = width,
            height = height,
            durationMs = duration,
            frameRate = declared ?: counted,
            rotation = rotation,
            videoCodec = track?.getString(MediaFormat.KEY_MIME),
        )
    }

    private fun MediaMetadataRetriever.duration(): Long? =
        extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

    private fun <T> retrieve(file: File, read: MediaMetadataRetriever.() -> T): T? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.read()
        } catch (_: Exception) {
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun videoTrack(file: File): MediaFormat? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.path)
            (0 until extractor.trackCount).map { extractor.getTrackFormat(it) }
                .firstOrNull { it.getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true }
        } catch (_: Exception) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }

    private fun MediaFormat.getIntOrNull(key: String): Int? = if (containsKey(key)) runCatching { getInteger(key) }.getOrNull() else null

    /** A number stored as an integer or a float, as extractors differ. */
    private fun MediaFormat.getNumberOrNull(key: String): Number? = if (containsKey(key)) getNumber(key) else null

    private companion object {
        const val SAMPLE_SIDE = 64
    }
}
