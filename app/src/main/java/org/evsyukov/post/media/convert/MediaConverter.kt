package org.evsyukov.post.media.convert

import android.content.Context
import org.evsyukov.post.core.media.Conversion
import java.io.File

/** Turns an original file into the variant a [Conversion] asks for (media.md A2). */
interface MediaConverter {
    /**
     * Writes the variant of [source] to [output]. Only [Conversion.ConvertImage] and
     * [Conversion.TranscodeVideo] produce a file; [onProgress] gets 0..100.
     *
     * @throws ConversionException when the conversion fails.
     * @throws IllegalArgumentException for a conversion that keeps the original.
     */
    suspend fun convert(source: File, conversion: Conversion, output: File, onProgress: (Int) -> Unit): ConvertedFile
}

/** The converters of the device: [ImageConverter] for images and [VideoTranscoder] for video. */
class DeviceMediaConverter(
    private val images: ImageConverter,
    private val videos: VideoTranscoder,
) : MediaConverter {
    constructor(context: Context) : this(ImageConverter(), VideoTranscoder(context))

    override suspend fun convert(source: File, conversion: Conversion, output: File, onProgress: (Int) -> Unit): ConvertedFile =
        when (conversion) {
            is Conversion.ConvertImage -> images.convert(source, conversion, output).also { onProgress(100) }
            is Conversion.TranscodeVideo -> videos.transcode(source, conversion, output, onProgress)
            Conversion.AsIs, Conversion.GifAsVideo -> throw IllegalArgumentException("$conversion keeps the original file")
        }
}
