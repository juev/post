package org.evsyukov.post.ui.composer

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.evsyukov.post.core.media.ConflictKind
import org.evsyukov.post.core.media.FixKind
import org.evsyukov.post.core.media.MediaKind
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.posts.AccountPostOptions
import org.evsyukov.post.data.posts.Attachment
import org.evsyukov.post.data.posts.KeptMedia
import org.evsyukov.post.data.posts.PollDraft

/**
 * What the composer shows, kept in the saved state of the screen so that it comes back after Android killed the
 * process in the background (R15, R27): the text and options as edited, and the attachments with the files
 * already copied into app storage (R9). Saved drafts come back from the database; this holds what was not saved.
 */
@Serializable
internal data class ComposerSnapshot(
    val draftId: Long?,
    val text: String,
    val spoilerText: String,
    val spoilerShown: Boolean,
    val language: String,
    val targets: List<Long>,
    val accountOptions: Map<Long, AccountPostOptions>,
    val poll: PollDraft?,
    val attachments: List<SnapshotFile>,
    val chosenFixes: Map<Network, Map<ConflictKind, FixKind>>,
    val keptMedia: List<KeptMedia>,
) {
    fun encode(): String = json.encodeToString(serializer(), this)

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** The snapshot in [value], or null when there is none or it does not read. */
        fun decode(value: String?): ComposerSnapshot? = value?.let {
            try {
                json.decodeFromString(serializer(), it)
            } catch (_: SerializationException) {
                null
            } catch (_: IllegalArgumentException) {
                null
            }
        }
    }
}

/** An attachment of the snapshot: everything the importer read and the composer edits, without the variants. */
@Serializable
internal data class SnapshotFile(
    val id: Long,
    val kind: MediaKind,
    val path: String,
    val mime: String,
    val sizeBytes: Long,
    val width: Int? = null,
    val height: Int? = null,
    val durationMs: Long? = null,
    val frameRate: Double? = null,
    val animated: Boolean = false,
    val hasTransparency: Boolean = false,
    val videoCodec: String? = null,
    val pieceIndex: Int = 0,
    val alt: String = "",
    val focusX: Float? = null,
    val focusY: Float? = null,
) {
    fun toAttachment() = Attachment(
        id = id, kind = kind, path = path, mime = mime, sizeBytes = sizeBytes, width = width, height = height,
        durationMs = durationMs, frameRate = frameRate, animated = animated, hasTransparency = hasTransparency,
        videoCodec = videoCodec, pieceIndex = pieceIndex, alt = alt, focusX = focusX, focusY = focusY,
    )

    companion object {
        fun of(a: Attachment) = SnapshotFile(
            a.id, a.kind, a.path, a.mime, a.sizeBytes, a.width, a.height, a.durationMs, a.frameRate, a.animated,
            a.hasTransparency, a.videoCodec, a.pieceIndex, a.alt, a.focusX, a.focusY,
        )
    }
}
