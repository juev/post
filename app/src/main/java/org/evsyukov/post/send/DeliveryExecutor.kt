package org.evsyukov.post.send

import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.Account
import org.evsyukov.post.data.posts.Delivery
import org.evsyukov.post.data.posts.DeliveryPlan
import org.evsyukov.post.data.posts.DeliveryProgress
import org.evsyukov.post.data.posts.Post
import java.io.File
import java.time.Instant

/** A file ready to upload for an attachment: the original or its variant (media.md A2). */
data class UploadFile(val file: File, val mime: String)

/**
 * One attempt of one delivery, as the [Publisher] hands it to the executor of the account's network.
 *
 * @property plan the plan frozen at the first attempt (R30); later attempts get the same one.
 * @property progress what earlier attempts published; the executor resumes from the first unpublished
 *   part and never publishes a part twice.
 * @property attempt the number of this attempt, from 1.
 * @property files prepares the files of the plan's attachments (media.md A4) and returns them by
 *   attachment id; it is called before the first upload, and only when the parts carry media.
 * @property saveProgress stores progress at once, so a crash after it resumes from there (R30).
 */
class DeliveryJob(
    val post: Post,
    val account: Account,
    val delivery: Delivery,
    val plan: DeliveryPlan,
    val progress: DeliveryProgress,
    val attempt: Int,
    val files: suspend () -> Map<Long, UploadFile>,
    val saveProgress: suspend (DeliveryProgress) -> Unit,
)

/** How an attempt of a delivery ended (R5, R29). */
sealed interface DeliveryOutcome {
    /** Every part is published; [progress] has their ids. */
    data class Sent(val progress: DeliveryProgress) : DeliveryOutcome

    /** A temporary error (R29): the delivery waits and is tried again at [at]. */
    data class Retry(val at: Instant, val message: String) : DeliveryOutcome

    /** An error that retrying cannot fix (R29): the delivery fails with the server's [message]. */
    data class Failed(val message: String) : DeliveryOutcome

    /** The server rejected the credentials (R5); the account is already marked as needing sign-in. */
    data object NeedsSignIn : DeliveryOutcome
}

/**
 * Publishes a frozen plan to one network. Step 8a registers the Mastodon executor, 8b the Bluesky one;
 * a delivery of a network without an executor stays waiting, untouched.
 */
interface DeliveryExecutor {
    val network: Network

    suspend fun publish(job: DeliveryJob): DeliveryOutcome
}
