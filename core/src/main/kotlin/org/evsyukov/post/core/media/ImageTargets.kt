package org.evsyukov.post.core.media

import org.evsyukov.post.core.media.Conversion.ConvertImage

/**
 * The conversion an image needs to meet a network's limits (media.md A5).
 *
 * An image is converted when the network does not take its type (HEIC, HEIF, AVIF and others) or
 * when it is above the size or resolution limit: [NetworkMediaRules.maxImageBytes],
 * [NetworkMediaRules.maxImagePixels] (Mastodon `image_matrix_limit`) or
 * [NetworkMediaRules.maxImageLongSide] (Bluesky 4000 px). The output is JPEG, or PNG when the image
 * has transparency, downscaled to the network's resolution limits and recompressed until it fits
 * the size limit. An unknown width or height is not checked against the resolution limits; the
 * converter applies them to the decoded image anyway.
 */
object ImageTargets {
    const val JPEG = "image/jpeg"
    const val PNG = "image/png"

    /** The conversion the image needs, or null when it already meets the network's limits. */
    fun target(
        width: Int?,
        height: Int?,
        sizeBytes: Long,
        mime: String,
        rules: NetworkMediaRules,
        hasTransparency: Boolean = false,
        stripMetadata: Boolean = true,
    ): ConvertImage? {
        val fits = rules.acceptsImage(mime) &&
            sizeBytes <= rules.maxImageBytes &&
            withinResolution(width, height, rules)
        if (fits) return null
        return ConvertImage(
            targetMime = if (hasTransparency) PNG else JPEG,
            maxLongSide = rules.maxImageLongSide,
            maxPixels = rules.maxImagePixels,
            maxBytes = rules.maxImageBytes,
            stripMetadata = stripMetadata,
        )
    }

    private fun withinResolution(width: Int?, height: Int?, rules: NetworkMediaRules): Boolean {
        if (width == null || height == null) return true
        val pixelsOk = rules.maxImagePixels?.let { width.toLong() * height <= it } ?: true
        val longSideOk = rules.maxImageLongSide?.let { maxOf(width, height) <= it } ?: true
        return pixelsOk && longSideOk
    }
}
