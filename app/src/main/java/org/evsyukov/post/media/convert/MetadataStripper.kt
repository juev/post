package org.evsyukov.post.media.convert

import java.io.ByteArrayOutputStream

/**
 * Removes metadata from a JPEG, PNG or WebP file without touching its pixels (media.md A6), for an
 * image that already fits the network and only loses its metadata. The image data is copied byte for
 * byte, so an animated WebP or APNG keeps its frames.
 *
 * Dropped: EXIF (with GPS and orientation), XMP, IPTC, comments and text chunks, and the data after
 * the end of a JPEG (the second image of a multi-picture or Ultra HDR file, with its own XMP). Kept:
 * what decoding needs, such as the JFIF header, the ICC colour profile and the Adobe marker.
 *
 * Every function returns null for a file it cannot parse; the caller then decodes and encodes it.
 * The caller also applies the orientation first: once its tag is gone, a rotated photo would lie on
 * its side.
 */
object MetadataStripper {

    fun strip(bytes: ByteArray, mime: String): ByteArray? = when (normalizeMime(mime)) {
        MIME_JPEG -> stripJpeg(bytes)
        MIME_PNG -> stripPng(bytes)
        MIME_WEBP -> stripWebp(bytes)
        else -> null
    }

    /** The MIME type by the file's signature: JPEG, PNG, WebP, GIF, or null. */
    fun sniff(bytes: ByteArray): String? = when {
        bytes.size >= 3 && bytes.u8(0) == 0xFF && bytes.u8(1) == 0xD8 && bytes.u8(2) == 0xFF -> MIME_JPEG
        bytes.size >= 8 && bytes.copyOfRange(0, 8).contentEquals(PNG_SIGNATURE) -> MIME_PNG
        bytes.size >= 12 && bytes.ascii(0, 4) == "RIFF" && bytes.ascii(8, 4) == "WEBP" -> MIME_WEBP
        bytes.size >= 6 && (bytes.ascii(0, 6) == "GIF87a" || bytes.ascii(0, 6) == "GIF89a") -> MIME_GIF
        else -> null
    }

