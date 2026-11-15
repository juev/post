package org.evsyukov.post.media.importer

import android.content.ContentResolver
import android.net.Uri
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.evsyukov.post.data.posts.Attachment
import java.io.File
import java.util.UUID
import kotlin.coroutines.coroutineContext

/**
 * Copies picked or shared files into app storage at once and reads their metadata (R9, media.md A1), so
 * a later send does not depend on the source app's URI grant. The composer and the Share sheet use it.
 *
 * Each file lands in `attachments/<uuid>.<ext>` under [filesDir]; [Attachment.path] holds that relative
 * path. The importer only makes the files and their rows: the caller saves the rows with the draft and
 * deletes the file of a row it drops before saving.
 */
class AttachmentImporter(
    private val resolver: ContentResolver,
    private val filesDir: File,
    private val probe: MediaProbe = MediaProbe(),
) {
    /** Why a file could not be added. */
    enum class Reason {
        /** The source could not be opened or read, or app storage could not take the copy. */
        UNREADABLE,

        /** The file is no image, video or audio file. */
        UNSUPPORTED,
    }

    data class Failure(val uri: Uri, val reason: Reason)

    /** The rows of the files that were added, in the order of the input, and the files that were not. */
    data class Result(val attachments: List<Attachment>, val failures: List<Failure>)

    /**
     * Copies each of [uris] and reads its metadata; every attachment belongs to [pieceIndex], the piece of the
     * text the cursor was in (threads.md T14). A file that fails leaves nothing behind and does not stop the
     * others. The rows are new ([Attachment.id] 0).
     */
    suspend fun import(uris: List<Uri>, pieceIndex: Int): Result = withContext(Dispatchers.IO) {
        val attachments = ArrayList<Attachment>()
        val failures = ArrayList<Failure>()
        for (uri in uris) {
            coroutineContext.ensureActive()
            when (val one = importOne(uri, pieceIndex)) {
                is One.Added -> attachments += one.attachment
                is One.Failed -> failures += Failure(uri, one.reason)
            }
        }
        Result(attachments, failures)
    }

    /** The file of a stored [path], under the files directory. */
    fun file(path: String): File = File(filesDir, path)

    /** One file: its row, or why it failed. */
    private sealed interface One {
        data class Added(val attachment: Attachment) : One
        data class Failed(val reason: Reason) : One
    }

    private fun importOne(uri: Uri, pieceIndex: Int): One {
        val dir = File(filesDir, DIR)
        val name = UUID.randomUUID().toString()
        val partial = File(dir, "$name.part")
        var target: File? = null
        fun fail(reason: Reason): One {
            partial.delete()
            target?.delete()
            return One.Failed(reason)
        }
        try {
            dir.mkdirs()
            val input = resolver.openInputStream(uri) ?: return fail(Reason.UNREADABLE)
            input.use { source -> partial.outputStream().use { source.copyTo(it) } }
            val mime = chooseMime(resolverType(uri), MediaSniffer.sniff(MediaSniffer.head(partial))) ?: return fail(Reason.UNSUPPORTED)
            val file = File(dir, "$name.${MediaSniffer.extension(mime)}")
            if (!partial.renameTo(file)) return fail(Reason.UNREADABLE)
            target = file
            val probed = probe.probe(file, mime) ?: return fail(Reason.UNSUPPORTED)
            return One.Added(
                Attachment(
                    kind = probed.kind,
                    path = "$DIR/${file.name}",
                    mime = probed.mime,
                    sizeBytes = probed.sizeBytes,
                    width = probed.width,
                    height = probed.height,
                    durationMs = probed.durationMs,
                    frameRate = probed.frameRate,
                    animated = probed.animated,
                    hasTransparency = probed.hasTransparency,
                    videoCodec = probed.videoCodec,
                    pieceIndex = pieceIndex,
                ),
            )
        } catch (e: CancellationException) {
            fail(Reason.UNREADABLE)
            throw e
        } catch (_: Exception) {
            // A provider may throw anything: a lost grant, a missing file, a full disk.
            return fail(Reason.UNREADABLE)
        }
    }

    private fun resolverType(uri: Uri): String? = runCatching { resolver.getType(uri) }.getOrNull()

    companion object {
        /** The directory of attachment copies under the files directory. */
        const val DIR = "attachments"

        /**
         * The type of a file: the content resolver's [declared] type when it names an image, a video or audio
         * type, otherwise the [sniffed] one; null when neither is a media type.
         */
        fun chooseMime(declared: String?, sniffed: String?): String? {
            val type = declared?.substringBefore(';')?.trim()?.lowercase()
            if (type != null && isMedia(type) && !type.endsWith("/*")) return type
            return sniffed?.takeIf { isMedia(it) }
        }

        private fun isMedia(type: String) = type.startsWith("image/") || type.startsWith("video/") || type.startsWith("audio/")
    }
}
