package org.evsyukov.post.media.importer

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/** Signatures, animation and the choice between the resolver's type and the sniffed one (R9, media.md A1, A8). */
class MediaSnifferTest {
    @get:Rule
    val temp = TemporaryFolder()

    private fun fixture(name: String): File = File(requireNotNull(javaClass.getResource("/media/$name")).toURI())

    private fun sniff(name: String) = MediaSniffer.sniff(MediaSniffer.head(fixture(name)))

    @Test
    fun everyFixtureIsRecognizedByItsBytes() {
        assertThat(sniff("photo_exif_rot90.jpg")).isEqualTo("image/jpeg")
        assertThat(sniff("alpha.png")).isEqualTo("image/png")
        assertThat(sniff("animated.gif")).isEqualTo("image/gif")
        assertThat(sniff("still.gif")).isEqualTo("image/gif")
        assertThat(sniff("clip.mp4")).isEqualTo("video/mp4")
        assertThat(sniff("tone.mp3")).isEqualTo("audio/mpeg")
    }

    @Test
    fun otherContainersAndUnknownBytes() {
        fun ftyp(brand: String) = byteArrayOf(0, 0, 0, 24) + "ftyp$brand".toByteArray() + ByteArray(12)
        assertThat(MediaSniffer.sniff(ftyp("heic"))).isEqualTo("image/heic")
        assertThat(MediaSniffer.sniff(ftyp("avif"))).isEqualTo("image/avif")
        assertThat(MediaSniffer.sniff(ftyp("qt  "))).isEqualTo("video/quicktime")
        assertThat(MediaSniffer.sniff(ftyp("M4A "))).isEqualTo("audio/mp4")
        assertThat(MediaSniffer.sniff("RIFF\u0000\u0000\u0000\u0000WEBPVP8 ".toByteArray())).isEqualTo("image/webp")
        assertThat(MediaSniffer.sniff("OggS\u0000".toByteArray())).isEqualTo("audio/ogg")
        assertThat(MediaSniffer.sniff(byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte()))).isEqualTo("video/webm")
        assertThat(MediaSniffer.sniff("hello, world".toByteArray())).isNull()
        assertThat(MediaSniffer.sniff(ByteArray(0))).isNull()
    }

    @Test
    fun aGifIsAnimatedOnlyWithMoreThanOneFrame() {
        assertThat(MediaSniffer.isAnimated(fixture("animated.gif"), "image/gif")).isTrue()
        assertThat(MediaSniffer.isAnimated(fixture("still.gif"), "image/gif")).isFalse()
        assertThat(MediaSniffer.gifFrames(ByteArrayInputStream(fixture("animated.gif").readBytes()))).isEqualTo(3)
        assertThat(MediaSniffer.isAnimated(fixture("alpha.png"), "image/png")).isFalse()
        assertThat(MediaSniffer.isAnimated(fixture("photo_exif_rot90.jpg"), "image/jpeg")).isFalse()
    }

    @Test
    fun anApngHasAnAnimationControlChunkBeforeItsImageData() {
        val still = fixture("alpha.png").readBytes()
        // acTL with 2 frames inserted after IHDR (8 signature + 25 IHDR bytes).
        val actl = byteArrayOf(0, 0, 0, 8) + "acTL".toByteArray() + byteArrayOf(0, 0, 0, 2, 0, 0, 0, 0) + ByteArray(4)
        val apng = temp.newFile("a.png").apply { writeBytes(still.copyOfRange(0, 33) + actl + still.copyOfRange(33, still.size)) }
        assertThat(MediaSniffer.isAnimated(apng, "image/png")).isTrue()
        val oneFrame = actl.copyOf().also { it[11] = 1 }
        val single = temp.newFile("b.png").apply { writeBytes(still.copyOfRange(0, 33) + oneFrame + still.copyOfRange(33, still.size)) }
        assertThat(MediaSniffer.isAnimated(single, "image/png")).isFalse()
    }

    @Test
    fun aWebpIsAnimatedByTheFlagOfItsExtendedHeader() {
        fun webp(flags: Int) = "RIFF".toByteArray() + ByteArray(4) + "WEBPVP8X".toByteArray() + byteArrayOf(10, 0, 0, 0, flags.toByte()) + ByteArray(9)
        assertThat(MediaSniffer.isAnimated(temp.newFile("a.webp").apply { writeBytes(webp(0x02)) }, "image/webp")).isTrue()
        assertThat(MediaSniffer.isAnimated(temp.newFile("b.webp").apply { writeBytes(webp(0x10)) }, "image/webp")).isFalse()
    }

    @Test
    fun theResolverTypeWinsWhenItNamesAMediaType() {
        assertThat(AttachmentImporter.chooseMime("image/heic", "image/jpeg")).isEqualTo("image/heic")
        assertThat(AttachmentImporter.chooseMime("Video/MP4; codecs=avc1", null)).isEqualTo("video/mp4")
        // No type, a generic one or a wildcard: the bytes decide.
        assertThat(AttachmentImporter.chooseMime(null, "image/png")).isEqualTo("image/png")
        assertThat(AttachmentImporter.chooseMime("application/octet-stream", "audio/mpeg")).isEqualTo("audio/mpeg")
        assertThat(AttachmentImporter.chooseMime("image/*", "image/gif")).isEqualTo("image/gif")
        // Neither is a media type.
        assertThat(AttachmentImporter.chooseMime("text/plain", null)).isNull()
        assertThat(AttachmentImporter.chooseMime(null, null)).isNull()
    }

    @Test
    fun extensionsFollowTheType() {
        assertThat(MediaSniffer.extension("image/jpeg")).isEqualTo("jpg")
        assertThat(MediaSniffer.extension("video/quicktime")).isEqualTo("mov")
        assertThat(MediaSniffer.extension("audio/mpeg")).isEqualTo("mp3")
        assertThat(MediaSniffer.extension("application/x-unknown")).isEqualTo("bin")
    }
}
