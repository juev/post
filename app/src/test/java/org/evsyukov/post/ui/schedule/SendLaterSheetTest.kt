package org.evsyukov.post.ui.schedule

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.core.schedule.WeeklySchedule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

/** The Send later sheet (product.md R24, screens.md) and the schedule edits behind the Schedule screen. */
@RunWith(AndroidJUnit4::class)
// A tall screen, so the whole list is composed at once.
@Config(qualifiers = "w411dp-h1600dp")
class SendLaterSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = ScheduleUiHarness(compose)
    private val picked = mutableListOf<LocalDateTime>()
    private var schedulesOpened = 0

    @After
    fun tearDown() = h.close()

    private val base = LocalDateTime.now().plusDays(2).withHour(9).withMinute(0).withSecond(0).withNano(0)
    private val six = (0L until 6L).map { base.plusDays(it) }

    private fun show(options: SendLaterOptions) {
        h.show {
            SendLaterSheet(options, onSlot = { picked += it }, onTime = {}, onOpenSchedule = { schedulesOpened++ }, onDismiss = {})
        }
        h.waitFor(SendLaterTags.PICK)
    }

    @Test
    fun theNextFreeSlotComesFirstThenFiveMoreAndThePicker() {
        show(SendLaterOptions(six, hasSchedule = true))

        h.waitFor(SendLaterTags.NEXT_FREE)
        for (index in 1..5) h.waitFor(SendLaterTags.slot(index))
        assertThat(h.exists(SendLaterTags.slot(6))).isFalse()
        assertThat(h.exists(SendLaterTags.NO_SCHEDULE)).isFalse()
        assertThat(h.exists(SendLaterTags.OPEN_SCHEDULE)).isFalse()
        assertThat(compose.onAllNodes(hasText("Next free slot")).fetchSemanticsNodes()).isNotEmpty()

        h.click(SendLaterTags.NEXT_FREE)
        h.click(SendLaterTags.slot(3))
        assertThat(picked).containsExactly(six[0], six[3]).inOrder()
    }

    @Test
    fun withoutAScheduleOnlyThePickerAndALinkToScheduleAreOffered() {
        show(SendLaterOptions(emptyList(), hasSchedule = false))

        assertThat(h.exists(SendLaterTags.NEXT_FREE)).isFalse()
        h.waitFor(SendLaterTags.NO_SCHEDULE)
        h.click(SendLaterTags.OPEN_SCHEDULE)
        assertThat(schedulesOpened).isEqualTo(1)
    }

    @Test
    fun pickDateAndTimeOpensTheDatePicker() {
        show(SendLaterOptions(six, hasSchedule = true))

        h.click(SendLaterTags.PICK)

        h.waitFor(org.evsyukov.post.ui.composer.ComposerTags.DATE_CONFIRM)
    }

    @Test
    fun slotsAreWrittenAsTodayTomorrowOrAShortDate() {
        val today = LocalDate.of(2026, 11, 16)
        val labels = listOf("Today", "Tomorrow")
        assertThat(formatDateTime(today.atTime(13, 0), today, Locale.UK, labels[0], labels[1])).isEqualTo("Today, 13:00")
        assertThat(formatDateTime(today.plusDays(1).atTime(9, 0), today, Locale.UK, labels[0], labels[1])).isEqualTo("Tomorrow, 09:00")
        assertThat(formatDateTime(today.plusDays(2).atTime(9, 0), today, Locale.UK, labels[0], labels[1])).isEqualTo("Wed 18 Nov, 09:00")
    }

    @Test
    fun scheduleEditsAddChangeDeleteAndCopy() {
        val nine = LocalTime.of(9, 0)
        val one = LocalTime.of(13, 0)
        var schedule = WeeklySchedule.EMPTY.withTime(DayOfWeek.MONDAY, null, nine)
        schedule = schedule.withTime(DayOfWeek.MONDAY, null, one)
        assertThat(schedule.timesOn(DayOfWeek.MONDAY)).containsExactly(nine, one).inOrder()

        schedule = schedule.withTime(DayOfWeek.MONDAY, one, LocalTime.of(18, 0))
        assertThat(schedule.timesOn(DayOfWeek.MONDAY)).containsExactly(nine, LocalTime.of(18, 0)).inOrder()

        val weekdays = schedule.copyDay(DayOfWeek.MONDAY, WEEKDAYS)
        assertThat(weekdays.times.keys).containsExactlyElementsIn(WEEKDAYS)
        assertThat(weekdays.timesOn(DayOfWeek.FRIDAY)).isEqualTo(schedule.timesOn(DayOfWeek.MONDAY))

        // Copying an empty day clears the targets.
        assertThat(weekdays.copyDay(DayOfWeek.SUNDAY, DayOfWeek.entries.toSet()).isEmpty()).isTrue()

        assertThat(schedule.withTime(DayOfWeek.MONDAY, nine, null).timesOn(DayOfWeek.MONDAY)).containsExactly(LocalTime.of(18, 0))
    }
}
