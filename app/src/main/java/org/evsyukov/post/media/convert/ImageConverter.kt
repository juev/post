package org.evsyukov.post.media.convert

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.os.Build
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import androidx.exifinterface.media.ExifInterface
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.evsyukov.post.core.media.Conversion.ConvertImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Performs [ConvertImage] (media.md A5, A6).
 *
 * An image that already fits loses only its metadata, byte for byte ([MetadataStripper]): this is the
 * metadata pass, which keeps the type, the pixel size and the frames of an animation. Any other image
 * is decoded with [ImageDecoder], which turns it upright by its EXIF orientation, downscaled to
 * [ConvertImage.maxLongSide] and [ConvertImage.maxPixels], and encoded as the target type, lowering
 * the quality and then the size until the file fits [ConvertImage.maxBytes]. An encoded bitmap has
 * no EXIF or XMP at all.
 */
class ImageConverter {

    /**
     * Writes the converted image to [output].
     *
     * @throws ConversionException.CannotFit when even the smallest attempt stays above the limit.
     * @throws ConversionException.ImageFailed when the image cannot be decoded or the target type is unknown.
     */
    suspend fun convert(source: File, conversion: ConvertImage, output: File): ConvertedFile = withContext(Dispatchers.IO) {
        val bytes = try {
            source.readBytes()
        } catch (e: IOException) {
            throw ConversionException.ImageFailed("cannot read the image", e)
        }
        val target = normalizeMime(conversion.targetMime)
        if (conversion.stripMetadata && MetadataStripper.sniff(bytes) == target) {
            stripOnly(source, bytes, target, conversion)?.let { stripped ->
                output.writeBytes(stripped.first)
                return@withContext stripped.second
            }
        }
        reencode(source, target, conversion, output)
    }

    /** The metadata pass: the stripped bytes when the image is upright and already fits, else null. */
    private fun stripOnly(source: File, bytes: ByteArray, mime: String, conversion: ConvertImage): Pair<ByteArray, ConvertedFile>? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        if (fitSize(width, height, conversion.maxLongSide, conversion.maxPixels) != width to height) return null
        val upright = try {
            val exif = ExifInterface(source)
            exif.rotationDegrees == 0 && !exif.isFlipped
        } catch (_: IOException) {
            false
        }
        if (!upright) return null
        val stripped = MetadataStripper.strip(bytes, mime) ?: return null
        if (stripped.size > conversion.maxBytes) return null
        return stripped to ConvertedFile(mime, stripped.size.toLong(), width, height)
    }

    private suspend fun reencode(source: File, target: String, conversion: ConvertImage, output: File): ConvertedFile {
        val format = when (target) {
            MIME_JPEG -> Bitmap.CompressFormat.JPEG
            MIME_PNG -> Bitmap.CompressFormat.PNG
            MIME_WEBP -> Bitmap.CompressFormat.WEBP_LOSSY
            else -> throw ConversionException.ImageFailed("cannot encode $target")
        }
        var bitmap = decode(source, conversion)
        if (format == Bitmap.CompressFormat.JPEG && bitmap.hasAlpha()) bitmap = flatten(bitmap)
        var smallest = Long.MAX_VALUE
        repeat(MAX_DOWNSCALES + 1) {
            coroutineContext.ensureActive()
            val qualities = if (format == Bitmap.CompressFormat.PNG) listOf(100) else QUALITIES
            var encoded: ByteArray? = null
            for (quality in qualities) {
                coroutineContext.ensureActive()
                encoded = compress(bitmap, format, quality)
                if (encoded.size <= conversion.maxBytes) {
                    output.writeBytes(encoded)
                    return ConvertedFile(target, encoded.size.toLong(), bitmap.width, bitmap.height)
                }
            }
            val size = encoded!!.size.toLong()
            smallest = minOf(smallest, size)
            // The bytes shrink about with the pixel count; aim a little below the limit.
            val factor = (sqrt(conversion.maxBytes.toDouble() / size) * 0.9).coerceIn(0.5, 0.9)
            val width = floor(bitmap.width * factor).toInt()
            val height = floor(bitmap.height * factor).toInt()
            if (width < MIN_SIDE || height < MIN_SIDE) throw ConversionException.CannotFit(conversion.maxBytes, smallest)
            bitmap = bitmap.scale(width, height)
        }
        throw ConversionException.CannotFit(conversion.maxBytes, smallest)
    }

    private fun decode(source: File, conversion: ConvertImage): Bitmap {
        val bitmap = try {
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.isMutableRequired = true
                // The size ImageDecoder reports is the upright one; it turns the image by its EXIF orientation.
                val (width, height) = fitSize(info.size.width, info.size.height, conversion.maxLongSide, conversion.maxPixels)
                if (width != info.size.width || height != info.size.height) decoder.setTargetSize(width, height)
            }
        } catch (e: IOException) {
            throw ConversionException.ImageFailed("cannot decode the image", e)
        } catch (e: IllegalArgumentException) {
            throw ConversionException.ImageFailed("cannot decode the image", e)
        }
        // An Ultra HDR gain map would be written back with its XMP; the upload is a plain image.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE && bitmap.hasGainmap()) bitmap.gainmap = null
        return bitmap
    }

    /** JPEG has no alpha: transparent pixels go onto white instead of black. */
    private fun flatten(bitmap: Bitmap): Bitmap {
        val opaque = createBitmap(bitmap.width, bitmap.height)
        Canvas(opaque).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return opaque
    }

    private fun compress(bitmap: Bitmap, format: Bitmap.CompressFormat, quality: Int): ByteArray {
        val out = ByteArrayOutputStream()
        if (!bitmap.compress(format, quality, out)) throw ConversionException.ImageFailed("cannot encode the image as $format")
        return out.toByteArray()
    }

    companion object {
        /** JPEG and WebP qualities tried in turn before the image is made smaller. */
        internal val QUALITIES = listOf(90, 82, 74, 66, 58, 50)

        /** How many times the image is made smaller before the converter gives up. */
        internal const val MAX_DOWNSCALES = 10

        private const val MIN_SIDE = 16

        /**
         * The largest size of [width] × [height], aspect kept, that fits [maxLongSide] and [maxPixels];
         * an image within both keeps its size (never scaled up).
         */
        fun fitSize(width: Int, height: Int, maxLongSide: Int?, maxPixels: Long?): Pair<Int, Int> {
            var scale = 1.0
            if (maxLongSide != null && max(width, height) > maxLongSide) {
                scale = minOf(scale, maxLongSide.toDouble() / max(width, height))
            }
            if (maxPixels != null && width.toLong() * height > maxPixels) {
                scale = minOf(scale, sqrt(maxPixels.toDouble() / (width.toLong() * height)))
            }
            if (scale >= 1.0) return width to height
            // The epsilon keeps 4000 from becoming 3999 through rounding; the loop below undoes any excess.
            var w = floor(width * scale + 1e-6).toInt().coerceAtLeast(1)
            var h = floor(height * scale + 1e-6).toInt().coerceAtLeast(1)
            while ((maxLongSide != null && max(w, h) > maxLongSide) || (maxPixels != null && w.toLong() * h > maxPixels)) {
                if (w >= h) w-- else h--
                if (w < 1 || h < 1) return 1 to 1
            }
            return w to h
        }
    }
}
