package org.evsyukov.post

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/** The shared HTTP client (step 5): the User-Agent names the app only; debug builds reach local servers. */
@RunWith(AndroidJUnit4::class)
class AppContainerHttpTest {
    private val container = AppContainer(ApplicationProvider.getApplicationContext())

    @Test
    fun everyRequestCarriesTheAppUserAgentAndNothingElseAboutTheUser() {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse.Builder().body("ok").build())

            container.http.newCall(Request.Builder().url(server.url("/")).header("User-Agent", "other").build()).execute().close()

            val request = server.takeRequest(5, TimeUnit.SECONDS)!!
            assertThat(request.headers["User-Agent"]).isEqualTo("Post/${BuildConfig.VERSION_NAME} (+https://github.com/juev/post)")
            assertThat(request.headers.names()).containsNoneOf("Authorization", "Cookie", "From")
        }
        assertThat(container.http.connectTimeoutMillis).isGreaterThan(0)
        assertThat(container.http.readTimeoutMillis).isGreaterThan(0)
    }

    @Test
    fun theDebugBuildTakesLocalHttpServersAndHttpsElsewhere() {
        assertThat(BuildConfig.DEBUG).isTrue()
        assertThat(container.serverPolicy.accepts("http://10.0.2.2:3000/".toHttpUrl())).isTrue()
        assertThat(container.serverPolicy.accepts("http://mastodon.social/".toHttpUrl())).isFalse()
        assertThat(container.serverPolicy.accepts("https://mastodon.social/".toHttpUrl())).isTrue()
    }
}
