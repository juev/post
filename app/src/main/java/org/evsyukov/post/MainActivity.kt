package org.evsyukov.post

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.browser.auth.AuthTabIntent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import org.evsyukov.post.oauth.AuthTabBrowserLauncher
import org.evsyukov.post.send.AndroidSendNotifier
import org.evsyukov.post.ui.AccountDetailsKey
import org.evsyukov.post.ui.LocalAppContainer
import org.evsyukov.post.ui.LocalBrowserLauncher
import org.evsyukov.post.ui.PostApp
import org.evsyukov.post.ui.PostsKey
import org.evsyukov.post.ui.theme.PostTheme

class MainActivity : ComponentActivity() {
    // Registered before the activity starts, so a result that arrives after the process was killed still
    // reaches the sign-in: the pending state is on disk.
    private val authTab = AuthTabIntent.registerActivityResultLauncher(this) { result ->
        val flow = (application as PostApplication).container.mastodonSignInFlow
        val uri = result.resultUri
        when {
            result.resultCode == AuthTabIntent.RESULT_OK && uri != null -> flow.onRedirect(uri.toString())
            else -> flow.cancel()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val container = (application as PostApplication).container
        val launcher = AuthTabBrowserLauncher(this, authTab)
        val openOnStart = when (intent?.getStringExtra(AndroidSendNotifier.EXTRA_OPEN)) {
            AndroidSendNotifier.OPEN_QUEUE -> PostsKey
            AndroidSendNotifier.OPEN_ACCOUNT -> intent.getLongExtra(AndroidSendNotifier.EXTRA_ACCOUNT_ID, -1).takeIf { it >= 0 }?.let(::AccountDetailsKey)
            else -> null
        }
        setContent {
            CompositionLocalProvider(LocalAppContainer provides container, LocalBrowserLauncher provides launcher) {
                PostTheme {
                    Surface(Modifier.fillMaxSize()) {
                        PostApp(openOnStart)
                    }
                }
            }
        }
    }
}
