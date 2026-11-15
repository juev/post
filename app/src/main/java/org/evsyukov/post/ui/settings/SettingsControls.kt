package org.evsyukov.post.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch
import org.evsyukov.post.AppContainer
import org.evsyukov.post.data.settings.AppSettings
import org.evsyukov.post.data.settings.Settings

object SettingsSectionTags {
    const val DEFAULTS = "settings-defaults-screen"
    const val SENDING = "settings-sending-screen"
    const val ABOUT = "settings-about-screen"

    /** The row of [value] in the choice group [group]. */
    fun option(group: String, value: Enum<*>) = "$group-${value.name}"
}

/** A change of the settings, run against the app's [AppSettings]. */
typealias SettingsEdit = suspend AppSettings.() -> Unit

/** The stored settings, null until the file is read. */
@Composable
internal fun rememberSettings(container: AppContainer): State<Settings?> {
    val source = remember(container) { container.settings.settings }
    return source.collectAsStateWithLifecycle(initialValue = null)
}

/** Runs [edit] in the app's scope, so a change made just before leaving the screen is still saved. */
internal fun AppContainer.editSettings(edit: SettingsEdit) {
    scope.launch { settings.edit() }
}

/** The title of a group of settings. */
@Composable
internal fun SettingsHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp).semantics { heading() },
    )
}

/** A line of explanation under a header or a choice group. */
@Composable
internal fun SettingsNote(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** An on/off setting: the whole row toggles, and its title labels the switch for screen readers. */
@Composable
internal fun SwitchRow(title: String, summary: String?, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.toggleable(value = checked, role = Role.Switch, onValueChange = onChange).testTag(tag),
        supportingContent = summary?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) },
        headlineContent = { Text(title) },
    )
}

/** One option of a [ChoiceGroup]. */
internal data class Choice<T : Enum<T>>(val value: T, val label: String, val summary: String? = null)

/** A setting with one value out of a few, as radio buttons; each row is selected by a tap anywhere on it. */
@Composable
internal fun <T : Enum<T>> ChoiceGroup(choices: List<Choice<T>>, selected: T, group: String, onSelect: (T) -> Unit) {
    Column(Modifier.selectableGroup().testTag(group)) {
        for (choice in choices) {
            ListItem(
                modifier = Modifier
                    .selectable(selected = choice.value == selected, role = Role.RadioButton, onClick = { onSelect(choice.value) })
                    .testTag(SettingsSectionTags.option(group, choice.value)),
                leadingContent = { RadioButton(selected = choice.value == selected, onClick = null) },
                supportingContent = choice.summary?.let { { Text(it) } },
                headlineContent = { Text(choice.label) },
            )
        }
    }
}
