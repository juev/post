package org.evsyukov.post.media.convert

import android.content.Context
import android.media.MediaCodecInfo
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.Effect
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.FrameDropEffect
import androidx.media3.effect.Presentation
import androidx.media3.transformer.AudioEncoderSettings
import androidx.media3.transformer.Composition
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.InAppMp4Muxer
import androidx.media3.transformer.ProgressHolder
import androidx.media3.transformer.Transformer
import androidx.media3.transformer.VideoEncoderSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.evsyukov.post.core.media.Conversion.TranscodeVideo
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Performs [TranscodeVideo] with Media3 Transformer (media.md A7): MP4 with H.264 video and AAC audio
 * at the bitrates of the conversion, the short side brought down to [TranscodeVideo.maxShortSide]
 * (never up), and frames dropped above [TranscodeVideo.maxFrameRate].
 *
 * The encoder may overshoot the requested bitrate, so the output is checked against
 * [TranscodeVideo.maxBytes]; one more attempt runs at a bitrate lowered by the overshoot, and a file
 * still too large fails with [ConversionException.CannotFit]. Cancelling the coroutine cancels the
 * export and deletes the partial file. Transformer runs on the main thread, which has a Looper.
 */
@OptIn(UnstableApi::class)
class VideoTranscoder(context: Context) {
    private val context: Context = context.applicationContext

    /**
     * Writes the transcoded video to [output]; [onProgress] gets 0..100 on the main thread, with 100
     * once the output fits. A second attempt starts again from 0.
     *
     * @throws ConversionException.CannotFit when the output stays above the limit after the retry.
     * @throws ConversionException.TranscodeFailed when Transformer fails.
     */
    suspend fun transcode(source: File, conversion: TranscodeVideo, output: File, onProgress: (Int) -> Unit = {}): ConvertedFile {
        val probe = withContext(Dispatchers.IO) { probe(source) }
        var videoBitrate = conversion.videoBitrate
        var smallest = Long.MAX_VALUE
        try {
            repeat(ATTEMPTS) { attempt ->
                output.delete()
                export(source, conversion, probe, videoBitrate, output, onProgress)
                val size = output.length()
                if (size <= conversion.maxBytes) {
                    val result = withContext(Dispatchers.IO) { probe(output) }
                    withContext(Dispatchers.Main) { onProgress(100) }
                    return ConvertedFile(MIME_MP4, size, result.width, result.height)
                }
                smallest = minOf(smallest, size)
                if (attempt + 1 < ATTEMPTS) videoBitrate = lowerBitrate(videoBitrate, size, conversion.maxBytes)
            }
        } catch (e: Throwable) {
            output.delete()
            throw e
        }
        output.delete()
        throw ConversionException.CannotFit(conversion.maxBytes, smallest)
    }

    private suspend fun export(
        source: File,
        conversion: TranscodeVideo,
        probe: Probe,
        videoBitrate: Int,
        output: File,
        onProgress: (Int) -> Unit,
    ): Unit = withContext(Dispatchers.Main) {
        // Constant bitrate: in VBR mode Android raises a low bitrate to its quality floor (VQApply),
        // and the file outgrows the size target.
        val video = VideoEncoderSettings.Builder()
            .setBitrate(videoBitrate)
            .setBitrateMode(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
            .build()
        val encoders = DefaultEncoderFactory.Builder(context)
            .setRequestedVideoEncoderSettings(video)
            .setRequestedAudioEncoderSettings(AudioEncoderSettings.Builder().setBitrate(conversion.audioBitrate).build())
            .setEnableFallback(true)
            .build()
        val transformer = Transformer.Builder(context)
            .setVideoMimeType(MimeTypes.VIDEO_H264)
            .setAudioMimeType(MimeTypes.AUDIO_AAC)
            .setEncoderFactory(encoders)
            // A streamable file reserves room for its index at the start, hundreds of kilobytes that
            // count against the size target; both networks process the upload on their side anyway.
            .setMuxerFactory(InAppMp4Muxer.Factory().setAttemptStreamableOutputEnabled(false))
            .build()
        val effects = buildList<Effect> {
            if (probe.shortSide == null || probe.shortSide > conversion.maxShortSide) {
                add(Presentation.createForShortSide(conversion.maxShortSide))
            }
            conversion.maxFrameRate?.let { add(FrameDropEffect.createDefaultFrameDropEffect(it.toFloat())) }
        }
        val item = EditedMediaItem.Builder(MediaItem.fromUri(Uri.fromFile(source)))
            .setEffects(Effects(emptyList(), effects))
            .build()
        coroutineScope {
            val progress = launch {
                val holder = ProgressHolder()
                var last = -1
                while (isActive) {
                    delay(PROGRESS_INTERVAL_MS)
                    if (transformer.getProgress(holder) == Transformer.PROGRESS_STATE_AVAILABLE && holder.progress != last) {
                        last = holder.progress
                        onProgress(last.coerceIn(0, 99))
                    }
                }
            }
            try {
                suspendCancellableCoroutine<Unit> { continuation ->
                    transformer.addListener(object : Transformer.Listener {
                        override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                            continuation.resume(Unit)
                        }

                        override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                            continuation.resumeWithException(
                                ConversionException.TranscodeFailed(exportException.errorCodeName, exportException),
                            )
                        }
                    })
                    transformer.start(item, output.absolutePath)
                }
            } catch (e: CancellationException) {
                // The continuation resumes on the main thread, the thread Transformer was built on.
                withContext(NonCancellable) { transformer.cancel() }
                throw e
            } finally {
                progress.cancel()
            }
        }
    }

    /** The source's frame size and the short side of it; null fields when the file does not say. */
    private class Probe(val width: Int?, val height: Int?) {
        val shortSide: Int? = if (width != null && height != null) minOf(width, height) else null
    }

    /** The upright frame size of [file], rotation applied. */
    private fun probe(file: File): Probe = MediaMetadataRetriever().let { retriever ->
        try {
            retriever.setDataSource(file.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation % 180 != 0) Probe(height, width) else Probe(width, height)
        } catch (e: RuntimeException) {
            throw ConversionException.TranscodeFailed(null, e)
        } finally {
            retriever.release()
        }
    }

    companion object {
        /** The first export and one retry at a lower bitrate. */
        internal const val ATTEMPTS = 2

        private const val PROGRESS_INTERVAL_MS = 200L

        /** The bitrate is not lowered below this; such a file cannot fit. */
        internal const val MIN_VIDEO_BITRATE = 100_000

        /**
         * The video bitrate for the retry: lowered by how much [actualBytes] overshot [maxBytes], with
         * a margin of 15 %, and not below [MIN_VIDEO_BITRATE].
         */
        internal fun lowerBitrate(videoBitrate: Int, actualBytes: Long, maxBytes: Long): Int {
            val lowered = videoBitrate.toBigInteger() * maxBytes.toBigInteger() * 85.toBigInteger() /
                (actualBytes.toBigInteger() * 100.toBigInteger())
            return lowered.toInt().coerceAtLeast(MIN_VIDEO_BITRATE).coerceAtMost(videoBitrate)
        }
    }
}
