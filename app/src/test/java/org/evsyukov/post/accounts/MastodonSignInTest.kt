package org.evsyukov.post.accounts

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.HttpUrl
import org.evsyukov.post.core.mastodon.Pkce
import org.evsyukov.post.core.mastodon.REDIRECT_URI
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.MutableClock
import org.evsyukov.post.data.RecordingFiles
import org.evsyukov.post.data.accounts.AccountLimits
import org.evsyukov.post.data.accounts.AccountsRepository
import org.evsyukov.post.data.accounts.RoomAccountsRepository
import org.evsyukov.post.data.accounts.RoomMastodonAppsRepository
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.db.DeliveryState
import org.evsyukov.post.data.inMemoryDatabase
import org.evsyukov.post.data.posts.DraftInput
import org.evsyukov.post.data.posts.RoomPostsRepository
import org.evsyukov.post.data.posts.Schedule
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.secrets.SecretStore
import org.evsyukov.post.data.settings.HistoryRetention
import org.evsyukov.post.oauth.FilePendingSignInStore
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.URLEncoder
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * Mastodon sign-in against a fake instance (networks.md N1, N4–N6; plan.md "Mastodon OAuth"): instance
 * documents, OAuth metadata, app registration, PKCE code exchange, verify_credentials and revoke.
 */
@RunWith(AndroidJUnit4::class)
class MastodonSignInTest {
    private val clock = MutableClock(Instant.parse("2026-11-16T08:00:00Z"))
    private val db = inMemoryDatabase()
    private val secrets = SecretStore(FakeCipher())
    private val accounts: AccountsRepository = RoomAccountsRepository(db, secrets, clock)
    private val apps = RoomMastodonAppsRepository(db, secrets)
    private val pendingFile = File(ApplicationProvider.getApplicationContext<android.content.Context>().noBackupFilesDir, "pending-${System.nanoTime()}.json")
    private val servers = FakeServers()
    private val host = "mastodon.example"
    private val registrations = AtomicInteger()
    private var tokenResponses = ArrayDeque<Pair<Int, String>>()

    /** The answer to an app token request (`grant_type=client_credentials`); the server knows the client by default. */
    private var appTokenResponse: Pair<Int, String> = 200 to """{"access_token":"app-token","token_type":"Bearer"}"""
    private var accountJson = """{"id":"42","username":"me","acct":"me","display_name":"Me Myself","avatar":"https://$host/avatars/me.png"}"""

    init {
        servers.json("GET", "$host/api/v2/instance", """{"domain":"$host","version":"4.5.0","api_versions":{"mastodon":7},
            "configuration":{"statuses":{"max_characters":1000,"max_media_attachments":6,"characters_reserved_per_url":23},
            "media_attachments":{"supported_mime_types":["image/jpeg","image/png","video/mp4"],"image_size_limit":10485760,"video_size_limit":41943040}}}""")
        servers.json("GET", "$host/api/v1/instance", """{"uri":"$host","version":"4.5.0"}""")
        servers.route("POST", "$host/api/v1/apps") {
            val n = registrations.incrementAndGet()
            FakeServers.json("""{"client_id":"client-$n","client_secret":"secret-$n"}""")
        }
        servers.route("POST", "$host/oauth/token") { request ->
            val (code, body) = if (request.form("grant_type") == "client_credentials") {
                appTokenResponse
            } else {
                tokenResponses.removeFirstOrNull() ?: (200 to """{"access_token":"token-1","token_type":"Bearer"}""")
            }
            FakeServers.json(body, code)
        }
        servers.route("GET", "$host/api/v1/accounts/verify_credentials") { FakeServers.json(accountJson) }
        servers.json("POST", "$host/oauth/revoke", "{}")
    }

    private fun signIn() = MastodonSignIn(accounts, apps, FilePendingSignInStore(pendingFile), servers.http, ServerPolicy(false), clock)

    @After
    fun tearDown() {
        servers.close()
        db.close()
        pendingFile.delete()
    }

    private fun redirect(url: HttpUrl, code: String = "the-code") =
        "$REDIRECT_URI?code=$code&state=${URLEncoder.encode(url.queryParameter("state")!!, Charsets.UTF_8)}"

