package org.evsyukov.post.ui.schedule

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.evsyukov.post.R
import org.evsyukov.post.core.schedule.upcomingSlots
import org.evsyukov.post.data.posts.Schedule
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/** The Schedule screen (screens.md, Schedule; R26; scheduling.md S1, S6). */
@RunWith(AndroidJUnit4::class)
// A tall screen, so the whole list is composed at once.
@Config(qualifiers = "w411dp-h1600dp")
class ScheduleScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val h = ScheduleUiHarness(compose)
    private val opened = mutableListOf<Long>()

    @After
    fun tearDown() = h.close()

    private fun show() {
        h.show { ScheduleScreen(onBack = {}, onOpenPost = { opened += it }) }
        h.waitFor(ScheduleTags.day(DayOfWeek.MONDAY))
    }

    @Test
    fun plusAddsATimeToTheDay() {
        show()

        h.click(ScheduleTags.add(DayOfWeek.TUESDAY))
        h.waitFor(ScheduleTags.TIME_DIALOG)
        // A new time starts at 09:00 and offers no Delete.
        assertThat(h.exists(ScheduleTags.TIME_DELETE)).isFalse()
        h.click(ScheduleTags.TIME_SAVE)

        h.waitUntil { h.schedule().timesOn(DayOfWeek.TUESDAY) == listOf(LocalTime.of(9, 0)) }
        h.waitFor(ScheduleTags.chip(DayOfWeek.TUESDAY, LocalTime.of(9, 0)))
    }

    @Test
    fun aTapOnATimeOffersDeleteAndDeletes() {
        h.slots(DayOfWeek.MONDAY to listOf("09:00", "13:00"))
        show()

        h.click(ScheduleTags.chip(DayOfWeek.MONDAY, LocalTime.of(9, 0)))
        h.click(ScheduleTags.TIME_DELETE)

        h.waitUntil { h.schedule().timesOn(DayOfWeek.MONDAY) == listOf(LocalTime.of(13, 0)) }
    }

    @Test
    fun aTapOnATimeChangesItWithSave() {
        h.slots(DayOfWeek.MONDAY to listOf("13:00"))
        show()

        h.click(ScheduleTags.chip(DayOfWeek.MONDAY, LocalTime.of(13, 0)))
        // The picker opens at the chip's time; saving it unchanged keeps one time, not two.
        h.click(ScheduleTags.TIME_SAVE)

        h.waitUntil { h.schedule().timesOn(DayOfWeek.MONDAY) == listOf(LocalTime.of(13, 0)) }
    }

    @Test
    fun copyToWeekdaysFillsMondayToFridayAndLeavesTheWeekend() {
        h.slots(DayOfWeek.MONDAY to listOf("09:00", "18:00"), DayOfWeek.SATURDAY to listOf("11:00"))
        show()

        h.click(ScheduleTags.menu(DayOfWeek.MONDAY))
        h.click(ScheduleTags.COPY_WEEKDAYS)

        val expected = listOf(LocalTime.of(9, 0), LocalTime.of(18, 0))
        h.waitUntil { h.schedule().timesOn(DayOfWeek.FRIDAY) == expected }
        for (day in WEEKDAYS) assertThat(h.schedule().timesOn(day)).isEqualTo(expected)
        assertThat(h.schedule().timesOn(DayOfWeek.SATURDAY)).containsExactly(LocalTime.of(11, 0))
        assertThat(h.schedule().timesOn(DayOfWeek.SUNDAY)).isEmpty()
    }

    @Test
    fun copyToAllDaysFillsTheWholeWeek() {
        h.slots(DayOfWeek.WEDNESDAY to listOf("12:00"))
        show()

        h.click(ScheduleTags.menu(DayOfWeek.WEDNESDAY))
        h.click(ScheduleTags.COPY_ALL)

        h.waitUntil { h.schedule().timesOn(DayOfWeek.SUNDAY) == listOf(LocalTime.of(12, 0)) }
        for (day in DayOfWeek.entries) assertThat(h.schedule().timesOn(day)).containsExactly(LocalTime.of(12, 0))
    }

    @Test
    fun comingSlotsShowThePostThatHoldsASlotOrFreeAndATapOpensThePost() {
        h.slots(*DayOfWeek.entries.map { it to listOf("10:00", "22:00") }.toTypedArray())
        val first = upcomingSlots(h.schedule(), ZoneId.systemDefault(), Instant.now(), 1).single()
        val id = h.queue("Release notes for the week\nmore", Schedule.Slot(first))
        show()

        h.reveal(ScheduleTags.coming(0))
        compose.onNodeWithText("Release notes for the week").assertExists()
        assertThat(compose.onAllNodes(hasText(h.string(R.string.schedule_free))).fetchSemanticsNodes()).isNotEmpty()

        h.click(ScheduleTags.coming(0))
        assertThat(opened).containsExactly(id)
    }

    @Test
    fun redistributeMovesSlotPostsToTheNewScheduleAndSaysHowMany() {
        // S6: posts keep their old slot until Redistribute.
        val old = LocalDateTime.now().plusDays(1).withHour(3).withMinute(0).withSecond(0).withNano(0)
        val a = h.queue("A", Schedule.Slot(old))
        val b = h.queue("B", Schedule.Slot(old.plusHours(1)))
        h.slots(*DayOfWeek.entries.map { it to listOf("20:00") }.toTypedArray())
        show()

        h.click(ScheduleTags.REDISTRIBUTE)

        h.waitUntil { (h.post(a)!!.schedule as Schedule.Slot).local.toLocalTime() == LocalTime.of(20, 0) }
        val slotA = (h.post(a)!!.schedule as Schedule.Slot).local
        val slotB = (h.post(b)!!.schedule as Schedule.Slot).local
        assertThat(slotB).isEqualTo(slotA.plusDays(1))
        h.waitUntil { compose.onAllNodes(hasText("2 posts moved to the new slots")).fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun theTimeZoneInUseIsShownAndEveryRowIsLabelled() {
        show()

        h.reveal(ScheduleTags.ZONE)
        compose.onNodeWithText(ZoneId.systemDefault().id, substring = true).assertExists()
        // Screen readers hear the full day name on the add button.
        compose.onNode(hasContentDescription("Add a time on Monday"), useUnmergedTree = true).assertExists()
        compose.onNode(hasContentDescription("More for Sunday"), useUnmergedTree = true).assertExists()
    }
}
