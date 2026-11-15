package org.evsyukov.post.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.evsyukov.post.R
import org.evsyukov.post.ui.common.BackTopBar

object SettingsTags {
    const val SCREEN = "settings"
    const val ACCOUNTS = "settings-accounts"
    const val DEFAULTS = "settings-defaults"
    const val SENDING = "settings-sending"
    const val SCHEDULE = "settings-schedule"
    const val ABOUT = "settings-about"
}

/** Settings: a list of sections (screens.md, Settings). */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenAccounts: () -> Unit = {},
    onOpenDefaults: () -> Unit = {},
    onOpenSending: () -> Unit = {},
    onOpenSchedule: () -> Unit = {},
    onOpenAbout: () -> Unit = {},
) {
    Scaffold(
        modifier = Modifier.testTag(SettingsTags.SCREEN),
        topBar = { BackTopBar(title = stringResource(R.string.settings), onBack = onBack) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { Section(Icons.Outlined.AccountCircle, R.string.settings_accounts, R.string.settings_accounts_summary, SettingsTags.ACCOUNTS, onOpenAccounts) }
            item { Section(Icons.Outlined.Tune, R.string.settings_defaults, R.string.settings_defaults_summary, SettingsTags.DEFAULTS, onOpenDefaults) }
            item { Section(Icons.AutoMirrored.Outlined.Send, R.string.settings_sending, R.string.settings_sending_summary, SettingsTags.SENDING, onOpenSending) }
            item { Section(Icons.Outlined.CalendarMonth, R.string.schedule, R.string.settings_schedule_summary, SettingsTags.SCHEDULE, onOpenSchedule) }
            item { Section(Icons.Outlined.Info, R.string.settings_about, R.string.settings_about_summary, SettingsTags.ABOUT, onOpenAbout) }
        }
    }
}

@Composable
private fun Section(icon: ImageVector, title: Int, summary: Int, tag: String, onClick: () -> Unit) {
    ListItem(
        onClick = onClick,
        supportingContent = { Text(stringResource(summary)) },
        // The headline labels the row for screen readers; the icon only repeats it.
        leadingContent = { Icon(icon, contentDescription = null) },
        modifier = Modifier.testTag(tag),
    ) { Text(stringResource(title)) }
}