    @Test
    fun aNewAccountSignsInWithPkceAndGetsItsProfileTokenAndLimits() = runBlocking<Unit> {
        val flow = signIn()

        val url = flow.start("@me@$host")

        // N4: registered once, with Post's name, website, redirect and the scopes for a server without
        // OAuth metadata (read:accounts instead of profile).
        val registration = servers.requests("$host/api/v1/apps").single()
        assertThat(registration.form("client_name")).isEqualTo("Post")
        assertThat(registration.form("website")).isEqualTo("https://github.com/juev/post")
        assertThat(registration.form("redirect_uris")).isEqualTo(REDIRECT_URI)
        assertThat(registration.form("scopes")).isEqualTo("write:statuses write:media read:statuses read:accounts")
        // N5: the authorization address with PKCE S256 and the persisted state.
        assertThat(url.host).isEqualTo(host)
        assertThat(url.encodedPath).isEqualTo("/oauth/authorize")
        assertThat(url.queryParameter("client_id")).isEqualTo("client-1")
        assertThat(url.queryParameter("redirect_uri")).isEqualTo(REDIRECT_URI)
        assertThat(url.queryParameter("code_challenge_method")).isEqualTo("S256")
        assertThat(url.queryParameter("force_login")).isEqualTo("true")
        val pending = FilePendingSignInStore(pendingFile).get()!!
        assertThat(pending.state).isEqualTo(url.queryParameter("state"))
        assertThat(url.queryParameter("code_challenge")).isEqualTo(Pkce.challenge(pending.codeVerifier))

        val done = flow.complete(redirect(url)) as MastodonSignIn.Completion.Done

        val exchange = servers.requests("$host/oauth/token").single()
        assertThat(exchange.form("code")).isEqualTo("the-code")
        assertThat(exchange.form("code_verifier")).isEqualTo(pending.codeVerifier)
        assertThat(exchange.form("client_secret")).isEqualTo("secret-1")
        // N6: the profile comes from verify_credentials with the new token, to the instance only (R4).
        val verify = servers.requests("$host/api/v1/accounts/verify_credentials").single()
        assertThat(verify.headers["Authorization"]).isEqualTo("Bearer token-1")
        assertThat(servers.seen.map { it.host }.toSet()).containsExactly(host)

        val account = accounts.get(done.accountId)!!
        assertThat(account.network).isEqualTo(Network.MASTODON)
        assertThat(account.server).isEqualTo("https://$host")
        assertThat(account.handle).isEqualTo("me@$host")
        assertThat(account.remoteId).isEqualTo("42")
        assertThat(account.displayName).isEqualTo("Me Myself")
        assertThat(account.avatarUrl).isEqualTo("https://$host/avatars/me.png")
        assertThat(account.state).isEqualTo(AccountState.OK)
        assertThat(accounts.readSecret(done.accountId)).isEqualTo(MastodonSecret("token-1"))
        // N1: the limits read at the start are stored with the time.
        val limits = account.limits as AccountLimits.Mastodon
        assertThat(limits.capabilities.maxCharacters).isEqualTo(1000)
        assertThat(limits.capabilities.maxMediaAttachments).isEqualTo(6)
        assertThat(account.limitsFetchedAt).isEqualTo(clock.now)
        assertThat(FilePendingSignInStore(pendingFile).get()).isNull()
    }

    @Test
    fun aServerThatListsTheProfileScopeGetsIt() = runBlocking<Unit> {
        servers.json("GET", "$host/.well-known/oauth-authorization-server", """{"issuer":"https://$host","scopes_supported":["read","profile","write:statuses"]}""")

        val url = signIn().start(host)

        assertThat(url.queryParameter("scope")).isEqualTo("write:statuses write:media read:statuses profile")
    }

    @Test
    fun theRegistrationOfAnInstanceIsReused() = runBlocking<Unit> {
        signIn().start(host)
        signIn().start("https://$host/")

        assertThat(registrations.get()).isEqualTo(1)
    }

    @Test
    fun aRegistrationTheServerForgotIsMadeAgainBeforeTheBrowserOpens() = runBlocking<Unit> {
        signIn().start(host)
        // The server removed the registration, for example when its last token was revoked.
        appTokenResponse = 401 to """{"error":"invalid_client","error_description":"Client authentication failed"}"""

        val url = signIn().start(host)

        assertThat(registrations.get()).isEqualTo(2)
        assertThat(url.queryParameter("client_id")).isEqualTo("client-2")
        assertThat(apps.get("https://$host")!!.clientId).isEqualTo("client-2")
    }

