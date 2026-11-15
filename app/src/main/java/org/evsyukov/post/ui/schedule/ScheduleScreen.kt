package org.evsyukov.post.ui.schedule

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalResources
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.evsyukov.post.R
import org.evsyukov.post.core.schedule.SendTime
import org.evsyukov.post.core.schedule.WeeklySchedule
import org.evsyukov.post.core.schedule.comingSlots
import org.evsyukov.post.send.schedule.movableSlotPosts
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.common.BackTopBar
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.TextStyle

object ScheduleTags {
    const val SCREEN = "schedule"
    const val COPY_WEEKDAYS = "schedule-copy-weekdays"
    const val COPY_ALL = "schedule-copy-all"
    const val TIME_DIALOG = "schedule-time-dialog"
    const val TIME_SAVE = "schedule-time-save"
    const val TIME_DELETE = "schedule-time-delete"
    const val REDISTRIBUTE = "schedule-redistribute"
    const val ZONE = "schedule-zone"
    const val NO_COMING = "schedule-no-coming"

    fun day(day: DayOfWeek) = "schedule-day-${day.value}"
    fun add(day: DayOfWeek) = "schedule-add-${day.value}"
    fun menu(day: DayOfWeek) = "schedule-menu-${day.value}"
    fun chip(day: DayOfWeek, time: LocalTime) = "schedule-chip-${day.value}-$time"
    fun coming(index: Int) = "schedule-coming-$index"
}

