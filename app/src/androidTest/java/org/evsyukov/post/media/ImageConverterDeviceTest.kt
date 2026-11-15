package org.evsyukov.post.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.media.Conversion.ConvertImage
import org.evsyukov.post.media.convert.ImageConverter
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Images on a device (media.md A5, A6): orientation applied, location gone, limits met. */
@RunWith(AndroidJUnit4::class)
class ImageConverterDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(context.cacheDir, "image-converter-test").apply { deleteRecursively(); mkdirs() }
    private val converter = ImageConverter()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun aRotatedPhotoWithLocationBecomesAnUprightJpegWithoutLocationWithinTheLimits() = runBlocking<Unit> {
        val source = jpegWithExif(TestMedia.markedNoise(1600, 800), orientation = ExifInterface.ORIENTATION_ROTATE_90)
        val output = File(dir, "out.jpg")
        val conversion = ConvertImage("image/jpeg", maxLongSide = 1000, maxPixels = null, maxBytes = 200_000, stripMetadata = true)

        val result = converter.convert(source, conversion, output)

        assertThat(result.mime).isEqualTo("image/jpeg")
        assertThat(result.sizeBytes).isEqualTo(output.length())
        assertThat(output.length()).isAtMost(200_000L)
        assertNoMetadata(output)
        val bitmap = BitmapFactory.decodeFile(output.absolutePath)
        assertThat(bitmap.width to bitmap.height).isEqualTo(result.width to result.height)
        // Upright: the 1600 × 800 photo turned by 90° stands 800 × 1600, then fits 1000 on the long side.
        assertThat(bitmap.height).isGreaterThan(bitmap.width)
        assertThat(maxOf(bitmap.width, bitmap.height)).isAtMost(1000)
        assertThat(bitmap.width.toDouble() / bitmap.height).isWithin(0.01).of(0.5)
        // Turning clockwise takes the red top-left corner to the top right.
        assertRed(bitmap, bitmap.width * 9 / 10, bitmap.height / 20)
        assertNotRed(bitmap, bitmap.width / 10, bitmap.height / 20)
    }

    @Test
    fun theMetadataPassKeepsTypeAndSizeAndDropsLocation() = runBlocking<Unit> {
        val source = jpegWithExif(TestMedia.markedNoise(640, 480), orientation = ExifInterface.ORIENTATION_NORMAL)
        val output = File(dir, "out.jpg")
        val conversion = ConvertImage("image/jpeg", maxLongSide = null, maxPixels = null, maxBytes = 16_000_000, stripMetadata = true)

        val result = converter.convert(source, conversion, output)

        assertThat(result.mime).isEqualTo("image/jpeg")
        assertThat(result.width to result.height).isEqualTo(640 to 480)
        assertThat(output.length()).isLessThan(source.length())
        assertNoMetadata(output)
        val bitmap = BitmapFactory.decodeFile(output.absolutePath)
        assertThat(bitmap.width to bitmap.height).isEqualTo(640 to 480)
        assertRed(bitmap, 20, 20)
    }

    @Test
    fun theMetadataPassTurnsARotatedPhotoUpright() = runBlocking<Unit> {
        val source = jpegWithExif(TestMedia.markedNoise(640, 480), orientation = ExifInterface.ORIENTATION_ROTATE_90)
        val output = File(dir, "out.jpg")
        val conversion = ConvertImage("image/jpeg", maxLongSide = null, maxPixels = null, maxBytes = 16_000_000, stripMetadata = true)

        val result = converter.convert(source, conversion, output)

        assertThat(result.width to result.height).isEqualTo(480 to 640)
        assertNoMetadata(output)
        val bitmap = BitmapFactory.decodeFile(output.absolutePath)
        assertThat(bitmap.width to bitmap.height).isEqualTo(480 to 640)
        assertRed(bitmap, 460, 20)
    }

    @Test
    fun aLargePngWithAlphaStaysPngUnderTheLimit() = runBlocking<Unit> {
        val source = File(dir, "large.png")
        source.outputStream().use { TestMedia.translucentNoise(1500, 1500).compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertThat(source.length()).isGreaterThan(4_000_000L)
        val output = File(dir, "out.png")
        val conversion = ConvertImage("image/png", maxLongSide = 4000, maxPixels = null, maxBytes = 1_000_000, stripMetadata = true)

        val result = converter.convert(source, conversion, output)

        assertThat(result.mime).isEqualTo("image/png")
        assertThat(output.length()).isAtMost(1_000_000L)
        val signature = output.readBytes().copyOfRange(0, 8)
        assertThat(signature).isEqualTo(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        val bitmap = BitmapFactory.decodeFile(output.absolutePath)
        assertThat(bitmap.hasAlpha()).isTrue()
        val alphas = (0 until 50).map { Color.alpha(bitmap.getPixel(it * bitmap.width / 50, bitmap.height / 2)) }
        assertThat(alphas.any { it < 255 }).isTrue()
    }

    private fun jpegWithExif(bitmap: Bitmap, orientation: Int): File {
        val file = File(dir, "source-${System.nanoTime()}.jpg")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }
        ExifInterface(file).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            setAttribute(ExifInterface.TAG_MAKE, "TestCamera")
            setLatLong(55.7558, 37.6173)
            saveAttributes()
        }
        // The fixture really carries what the converter must remove.
        ExifInterface(file).apply {
            assertThat(latLong).isNotNull()
            assertThat(getAttributeInt(ExifInterface.TAG_ORIENTATION, 0)).isEqualTo(orientation)
        }
        return file
    }

    private fun assertNoMetadata(file: File) {
        val exif = ExifInterface(file)
        assertThat(exif.latLong).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_GPS_LATITUDE)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_GPS_LONGITUDE)).isNull()
        assertThat(exif.getAttribute(ExifInterface.TAG_MAKE)).isNull()
        assertThat(exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED))
            .isAnyOf(ExifInterface.ORIENTATION_UNDEFINED, ExifInterface.ORIENTATION_NORMAL)
        assertThat(String(file.readBytes(), Charsets.ISO_8859_1)).doesNotContain("Exif\u0000\u0000")
    }

    private fun assertRed(bitmap: Bitmap, x: Int, y: Int) {
        val pixel = bitmap.getPixel(x, y)
        assertThat(Color.red(pixel)).isGreaterThan(180)
        assertThat(Color.green(pixel)).isLessThan(90)
    }

    private fun assertNotRed(bitmap: Bitmap, x: Int, y: Int) {
        assertThat(Color.red(bitmap.getPixel(x, y))).isLessThan(120)
    }
}