    @Test
    fun aRegistrationIsKeptWhenTheCheckFailsForAnotherReason() = runBlocking<Unit> {
        signIn().start(host)
        // A server that does not give app tokens, or is down for a moment, says nothing about the registration.
        appTokenResponse = 400 to """{"error":"unsupported_grant_type"}"""

        val url = signIn().start(host)

        assertThat(registrations.get()).isEqualTo(1)
        assertThat(url.queryParameter("client_id")).isEqualTo("client-1")
    }

    @Test
    fun aRedirectWithAnotherStateIsRejectedWithoutAnyRequest() = runBlocking<Unit> {
        val flow = signIn()
        val url = flow.start(host)
        val before = servers.seen.size

        val e = assertThrows(SignInException::class.java) {
            runBlocking { flow.complete("$REDIRECT_URI?code=stolen&state=forged") }
        }
        val noState = assertThrows(SignInException::class.java) { runBlocking { flow.complete("$REDIRECT_URI?code=stolen") } }
        val otherAddress = assertThrows(SignInException::class.java) {
            runBlocking { flow.complete("https://evil.example/oauth/mastodon?code=x&state=${url.queryParameter("state")}") }
        }

        assertThat(e.problem).isEqualTo(SignInProblem.UNKNOWN_STATE)
        assertThat(noState.problem).isEqualTo(SignInProblem.UNKNOWN_STATE)
        assertThat(otherAddress.problem).isEqualTo(SignInProblem.UNKNOWN_STATE)
        assertThat(servers.seen.size).isEqualTo(before)
        // The real redirect still completes.
        assertThat(flow.complete(redirect(url))).isInstanceOf(MastodonSignIn.Completion.Done::class.java)
    }

    @Test
    fun withoutAPendingSignInEveryRedirectIsRejected() = runBlocking<Unit> {
        val e = assertThrows(SignInException::class.java) { runBlocking { signIn().complete("$REDIRECT_URI?code=c&state=s") } }

        assertThat(e.problem).isEqualTo(SignInProblem.UNKNOWN_STATE)
        assertThat(servers.seen).isEmpty()
    }

    @Test
    fun theSignInCompletesAfterTheProcessDied() = runBlocking<Unit> {
        val url = signIn().start(host)

        // A new process: new objects, the same files and database.
        val afterRestart = signIn()
        val done = afterRestart.complete(redirect(url)) as MastodonSignIn.Completion.Done

        assertThat(accounts.get(done.accountId)!!.handle).isEqualTo("me@$host")
    }

    @Test
    fun invalidClientRegistersAgainOnceAndRestarts() = runBlocking<Unit> {
        tokenResponses.add(401 to """{"error":"invalid_client","error_description":"Client authentication failed"}""")
        val flow = signIn()
        val first = flow.start(host)

        val restart = flow.complete(redirect(first)) as MastodonSignIn.Completion.Restart

        assertThat(registrations.get()).isEqualTo(2)
        assertThat(apps.get("https://$host")!!.clientId).isEqualTo("client-2")
        assertThat(restart.url.queryParameter("client_id")).isEqualTo("client-2")
        assertThat(restart.url.queryParameter("state")).isNotEqualTo(first.queryParameter("state"))
        // The old state is gone; the new one completes.
        assertThat(assertThrows(SignInException::class.java) { runBlocking { flow.complete(redirect(first)) } }.problem)
            .isEqualTo(SignInProblem.UNKNOWN_STATE)
        val done = flow.complete(redirect(restart.url)) as MastodonSignIn.Completion.Done
        assertThat(accounts.get(done.accountId)).isNotNull()
        assertThat(servers.requests("$host/oauth/token").last().form("client_id")).isEqualTo("client-2")
    }

    @Test
    fun aSecondInvalidClientFailsInsteadOfLooping() = runBlocking<Unit> {
        repeat(2) { tokenResponses.add(401 to """{"error":"invalid_client"}""") }
        val flow = signIn()
        val restart = flow.complete(redirect(flow.start(host))) as MastodonSignIn.Completion.Restart

        val e = assertThrows(SignInException::class.java) { runBlocking { flow.complete(redirect(restart.url)) } }

        assertThat(e.problem).isEqualTo(SignInProblem.CLIENT_REJECTED)
        assertThat(registrations.get()).isEqualTo(2)
        assertThat(FilePendingSignInStore(pendingFile).get()).isNull()
        assertThat(accounts.observe().first()).isEmpty()
    }