    fun stripJpeg(bytes: ByteArray): ByteArray? {
        if (sniff(bytes) != MIME_JPEG) return null
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 2)
        var pos = 2
        while (pos < bytes.size) {
            if (bytes.u8(pos) != 0xFF) return null
            // Any number of 0xFF may pad the space before a marker.
            while (pos + 1 < bytes.size && bytes.u8(pos + 1) == 0xFF) pos++
            if (pos + 1 >= bytes.size) return null
            val marker = bytes.u8(pos + 1)
            when {
                marker == EOI -> {
                    out.write(bytes, pos, 2)
                    return out.toByteArray()
                }
                marker in RST0..RST7 || marker == TEM -> {
                    out.write(bytes, pos, 2)
                    pos += 2
                    continue
                }
            }
            if (pos + 4 > bytes.size) return null
            val length = bytes.u16be(pos + 2)
            val end = pos + 2 + length
            if (length < 2 || end > bytes.size) return null
            if (keepJpegSegment(marker, bytes, pos + 4, end)) out.write(bytes, pos, end - pos)
            pos = end
            if (marker == SOS) {
                // Entropy-coded data runs to the next marker other than stuffing and restarts.
                val start = pos
                while (pos + 1 < bytes.size) {
                    if (bytes.u8(pos) == 0xFF) {
                        val next = bytes.u8(pos + 1)
                        if (next != 0x00 && next !in RST0..RST7 && next != 0xFF) break
                    }
                    pos++
                }
                if (pos + 1 >= bytes.size) return null
                out.write(bytes, start, pos - start)
            }
        }
        return null
    }

    private fun keepJpegSegment(marker: Int, bytes: ByteArray, dataStart: Int, end: Int): Boolean = when (marker) {
        COM -> false
        APP0 -> true
        APP2 -> startsWith(bytes, dataStart, end, "ICC_PROFILE\u0000")
        APP14 -> startsWith(bytes, dataStart, end, "Adobe")
        in APP1..APP15 -> false
        else -> true
    }

    fun stripPng(bytes: ByteArray): ByteArray? {
        if (sniff(bytes) != MIME_PNG) return null
        val out = ByteArrayOutputStream(bytes.size)
        out.write(bytes, 0, 8)
        var pos = 8
        while (pos + 12 <= bytes.size) {
            val length = bytes.u32be(pos)
            if (length < 0 || pos + 12L + length > bytes.size) return null
            val type = bytes.ascii(pos + 4, 4)
            val end = pos + 12 + length.toInt()
            if (type !in PNG_DROPPED) out.write(bytes, pos, end - pos)
            pos = end
            if (type == "IEND") return out.toByteArray()
        }
        return null
    }

    fun stripWebp(bytes: ByteArray): ByteArray? {
        if (sniff(bytes) != MIME_WEBP) return null
        val riffEnd = 8L + bytes.u32le(4)
        if (riffEnd > bytes.size || riffEnd < 12) return null
        val chunks = ByteArrayOutputStream(bytes.size)
        var pos = 12
        while (pos + 8 <= riffEnd) {
            val fourCc = bytes.ascii(pos, 4)
            val size = bytes.u32le(pos + 4)
            val padded = size + (size and 1)
            if (pos + 8L + padded > riffEnd) return null
            val end = pos + 8 + padded.toInt()
            when (fourCc) {
                "EXIF", "XMP " -> Unit
                "VP8X" -> {
                    if (size < 10) return null
                    val copy = bytes.copyOfRange(pos, end)
                    // The flags say which metadata chunks follow; they no longer do.
                    copy[8] = (copy[8].toInt() and (VP8X_EXIF or VP8X_XMP).inv()).toByte()
                    chunks.write(copy)
                }
                else -> chunks.write(bytes, pos, end - pos)
            }
            pos = end
        }
        if (pos.toLong() != riffEnd) return null
        val body = chunks.toByteArray()
        val out = ByteArrayOutputStream(body.size + 12)
        out.write("RIFF".toByteArray(Charsets.US_ASCII))
        out.write(le32(body.size + 4))
        out.write("WEBP".toByteArray(Charsets.US_ASCII))
        out.write(body)
        return out.toByteArray()
    }

    private fun startsWith(bytes: ByteArray, start: Int, end: Int, prefix: String): Boolean {
        val p = prefix.toByteArray(Charsets.ISO_8859_1)
        return end - start >= p.size && p.indices.all { bytes[start + it] == p[it] }
    }

    private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF
    private fun ByteArray.u16be(at: Int): Int = (u8(at) shl 8) or u8(at + 1)
    private fun ByteArray.u32be(at: Int): Long =
        (u8(at).toLong() shl 24) or (u8(at + 1).toLong() shl 16) or (u8(at + 2).toLong() shl 8) or u8(at + 3).toLong()
    private fun ByteArray.u32le(at: Int): Long =
        (u8(at + 3).toLong() shl 24) or (u8(at + 2).toLong() shl 16) or (u8(at + 1).toLong() shl 8) or u8(at).toLong()
    private fun ByteArray.ascii(at: Int, length: Int): String = String(this, at, length, Charsets.ISO_8859_1)
    private fun le32(value: Int): ByteArray =
        byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte(), (value shr 24).toByte())

    private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

    /** Ancillary PNG chunks with metadata: EXIF, text of any kind, the modification time. */
    private val PNG_DROPPED = setOf("eXIf", "tEXt", "zTXt", "iTXt", "tIME")

    private const val SOS = 0xDA
    private const val EOI = 0xD9
    private const val TEM = 0x01
    private const val RST0 = 0xD0
    private const val RST7 = 0xD7
    private const val APP0 = 0xE0
    private const val APP1 = 0xE1
    private const val APP2 = 0xE2
    private const val APP14 = 0xEE
    private const val APP15 = 0xEF
    private const val COM = 0xFE
    private const val VP8X_EXIF = 0x08
    private const val VP8X_XMP = 0x04
}
