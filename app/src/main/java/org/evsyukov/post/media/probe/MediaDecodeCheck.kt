package org.evsyukov.post.media.probe

import android.graphics.ImageDecoder
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File

/**
 * The quick check of media.md A4: whether this device can decode a file the sender will convert.
 *
 * It runs when the post is queued, in the composer or the Share sheet, and takes a fraction of a second:
 * an image is decoded at a token size, a video's tracks are read and a decoder is looked up for each. A
 * file that fails it blocks queuing, so a photo or a clip this device cannot handle shows up while the
 * user is still on the screen, not in a notification at send time.
 */
fun interface MediaDecodeCheck {
    /** Null when the device can decode [file], whose type is [mime]; otherwise why it cannot. */
    fun problem(file: File, mime: String): String?
}

/** [MediaDecodeCheck] through the platform decoders: [ImageDecoder] and [MediaCodecList]. */
class DeviceMediaDecodeCheck : MediaDecodeCheck {

    override fun problem(file: File, mime: String): String? =
        when (mime.substringBefore(';').trim().lowercase().substringBefore('/')) {
            "image" -> imageProblem(file)
            "video" -> videoProblem(file)
            // An audio file is uploaded as it is; the app converts only images and video (A5-A7).
            else -> null
        }

    /**
     * Decodes the image at a token size: enough to make the decoder read the file and its frames, cheap
     * even for a full-size photo. [org.evsyukov.post.media.convert.ImageConverter] decodes with the same
     * API (A5), so what this rejects the converter cannot convert either.
     */
    private fun imageProblem(file: File): String? = try {
        val bitmap = ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, _, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            decoder.setTargetSize(1, 1)
        }
        bitmap.recycle()
        null
    } catch (e: Exception) {
        "the image cannot be decoded: ${e.javaClass.simpleName}"
    }

    /**
     * Reads the tracks with [MediaExtractor] and looks up a decoder for every video and audio track: a
     * type this device cannot decode makes the transcode fail (A7). A file with no such track has nothing
     * to transcode.
     */
    private fun videoProblem(file: File): String? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.path)
            val decoders = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            var tracks = 0
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val trackMime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (!trackMime.startsWith("video/") && !trackMime.startsWith("audio/")) continue
                tracks++
                if (decoders.findDecoderForFormat(format) == null) return "no decoder for $trackMime"
            }
            return if (tracks == 0) "the file has no video or audio track" else null
        } catch (e: Exception) {
            return "the video cannot be read: ${e.javaClass.simpleName}"
        } finally {
            runCatching { extractor.release() }
        }
    }
}
