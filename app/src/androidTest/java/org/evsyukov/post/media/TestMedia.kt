package org.evsyukov.post.media

import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import kotlin.random.Random

/** Media files the device tests make themselves, so no fixture binary lives in the repository. */
object TestMedia {

    /**
     * Encodes [seconds] of [width] × [height] H.264 video at [fps] into an MP4 file. Every frame is a
     * moving pattern of waves with a little grain, so the encoder has motion and detail to spend its
     * bitrate on, as in a camera clip.
     */
    fun video(file: File, width: Int, height: Int, seconds: Int, fps: Int = 30, bitrate: Int = 6_000_000) {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val muxer = MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val random = Random(7)
        val total = seconds * fps
        var frame = 0
        var track = -1
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                if (!inputDone) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val pts = frame * 1_000_000L / fps
                        if (frame == total) {
                            codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            fill(codec.getInputImage(index)!!, frame, random)
                            codec.queueInputBuffer(index, 0, width * height * 3 / 2, pts, 0)
                            frame++
                        }
                    }
                }
                val out = codec.dequeueOutputBuffer(info, 10_000)
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                } else if (out >= 0) {
                    if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) info.size = 0
                    if (info.size > 0) muxer.writeSampleData(track, codec.getOutputBuffer(out)!!, info)
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
        } finally {
            codec.stop()
            codec.release()
            muxer.stop()
            muxer.release()
        }
    }

    private fun fill(image: Image, frame: Int, random: Random) {
        val luma = image.planes[0]
        val row = ByteArray(image.width)
        val waves = IntArray(image.width) { x -> (60 * kotlin.math.sin((x + frame * 4) / 23.0)).toInt() }
        for (y in 0 until image.height) {
            val band = (40 * kotlin.math.cos((y + frame * 2) / 17.0)).toInt()
            for (x in 0 until image.width) {
                row[x] = (128 + waves[x] + band + random.nextInt(6)).coerceIn(0, 255).toByte()
            }
            if (luma.pixelStride == 1) {
                luma.buffer.position(y * luma.rowStride)
                luma.buffer.put(row)
            } else {
                for (x in 0 until image.width) luma.buffer.put(y * luma.rowStride + x * luma.pixelStride, row[x])
            }
        }
        for (plane in 1..2) {
            val chroma = image.planes[plane]
            for (y in 0 until image.height / 2) {
                for (x in 0 until image.width / 2) {
                    chroma.buffer.put(y * chroma.rowStride + x * chroma.pixelStride, (96 + plane * 24 + (frame % 32)).toByte())
                }
            }
        }
    }

    /** The video tracks' MIME type and size of an MP4 file. */
    fun videoTrack(file: File): Triple<String, Int, Int> {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/")) {
                    return Triple(mime, format.getInteger(MediaFormat.KEY_WIDTH), format.getInteger(MediaFormat.KEY_HEIGHT))
                }
            }
            error("no video track in $file")
        } finally {
            extractor.release()
        }
    }

    /** A bitmap of noise in green and blue, red 0, with a solid red block in its top-left quarter. */
    fun markedNoise(width: Int, height: Int): Bitmap {
        val random = Random(3)
        val pixels = IntArray(width * height) { i ->
            val x = i % width
            val y = i / width
            if (x < width / 4 && y < height / 4) Color.RED else Color.rgb(0, random.nextInt(256), random.nextInt(256))
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /** A bitmap of noise in every channel, alpha included. */
    fun translucentNoise(width: Int, height: Int): Bitmap {
        val random = Random(5)
        val pixels = IntArray(width * height) { Color.argb(random.nextInt(256), random.nextInt(256), random.nextInt(256), random.nextInt(256)) }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }
}
