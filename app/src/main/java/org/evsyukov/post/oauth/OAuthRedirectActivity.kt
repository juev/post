package org.evsyukov.post.oauth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import org.evsyukov.post.MainActivity
import org.evsyukov.post.PostApplication

/**
 * Receives `org.evsyukov.post://oauth/mastodon?code=…&state=…` when the sign-in page ran in a Custom Tab
 * because the browser has no Auth Tab. It hands the address to [MastodonSignInFlow], which rejects a
 * redirect whose state Post did not issue, and brings the app back. It has no window.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val data = intent?.data
        if (savedInstanceState == null && data != null) {
            (application as PostApplication).container.mastodonSignInFlow.onRedirect(data.toString())
        }
        startActivity(
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
        finish()
    }
}
