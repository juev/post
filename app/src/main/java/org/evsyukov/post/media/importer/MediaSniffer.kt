package org.evsyukov.post.media.importer

import java.io.DataInputStream
import java.io.EOFException
import java.io.File
import java.io.IOException
import java.io.InputStream

/**
 * What the bytes of a file say about it, without Android: the type by its signature, whether an image is
 * animated, and the file extension of a type. The importer asks the content resolver for the type first
 * and falls back to these signatures (R9).
 */
object MediaSniffer {

    /** How many bytes of the head [sniff] needs. */
    const val HEAD_BYTES = 32

    /** The MIME type of a file starting with [head], or null when no known signature matches. */
    fun sniff(head: ByteArray): String? {
        fun at(offset: Int, vararg bytes: Int) =
            head.size >= offset + bytes.size && bytes.indices.all { head[offset + it].toInt() and 0xFF == bytes[it] }
        fun ascii(offset: Int, text: String) = at(offset, *text.map { it.code }.toIntArray())
        return when {
            at(0, 0xFF, 0xD8, 0xFF) -> "image/jpeg"
            at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> "image/png"
            ascii(0, "GIF87a") || ascii(0, "GIF89a") -> "image/gif"
            ascii(0, "RIFF") && ascii(8, "WEBP") -> "image/webp"
            ascii(0, "RIFF") && ascii(8, "WAVE") -> "audio/wav"
            ascii(0, "RIFF") && ascii(8, "AVI ") -> "video/x-msvideo"
            ascii(4, "ftyp") -> ftyp(String(head, 8, 4, Charsets.ISO_8859_1))
            at(0, 0x1A, 0x45, 0xDF, 0xA3) -> "video/webm"
            ascii(0, "OggS") -> "audio/ogg"
            ascii(0, "fLaC") -> "audio/flac"
            ascii(0, "ID3") -> "audio/mpeg"
            // An MPEG audio frame: 11 sync bits, then a layer that is not "reserved" (ADTS AAC has layer 0).
            head.size >= 2 && head[0].toInt() and 0xFF == 0xFF && head[1].toInt() and 0xE0 == 0xE0 && head[1].toInt() and 0x06 != 0 -> "audio/mpeg"
            at(0, 0xFF, 0xF1) || at(0, 0xFF, 0xF9) -> "audio/aac"
            else -> null
        }
    }

    /** The type of an ISO base media file by its major brand. */
    private fun ftyp(brand: String): String = when (brand.lowercase()) {
        "heic", "heix", "heim", "heis", "hevc", "hevx", "mif1", "msf1" -> "image/heic"
        "avif", "avis" -> "image/avif"
        "qt  " -> "video/quicktime"
        "m4a ", "m4b ", "m4p " -> "audio/mp4"
        "3gp4", "3gp5", "3gp6", "3gp7", "3ge6", "3ge7", "3gg6", "3g2a" -> "video/3gpp"
        else -> "video/mp4"
    }

    /** Reads up to [HEAD_BYTES] bytes from the start of [file]. */
    fun head(file: File): ByteArray = file.inputStream().use { input ->
        val buffer = ByteArray(HEAD_BYTES)
        var read = 0
        while (read < buffer.size) {
            val n = input.read(buffer, read, buffer.size - read)
            if (n < 0) break
            read += n
        }
        buffer.copyOf(read)
    }

    /**
     * Whether the image in [file] of type [mime] has more than one frame: a GIF with two image
     * descriptors, an APNG (an `acTL` chunk before the image data), a WebP with the animation flag.
     * Any other type, or a file that cannot be read, is not animated.
     */
    fun isAnimated(file: File, mime: String): Boolean = try {
        when (mime.lowercase()) {
            "image/gif" -> file.inputStream().buffered().use { gifFrames(it, stopAt = 2) >= 2 }
            "image/png", "image/apng" -> file.inputStream().buffered().use { isApng(DataInputStream(it)) }
            "image/webp" -> file.inputStream().buffered().use { isAnimatedWebp(it) }
            else -> false
        }
    } catch (_: IOException) {
        false
    }

