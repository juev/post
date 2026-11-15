package org.evsyukov.post.media.convert

import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.data.containsBytes
import org.junit.Test
import java.io.ByteArrayOutputStream

/** A6: the metadata pass drops EXIF, GPS, XMP and text without touching the image data. */
class MetadataStripperTest {

    @Test
    fun jpegLosesExifXmpCommentsAndTrailingImageButKeepsWhatDecodingNeeds() {
        val scan = intArrayOf(0x12, 0x34, 0xFF, 0x00, 0x56, 0xFF, 0xD0, 0x78)
        val jpeg = build {
            raw(0xFF, 0xD8)
            segment(0xE0, "JFIF\u0000\u0001\u0001")
            segment(0xE1, "Exif\u0000\u0000GPSLatitude55.75")
            segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000<x:xmpmeta/>")
            segment(0xE2, "ICC_PROFILE\u0000\u0001\u0001profile")
            segment(0xE2, "MPF\u0000multi")
            segment(0xED, "Photoshop 3.0\u0000IPTC")
            segment(0xEE, "Adobe\u0000d")
            segment(0xFE, "a comment")
            segment(0xDB, "quant")
            segment(0xDA, "scan")
            raw(*scan)
            raw(0xFF, 0xD9)
            raw(0xFF, 0xD8)
            segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000gainmap")
            raw(0xFF, 0xD9)
        }

        val stripped = MetadataStripper.strip(jpeg, "image/jpg")!!

        val expected = build {
            raw(0xFF, 0xD8)
            segment(0xE0, "JFIF\u0000\u0001\u0001")
            segment(0xE2, "ICC_PROFILE\u0000\u0001\u0001profile")
            segment(0xEE, "Adobe\u0000d")
            segment(0xDB, "quant")
            segment(0xDA, "scan")
            raw(*scan)
            raw(0xFF, 0xD9)
        }
        assertThat(stripped).isEqualTo(expected)
        assertThat(stripped.containsBytes("GPS".toByteArray())).isFalse()
    }

    @Test
    fun progressiveJpegKeepsEverySegmentBetweenScans() {
        val jpeg = build {
            raw(0xFF, 0xD8)
            segment(0xDA, "scan1")
            raw(0x01, 0x02, 0xFF, 0x00)
            segment(0xC4, "huffman")
            segment(0xE1, "Exif\u0000\u0000late")
            segment(0xDA, "scan2")
            raw(0x03, 0xFF, 0xFF, 0xD9)
        }

        val stripped = MetadataStripper.stripJpeg(jpeg)!!

        assertThat(stripped.containsBytes("huffman".toByteArray())).isTrue()
        assertThat(stripped.containsBytes("scan2".toByteArray())).isTrue()
        assertThat(stripped.containsBytes("Exif".toByteArray())).isFalse()
        assertThat(stripped.takeLast(2)).containsExactly(0xFF.toByte(), 0xD9.toByte()).inOrder()
    }

    @Test
    fun aTruncatedJpegIsNotParsed() {
        val jpeg = build {
            raw(0xFF, 0xD8)
            segment(0xE1, "Exif\u0000\u0000")
            segment(0xDA, "scan")
            raw(0x01, 0x02)
        }

        assertThat(MetadataStripper.stripJpeg(jpeg)).isNull()
        assertThat(MetadataStripper.stripJpeg(bytes(0xFF, 0xD8, 0xFF, 0xE1, 0x00, 0x40))).isNull()
    }

    @Test
    fun pngLosesExifTextAndTimeChunksAndKeepsTheRest() {
        val png = build {
            raw(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            chunk("IHDR", "header-bytes!")
            chunk("iCCP", "profile")
            chunk("eXIf", "MM\u0000*GPS")
            chunk("tEXt", "Comment\u0000hello")
            chunk("iTXt", "XML:com.adobe.xmp\u0000xmp")
            chunk("zTXt", "z")
            chunk("tIME", "1234567")
            chunk("acTL", "anim")
            chunk("IDAT", "pixels")
            chunk("IEND", "")
        }

        val stripped = MetadataStripper.strip(png, "image/png")!!

        val expected = build {
            raw(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
            chunk("IHDR", "header-bytes!")
            chunk("iCCP", "profile")
            chunk("acTL", "anim")
            chunk("IDAT", "pixels")
            chunk("IEND", "")
        }
        assertThat(stripped).isEqualTo(expected)
    }

    @Test
    fun webpLosesExifAndXmpChunksAndTheirFlags() {
        val vp8x = bytes(0x3C, 0, 0, 0, 9, 0, 0, 9, 0, 0)
        val webp = riff {
            webpChunk("VP8X", vp8x)
            webpChunk("ANIM", "anim".toByteArray())
            webpChunk("ANMF", "frame".toByteArray())
            webpChunk("EXIF", "MM\u0000*GPS".toByteArray())
            webpChunk("XMP ", "<x:xmpmeta/>".toByteArray())
        }

        val stripped = MetadataStripper.strip(webp, "image/webp")!!

        val expected = riff {
            webpChunk("VP8X", bytes(0x30, 0, 0, 0, 9, 0, 0, 9, 0, 0))
            webpChunk("ANIM", "anim".toByteArray())
            webpChunk("ANMF", "frame".toByteArray())
        }
        assertThat(stripped).isEqualTo(expected)
    }

    @Test
    fun theTypeIsTakenFromTheBytes() {
        assertThat(MetadataStripper.sniff(bytes(0xFF, 0xD8, 0xFF, 0xE0))).isEqualTo("image/jpeg")
        assertThat(MetadataStripper.sniff(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))).isEqualTo("image/png")
        assertThat(MetadataStripper.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray())).isEqualTo("image/webp")
        assertThat(MetadataStripper.sniff("GIF89a....".toByteArray())).isEqualTo("image/gif")
        assertThat(MetadataStripper.sniff("ftypheic....".toByteArray())).isNull()
        assertThat(MetadataStripper.strip("ftypheic....".toByteArray(), "image/heic")).isNull()
    }

    private class Builder {
        val out = ByteArrayOutputStream()

        fun raw(vararg values: Int) = values.forEach { out.write(it) }

        fun segment(marker: Int, data: String) {
            val payload = data.toByteArray(Charsets.ISO_8859_1)
            raw(0xFF, marker, (payload.size + 2) shr 8, (payload.size + 2) and 0xFF)
            out.write(payload)
        }

        fun chunk(type: String, data: String) {
            val payload = data.toByteArray(Charsets.ISO_8859_1)
            raw(payload.size shr 24, (payload.size shr 16) and 0xFF, (payload.size shr 8) and 0xFF, payload.size and 0xFF)
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(payload)
            raw(0xDE, 0xAD, 0xBE, 0xEF)
        }

        fun webpChunk(fourCc: String, data: ByteArray) {
            out.write(fourCc.toByteArray(Charsets.US_ASCII))
            raw(data.size and 0xFF, (data.size shr 8) and 0xFF, (data.size shr 16) and 0xFF, data.size shr 24)
            out.write(data)
            if (data.size % 2 == 1) raw(0)
        }
    }

    private fun build(block: Builder.() -> Unit): ByteArray = Builder().apply(block).out.toByteArray()

    private fun riff(block: Builder.() -> Unit): ByteArray {
        val body = build(block)
        return build {
            out.write("RIFF".toByteArray())
            val size = body.size + 4
            raw(size and 0xFF, (size shr 8) and 0xFF, (size shr 16) and 0xFF, size shr 24)
            out.write("WEBP".toByteArray())
            out.write(body)
        }
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
}
