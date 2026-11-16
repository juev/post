package org.evsyukov.post

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.auth.AuthTabIntent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.navigation3.runtime.NavKey
import org.evsyukov.post.oauth.AuthTabBrowserLauncher
import org.evsyukov.post.send.AndroidSendNotifier
import org.evsyukov.post.ui.AccountDetailsKey
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.LocalBrowserLauncher
import org.evsyukov.post.ui.PostApp
import org.evsyukov.post.ui.PostsKey
import org.evsyukov.post.ui.ScheduleKey
import org.evsyukov.post.ui.theme.PostTheme

class MainActivity : ComponentActivity() {
    /** A screen a new intent asked for, opened by [PostApp] and then cleared. */
    private var request by mutableStateOf<NavKey?>(null)

    // Registered before the activity starts, so a result that arrives after the process was killed still
    // reaches the sign-in: the pending state is on disk.
    private val authTab = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        val flow = (application as AppContainerHost).container.mastodonSignInFlow
        val uri = result.resultUri
        when {
            result.resultCode == AuthTabIntent.RESULT_OK && uri != null -> flow.onRedirect(uri.toString())
            else -> flow.cancel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as AppContainerHost).container
        val launcher = AuthTabBrowserLauncher(this, authTab)
        val openOnStart = screenOf(intent)
        // A draft saved by the Share sheet opens as the start screen (R20).
        val draftId = intent?.getLongExtra(EXTRA_DRAFT_ID, -1L)?.takeIf { it > 0 }
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container, LocalBrowserLauncher provides launcher) {
                PostTheme {
                    Surface(Modifier.fillMaxSize()) {
                        PostApp(openOnStart, draftId, request = request, onRequestHandled = { request = null })
                    }
                }
            }
        }
    }

    /**
     * A notification tapped while the app runs (R5, R32, Q6): the screen it names opens over what is shown, and
     * the composer keeps its draft.
     */
    public override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        screenOf(intent)?.let { request = it }
    }

    /** The screen [intent] asks for: one a notification names, or Schedule from the Share sheet. */
    private fun screenOf(intent: Intent?): NavKey? = when (intent?.getStringExtra(AndroidSendNotifier.EXTRA_OPEN)) {
        AndroidSendNotifier.OPEN_QUEUE -> PostsKey
        AndroidSendNotifier.OPEN_ACCOUNT -> intent.getLongExtra(AndroidSendNotifier.EXTRA_ACCOUNT_ID, -1).takeIf { it >= 0 }?.let(::AccountDetailsKey)
        else -> null
    } ?: if (intent?.getBooleanExtra(EXTRA_OPEN_SCHEDULE, false) == true) ScheduleKey else null

    companion object {
        /** The draft to open in the composer, from Open in composer of the Share sheet. */
        const val EXTRA_DRAFT_ID = "org.evsyukov.post.extra.DRAFT_ID"

        /** Opens Schedule over the composer, from Set up schedule of the Share sheet. */
        const val EXTRA_OPEN_SCHEDULE = "org.evsyukov.post.extra.OPEN_SCHEDULE"
    }
}
