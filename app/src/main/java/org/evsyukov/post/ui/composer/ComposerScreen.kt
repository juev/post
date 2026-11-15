package org.evsyukov.post.ui.composer

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import org.evsyukov.post.R

object ComposerTags {
    const val SCREEN = "composer"
    const val POSTS = "composer-posts"
    const val SETTINGS = "composer-settings"
    const val MORE = "composer-more"
    const val SCHEDULE = "composer-schedule"
}

/**
 * The start screen. Its top bar leads to Posts and Settings, and to Schedule through the overflow menu
 * (screens.md, Composer). The editor itself comes later.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ComposerScreen(
    onOpenPosts: () -> Unit,
    onOpenSchedule: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Scaffold(
        modifier = Modifier.testTag(ComposerTags.SCREEN),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name)) },
                actions = {
                    IconButton(onClick = onOpenPosts, modifier = Modifier.testTag(ComposerTags.POSTS)) {
                        Icon(Icons.AutoMirrored.Outlined.List, contentDescription = stringResource(R.string.posts))
                    }
                    IconButton(onClick = onOpenSettings, modifier = Modifier.testTag(ComposerTags.SETTINGS)) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.settings))
                    }
                    Box {
                        IconButton(onClick = { menu = true }, modifier = Modifier.testTag(ComposerTags.MORE)) {
                            Icon(Icons.Filled.MoreVert, contentDescription = stringResource(R.string.more))
                        }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.schedule)) },
                                leadingIcon = { Icon(Icons.Outlined.Schedule, contentDescription = null) },
                                onClick = {
                                    menu = false
                                    onOpenSchedule()
                                },
                                modifier = Modifier.testTag(ComposerTags.SCHEDULE),
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding))
    }
}
