package org.evsyukov.post.data.posts

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.evsyukov.post.data.db.AttachmentDao
import java.io.File
import java.time.Clock
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * The attachment files that live screens hold and the database does not know yet: the copies of a composer or a
 * Share sheet that is not saved. The sweep keeps them whatever their age.
 */
class FilesInUse {
    private val holders = ConcurrentHashMap<Any, () -> Collection<String>>()

    /** [owner] holds the files [paths] gives at the moment of a sweep, until [release]. */
    fun hold(owner: Any, paths: () -> Collection<String>) {
        holders[owner] = paths
    }

    fun release(owner: Any) {
        holders.remove(owner)
    }

    fun paths(): Set<String> = holders.values.flatMapTo(HashSet()) { it() }
}

/**
 * Deletes copies of attachments and their variants that nothing refers to (media.md A1): no attachment or variant row,
 * no live screen, and older than [minAge]. Such files are left by a process killed while a draft had unsaved
 * attachments, or by a copy or a conversion it cut short (`.part`).
 *
 * The age keeps files that a composer restored after process death still needs: it brings back an unsaved copy
 * from its saved state, and drops one that is gone (`ComposerModel.restore`).
 */
class AttachmentSweeper(
    private val dao: AttachmentDao,
    filesDir: File,
    private val inUse: FilesInUse,
    private val clock: Clock = Clock.systemUTC(),
    private val minAge: Duration = MIN_AGE,
) {
    private val files = FilesDirAttachmentFiles(filesDir)
    private val root: File = filesDir.canonicalFile

    /** Deletes the orphan files and returns how many went. */
    suspend fun sweep(): Int = withContext(Dispatchers.IO) {
        // Live screens first: a screen that saves meanwhile has its rows in the database before it lets go of the files.
        val held = inUse.paths()
        val keep = (held + dao.allPaths()).mapNotNullTo(HashSet()) { files.resolve(it) }
        val keepDirs = dao.postsWithAttachments().mapTo(HashSet()) { File(root, "$VARIANTS/$it").canonicalFile }
        val cutoff = clock.millis() - minAge.toMillis()
        var deleted = 0
        for (name in DIRS) {
            val dir = File(root, name)
            // Bottom up, so a directory is looked at after its files.
            for (entry in dir.walkBottomUp()) {
                if (entry == dir || entry.lastModified() >= cutoff) continue
                val file = files.resolve(entry.relativeTo(root).path) ?: continue
                when {
                    file.isFile -> if (file !in keep && file.delete()) deleted++
                    // An empty one only; a conversion of a post with attachments may be about to write into it.
                    file.isDirectory -> if (file !in keepDirs && file.list().isNullOrEmpty()) file.delete()
                }
            }
        }
        deleted
    }

    companion object {
        /** Files younger than this stay; a composer restored within a day after process death still finds its copies. */
        val MIN_AGE: Duration = Duration.ofHours(24)

        private const val VARIANTS = "variants"

        /** The directories of attachment copies and of variants under the files directory. */
        private val DIRS = listOf("attachments", VARIANTS)
    }
}

/** Gives the worker its [AttachmentSweeper]; the application implements it. */
interface AttachmentSweeperHost {
    val attachmentSweeper: AttachmentSweeper
}

/** Runs [AttachmentSweeper.sweep] once a day; the application also runs it at start. */
class AttachmentSweepWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val host = applicationContext as? AttachmentSweeperHost ?: return Result.failure()
        host.attachmentSweeper.sweep()
        return Result.success()
    }

    companion object {
        const val DAILY = "attachment-sweep-daily"

        /** The daily sweep, a unique periodic job; calling it again keeps the job already there. */
        fun schedule(workManager: WorkManager) {
            workManager.enqueueUniquePeriodicWork(
                DAILY,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<AttachmentSweepWorker>(1, TimeUnit.DAYS).build(),
            )
        }
    }
}
