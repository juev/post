package org.evsyukov.post.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.evsyukov.post.R
import org.evsyukov.post.ui.common.BackTopBar

object SettingsTags {
    const val SCREEN = "settings"
}

/** A placeholder with the top bar of the screen; its content comes later (screens.md, Settings). */
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.testTag(SettingsTags.SCREEN),
        topBar = { BackTopBar(title = stringResource(R.string.settings), onBack = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding))
    }
}
