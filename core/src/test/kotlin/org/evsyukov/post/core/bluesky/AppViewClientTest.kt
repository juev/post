package org.evsyukov.post.core.bluesky

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test

/** The profile shown for a new Bluesky account (product.md R2), read without credentials (R4). */
class AppViewClientTest {
    private val server = MockWebServer().apply { start() }
    private val client = AppViewClient(OkHttpClient(), testJson, server.url("/"))

    @After fun close() = server.close()

    @Test
    fun getProfileReadsNameAndAvatarWithoutCredentials() = runBlocking {
        server.enqueue(
            jsonResponse(
                """{"did":"did:plc:abc","handle":"alice.example.com","displayName":"Alice",
                   "avatar":"https://cdn.bsky.app/img/avatar/plain/did:plc:abc/x@jpeg","followersCount":3}""",
            ),
        )

        val profile = client.getProfile("did:plc:abc")

        assertThat(profile).isEqualTo(Profile("did:plc:abc", "alice.example.com", "Alice", "https://cdn.bsky.app/img/avatar/plain/did:plc:abc/x@jpeg"))
        val request = server.next()
        assertThat(request.url.encodedPath).isEqualTo("/xrpc/app.bsky.actor.getProfile")
        assertThat(request.url.queryParameter("actor")).isEqualTo("did:plc:abc")
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.headers["Authorization"]).isNull()
    }

    @Test
    fun aProfileWithoutNameOrAvatarGivesNulls() = runBlocking {
        server.enqueue(jsonResponse("""{"did":"did:plc:abc","handle":"alice.example.com","displayName":"  "}"""))

        val profile = client.getProfile("alice.example.com")

        assertThat(profile.displayName).isNull()
        assertThat(profile.avatar).isNull()
    }

    @Test
    fun anUnknownActorIsAnXrpcError() {
        server.enqueue(jsonResponse("""{"error":"InvalidRequest","message":"Profile not found"}""", code = 400))

        val e = assertThrows(XrpcException::class.java) { runBlocking { client.getProfile("did:plc:gone") } }

        assertThat(e.status).isEqualTo(400)
        assertThat(e.error).isEqualTo("InvalidRequest")
    }

    @Test
    fun anAnswerWithoutDidIsInvalid() {
        server.enqueue(jsonResponse("""{"handle":"alice.example.com"}"""))

        val e = assertThrows(XrpcException::class.java) { runBlocking { client.getProfile("alice.example.com") } }

        assertThat(e.error).isEqualTo(XrpcException.INVALID_RESPONSE)
    }
}
