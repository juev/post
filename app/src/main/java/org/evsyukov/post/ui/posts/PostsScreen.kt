package org.evsyukov.post.ui.posts

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

object PostsTags {
    const val SCREEN = "posts"
}

/** A placeholder with the top bar of the screen; its content comes later (screens.md, Posts). */
@Composable
fun PostsScreen(onBack: () -> Unit) {
    Scaffold(
        modifier = Modifier.testTag(PostsTags.SCREEN),
        topBar = { BackTopBar(title = stringResource(R.string.posts), onBack = onBack) },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding))
    }
}
