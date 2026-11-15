package org.evsyukov.post.data.posts

import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Attachment files are deleted by their stored path, and only inside the app's files directory. */
class FilesDirAttachmentFilesTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun filesUnderTheRootAreDeletedByTheirRelativePath() {
        val root = temp.newFolder("files")
        val original = File(root, "attachments/1/original.jpg").apply { parentFile!!.mkdirs(); writeText("o") }
        val variant = File(root, "attachments/1/variant-bluesky.jpg").apply { writeText("v") }
        val other = File(root, "attachments/2/original.jpg").apply { parentFile!!.mkdirs(); writeText("x") }

        FilesDirAttachmentFiles(root).delete(listOf("attachments/1/original.jpg", "attachments/1/variant-bluesky.jpg", "attachments/9/gone.jpg"))

        assertThat(original.exists()).isFalse()
        assertThat(variant.exists()).isFalse()
        assertThat(other.exists()).isTrue()
    }

    @Test
    fun aPathThatLeadsOutOfTheRootIsNeverTouched() {
        val root = temp.newFolder("files")
        val outside = temp.newFile("outside.txt").apply { writeText("keep") }
        val files = FilesDirAttachmentFiles(root)

        files.delete(listOf("../outside.txt", "attachments/../../outside.txt", outside.absolutePath.removePrefix("/").let { "../".repeat(20) + it }, "", "."))

        assertThat(outside.exists()).isTrue()
        assertThat(root.exists()).isTrue()
        assertThat(files.resolve("../outside.txt")).isNull()
        assertThat(files.resolve("")).isNull()
        assertThat(files.resolve("attachments/1/a.jpg")).isEqualTo(File(root, "attachments/1/a.jpg").canonicalFile)
    }

    @Test
    fun aLinkInsideTheRootThatPointsOutIsNotFollowed() {
        val root = temp.newFolder("files")
        val elsewhere = temp.newFolder("elsewhere")
        val target = File(elsewhere, "secret.txt").apply { writeText("keep") }
        java.nio.file.Files.createSymbolicLink(File(root, "link").toPath(), elsewhere.toPath())

        FilesDirAttachmentFiles(root).delete(listOf("link/secret.txt"))

        assertThat(target.exists()).isTrue()
    }
}