    @Test
    fun theSameAccountSigningInAgainGetsTheNewTokenInsteadOfADuplicate() = runBlocking<Unit> {
        val flow = signIn()
        val id = (flow.complete(redirect(flow.start(host))) as MastodonSignIn.Completion.Done).accountId
        accounts.setLabel(id, "work")
        accounts.setState(id, AccountState.NEEDS_SIGN_IN)
        tokenResponses.add(200 to """{"access_token":"token-2"}""")

        val again = (flow.complete(redirect(flow.start(host, accountId = id))) as MastodonSignIn.Completion.Done).accountId

        assertThat(again).isEqualTo(id)
        assertThat(accounts.observe().first()).hasSize(1)
        assertThat(accounts.readSecret(id)).isEqualTo(MastodonSecret("token-2"))
        assertThat(accounts.get(id)!!.state).isEqualTo(AccountState.OK)
        assertThat(accounts.get(id)!!.label).isEqualTo("work")
    }

    @Test
    fun anotherAccountSigningInAgainIsRejectedAndTheStoppedOneStaysAsItWas() = runBlocking<Unit> {
        val signedIn = mutableListOf<Long>()
        val flow = MastodonSignIn(accounts, apps, FilePendingSignInStore(pendingFile), servers.http, ServerPolicy(false), clock) { signedIn += it }
        val id = (flow.complete(redirect(flow.start(host))) as MastodonSignIn.Completion.Done).accountId
        accounts.setState(id, AccountState.NEEDS_SIGN_IN)
        signedIn.clear()
        val me = accountJson
        accountJson = """{"id":"43","username":"bob","acct":"bob","display_name":"Bob"}"""
        tokenResponses.add(200 to """{"access_token":"token-bob"}""")

        val e = assertThrows(SignInException::class.java) { runBlocking { flow.complete(redirect(flow.start(host, accountId = id))) } }

        assertThat(e.problem).isEqualTo(SignInProblem.WRONG_ACCOUNT)
        assertThat(e.mismatch).isEqualTo(AccountMismatch(signedIn = "bob@$host", expected = "me@$host"))
        // R5: no second account; the stopped one keeps its state, token and deliveries.
        assertThat(accounts.observe().first().map { it.id }).containsExactly(id)
        assertThat(accounts.get(id)!!.state).isEqualTo(AccountState.NEEDS_SIGN_IN)
        assertThat(accounts.readSecret(id)).isEqualTo(MastodonSecret("token-1"))
        assertThat(signedIn).isEmpty()
        // Post keeps nothing of the other account: its token is revoked.
        assertThat(servers.requests("$host/oauth/revoke").map { it.form("token") }).contains("token-bob")
        // The pending sign-in stays, and signing in as the right account completes the next try.
        assertThat(FilePendingSignInStore(pendingFile).get()!!.accountId).isEqualTo(id)
        accountJson = me
        tokenResponses.add(200 to """{"access_token":"token-2"}""")

        val again = (flow.complete(redirect(flow.start(host, accountId = id))) as MastodonSignIn.Completion.Done).accountId

        assertThat(again).isEqualTo(id)
        assertThat(accounts.get(id)!!.state).isEqualTo(AccountState.OK)
        assertThat(accounts.readSecret(id)).isEqualTo(MastodonSecret("token-2"))
        assertThat(signedIn).containsExactly(id)
    }

    @Test
    fun theSameAccountIdOnAnotherInstanceIsAnotherAccount() = runBlocking<Unit> {
        val other = "other.example"
        servers.json("GET", "$other/api/v2/instance", """{"domain":"$other"}""")
        servers.json("POST", "$other/api/v1/apps", """{"client_id":"other-client","client_secret":"other-secret"}""")
        servers.json("POST", "$other/oauth/token", """{"access_token":"other-token"}""")
        servers.json("GET", "$other/api/v1/accounts/verify_credentials", """{"id":"42","username":"me","acct":"me"}""")
        servers.json("POST", "$other/oauth/revoke", "{}")
        val flow = signIn()
        val id = (flow.complete(redirect(flow.start(host))) as MastodonSignIn.Completion.Done).accountId

        val e = assertThrows(SignInException::class.java) { runBlocking { flow.complete(redirect(flow.start(other, accountId = id))) } }

        assertThat(e.problem).isEqualTo(SignInProblem.WRONG_ACCOUNT)
        assertThat(e.mismatch).isEqualTo(AccountMismatch(signedIn = "me@$other", expected = "me@$host"))
        assertThat(accounts.observe().first()).hasSize(1)
        assertThat(accounts.get(id)!!.server).isEqualTo("https://$host")
        assertThat(servers.requests("$other/oauth/revoke").single().form("token")).isEqualTo("other-token")
    }

