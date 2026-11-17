package org.evsyukov.post.send

import org.evsyukov.post.data.history.HistoryItem
import org.evsyukov.post.data.posts.KeptMedia
import org.evsyukov.post.data.posts.PublishedMedia

/**
 * The files of part [index] of the published copy [previous] that an edit keeps (R40, N30, N32), with the
 * edits of [edits] applied: a removed file is left out, any other takes its edited alt text. A file without an
 * edit stays as published.
 *
 * The edits name a file first by the draft attachment it came from, which every copy that published it names
 * the same (#27), then by its place among all files of the post, the same in every copy of one network whatever
 * its split; older edits name it by part and index of the copy the composer listed.
 */
internal fun keptMediaOf(edits: List<KeptMedia>, previous: HistoryItem?, index: Int): List<PublishedMedia> {
    val parts = previous?.parts.orEmpty()
    val published = parts.getOrNull(index) ?: return emptyList()
    val byAttachment = edits.filter { it.attachmentId != null }.associateBy { it.attachmentId }
    val byOrdinal = edits.filter { it.ordinal != null }.associateBy { it.ordinal }
    val byPlace = edits.filter { it.ordinal == null && it.part == index }.associateBy { it.index }
    val before = parts.take(index).sumOf { it.media.size }
    return published.media.mapIndexedNotNull { position, media ->
        // A file this copy published under another attachment is one the edit does not name: it stays as it is.
        // An edit saved before the attachment ids has none, and every file still goes by place.
        val edit = if (media.attachmentId != null && byAttachment.isNotEmpty()) {
            byAttachment[media.attachmentId]
        } else {
            byOrdinal[before + position] ?: byPlace[position]
        }
        when {
            edit == null -> media
            edit.removed -> null
            else -> media.copy(alt = edit.alt)
        }
    }
}
