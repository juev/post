package org.evsyukov.post.media.prepare

import androidx.room.withTransaction
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import org.evsyukov.post.core.media.Conversion
import org.evsyukov.post.core.media.makesVariant
import org.evsyukov.post.data.db.AttachmentEntity
import org.evsyukov.post.data.db.PostDatabase
import org.evsyukov.post.data.db.VariantEntity
import org.evsyukov.post.data.posts.FilesDirAttachmentFiles
import org.evsyukov.post.media.convert.MediaConverter
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest
import java.time.Clock

/**
 * One file the sender needs: attachment [attachmentId] converted by [conversion] for [profileKey].
 * [profileKey] is the caller's name for the request (a network or an account) and comes back in
 * [PreparedMedia]; the stored variant is keyed by the conversion itself, see [MediaPreparer].
 */
data class VariantRequest(
    val attachmentId: Long,
    val profileKey: String,
    val conversion: Conversion,
)

/**
 * The file to upload for a [VariantRequest]: a variant or the original. [path] is relative to the
 * app's files directory, as in the database; [mime] is the type to upload it with.
 */
data class PreparedMedia(
    val attachmentId: Long,
    val profileKey: String,
    val path: String,
    val mime: String,
    val size: Long,
    val width: Int?,
    val height: Int?,
)

/**
 * Prepares the files a post uploads (media.md A2, A4): converts attachments into variants under
 * `variants/<postId>/` in the files directory and records them in the `variants` table.
 *
 * - [Conversion.AsIs] and [Conversion.GifAsVideo] use the original file and make no variant; the GIF
 *   goes to the Bluesky video service as `image/gif` (A8).
 * - A variant row is keyed by the attachment and a key of the conversion ([profileKeyOf]), so requests
 *   with the same attachment and the same conversion, such as the accounts of one network, share one
 *   file, and a conversion that changed (new limits) makes a new variant instead of reusing a stale one.
 * - [prepare] is idempotent: a variant whose file still exists is reused; one whose file is gone is
 *   made again. A conversion that fails or is cancelled leaves neither a row nor a file.
 */
class MediaPreparer(
    private val db: PostDatabase,
    filesDir: File,
    private val converter: MediaConverter,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val root: File = filesDir.canonicalFile
    private val files = FilesDirAttachmentFiles(root)
    private val dao = db.attachmentDao()

    /** One preparation at a time, so two calls never convert the same variant twice. */
    private val mutex = Mutex()

    /**
     * Returns the file of every request, in the order of [requests]. [onProgress] gets 0..100 over all
     * conversions this call has to run.
     *
     * @throws IllegalArgumentException when a request names an attachment that is not of [postId].
     * @throws FileNotFoundException when an original file is missing.
     * @throws org.evsyukov.post.media.convert.ConversionException when a conversion fails.
     */
    suspend fun prepare(postId: Long, requests: List<VariantRequest>, onProgress: (Int) -> Unit = {}): List<PreparedMedia> =
        mutex.withLock {
            val attachments = dao.forPost(postId).associateBy { it.id }
            for (request in requests) {
                require(request.attachmentId in attachments) { "attachment ${request.attachmentId} is not of post $postId" }
            }
            val jobs = requests
                .filter { it.conversion.makesVariant }
                .map { it.attachmentId to it.conversion }
                .distinct()
            val made = HashMap<Pair<Long, Conversion>, VariantEntity>()
            jobs.forEachIndexed { index, (attachmentId, conversion) ->
                made[attachmentId to conversion] = variant(postId, attachments.getValue(attachmentId), conversion) { percent ->
                    onProgress((index * 100 + percent.coerceIn(0, 100)) / jobs.size)
                }
            }
            onProgress(100)
            requests.map { request ->
                val attachment = attachments.getValue(request.attachmentId)
                when (val conversion = request.conversion) {
                    Conversion.AsIs -> attachment.prepared(request.profileKey, attachment.mime)
                    Conversion.GifAsVideo -> attachment.prepared(request.profileKey, GIF)
                    else -> made.getValue(request.attachmentId to conversion).let {
                        PreparedMedia(it.attachmentId, request.profileKey, it.path, it.mime, it.sizeBytes, it.width, it.height)
                    }
                }
            }
        }

    /** Deletes every variant of [postId] with its file; the originals stay. */
    suspend fun deleteVariants(postId: Long) {
        mutex.withLock {
            val paths = db.withTransaction {
                val paths = dao.variantPathsOfPost(postId)
                db.openHelper.writableDatabase.execSQL(
                    "DELETE FROM variants WHERE attachmentId IN (SELECT id FROM attachments WHERE postId = ?)",
                    arrayOf<Any>(postId),
                )
                paths
            }
            files.delete(paths)
            File(root, variantDir(postId)).deleteRecursively()
        }
    }

    private suspend fun variant(postId: Long, attachment: AttachmentEntity, conversion: Conversion, onProgress: (Int) -> Unit): VariantEntity {
        val key = profileKeyOf(conversion)
        val existing = dao.variant(attachment.id, key)
        if (existing != null && files.resolve(existing.path)?.isFile == true) {
            onProgress(100)
            return existing
        }
        val source = files.resolve(attachment.path)?.takeIf { it.isFile }
            ?: throw FileNotFoundException("the original of attachment ${attachment.id} is missing")
        val dir = File(root, variantDir(postId)).apply { mkdirs() }
        val partial = File(dir, "${attachment.id}-$key.part")
        try {
            val result = converter.convert(source, conversion, partial, onProgress)
            return withContext(NonCancellable) {
                val name = "${attachment.id}-$key.${extension(result.mime)}"
                val final = File(dir, name)
                if (!partial.renameTo(final)) throw IOException("cannot move the variant into place")
                val entity = VariantEntity(
                    attachmentId = attachment.id,
                    profileKey = key,
                    path = "${variantDir(postId)}/$name",
                    mime = result.mime,
                    sizeBytes = final.length(),
                    width = result.width,
                    height = result.height,
                    createdAt = clock.millis(),
                )
                try {
                    dao.putVariant(entity)
                } catch (e: Exception) {
                    final.delete()
                    throw e
                }
                existing?.path?.takeIf { it != entity.path }?.let { files.delete(listOf(it)) }
                entity
            }
        } finally {
            partial.delete()
        }
    }

    private fun AttachmentEntity.prepared(profileKey: String, mime: String) =
        PreparedMedia(id, profileKey, path, mime, sizeBytes, width, height)

    companion object {
        private const val GIF = "image/gif"

        private fun variantDir(postId: Long) = "variants/$postId"

        /**
         * The key a variant of [conversion] is stored under: its kind and a hash of all its targets, so
         * equal conversions share a variant and different ones never do.
         */
        fun profileKeyOf(conversion: Conversion): String {
            val kind = when (conversion) {
                is Conversion.ConvertImage -> "image"
                is Conversion.TranscodeVideo -> "video"
                Conversion.AsIs -> "as-is"
                Conversion.GifAsVideo -> "gif-as-video"
            }
            val digest = MessageDigest.getInstance("SHA-256").digest(Json.encodeToString<Conversion>(conversion).toByteArray())
            return kind + "-" + digest.take(8).joinToString("") { "%02x".format(it) }
        }

        private fun extension(mime: String): String = when (mime.substringBefore(';').trim().lowercase()) {
            "image/jpeg" -> "jpg"
            "image/png" -> "png"
            "image/webp" -> "webp"
            "image/gif" -> "gif"
            "video/mp4" -> "mp4"
            else -> "bin"
        }
    }
}
