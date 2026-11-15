package org.evsyukov.post.ui.settings

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
import android.provider.Settings.EXTRA_APP_PACKAGE
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.evsyukov.post.R
import org.evsyukov.post.core.schedule.LateThreshold
import org.evsyukov.post.data.settings.HistoryRetention
import org.evsyukov.post.data.settings.Settings
import org.evsyukov.post.data.settings.VideoQuality
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.common.BackTopBar
import java.time.Instant

object SendingTags {
    const val SCREEN = SettingsSectionTags.SENDING
    const val LIST = "sending-list"
    const val SUCCESS_NOTIFICATIONS = "sending-success-notifications"
    const val SYSTEM_NOTIFICATIONS = "sending-system-notifications"
    const val HISTORY = "sending-history"
    const val LATE_THRESHOLD = "sending-late-threshold"
    const val NUMBER_PARTS = "sending-number-parts"
    const val VIDEO_QUALITY = "sending-video-quality"
    const val KEEP_METADATA = "sending-keep-metadata"
}

/**
 * Settings → Sending (screens.md): the success summary and a way to the system notification settings
 * (R32), history retention (R31), the late threshold (Q4), thread numbers (T11), the video transcoding
 * quality and keeping image metadata (A6).
 */
@Composable
fun SendingSettingsScreen(onBack: () -> Unit) {
    val container = LocalAppContainer.current
    val settings by rememberSettings(container)
    SendingSettingsContent(
        settings = settings,
        onBack = onBack,
        onEdit = container::editSettings,
        onRetention = { value ->
            // A shorter period, or none, applies to the records already kept (screens.md, Sent).
            container.scope.launch {
                container.settings.setHistoryRetention(value)
                container.history.purgeExpired(Instant.now())
            }
        },
    )
}

/** Sending without its data source; nothing shows until [settings] are read. */
@Composable
fun SendingSettingsContent(
    settings: Settings?,
    onBack: () -> Unit,
    onEdit: (SettingsEdit) -> Unit,
    onRetention: (HistoryRetention) -> Unit,
) {
    val context = LocalContext.current
    Scaffold(
        modifier = Modifier.testTag(SendingTags.SCREEN),
        topBar = { BackTopBar(title = stringResource(R.string.settings_sending), onBack = onBack) },
    ) { padding ->
        if (settings == null) return@Scaffold
        LazyColumn(Modifier.fillMaxSize().padding(padding).testTag(SendingTags.LIST)) {
            item { SettingsHeader(stringResource(R.string.sending_notifications)) }
            item {
                SwitchRow(
                    stringResource(R.string.sending_success_notifications),
                    stringResource(R.string.sending_success_notifications_summary),
                    settings.successNotifications,
                    SendingTags.SUCCESS_NOTIFICATIONS,
                ) { value -> onEdit { setSuccessNotifications(value) } }
            }
            item {
                ListItem(
                    onClick = { openNotificationSettings(context) },
                    modifier = Modifier.testTag(SendingTags.SYSTEM_NOTIFICATIONS),
                    supportingContent = { Text(stringResource(R.string.sending_system_notifications_summary)) },
                    trailingContent = { Icon(Icons.AutoMirrored.Outlined.OpenInNew, contentDescription = null) },
                ) { Text(stringResource(R.string.sending_system_notifications)) }
            }

            item { SettingsHeader(stringResource(R.string.sending_history)) }
            item { SettingsNote(stringResource(R.string.sending_history_summary)) }
            item {
                ChoiceGroup(
                    listOf(
                        Choice(HistoryRetention.OFF, stringResource(R.string.history_off)),
                        Choice(HistoryRetention.DAYS_7, stringResource(R.string.history_days_7)),
                        Choice(HistoryRetention.DAYS_30, stringResource(R.string.history_days_30)),
                        Choice(HistoryRetention.DAYS_90, stringResource(R.string.history_days_90)),
                    ),
                    settings.historyRetention,
                    SendingTags.HISTORY,
                    onRetention,
                )
            }

            item { SettingsHeader(stringResource(R.string.sending_late_threshold)) }
            item { SettingsNote(stringResource(R.string.sending_late_threshold_summary)) }
            item {
                ChoiceGroup(
                    listOf(
                        Choice(LateThreshold.MINUTES_15, stringResource(R.string.late_minutes_15)),
                        Choice(LateThreshold.MINUTES_30, stringResource(R.string.late_minutes_30)),
                        Choice(LateThreshold.HOUR_1, stringResource(R.string.late_hour_1)),
                        Choice(LateThreshold.HOURS_3, stringResource(R.string.late_hours_3)),
                    ),
                    settings.lateThreshold,
                    SendingTags.LATE_THRESHOLD,
                ) { value -> onEdit { setLateThreshold(value) } }
            }

            item { SettingsHeader(stringResource(R.string.sending_threads)) }
            item {
                SwitchRow(
                    stringResource(R.string.sending_number_parts),
                    stringResource(R.string.sending_number_parts_summary),
                    settings.numberThreadParts,
                    SendingTags.NUMBER_PARTS,
                ) { value -> onEdit { setNumberThreadParts(value) } }
            }

            item { SettingsHeader(stringResource(R.string.sending_media)) }
            item { SettingsNote(stringResource(R.string.sending_video_quality)) }
            item {
                ChoiceGroup(
                    listOf(
                        Choice(VideoQuality.STANDARD, stringResource(R.string.video_quality_standard), stringResource(R.string.video_quality_standard_summary)),
                        Choice(VideoQuality.HIGH, stringResource(R.string.video_quality_high), stringResource(R.string.video_quality_high_summary)),
                    ),
                    settings.videoQuality,
                    SendingTags.VIDEO_QUALITY,
                ) { value -> onEdit { setVideoQuality(value) } }
            }
            item {
                SwitchRow(
                    stringResource(R.string.sending_keep_metadata),
                    stringResource(R.string.sending_keep_metadata_summary),
                    settings.keepImageMetadata,
                    SendingTags.KEEP_METADATA,
                ) { value -> onEdit { setKeepImageMetadata(value) } }
            }
            item { Spacer(Modifier.padding(12.dp)) }
        }
    }
}

/** The notification settings of the app in the system settings (R32). */
private fun openNotificationSettings(context: Context) {
    val intent = Intent(ACTION_APP_NOTIFICATION_SETTINGS).putExtra(EXTRA_APP_PACKAGE, context.packageName)
    try {
        context.startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // A device without that settings page: nothing to open.
    }
}
