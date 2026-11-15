package org.evsyukov.post.oauth

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.result.ActivityResultLauncher
import androidx.browser.auth.AuthTabIntent
import androidx.browser.customtabs.CustomTabsClient
import androidx.browser.customtabs.CustomTabsIntent

/** Opens a sign-in page: in an Auth Tab when the browser has one, else in a Custom Tab. */
fun interface BrowserLauncher {
    /** Returns false when no browser could open [url]. */
    fun open(url: Uri): Boolean
}

/**
 * The launcher of MainActivity. An Auth Tab returns the redirect as an activity result to
 * [authTabLauncher]; a Custom Tab sends it to [OAuthRedirectActivity] through the intent filter.
 */
class AuthTabBrowserLauncher(
    private val context: Context,
    private val authTabLauncher: ActivityResultLauncher<Intent>,
) : BrowserLauncher {
    override fun open(url: Uri): Boolean = try {
        val browser = CustomTabsClient.getPackageName(context, null)
        if (browser != null && CustomTabsClient.isAuthTabSupported(context, browser)) {
            AuthTabIntent.Builder().build().launch(authTabLauncher, url, REDIRECT_SCHEME)
        } else {
            CustomTabsIntent.Builder().build().launchUrl(context, url)
        }
        true
    } catch (_: ActivityNotFoundException) {
        false
    }

    companion object {
        const val REDIRECT_SCHEME = "org.evsyukov.post"
    }
}
