package org.evsyukov.post.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.evsyukov.post.R
import org.evsyukov.post.ui.common.BackTopBar

object SettingsSectionTags {
    const val DEFAULTS = "settings-defaults-screen"
    const val SENDING = "settings-sending-screen"
    const val ABOUT = "settings-about-screen"
}

// Placeholders: step 12 fills Defaults, Sending and About (screens.md, Settings). Not for the release as they are.

/** Settings → Defaults: default targets, Share action, alt text, media fixes, Bluesky links. */
@Composable
fun DefaultsSettingsScreen(onBack: () -> Unit) = Placeholder(R.string.settings_defaults, SettingsSectionTags.DEFAULTS, onBack)

/** Settings → Sending: notifications, history, late threshold, thread numbers, video, metadata. */
@Composable
fun SendingSettingsScreen(onBack: () -> Unit) = Placeholder(R.string.settings_sending, SettingsSectionTags.SENDING, onBack)

/** Settings → About: version, source repository, privacy note. */
@Composable
fun AboutScreen(onBack: () -> Unit) = Placeholder(R.string.settings_about, SettingsSectionTags.ABOUT, onBack)

@Composable
private fun Placeholder(title: Int, tag: String, onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.testTag(tag),
        topBar = { BackTopBar(title = stringResource(title), onBack = onBack) },
    ) { padding ->
        Text(
            stringResource(R.string.settings_placeholder),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp),
        )
    }
}
