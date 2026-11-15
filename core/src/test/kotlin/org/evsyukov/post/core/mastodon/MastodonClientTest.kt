package org.evsyukov.post.core.mastodon

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

/** Request shape and answer handling of every call (networks.md N1–N12, N28–N31). */
class MastodonClientTest {
    @get:Rule val tmp = TemporaryFolder()

    private val server = MockWebServer().apply { start() }
    private val client = MastodonClient(server.url("/"), OkHttpClient())
    private val token = "tok-SECRET-123"

    @After fun close() = server.close()

    private fun take(): RecordedRequest = requireNotNull(server.takeRequest(1, TimeUnit.SECONDS)) { "no request" }

    private fun RecordedRequest.assertBearer() = assertThat(headers["Authorization"]).isEqualTo("Bearer $token")

    // ---- Server information ----

    @Test
    fun instanceV2IsAPublicGet() = runBlocking<Unit> {
        server.enqueue(json(fixture("mastodon-4.5-instance-v2.json")))

        val instance = client.instanceV2()

        val request = take()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.target).isEqualTo("/api/v2/instance")
        assertThat(request.headers["Authorization"]).isNull()
        assertThat(instance.domain).isEqualTo("mastodon.social")
        assertThat(instance.configuration!!.statuses!!.maxCharacters).isEqualTo(500)
        assertThat(instance.configuration.mediaAttachments!!.videoSizeLimit).isEqualTo(103_809_024)
        assertThat(instance.apiVersions!!.mastodon).isEqualTo(7)
    }

    @Test
    fun instanceV1ReadsPleromaPostFormats() = runBlocking<Unit> {
        server.enqueue(json(fixture("pleroma-instance-v1.json")))

        val instance = client.instanceV1()

        assertThat(take().target).isEqualTo("/api/v1/instance")
        assertThat(instance.pleroma!!.metadata!!.postFormats).contains("text/markdown")
        assertThat(instance.maxTootChars).isEqualTo(5000)
        assertThat(instance.pollLimits!!.maxOptionChars).isEqualTo(200)
    }

    @Test
    fun nodeInfoFollowsTheHighestSupportedSchema() = runBlocking<Unit> {
        val base = server.url("/").toString().trimEnd('/')
        server.enqueue(
            json(
                """{"links":[
                  {"rel":"http://nodeinfo.diaspora.software/ns/schema/2.0","href":"$base/nodeinfo/2.0.json"},
                  {"rel":"http://nodeinfo.diaspora.software/ns/schema/2.1","href":"$base/nodeinfo/2.1.json"},
                  {"rel":"http://nodeinfo.diaspora.software/ns/schema/1.0","href":"$base/nodeinfo/1.0.json"},
                  {"rel":"https://example.com/ns/other/9.9","href":"$base/other"}]}""",
            ),
        )
        server.enqueue(json(fixture("pleroma-nodeinfo-2.1.json")))

        val info = client.nodeInfo()

        assertThat(take().target).isEqualTo("/.well-known/nodeinfo")
        assertThat(take().target).isEqualTo("/nodeinfo/2.1.json")
        assertThat(info!!.software!!.name).isEqualTo("pleroma")
        assertThat(info.metadata!!.postFormats).contains("text/markdown")
    }

    @Test
    fun nodeInfoResolvesARelativeLink() = runBlocking<Unit> {
        server.enqueue(json("""{"links":[{"rel":"http://nodeinfo.diaspora.software/ns/schema/2.0","href":"/nodeinfo/2.0"}]}"""))
        server.enqueue(json(fixture("mastodon-nodeinfo-2.0.json")))

        assertThat(client.nodeInfo()!!.software!!.name).isEqualTo("mastodon")
        take()
        assertThat(take().target).isEqualTo("/nodeinfo/2.0")
    }

    @Test
    fun nodeInfoIsNullWhenAbsent() = runBlocking<Unit> {
        server.enqueue(json("""{"error":"Not Found"}""", 404))
        assertThat(client.nodeInfo()).isNull()

        server.enqueue(json("""{"links":[{"rel":"http://nodeinfo.diaspora.software/ns/schema/2.0","href":"/nodeinfo/2.0"}]}"""))
        server.enqueue(json("{}", 404))
        assertThat(client.nodeInfo()).isNull()

        server.enqueue(json("""{"links":[{"rel":"http://nodeinfo.diaspora.software/ns/schema/3.0","href":"/nodeinfo/3.0"}]}"""))
        assertThat(client.nodeInfo()).isNull()
        assertThat(server.requestCount).isEqualTo(4)
    }

    // product.md R34: Post talks only to the account's server.
    @Test
    fun nodeInfoDoesNotFollowALinkToAnotherOrigin() = runBlocking<Unit> {
        server.enqueue(json("""{"links":[{"rel":"http://nodeinfo.diaspora.software/ns/schema/2.1","href":"https://tracker.example/nodeinfo/2.1"}]}"""))

        assertThat(client.nodeInfo()).isNull()
        assertThat(server.requestCount).isEqualTo(1)
    }

    @Test
    fun authorizationServerMetadataIsParsedAndNullOn404() = runBlocking<Unit> {
        server.enqueue(
            json("""{"issuer":"https://social.example/","authorization_endpoint":"https://social.example/oauth/authorize",
                "scopes_supported":["read","write:statuses","profile"],"code_challenge_methods_supported":["S256"]}"""),
        )
        val meta = client.authorizationServerMetadata()!!
        assertThat(take().target).isEqualTo("/.well-known/oauth-authorization-server")
        assertThat(meta.scopesSupported).contains("profile")
        assertThat(meta.codeChallengeMethodsSupported).containsExactly("S256")

        server.enqueue(json("""{"error":"Not found"}""", 404))
        assertThat(client.authorizationServerMetadata()).isNull()

        server.enqueue(json("""{"error":"boom"}""", 500))
        val e = assertThrows(MastodonException.Http::class.java) { runBlocking { client.authorizationServerMetadata() } }
        assertThat(e.status).isEqualTo(500)
    }

    // Akkoma develop sends unknown /.well-known/ paths to its web app page: router.ex
    // `get("/*path", RedirectController, :redirector_with_preload)`, which answers 200 text/html.
    // Without metadata Post asks for read:accounts (networks.md N4), so sign-in must go on.
    @Test
    fun authorizationServerMetadataIsNullForAnAkkomaHtmlPage() = runBlocking<Unit> {
        server.enqueue(html(AKKOMA_INDEX_HTML))
        val meta = client.authorizationServerMetadata()
        assertThat(meta).isNull()
        assertThat(scopesFor(meta)).isEqualTo("write:statuses write:media read:statuses read:accounts")

        server.enqueue(json("""{"error":"Gone"}""", 410))
        assertThat(client.authorizationServerMetadata()).isNull()

        // A JSON answer that is not the document: an array, or scopes_supported of the wrong type.
        server.enqueue(json("""["profile"]"""))
        assertThat(client.authorizationServerMetadata()).isNull()
        server.enqueue(json("""{"scopes_supported":"profile"}"""))
        assertThat(client.authorizationServerMetadata()).isNull()

        // A server that is down is not a server without metadata.
        server.enqueue(html(AKKOMA_INDEX_HTML, 503))
        assertThat(assertThrows(MastodonException::class.java) { runBlocking { client.authorizationServerMetadata() } }.isTransient).isTrue()
    }

    // product.md R34: another port or scheme is another origin, even on the same host.
    @Test
    fun nodeInfoDoesNotFollowALinkToAnotherPortOrScheme() = runBlocking<Unit> {
        val origin = server.url("/")
        val otherPort = origin.newBuilder().port(if (origin.port == 1) 2 else 1).encodedPath("/nodeinfo/2.1").build()
        val otherScheme = origin.newBuilder().scheme("https").encodedPath("/nodeinfo/2.1").build()
        for (href in listOf(otherPort, otherScheme)) {
            server.enqueue(json("""{"links":[{"rel":"http://nodeinfo.diaspora.software/ns/schema/2.1","href":"$href"}]}"""))
            assertThat(client.nodeInfo()).isNull()
        }
        assertThat(server.requestCount).isEqualTo(2)
    }

    // A redirect is never followed (class description), and without the document Post falls back
    // to read:accounts and to the other F1 sources, so it counts as no document.
    @Test
    fun aRedirectOnADiscoveryDocumentMeansNoDocument() = runBlocking<Unit> {
        server.enqueue(MockResponse.Builder().code(301).setHeader("Location", "https://elsewhere.example/.well-known/nodeinfo").build())
        assertThat(client.nodeInfo()).isNull()

        server.enqueue(json("""{"links":[{"rel":"http://nodeinfo.diaspora.software/ns/schema/2.0","href":"/nodeinfo/2.0"}]}"""))
        server.enqueue(MockResponse.Builder().code(308).setHeader("Location", "/nodeinfo/2.0.json").build())
        assertThat(client.nodeInfo()).isNull()

        server.enqueue(MockResponse.Builder().code(302).setHeader("Location", "/").build())
        val meta = client.authorizationServerMetadata()
        assertThat(meta).isNull()
        assertThat(scopesFor(meta)).isEqualTo("write:statuses write:media read:statuses read:accounts")

        assertThat(server.requestCount).isEqualTo(4)
    }

    // The same fallback page on either NodeInfo request means no NodeInfo.
    @Test
    fun nodeInfoIsNullForAnHtmlPage() = runBlocking<Unit> {
        server.enqueue(html(AKKOMA_INDEX_HTML))
        assertThat(client.nodeInfo()).isNull()

        server.enqueue(json("""{"links":[{"rel":"http://nodeinfo.diaspora.software/ns/schema/2.1","href":"/nodeinfo/2.1.json"}]}"""))
        server.enqueue(html(AKKOMA_INDEX_HTML))
        assertThat(client.nodeInfo()).isNull()
        assertThat(server.requestCount).isEqualTo(3)
    }

    // ---- Sign-in ----

    @Test
    fun registerAppSendsTheFormFields() = runBlocking<Unit> {
        server.enqueue(
            json("""{"id":"563419","name":"Post","website":"https://example.org","scopes":["write:statuses"],
                "redirect_uri":"org.evsyukov.post://oauth/mastodon","redirect_uris":["org.evsyukov.post://oauth/mastodon"],
                "client_id":"cid-123","client_secret":"csecret-456","client_secret_expires_at":0}"""),
        )

        val app = client.registerApp("Post", listOf(REDIRECT_URI), "write:statuses write:media read:statuses profile", "https://example.org")

        val request = take()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.target).isEqualTo("/api/v1/apps")
        assertThat(request.form()).containsExactly(
            "client_name" to "Post",
            "redirect_uris" to REDIRECT_URI,
            "scopes" to "write:statuses write:media read:statuses profile",
            "website" to "https://example.org",
        ).inOrder()
        assertThat(app.clientId).isEqualTo("cid-123")
        assertThat(app.clientSecret).isEqualTo("csecret-456")
        assertThat(app.toString()).doesNotContain("csecret-456")
        assertThat(app.toString()).doesNotContain("cid-123")
    }

    @Test
    fun registerAppJoinsSeveralRedirectUrisAndLeavesOutAMissingWebsite() = runBlocking<Unit> {
        server.enqueue(json("""{"client_id":"a","client_secret":"b"}"""))

        client.registerApp("Post", listOf("org.evsyukov.post://oauth/mastodon", "urn:ietf:wg:oauth:2.0:oob"), "read", website = null)

        assertThat(take().form()).containsExactly(
            "client_name" to "Post",
            "redirect_uris" to "org.evsyukov.post://oauth/mastodon\nurn:ietf:wg:oauth:2.0:oob",
            "scopes" to "read",
        ).inOrder()
    }

    @Test
    fun authorizeUrlCarriesCodeFlowPkceAndForceLogin() {
        val url = client.authorizeUrl("cid", REDIRECT_URI, "write:statuses profile", "st-1", "chal-2")

        assertThat(url.encodedPath).isEqualTo("/oauth/authorize")
        assertThat(url.host).isEqualTo(server.url("/").host)
        assertThat(url.queryParameterNames).containsExactly(
            "response_type", "client_id", "redirect_uri", "scope", "state", "code_challenge", "code_challenge_method", "force_login",
        )
        assertThat(url.queryParameter("response_type")).isEqualTo("code")
        assertThat(url.queryParameter("client_id")).isEqualTo("cid")
        assertThat(url.queryParameter("redirect_uri")).isEqualTo(REDIRECT_URI)
        assertThat(url.queryParameter("scope")).isEqualTo("write:statuses profile")
        assertThat(url.queryParameter("state")).isEqualTo("st-1")
        assertThat(url.queryParameter("code_challenge")).isEqualTo("chal-2")
        assertThat(url.queryParameter("code_challenge_method")).isEqualTo("S256")
        assertThat(url.queryParameter("force_login")).isEqualTo("true")

        val withoutForce = client.authorizeUrl("cid", REDIRECT_URI, "read", "s", "c", forceLogin = false)
        assertThat(withoutForce.queryParameterNames).doesNotContain("force_login")
    }

    @Test
    fun exchangeCodeSendsTheAuthorizationCodeGrant() = runBlocking<Unit> {
        server.enqueue(json("""{"access_token":"$token","token_type":"Bearer","scope":"write:statuses profile","created_at":1573979017}"""))

        val result = client.exchangeCode("cid", "csecret", REDIRECT_URI, "code-abc", "verifier-xyz", "write:statuses profile")

        val request = take()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.target).isEqualTo("/oauth/token")
        assertThat(request.form()).containsExactly(
            "grant_type" to "authorization_code",
            "code" to "code-abc",
            "client_id" to "cid",
            "client_secret" to "csecret",
            "redirect_uri" to REDIRECT_URI,
            "code_verifier" to "verifier-xyz",
            "scope" to "write:statuses profile",
        ).inOrder()
        assertThat(result.accessToken).isEqualTo(token)
        assertThat(result.scope).isEqualTo("write:statuses profile")
        assertThat(result.createdAt).isEqualTo(1573979017)
        assertThat(result.toString()).doesNotContain(token)
    }

    @Test
    fun revokeTokenPostsClientCredentialsAndToken() = runBlocking<Unit> {
        server.enqueue(json("{}"))

        client.revokeToken("cid", "csecret", token)

        val request = take()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.target).isEqualTo("/oauth/revoke")
        assertThat(request.form()).containsExactly("client_id" to "cid", "client_secret" to "csecret", "token" to token).inOrder()
    }

    @Test
    fun verifyCredentialsReadsTheAccount() = runBlocking<Unit> {
        server.enqueue(
            json("""{"id":"14715","username":"trwnh","acct":"trwnh","display_name":"infinite love","locked":false,
                "avatar":"https://files.example/avatars/1.png","url":"https://mastodon.social/@trwnh","followers_count":1}"""),
        )

        val account = client.verifyCredentials(token)

        val request = take()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.target).isEqualTo("/api/v1/accounts/verify_credentials")
        request.assertBearer()
        assertThat(account).isEqualTo(Account("14715", "trwnh", "trwnh", "infinite love", "https://files.example/avatars/1.png", "https://mastodon.social/@trwnh"))
    }

    // ---- Media ----

    @Test
    fun uploadMediaSendsMultipartWithFileAltAndFocus() = runBlocking<Unit> {
        val file = tmp.newFile("a1b2.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4, 5)) }
        server.enqueue(json(MEDIA_JSON))

        val upload = client.uploadMedia(token, file, "image/jpeg", "A red apple", 0.5f to -0.25f)

        val request = take()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.target).isEqualTo("/api/v2/media")
        request.assertBearer()
        val parts = request.multipart()
        assertThat(parts.map { it.name }).containsExactly("file", "description", "focus").inOrder()
        assertThat(parts[0].fileName).isEqualTo("a1b2.jpg")
        assertThat(parts[0].contentType).isEqualTo("image/jpeg")
        assertThat(parts[0].bytes).isEqualTo(byteArrayOf(1, 2, 3, 4, 5))
        assertThat(parts[1].text).isEqualTo("A red apple")
        assertThat(parts[2].text).isEqualTo("0.50,-0.25")
        assertThat(upload).isEqualTo(MediaUpload("22348641", "image", processed = true, url = "https://files.example/original/e9.jpeg"))
    }

    // networks.md N7: video answers 202 and is polled.
    @Test
    fun uploadMediaReportsAsyncProcessingFor202AndOmitsNullFields() = runBlocking<Unit> {
        val file = tmp.newFile("clip.mp4").apply { writeBytes(ByteArray(64)) }
        server.enqueue(json(MEDIA_PROCESSING_JSON, 202))

        val upload = client.uploadMedia(token, file, "video/mp4", description = null, focus = null)

        assertThat(take().multipart().map { it.name }).containsExactly("file")
        assertThat(upload.processed).isFalse()
        assertThat(upload.type).isEqualTo("video")
        assertThat(upload.url).isNull()
    }

    @Test
    fun focusOutsideTheRangeIsRejectedBeforeAnyRequest() {
        val file = tmp.newFile("x.jpg")
        assertThrows(IllegalArgumentException::class.java) {
            runBlocking { client.uploadMedia(token, file, "image/jpeg", null, 1.5f to 0f) }
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    // product.md R29: a missing local file is not a network failure that a retry could fix.
    @Test
    fun aMissingOrUnreadableFileIsRejectedBeforeAnyRequest() {
        val missing = java.io.File(tmp.root, "gone.jpg")
        val directory = tmp.newFolder("dir.jpg")
        for (file in listOf(missing, directory)) {
            val e = assertThrows(IllegalArgumentException::class.java) {
                runBlocking { client.uploadMedia(token, file, "image/jpeg", "alt", null) }
            }
            assertThat(e.message).doesNotContain(token)
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun getMediaMapsProcessingReadyAndFailed() = runBlocking<Unit> {
        server.enqueue(json(MEDIA_PROCESSING_JSON, 206))
        server.enqueue(json(MEDIA_JSON, 200))
        server.enqueue(json("""{"error":"Error processing thumbnail for uploaded media"}""", 422))

        assertThat(client.getMedia(token, "22348641")).isEqualTo(MediaState.Processing)
        val ready = client.getMedia(token, "22348641")
        assertThat(ready).isInstanceOf(MediaState.Ready::class.java)
        assertThat((ready as MediaState.Ready).attachment.url).isEqualTo("https://files.example/original/e9.jpeg")
        assertThat(client.getMedia(token, "22348641")).isEqualTo(MediaState.Failed("Error processing thumbnail for uploaded media"))

        repeat(3) {
            val request = take()
            assertThat(request.method).isEqualTo("GET")
            assertThat(request.target).isEqualTo("/api/v1/media/22348641")
            request.assertBearer()
        }
    }

    @Test
    fun getMediaThrowsForOtherErrors() {
        server.enqueue(json("""{"error":"Record not found"}""", 404))
        val e = assertThrows(MastodonException::class.java) { runBlocking { client.getMedia(token, "1") } }
        assertThat(e.isNotFound).isTrue()
    }

    @Test
    fun updateMediaPutsAltTextAndFocusAsJson() = runBlocking<Unit> {
        server.enqueue(json(MEDIA_JSON))

        client.updateMedia(token, "22348641", "New alt", 0f to 1f)

        val request = take()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.target).isEqualTo("/api/v1/media/22348641")
        request.assertBearer()
        assertThat(request.jsonBody()).isEqualTo(buildJsonObject { put("description", "New alt"); put("focus", "0.00,1.00") })

        server.enqueue(json(MEDIA_JSON))
        client.updateMedia(token, "22348641", "Only alt", null)
        assertThat(take().jsonBody()).isEqualTo(buildJsonObject { put("description", "Only alt") })
    }

    @Test
    fun deleteMediaIgnores404() = runBlocking<Unit> {
        server.enqueue(json("{}"))
        server.enqueue(json("""{"error":"Record not found"}""", 404))
        server.enqueue(json("""{"error":"boom"}""", 500))

        client.deleteMedia(token, "7")
        client.deleteMedia(token, "8")
        val e = assertThrows(MastodonException::class.java) { runBlocking { client.deleteMedia(token, "9") } }
        assertThat(e.isTransient).isTrue()

        val first = take()
        assertThat(first.method).isEqualTo("DELETE")
        assertThat(first.target).isEqualTo("/api/v1/media/7")
        first.assertBearer()
    }

    // ---- Statuses ----

    @Test
    fun createStatusSendsEveryFieldAsJsonWithIdempotencyKey() = runBlocking<Unit> {
        server.enqueue(json(STATUS_JSON))
        val params = StatusParams(
            status = "Hello *there*",
            mediaIds = listOf("m1", "m2"),
            spoilerText = "cw",
            sensitive = true,
            visibility = Visibility.UNLISTED,
            language = "en",
            inReplyToId = "103254962155278800",
            quoteApprovalPolicy = "followers",
            contentType = "text/markdown",
        )

        val status = client.createStatus(token, params, "key-part-1")

        val request = take()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.target).isEqualTo("/api/v1/statuses")
        request.assertBearer()
        assertThat(request.headers["Idempotency-Key"]).isEqualTo("key-part-1")
        assertThat(request.jsonBody()).isEqualTo(
            buildJsonObject {
                put("status", "Hello *there*")
                put("media_ids", buildJsonArray { add(JsonPrimitive("m1")); add(JsonPrimitive("m2")) })
                put("spoiler_text", "cw")
                put("sensitive", true)
                put("visibility", "unlisted")
                put("language", "en")
                put("in_reply_to_id", "103254962155278800")
                put("quote_approval_policy", "followers")
                put("content_type", "text/markdown")
            },
        )
        assertThat(status.id).isEqualTo("103254962155278888")
        assertThat(status.url).isEqualTo("https://mastodon.example/@me/103254962155278888")
        assertThat(status.inReplyToId).isEqualTo("103254962155278800")
        assertThat(status.visibility).isEqualTo("unlisted")
        assertThat(status.mediaAttachments.single().description).isEqualTo("alt")
    }

    @Test
    fun createStatusLeavesOutNullFieldsAndSendsAPoll() = runBlocking<Unit> {
        server.enqueue(json(STATUS_JSON))
        val params = StatusParams(status = "Which?", poll = PollParams(listOf("A", "B"), expiresInSeconds = 86_400, multiple = true))

        client.createStatus(token, params, "k")

        assertThat(take().jsonBody()).isEqualTo(
            buildJsonObject {
                put("status", "Which?")
                put("sensitive", false)
                put("visibility", "public")
                put(
                    "poll",
                    buildJsonObject {
                        put("options", buildJsonArray { add(JsonPrimitive("A")); add(JsonPrimitive("B")) })
                        put("expires_in", 86_400)
                        put("multiple", true)
                    },
                )
            },
        )
    }

    @Test
    fun visibilityHasTheWireValuesOfTheApi() {
        assertThat(Visibility.entries.map { it.wire }).containsExactly("public", "unlisted", "private", "direct").inOrder()
    }

    @Test
    fun editStatusPutsTheWholeNewStateWithMediaAttributes() = runBlocking<Unit> {
        server.enqueue(json(STATUS_JSON))
        val params = StatusEditParams(
            status = "Edited",
            mediaIds = listOf("m1", "m3"),
            mediaAttributes = listOf(MediaAttribute("m1", "new alt", -0.5f to 0.5f), MediaAttribute("m3", description = "third")),
            language = "de",
        )

        client.editStatus(token, "109", params)

        val request = take()
        assertThat(request.method).isEqualTo("PUT")
        assertThat(request.target).isEqualTo("/api/v1/statuses/109")
        request.assertBearer()
        assertThat(request.headers["Idempotency-Key"]).isNull()
        val body = request.jsonBody()
        assertThat(body).isEqualTo(
            buildJsonObject {
                put("status", "Edited")
                put("spoiler_text", "")
                put("sensitive", false)
                put("language", "de")
                put("media_ids", buildJsonArray { add(JsonPrimitive("m1")); add(JsonPrimitive("m3")) })
                put(
                    "media_attributes",
                    buildJsonArray {
                        add(buildJsonObject { put("id", "m1"); put("description", "new alt"); put("focus", "-0.50,0.50") })
                        add(buildJsonObject { put("id", "m3"); put("description", "third") })
                    },
                )
            },
        )
        // Visibility and the reply target cannot change in an edit (N28).
        assertThat(body.keys).containsNoneOf("visibility", "in_reply_to_id")
    }

    // N30: removing every attachment sends an empty media_ids, not nothing.
    @Test
    fun editStatusSendsEmptyMediaIdsWhenAllMediaAreRemoved() = runBlocking<Unit> {
        server.enqueue(json(STATUS_JSON))

        client.editStatus(token, "109", StatusEditParams(status = "Text only", quoteApprovalPolicy = "nobody", contentType = "text/plain"))

        val body = take().jsonBody()
        assertThat(body["media_ids"]!!.jsonArray).isEmpty()
        assertThat(body.keys).doesNotContain("media_attributes")
        assertThat(body["quote_approval_policy"]!!.jsonPrimitive.content).isEqualTo("nobody")
        assertThat(body["content_type"]!!.jsonPrimitive.content).isEqualTo("text/plain")
    }

    // N28: Api::V1::StatusesController#update always passes :text, and UpdateStatusService sets
    // the text to '' when it is absent, so a media-only edit sends the empty text explicitly.
    @Test
    fun editStatusWithoutTextSendsAnEmptyText() = runBlocking<Unit> {
        server.enqueue(json(STATUS_JSON))

        client.editStatus(token, "109", StatusEditParams(status = null, mediaIds = listOf("m1")))

        val body = take().jsonBody()
        assertThat(body["status"]!!.jsonPrimitive.content).isEqualTo("")
        assertThat(body["spoiler_text"]!!.jsonPrimitive.content).isEqualTo("")
        assertThat(body["media_ids"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("m1")
    }

    @Test
    fun deleteStatusIsDeletedOr404AlreadyGone() = runBlocking<Unit> {
        server.enqueue(json(STATUS_JSON))
        server.enqueue(json("""{"error":"Record not found"}""", 404))

        assertThat(client.deleteStatus(token, "109")).isEqualTo(DeleteResult.Deleted)
        assertThat(client.deleteStatus(token, "110")).isEqualTo(DeleteResult.AlreadyGone)

        val request = take()
        assertThat(request.method).isEqualTo("DELETE")
        assertThat(request.target).isEqualTo("/api/v1/statuses/109")
        request.assertBearer()
        assertThat(take().target).isEqualTo("/api/v1/statuses/110")
    }

    @Test
    fun anIdIsOnePathSegment() = runBlocking<Unit> {
        server.enqueue(json("{}", 404))

        client.deleteStatus(token, "../../oauth/revoke")

        assertThat(take().target).isEqualTo("/api/v1/statuses/..%2F..%2Foauth%2Frevoke")
    }

    // OkHttp resolves "." and ".." even as one segment: DELETE /api/v1/statuses/.. would go to /api/v1/.
    // An empty id would address the collection, and a 404 there would read as "already gone".
    @Test
    fun dotAndEmptyIdsAreRefusedBeforeAnyRequest() {
        for (id in listOf("", ".", "..")) {
            assertThrows(IllegalArgumentException::class.java) { runBlocking { client.deleteStatus(token, id) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { client.editStatus(token, id, StatusEditParams(status = "x")) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { client.getMedia(token, id) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { client.updateMedia(token, id, "alt", null) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { client.deleteMedia(token, id) } }
            assertThrows(IllegalArgumentException::class.java) { runBlocking { client.accountStatuses(token, id) } }
        }
        assertThat(server.requestCount).isEqualTo(0)
    }

    @Test
    fun idsWithDotsInsideStayOneSegment() = runBlocking<Unit> {
        server.enqueue(json("{}", 404))
        client.deleteStatus(token, "...")
        assertThat(take().target).isEqualTo("/api/v1/statuses/...")
    }

    @Test
    fun accountStatusesListsOwnStatusesWithoutBoosts() = runBlocking<Unit> {
        server.enqueue(
            json(
                """[$STATUS_JSON,
                   {"id":"2","created_at":"2019-12-05T08:19:26.052Z","in_reply_to_id":null,"visibility":"public",
                    "url":"https://mastodon.example/@me/2","content":"<p>test</p>","text":"test","media_attachments":[]}]""",
            ),
        )

        val statuses = client.accountStatuses(token, "14715")

        val request = take()
        assertThat(request.method).isEqualTo("GET")
        assertThat(request.url.encodedPath).isEqualTo("/api/v1/accounts/14715/statuses")
        assertThat(request.url.queryParameter("limit")).isEqualTo("40")
        assertThat(request.url.queryParameter("exclude_reblogs")).isEqualTo("true")
        request.assertBearer()
        assertThat(statuses.map { it.id }).containsExactly("103254962155278888", "2").inOrder()
        assertThat(statuses[0].content).isEqualTo("<p>test content</p>")
        assertThat(statuses[0].text).isNull()
        assertThat(statuses[1].text).isEqualTo("test")
        assertThat(statuses[1].createdAtInstant()).isEqualTo(java.time.Instant.parse("2019-12-05T08:19:26.052Z"))
        assertThat(statuses[1].inReplyToId).isNull()
        assertThat(statuses[1].visibility).isEqualTo("public")
        assertThat(statuses[1].url).isEqualTo("https://mastodon.example/@me/2")

        server.enqueue(json("[]"))
        client.accountStatuses(token, "14715", limit = 5)
        assertThat(take().url.queryParameter("limit")).isEqualTo("5")
    }

    @Test
    fun originMustBeAServerRoot() {
        assertThrows(IllegalArgumentException::class.java) { MastodonClient("https://mastodon.example/api/".toHttpUrl(), OkHttpClient()) }
        assertThrows(IllegalArgumentException::class.java) { MastodonClient("https://user:pw@mastodon.example/".toHttpUrl(), OkHttpClient()) }
        assertThrows(IllegalArgumentException::class.java) { MastodonClient("https://mastodon.example/?a=b".toHttpUrl(), OkHttpClient()) }
        MastodonClient("https://mastodon.example".toHttpUrl(), OkHttpClient())
    }
}
