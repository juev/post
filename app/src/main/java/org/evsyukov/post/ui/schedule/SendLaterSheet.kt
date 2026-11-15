package org.evsyukov.post.ui.schedule

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.combine
import org.evsyukov.post.R
import org.evsyukov.post.send.schedule.sendLaterSlots
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.composer.SendLaterPicker
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object SendLaterTags {
    const val SHEET = "send-later-sheet"
    const val NEXT_FREE = "send-later-next-free"
    const val PICK = "send-later-pick"
    const val OPEN_SCHEDULE = "send-later-open-schedule"
    const val NO_SCHEDULE = "send-later-no-schedule"

    fun slot(index: Int) = "send-later-slot-$index"
}

/** What the Send later sheet offers: the free slots, and whether there is a schedule at all. */
data class SendLaterOptions(val slots: List<LocalDateTime>, val hasSchedule: Boolean)

/**
 * The free slots for the Send later sheet, from the queue and the schedule as they are now (R24). [except] is a
 * queued post whose time changes: its own slot counts as free. Null while loading.
 */
@Composable
fun rememberSendLaterOptions(except: Long? = null): SendLaterOptions? {
    val container = LocalAppContainer.current
    val options by produceState<SendLaterOptions?>(null, except) {
        container.posts.observeQueue().combine(container.schedule.observe()) { queue, schedule ->
            SendLaterOptions(sendLaterSlots(queue, schedule, ZoneId.systemDefault(), Instant.now(), except), !schedule.isEmpty())
        }.collect { value = it }
    }
    return options
}

/**
 * The Send later sheet (product.md R24, screens.md): the next free slot first and marked as the default, the
 * next five free slots, then Pick date and time. Without a schedule only the picker is offered, with a link to
 * Schedule. A slot is queued as a slot (S2); a picked time as an explicit time (S5).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SendLaterSheet(
    options: SendLaterOptions,
    onSlot: (LocalDateTime) -> Unit,
    onTime: (Instant) -> Unit,
    onOpenSchedule: () -> Unit,
    onDismiss: () -> Unit,
) {
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        SendLaterPicker(onPick = onTime, onDismiss = onDismiss)
        return
    }
    val state = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // The rows take the sheet's colour rather than bands of their own.
    val rowColors = ListItemDefaults.colors(containerColor = Color.Transparent)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = state, modifier = Modifier.testTag(SendLaterTags.SHEET)) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                stringResource(R.string.schedule_send_later_title),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            )
            val slots = options.slots
            if (slots.isNotEmpty()) {
                val first = slots.first()
                ListItem(
                    onClick = { onSlot(first) },
                    colors = rowColors,
                    overlineContent = { Text(stringResource(R.string.schedule_next_free_slot)) },
                    leadingContent = { Icon(Icons.Outlined.Schedule, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.testTag(SendLaterTags.NEXT_FREE),
                ) { Text(formatSlot(first)) }
                if (slots.size > 1) {
                    Text(
                        stringResource(R.string.schedule_other_slots),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp),
                    )
                }
                slots.drop(1).forEachIndexed { index, slot ->
                    ListItem(
                        onClick = { onSlot(slot) },
                        colors = rowColors,
                        leadingContent = { Icon(Icons.Outlined.Event, contentDescription = null) },
                        modifier = Modifier.testTag(SendLaterTags.slot(index + 1)),
                    ) { Text(formatSlot(slot)) }
                }
                HorizontalDivider(Modifier.padding(vertical = 4.dp))
            } else if (!options.hasSchedule) {
                Text(
                    stringResource(R.string.schedule_no_schedule),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp).testTag(SendLaterTags.NO_SCHEDULE),
                )
            }
            ListItem(
                onClick = { picking = true },
                colors = rowColors,
                leadingContent = { Icon(Icons.Outlined.CalendarMonth, contentDescription = null) },
                modifier = Modifier.testTag(SendLaterTags.PICK),
            ) { Text(stringResource(R.string.schedule_pick_date_time)) }
            if (!options.hasSchedule) {
                TextButton(
                    onClick = onOpenSchedule,
                    modifier = Modifier.padding(horizontal = 12.dp).testTag(SendLaterTags.OPEN_SCHEDULE),
                ) { Text(stringResource(R.string.schedule_open_schedule)) }
            }
        }
    }
}