    @Test
    fun anAccountRemovedDuringItsSignInIsAddedAgain() = runBlocking<Unit> {
        val flow = signIn()
        val id = (flow.complete(redirect(flow.start(host))) as MastodonSignIn.Completion.Done).accountId
        val url = flow.start(host, accountId = id)
        accounts.remove(id)

        val done = flow.complete(redirect(url)) as MastodonSignIn.Completion.Done

        assertThat(accounts.get(done.accountId)!!.handle).isEqualTo("me@$host")
    }

    @Test
    fun aDeniedAuthorizationClearsThePendingSignIn() = runBlocking<Unit> {
        val flow = signIn()
        val url = flow.start(host)

        val e = assertThrows(SignInException::class.java) {
            runBlocking { flow.complete("$REDIRECT_URI?error=access_denied&state=${url.queryParameter("state")}") }
        }

        assertThat(e.problem).isEqualTo(SignInProblem.DENIED)
        assertThat(FilePendingSignInStore(pendingFile).get()).isNull()
        assertThat(servers.requests("$host/oauth/token")).isEmpty()
    }

    @Test
    fun aFailedExchangeClearsThePendingSignInAndReportsTheServer() = runBlocking<Unit> {
        tokenResponses.add(400 to """{"error":"invalid_grant"}""")
        val flow = signIn()

        val e = assertThrows(SignInException::class.java) { runBlocking { flow.complete(redirect(flow.start(host))) } }

        assertThat(e.problem).isEqualTo(SignInProblem.SERVER)
        assertThat(FilePendingSignInStore(pendingFile).get()).isNull()
    }

    @Test
    fun aServerWithoutInstanceDocumentsIsNotMastodon() = runBlocking<Unit> {
        val e = assertThrows(SignInException::class.java) { runBlocking { signIn().start("other.example") } }

        assertThat(e.problem).isEqualTo(SignInProblem.NOT_MASTODON)
        assertThat(FilePendingSignInStore(pendingFile).get()).isNull()
    }

    @Test
    fun anInvalidOrPlainHttpAddressFailsBeforeAnyRequest() = runBlocking<Unit> {
        for (input in listOf("not a server", "http://$host")) {
            val e = assertThrows(SignInException::class.java) { runBlocking { signIn().start(input) } }
            assertThat(e.problem).isEqualTo(SignInProblem.INVALID_ADDRESS)
        }
        assertThat(servers.seen).isEmpty()
    }

    @Test
    fun removingAMastodonAccountRevokesTheTokenAndFailsItsDeliveries() = runBlocking<Unit> {
        val flow = signIn()
        val id = (flow.complete(redirect(flow.start(host))) as MastodonSignIn.Completion.Done).accountId
        val posts = RoomPostsRepository(db, RecordingFiles(), { HistoryRetention.DAYS_30 }, clock)
        val post = posts.saveDraft(DraftInput(text = "hello", targets = listOf(id)))
        posts.queue(post, Schedule.Now(clock.now), listOf(id))

        assertThat(AccountRemover(accounts, apps, servers.http).remove(id)).isTrue()

        // N5: the token is revoked with the app's credentials.
        val revoke = servers.requests("$host/oauth/revoke").single()
        assertThat(revoke.form("token")).isEqualTo("token-1")
        assertThat(revoke.form("client_id")).isEqualTo("client-1")
        // R1: the account is gone and its delivery failed with the reason.
        assertThat(accounts.get(id)).isNull()
        val delivery = posts.get(post)!!.deliveries.single()
        assertThat(delivery.state).isEqualTo(DeliveryState.FAILED)
        assertThat(delivery.error).isEqualTo(AccountsRepository.ACCOUNT_REMOVED)
    }

    @Test
    fun anAccountIsRemovedEvenWhenTheRevokeFails() = runBlocking<Unit> {
        val flow = signIn()
        val id = (flow.complete(redirect(flow.start(host))) as MastodonSignIn.Completion.Done).accountId
        servers.json("POST", "$host/oauth/revoke", """{"error":"server"}""", code = 503)

        assertThat(AccountRemover(accounts, apps, servers.http).remove(id)).isTrue()

        assertThat(servers.requests("$host/oauth/revoke")).hasSize(1)
        assertThat(accounts.get(id)).isNull()
    }
}
