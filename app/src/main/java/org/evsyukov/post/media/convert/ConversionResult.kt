package org.evsyukov.post.media.convert

/** A file a conversion wrote: its type, size in bytes and pixel size as shown (orientation applied). */
data class ConvertedFile(
    val mime: String,
    val sizeBytes: Long,
    val width: Int?,
    val height: Int?,
)

/**
 * Why a conversion failed (media.md A4: a failed conversion shows up while the user is still in the
 * composer). The sender shows the kind; the message is for logs.
 */
sealed class ConversionException(message: String, cause: Throwable? = null) : Exception(message, cause) {

    /** The output stays above [maxBytes] after every attempt; [actualBytes] is the smallest one made. */
    class CannotFit(val maxBytes: Long, val actualBytes: Long) :
        ConversionException("the output of $actualBytes bytes does not fit $maxBytes bytes")

    /** Media3 Transformer failed; [errorCode] is the name of its `ExportException` code, when it gave one. */
    class TranscodeFailed(val errorCode: String?, cause: Throwable? = null) :
        ConversionException("video transcoding failed: ${errorCode ?: cause?.message}", cause)

    /** The image cannot be decoded or encoded to the target type. */
    class ImageFailed(message: String, cause: Throwable? = null) : ConversionException(message, cause)
}

internal const val MIME_JPEG = "image/jpeg"
internal const val MIME_PNG = "image/png"
internal const val MIME_WEBP = "image/webp"
internal const val MIME_GIF = "image/gif"
internal const val MIME_MP4 = "video/mp4"

/** A MIME type in lower case without parameters, with the common `image/jpg` spelled `image/jpeg`. */
internal fun normalizeMime(mime: String): String {
    val bare = mime.substringBefore(';').trim().lowercase()
    return if (bare == "image/jpg") MIME_JPEG else bare
}
