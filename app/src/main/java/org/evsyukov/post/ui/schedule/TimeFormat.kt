package org.evsyukov.post.ui.schedule

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import org.evsyukov.post.R
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.util.Locale

/** A local time as the locale writes it, such as 09:00 or 9:00 AM. */
fun formatTime(time: LocalTime, locale: Locale): String =
    DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(time)

/** "Today", "Tomorrow" or a short date such as "Tue 18 Nov" for [date] seen on [today]. */
fun formatDay(date: LocalDate, today: LocalDate, locale: Locale, todayLabel: String, tomorrowLabel: String): String = when (date) {
    today -> todayLabel
    today.plusDays(1) -> tomorrowLabel
    else -> DateTimeFormatter.ofPattern("EEE d MMM", locale).format(date)
}

/** A slot or a send time such as "Tue 18 Nov, 09:00" or "Today, 13:00". */
fun formatDateTime(local: LocalDateTime, today: LocalDate, locale: Locale, todayLabel: String, tomorrowLabel: String): String =
    formatDay(local.toLocalDate(), today, locale, todayLabel, tomorrowLabel) + ", " + formatTime(local.toLocalTime(), locale)

/** [formatDateTime] with the strings and locale of the composition. */
@Composable
fun formatSlot(local: LocalDateTime): String {
    val locale = LocalConfiguration.current.locales[0]
    return formatDateTime(
        local,
        LocalDate.now(),
        locale,
        stringResource(R.string.schedule_today),
        stringResource(R.string.schedule_tomorrow),
    )
}

/** An instant in the device zone, written as [formatSlot] writes a slot. */
@Composable
fun formatInstant(instant: Instant): String = formatSlot(LocalDateTime.ofInstant(instant, ZoneId.systemDefault()))

/** The full name of a weekday in [locale], such as Monday or понедельник. */
fun dayName(day: DayOfWeek, locale: Locale): String = day.getDisplayName(TextStyle.FULL_STANDALONE, locale)

/** The short name of a weekday in [locale], such as Mon or Пн. */
fun shortDayName(day: DayOfWeek, locale: Locale): String = day.getDisplayName(TextStyle.SHORT_STANDALONE, locale)
