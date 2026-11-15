package org.evsyukov.post.oauth

import android.app.Activity
import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.evsyukov.post.PostApplication
import org.evsyukov.post.accounts.SignInProblem
import org.evsyukov.post.accounts.originOf
import org.evsyukov.post.core.mastodon.MastodonCapabilities
import org.evsyukov.post.core.mastodon.Pkce
import org.evsyukov.post.core.mastodon.REDIRECT_URI
import org.evsyukov.post.data.accounts.MastodonApp
import org.evsyukov.post.data.secrets.MastodonSecret
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetAddress
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * The Custom Tab fallback of the Mastodon sign-in (networks.md N4–N5) on a device: the browser's
 * redirect to `org.evsyukov.post://oauth/mastodon` reaches OAuthRedirectActivity through its intent
 * filter, with the real Keystore and the files of the app. A local server stands in for the instance.
 */
@RunWith(AndroidJUnit4::class)
class OAuthRedirectActivityTest {
    private val app = ApplicationProvider.getApplicationContext<PostApplication>()
    private val container = app.container
    private val server = MockWebServer()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private lateinit var origin: String

    @Before
    fun setUp() = runBlocking {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                val body = when (request.url.encodedPath) {
                    "/oauth/token" -> """{"access_token":"device-token","token_type":"Bearer"}"""
                    "/api/v1/accounts/verify_credentials" -> """{"id":"777","username":"tester","acct":"tester","display_name":"Device Tester"}"""
                    else -> return MockResponse.Builder().code(404).build()
                }
                return MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()
            }
        }
        server.start(InetAddress.getByName("localhost"), 0)
        origin = originOf(server.url("/"))
        assertThat(origin).startsWith("http://localhost:")
        container.mastodonSignInFlow.reset()
        container.mastodonApps.put(MastodonApp(origin, "client-1", "secret-1", REDIRECT_URI, "write:statuses", Instant.now()))
        container.pendingSignIns.put(
            PendingSignIn(
                origin = origin,
                state = STATE,
                codeVerifier = Pkce.verifier(),
                scopes = "write:statuses",
                clientId = "client-1",
                accountId = null,
                capabilities = MastodonCapabilities(maxCharacters = 999),
                registeredAgain = false,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    @After
    fun tearDown() = runBlocking {
        finishActivities()
        container.accounts.observe().first().filter { it.server == origin }.forEach { container.accounts.remove(it.id) }
        container.mastodonApps.delete(origin)
        container.pendingSignIns.clear()
        container.mastodonSignInFlow.reset()
        server.close()
    }

    @Test
    fun theRedirectWithThePendingStateCompletesTheSignIn() = runBlocking {
        redirect("$REDIRECT_URI?code=device-code&state=$STATE")

        val done = withTimeout(TIMEOUT) { container.mastodonSignInFlow.state.first { it is MastodonFlowState.Done || it is MastodonFlowState.Failed } }

        assertThat(done).isInstanceOf(MastodonFlowState.Done::class.java)
        val account = container.accounts.get((done as MastodonFlowState.Done).accountId)!!
        assertThat(account.handle).isEqualTo("tester@localhost")
        assertThat(account.displayName).isEqualTo("Device Tester")
        assertThat(account.limits.toString()).contains("maxCharacters=999")
        // R4: the token is sealed with the Keystore key and opens again.
        assertThat(container.accounts.readSecret(account.id)).isEqualTo(MastodonSecret("device-token"))
        assertThat(container.pendingSignIns.get()).isNull()
        assertThat(requests.map { it.url.encodedPath }).containsExactly("/oauth/token", "/api/v1/accounts/verify_credentials").inOrder()
    }

    @Test
    fun aRedirectWithAnUnknownStateIsRejectedWithoutAnyRequest() = runBlocking {
        redirect("$REDIRECT_URI?code=stolen-code&state=forged-state")

        val failed = withTimeout(TIMEOUT) { container.mastodonSignInFlow.state.first { it is MastodonFlowState.Done || it is MastodonFlowState.Failed } }

        assertThat(failed).isEqualTo(MastodonFlowState.Failed(SignInProblem.UNKNOWN_STATE))
        assertThat(requests).isEmpty()
        // The real sign-in still waits for its redirect.
        assertThat(container.pendingSignIns.get()!!.state).isEqualTo(STATE)
        assertThat(container.accounts.observe().first().none { it.server == origin }).isTrue()
    }

    /** Sends the redirect as the browser does: an implicit VIEW intent, resolved by the manifest filter. */
    private fun redirect(uri: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(app.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val resolved = app.packageManager.resolveActivity(intent, 0)
        assertThat(resolved?.activityInfo?.name).isEqualTo(OAuthRedirectActivity::class.java.name)
        app.startActivity(intent)
    }

    private fun finishActivities() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.runOnMainSync {
            val registry = ActivityLifecycleMonitorRegistry.getInstance()
            for (stage in listOf(Stage.RESUMED, Stage.STARTED, Stage.PAUSED, Stage.STOPPED, Stage.CREATED)) {
                registry.getActivitiesInStage(stage).forEach(Activity::finish)
            }
        }
        instrumentation.waitForIdleSync()
    }

    private companion object {
        const val STATE = "pending-state-for-the-device-test"
        const val TIMEOUT = 15_000L
    }
}
