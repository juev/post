package org.evsyukov.post.send.schedule

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.MainActivity
import org.evsyukov.post.send.AndroidSendNotifier
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf

/** The late notification (scheduling.md Q6): its text, its three actions and what they start. */
@RunWith(AndroidJUnit4::class)
class LateNotificationTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val manager = context.getSystemService(NotificationManager::class.java)
    private val notifier = AndroidLateNotifier(context)

    @Before
    fun grant() {
        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun late(): Notification = shadowOf(manager).getNotification(AndroidLateNotifier.LATE_ID)

    @Test
    fun itCountsTheHeldPostsAndOffersSendNowRescheduleAndReview() {
        notifier.showLate(2, canReschedule = true)

        val notification = late()
        assertThat(notification.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("2 posts missed their time")
        assertThat(notification.actions.map { it.title.toString() }).containsExactly("Send now", "Reschedule", "Review").inOrder()
        // Not dropped by the app or by a tap until a decision is made.
        assertThat(notification.flags and Notification.FLAG_ONGOING_EVENT).isNotEqualTo(0)
        assertThat(notification.flags and Notification.FLAG_AUTO_CANCEL).isEqualTo(0)

        val sendNow = shadowOf(notification.actions[0].actionIntent).savedIntent
        assertThat(sendNow.component!!.className).isEqualTo(LateActionReceiver::class.java.name)
        assertThat(sendNow.action).isEqualTo(LateActionReceiver.ACTION_SEND_NOW)
        val reschedule = shadowOf(notification.actions[1].actionIntent).savedIntent
        assertThat(reschedule.action).isEqualTo(LateActionReceiver.ACTION_RESCHEDULE)
        val review = shadowOf(notification.actions[2].actionIntent)
        assertThat(review.isActivityIntent).isTrue()
        assertThat(review.savedIntent.component!!.className).isEqualTo(MainActivity::class.java.name)
        assertThat(review.savedIntent.getStringExtra(AndroidSendNotifier.EXTRA_OPEN)).isEqualTo(AndroidSendNotifier.OPEN_QUEUE)
    }

    @Test
    fun withoutAScheduleRescheduleIsHidden() {
        notifier.showLate(1, canReschedule = false)

        val notification = late()
        assertThat(notification.extras.getString(Notification.EXTRA_TITLE)).isEqualTo("1 post missed its time")
        assertThat(notification.actions.map { it.title.toString() }).containsExactly("Send now", "Review").inOrder()
    }

    @Test
    fun moreHeldPostsUpdateTheOneNotificationAndCancelRemovesIt() {
        notifier.showLate(1, canReschedule = true)
        notifier.showLate(3, canReschedule = true)

        assertThat(shadowOf(manager).allNotifications).hasSize(1)
        assertThat(late().extras.getString(Notification.EXTRA_TITLE)).isEqualTo("3 posts missed their time")

        notifier.cancelLate()
        assertThat(shadowOf(manager).allNotifications).isEmpty()
    }

    @Test
    @org.robolectric.annotation.Config(qualifiers = "ru")
    fun theTitleIsInRussianWithItsPluralForms() {
        notifier.showLate(2, canReschedule = true)
        assertThat(late().extras.getString(Notification.EXTRA_TITLE)).isEqualTo("2 поста пропустили своё время")
        notifier.showLate(5, canReschedule = true)
        assertThat(late().extras.getString(Notification.EXTRA_TITLE)).isEqualTo("5 постов пропустили своё время")
    }
}
