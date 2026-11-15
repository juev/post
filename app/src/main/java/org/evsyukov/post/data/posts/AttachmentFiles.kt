package org.evsyukov.post.data.posts

import java.io.File

/** The files of attachments and their variants, by the paths stored in the database. */
interface AttachmentFiles {
    /** Deletes the files of [paths]; a file that is already gone is skipped. */
    fun delete(paths: Collection<String>)
}

/**
 * Attachment files under [root], the app's files directory. A path is relative to [root]; one that
 * leads outside it (`..`, an absolute path elsewhere, a link out) is never touched.
 */
class FilesDirAttachmentFiles(root: File) : AttachmentFiles {
    private val root: File = root.canonicalFile

    /** The file of [path] under the root, or null when the path leads outside it. */
    fun resolve(path: String): File? {
        val file = File(root, path).canonicalFile
        return file.takeIf { it != root && it.toPath().startsWith(root.toPath()) }
    }

    override fun delete(paths: Collection<String>) {
        for (path in paths) {
            val file = resolve(path) ?: continue
            if (file.isFile) file.delete()
        }
    }
}
