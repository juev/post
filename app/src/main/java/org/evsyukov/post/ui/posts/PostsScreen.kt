package org.evsyukov.post.ui.posts

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import org.evsyukov.post.R
import org.evsyukov.post.ui.common.BackTopBar

object PostsTags {
    const val SCREEN = "posts"
    const val TAB_QUEUE = "posts-tab-queue"
    const val TAB_DRAFTS = "posts-tab-drafts"
    const val TAB_SENT = "posts-tab-sent"
}

/**
 * Posts (screens.md, Posts): the tabs Queue, Drafts and Sent. Queue lists the queue with its groups; Drafts and
 * Sent are filled by a later step. [onEdit] opens a queued post in the composer; [onOpenSchedule] the
 * Schedule screen, from the Send later sheet of Change time.
 */
@Composable
fun PostsScreen(onBack: () -> Unit, onEdit: (Long) -> Unit = {}, onOpenSchedule: () -> Unit = {}) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    Scaffold(
        modifier = Modifier.testTag(PostsTags.SCREEN),
        topBar = { BackTopBar(title = stringResource(R.string.posts), onBack = onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.posts_tab_queue)) }, modifier = Modifier.testTag(PostsTags.TAB_QUEUE))
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.posts_tab_drafts)) }, modifier = Modifier.testTag(PostsTags.TAB_DRAFTS))
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text(stringResource(R.string.posts_tab_sent)) }, modifier = Modifier.testTag(PostsTags.TAB_SENT))
            }
            when (tab) {
                0 -> QueueTab(snackbar = snackbar, onEdit = onEdit, onOpenSchedule = onOpenSchedule)
                else -> Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.posts_tab_coming), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