/**
 * The weekly schedule of slots (screens.md, Schedule; R26, scheduling.md S1, S6): weekday rows of times, the
 * coming slots of the next seven days with the posts that hold them, Redistribute, and the time zone in use.
 * A tap on a post in Coming slots calls [onOpenPost].
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ScheduleScreen(onBack: () -> Unit, onOpenPost: (Long) -> Unit = {}) {
    val container = LocalAppContainer.current
    val schedule by container.schedule.observe().collectAsStateWithLifecycle(initialValue = null)
    val queue by container.posts.observeQueue().collectAsStateWithLifecycle(initialValue = emptyList())
    val locale = LocalResources.current.configuration.locales[0]
    val resources = LocalResources.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editing by remember { mutableStateOf<Pair<DayOfWeek, LocalTime?>?>(null) }
    val zone = ZoneId.systemDefault()

    fun save(change: (WeeklySchedule) -> WeeklySchedule) {
        val current = schedule ?: return
        container.scope.launch { container.schedule.save(change(current)) }
    }

    Scaffold(
        modifier = Modifier.testTag(ScheduleTags.SCREEN),
        topBar = { BackTopBar(title = stringResource(R.string.schedule), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        val current = schedule ?: return@Scaffold
        val byId = queue.associateBy { it.id }
        val occupiedBy = movableSlotPosts(queue, zone).associate { (it.time as SendTime.Slot).local to it.id }
        val coming = comingSlots(current, zone, Instant.now(), occupiedBy)
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            for (day in DayOfWeek.entries) {
                item(key = "day-$day") {
                    DayRow(
                        day = day,
                        times = current.timesOn(day),
                        locale = locale,
                        onEdit = { editing = day to it },
                        onAdd = { editing = day to null },
                        onCopyWeekdays = { save { it.copyDay(day, WEEKDAYS) } },
                        onCopyAll = { save { it.copyDay(day, DayOfWeek.entries.toSet()) } },
                    )
                }
            }
            item(key = "coming-title") {
                HorizontalDivider(Modifier.padding(top = 8.dp))
                Text(
                    stringResource(R.string.schedule_coming),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                if (coming.isEmpty()) {
                    Text(
                        stringResource(R.string.schedule_no_coming),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp).testTag(ScheduleTags.NO_COMING),
                    )
                }
            }
            itemsIndexed(coming, key = { _, slot -> "slot-${slot.local}" }) { index, slot ->
                val post = slot.postId?.let(byId::get)
                val label: @Composable () -> Unit = {
                    if (post == null) {
                        Text(stringResource(R.string.schedule_free), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Text(firstLine(post.text), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                val time: @Composable () -> Unit = { Text(formatSlot(slot.local), style = MaterialTheme.typography.labelLarge) }
                if (post != null) {
                    ListItem(onClick = { onOpenPost(post.id) }, leadingContent = time, modifier = Modifier.testTag(ScheduleTags.coming(index)), content = label)
                } else {
                    ListItem(leadingContent = time, modifier = Modifier.testTag(ScheduleTags.coming(index)), content = label)
                }
            }
            item(key = "footer") {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            val moved = container.queueActions.redistribute()
                            snackbar.showSnackbar(
                                if (moved > 0) {
                                    resources.getQuantityString(R.plurals.schedule_redistributed, moved, moved)
                                } else {
                                    resources.getString(R.string.schedule_nothing_to_redistribute)
                                },
                            )
                        }
                    },
                    modifier = Modifier.padding(16.dp).testTag(ScheduleTags.REDISTRIBUTE),
                ) { Text(stringResource(R.string.schedule_redistribute)) }
                Text(
                    stringResource(R.string.schedule_time_zone, "${zone.id} (${zone.getDisplayName(TextStyle.FULL, locale)})"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp).testTag(ScheduleTags.ZONE),
                )
            }
        }
    }

    editing?.let { (day, time) ->
        TimeDialog(
            title = stringResource(R.string.schedule_time_title, dayName(day, locale)),
            initial = time ?: LocalTime.of(9, 0),
            canDelete = time != null,
            onSave = { new ->
                editing = null
                save { it.withTime(day, time, new) }
            },
            onDelete = {
                editing = null
                save { it.withTime(day, time, null) }
            },
            onDismiss = { editing = null },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DayRow(
    day: DayOfWeek,
    times: List<LocalTime>,
    locale: java.util.Locale,
    onEdit: (LocalTime) -> Unit,
    onAdd: () -> Unit,
    onCopyWeekdays: () -> Unit,
    onCopyAll: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val name = dayName(day, locale)
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp).testTag(ScheduleTags.day(day)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            shortDayName(day, locale),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.width(48.dp).semantics { contentDescription = name },
        )
        FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (times.isEmpty()) {
                val none = stringResource(R.string.schedule_no_times)
                Text(
                    "—",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp).semantics { contentDescription = none },
                )
            }
            for (time in times) {
                val label = formatTime(time, locale)
                val description = stringResource(R.string.schedule_time_chip, name, label)
                AssistChip(
                    onClick = { onEdit(time) },
                    label = { Text(label) },
                    modifier = Modifier.semantics { contentDescription = description }.testTag(ScheduleTags.chip(day, time)),
                )
            }
        }
        IconButton(onClick = onAdd, modifier = Modifier.testTag(ScheduleTags.add(day))) {
            Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.schedule_add_time, name))
        }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag(ScheduleTags.menu(day))) {
                Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.schedule_day_menu, name))
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.schedule_copy_weekdays)) },
                    onClick = {
                        menu = false
                        onCopyWeekdays()
                    },
                    modifier = Modifier.testTag(ScheduleTags.COPY_WEEKDAYS),
                )
                DropdownMenuItem(
                    text = { Text(stringResource(R.string.schedule_copy_all)) },
                    onClick = {
                        menu = false
                        onCopyAll()
                    },
                    modifier = Modifier.testTag(ScheduleTags.COPY_ALL),
                )
            }
        }
    }
}

/** Adds, changes or deletes one time of a day. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeDialog(
    title: String,
    initial: LocalTime,
    canDelete: Boolean,
    onSave: (LocalTime) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute)
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag(ScheduleTags.TIME_DIALOG),
        title = { Text(title) },
        text = { TimePicker(state = state) },
        confirmButton = {
            TextButton(onClick = { onSave(LocalTime.of(state.hour, state.minute)) }, modifier = Modifier.testTag(ScheduleTags.TIME_SAVE)) {
                Text(stringResource(R.string.schedule_save_time))
            }
        },
        dismissButton = {
            Row {
                if (canDelete) {
                    TextButton(onClick = onDelete, modifier = Modifier.testTag(ScheduleTags.TIME_DELETE)) {
                        Text(stringResource(R.string.schedule_delete_time))
                    }
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** The first non-blank line of a post, for one-line rows. */
fun firstLine(text: String): String = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
