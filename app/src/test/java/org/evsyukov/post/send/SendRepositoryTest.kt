package org.evsyukov.post.send

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.db.PostState
import org.evsyukov.post.data.posts.DeliveryPlan
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.DeliveryProgress
import org.evsyukov.post.data.posts.PartProgress
import org.evsyukov.post.data.posts.PlannedPart
import org.evsyukov.post.data.posts.PostsRepository
import org.evsyukov.post.data.posts.PublishedPart
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The repository calls the sender adds (R5, R29, media.md A7). */
@RunWith(AndroidJUnit4::class)
class SendRepositoryTest {
    private val f = SendFixture()

    @After
    fun tearDown() = f.close()

    /** A4: an edit of a queued post drops the variants and plans prepared for its old text. */
    @Test
    fun editingAQueuedPostDropsItsPreparedVariantsAndPlans() {
        val alice = f.mastodon()
        val id = f.queue("Photo", listOf(alice), attachments = listOf(f.image()))
        blocking { f.publisher.prepareQueued() }

        val prepared = f.post(id)!!
        assertThat(prepared.deliveries.single().plan).isNotNull()
        val variants = blocking { f.db.attachmentDao().variantPathsOfPost(id) }
        assertThat(variants).hasSize(1)
        assertThat(File(f.filesDir, variants.single()).exists()).isTrue()

        blocking {
            f.posts.saveDraft(DraftInput(id = id, text = "Photo, edited", targets = listOf(alice), attachments = prepared.attachments))
        }

        val edited = f.post(id)!!
        assertThat(edited.state).isEqualTo(PostState.QUEUED)
        assertThat(edited.deliveries.single().plan).isNull()
        assertThat(blocking { f.db.attachmentDao().variantPathsOfPost(id) }).isEmpty()
        assertThat(File(f.filesDir, variants.single()).exists()).isFalse()
    }

    @Test
    fun aPostWithAPublishedCopyCannotGoBackToTheDrafts() {
        val alice = f.mastodon(host = "a.example")
        val bob = f.mastodon(host = "b.example", remoteId = "202")
        val id = f.queue("Hello", listOf(alice, bob))
        val first = f.post(id)!!.deliveries.first().id
        blocking {
            f.posts.startDelivery(first, DeliveryPlan(listOf(PlannedPart("Hello"))))
            f.posts.deliverySucceeded(first, DeliveryProgress(listOf(PartProgress(0, published = PublishedPart(id = "s1")))))
        }

        assertThat(blocking { f.posts.returnToDraft(id) }).isFalse()
        assertThat(f.post(id)!!.deliveries).hasSize(2)
        assertThat(f.post(id)!!.state).isNotEqualTo(PostState.DRAFT)
    }

    @Test
    fun aPostWhosePartMayBeOnTheServerCannotGoBackToTheDrafts() {
        val alice = f.mastodon()
        val id = f.queue("Hello", listOf(alice))
        val delivery = f.post(id)!!.deliveries.single().id
        blocking {
            f.posts.startDelivery(delivery, DeliveryPlan(listOf(PlannedPart("Hello"))))
            f.posts.saveProgress(delivery, DeliveryProgress(listOf(PartProgress(0, key = "k", unknownSince = 1))))
        }
        assertThat(blocking { f.posts.returnToDraft(id) }).isFalse()
    }

    @Test
    fun onlyTheNeedsSignInDeliveriesOfTheAccountResume() {
        val alice = f.mastodon()
        val bob = f.mastodon(host = "b.example", remoteId = "202")
        val signIn = f.queue("A", listOf(alice))
        val rejected = f.queue("B", listOf(alice))
        val other = f.queue("C", listOf(bob))
        fun fail(postId: Long, error: String) = blocking {
            val d = f.post(postId)!!.deliveries.single().id
            f.posts.startDelivery(d, DeliveryPlan(listOf(PlannedPart("x"))))
            f.posts.deliveryFailed(d, error, null)
        }
        fail(signIn, PostsRepository.NEEDS_SIGN_IN)
        fail(rejected, "Validation failed")
        fail(other, PostsRepository.NEEDS_SIGN_IN)

        val resumed = blocking { f.posts.resumeAfterSignIn(alice) }

        assertThat(resumed).containsExactly(signIn)
        assertThat(f.post(signIn)!!.deliveries.single().state).isEqualTo(DeliveryState.WAITING)
        assertThat(f.post(signIn)!!.state).isEqualTo(PostState.SENDING)
        assertThat(f.post(rejected)!!.deliveries.single().state).isEqualTo(DeliveryState.FAILED)
        assertThat(f.post(other)!!.deliveries.single().state).isEqualTo(DeliveryState.FAILED)
    }

    @Test
    fun theEarliestRetryIsThatOfAWaitingDelivery() {
        val alice = f.mastodon()
        val a = f.queue("A", listOf(alice))
        val b = f.queue("B", listOf(alice))
        assertThat(blocking { f.posts.earliestRetry() }).isNull()
        fun retryAt(postId: Long, seconds: Long) = blocking {
            val d = f.post(postId)!!.deliveries.single().id
            f.posts.startDelivery(d, DeliveryPlan(listOf(PlannedPart("x"))))
            f.posts.deliveryFailed(d, "down", f.clock.now.plusSeconds(seconds))
        }
        retryAt(a, 120)
        retryAt(b, 30)
        assertThat(blocking { f.posts.earliestRetry() }).isEqualTo(f.clock.now.plusSeconds(30))
    }

    @Test
    fun aDeliveryOfARemovedAccountIsNotRetried() {
        val alice = f.mastodon()
        val id = f.queue("A", listOf(alice))
        val d = f.post(id)!!.deliveries.single().id
        blocking {
            f.posts.startDelivery(d, DeliveryPlan(listOf(PlannedPart("x"))))
            f.posts.deliveryFailed(d, "boom", null)
            f.accounts.remove(alice)
        }
        assertThat(blocking { f.posts.retryDelivery(d) }).isFalse()
    }
}