    /** Counts the image descriptors of a GIF, stopping at [stopAt]. */
    internal fun gifFrames(input: InputStream, stopAt: Int = Int.MAX_VALUE): Int {
        val header = input.readExactly(13)
        val packed = header[10].toInt() and 0xFF
        if (packed and 0x80 != 0) input.skipExactly(3L * (1 shl ((packed and 0x07) + 1)))
        var frames = 0
        while (frames < stopAt) {
            when (input.read()) {
                0x21 -> {
                    input.read() // label
                    skipSubBlocks(input)
                }
                0x2C -> {
                    frames++
                    val descriptor = input.readExactly(9)
                    val flags = descriptor[8].toInt() and 0xFF
                    if (flags and 0x80 != 0) input.skipExactly(3L * (1 shl ((flags and 0x07) + 1)))
                    input.read() // LZW minimum code size
                    skipSubBlocks(input)
                }
                else -> return frames // 0x3B trailer, end of file or garbage
            }
        }
        return frames
    }

    private fun skipSubBlocks(input: InputStream) {
        while (true) {
            val size = input.read()
            if (size <= 0) return
            input.skipExactly(size.toLong())
        }
    }

    private fun isApng(input: DataInputStream): Boolean {
        input.skipExactly(8)
        while (true) {
            val length = try {
                input.readInt().toLong() and 0xFFFFFFFFL
            } catch (_: EOFException) {
                return false
            }
            val type = String(input.readExactly(4), Charsets.ISO_8859_1)
            when (type) {
                "acTL" -> {
                    val frames = input.readInt()
                    return frames > 1
                }
                "IDAT", "IEND" -> return false
                else -> input.skipExactly(length + 4) // data and CRC
            }
        }
    }

    private fun isAnimatedWebp(input: InputStream): Boolean {
        val header = input.readExactly(12)
        if (String(header, 8, 4, Charsets.ISO_8859_1) != "WEBP") return false
        val chunk = input.readExactly(8)
        if (String(chunk, 0, 4, Charsets.ISO_8859_1) != "VP8X") return false
        val flags = input.read()
        return flags >= 0 && flags and 0x02 != 0
    }

    private fun InputStream.readExactly(count: Int): ByteArray {
        val buffer = ByteArray(count)
        var read = 0
        while (read < count) {
            val n = read(buffer, read, count - read)
            if (n < 0) throw EOFException()
            read += n
        }
        return buffer
    }

    private fun InputStream.skipExactly(count: Long) {
        var left = count
        while (left > 0) {
            val skipped = skip(left)
            if (skipped > 0) {
                left -= skipped
            } else {
                if (read() < 0) throw EOFException()
                left--
            }
        }
    }

    /** The file extension the app stores a file of [mime] under. */
    fun extension(mime: String): String = when (mime.substringBefore(';').trim().lowercase()) {
        "image/jpeg", "image/jpg" -> "jpg"
        "image/png", "image/apng" -> "png"
        "image/gif" -> "gif"
        "image/webp" -> "webp"
        "image/heic", "image/heif" -> "heic"
        "image/avif" -> "avif"
        "video/mp4" -> "mp4"
        "video/quicktime" -> "mov"
        "video/webm" -> "webm"
        "video/3gpp" -> "3gp"
        "video/mpeg" -> "mpg"
        "video/x-matroska" -> "mkv"
        "audio/mpeg", "audio/mp3" -> "mp3"
        "audio/mp4", "audio/m4a", "audio/x-m4a" -> "m4a"
        "audio/aac" -> "aac"
        "audio/ogg", "audio/vorbis", "audio/opus" -> "ogg"
        "audio/wav", "audio/x-wav", "audio/wave", "audio/vnd.wave" -> "wav"
        "audio/flac" -> "flac"
        "audio/webm" -> "weba"
        "audio/3gpp" -> "3gp"
        else -> "bin"
    }
}
