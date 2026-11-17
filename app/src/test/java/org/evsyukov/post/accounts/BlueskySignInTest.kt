package org.evsyukov.post.accounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.MutableClock
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.accounts.RoomAccountsRepository
import org.evsyukov.post.data.containsBytes
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.inMemoryDatabase
import org.evsyukov.post.data.rows
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.data.secrets.SecretStore
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/**
 * Bluesky sign-in with an app password against a fake AppView, PLC directory and PDS (networks.md
 * N13–N15): handle → DID → document → PDS, createSession, the e-mail code, and the profile.
 */
@RunWith(AndroidJUnit4::class)
class BlueskySignInTest {
    private val clock = MutableClock(Instant.parse("2026-11-16T08:00:00Z"))
    private val db = inMemoryDatabase()
    private val accounts = RoomAccountsRepository(db, SecretStore(FakeCipher()), clock)
    private val servers = FakeServers()
    private val did = "did:plc:ewvi7nxzyoun6zhxrhs64oiz"
    private val handle = "alice.example.com"
    private val password = "abcd-efgh-ijkl-mnop"
    private val appView = "public.api.bsky.app"
    private val pds = "pds.example.net"

    init {
        servers.route("GET", "$appView/xrpc/com.atproto.identity.resolveHandle") {
            if (it.url.queryParameter("handle") == handle) FakeServers.json("""{"did":"$did"}""")
            else FakeServers.json("""{"error":"HandleNotFound","message":"Unable to resolve handle"}""", 400)
        }
        servers.json("GET", "plc.directory/$did", document(handle))
        servers.json("GET", "$appView/xrpc/app.bsky.actor.getProfile", """{"did":"$did","handle":"$handle","displayName":"Alice","avatar":"https://cdn.bsky.app/img/avatar/plain/$did/a@jpeg"}""")
        session("access-1", "refresh-1")
    }

    private fun document(claimed: String, endpoint: String = "https://$pds") =
        """{"id":"$did","alsoKnownAs":["at://$claimed"],
            "service":[{"id":"#atproto_pds","type":"AtprotoPersonalDataServer","serviceEndpoint":"$endpoint"}]}"""

    private fun session(access: String, refresh: String, extra: String = "") =
        servers.json("POST", "$pds/xrpc/com.atproto.server.createSession", """{"did":"$did","handle":"$handle","accessJwt":"$access","refreshJwt":"$refresh"$extra}""")

    private fun signIn(policy: ServerPolicy = ServerPolicy(false)) = BlueskySignIn(accounts, servers.http, policy)

    @After
    fun tearDown() {
        servers.close()
        db.close()
    }

    @Test
    fun aHandleAndAppPasswordAddAnAccountWithItsSessionAndProfile() = runBlocking<Unit> {
        val done = signIn().signIn("@Alice.Example.com ", password) as BlueskySignIn.Result.Done

        val account = accounts.get(done.accountId)!!
        assertThat(account.network).isEqualTo(Network.BLUESKY)
        assertThat(account.server).isEqualTo("https://$pds")
        assertThat(account.handle).isEqualTo(handle)
        assertThat(account.remoteId).isEqualTo(did)
        assertThat(account.displayName).isEqualTo("Alice")
        assertThat(account.avatarUrl).isEqualTo("https://cdn.bsky.app/img/avatar/plain/$did/a@jpeg")
        assertThat(accounts.readSecret(done.accountId)).isEqualTo(BlueskySecret("access-1", "refresh-1"))
        // The DID signs in, at the PDS of its document.
        val create = servers.requests("$pds/xrpc/com.atproto.server.createSession").single()
        val body = Json.parseToJsonElement(create.body).jsonObject
        assertThat(body["identifier"]!!.jsonPrimitive.content).isEqualTo(did)
        assertThat(body["password"]!!.jsonPrimitive.content).isEqualTo(password)
    }

    @Test
    fun thePasswordGoesToThePdsOnlyAndIsNeverStored() = runBlocking<Unit> {
        signIn().signIn(handle, password)

        // R4, N15: only the PDS saw the password; the AppView and the PLC directory got no credentials.
        assertThat(servers.seen.filter { password in it.body || password in it.url.toString() }.map { it.host }.toSet()).containsExactly(pds)
        assertThat(servers.seen.filter { it.host != pds }.mapNotNull { it.headers["Authorization"] }).isEmpty()
        // Nothing in the database holds the password, sealed or not.
        val sealed = FakeCipher().encrypt(password.encodeToByteArray())
        for (row in db.rows("SELECT * FROM accounts")) {
            for (value in row) {
                val bytes = when (value) {
                    is ByteArray -> value
                    is String -> value.encodeToByteArray()
                    else -> continue
                }
                assertThat(bytes.containsBytes(password.encodeToByteArray())).isFalse()
                assertThat(bytes.containsBytes(sealed.copyOfRange(3, sealed.size))).isFalse()
            }
        }
        val secret = accounts.readSecret(accounts.observe().first().single().id)
        assertThat(secret.toString()).doesNotContain("access-1")
    }

    @Test
    fun aDidSignsInWithTheHandleItsDocumentClaims() = runBlocking<Unit> {
        val done = signIn().signIn(did, password) as BlueskySignIn.Result.Done

        assertThat(accounts.get(done.accountId)!!.handle).isEqualTo(handle)
    }

    @Test
    fun theEmailCodeIsAskedForAndSentOnTheNextTry() = runBlocking<Unit> {
        var calls = 0
        servers.route("POST", "$pds/xrpc/com.atproto.server.createSession") {
            calls++
            val body = Json.parseToJsonElement(it.body).jsonObject
            if (body["authFactorToken"] == null) {
                FakeServers.json("""{"error":"AuthFactorTokenRequired","message":"A sign in code has been sent to your email address"}""", 401)
            } else {
                FakeServers.json("""{"did":"$did","handle":"$handle","accessJwt":"a","refreshJwt":"r"}""")
            }
        }

        assertThat(signIn().signIn(handle, password)).isEqualTo(BlueskySignIn.Result.NeedsCode)
        val done = signIn().signIn(handle, password, " ABCDE-12345 ")

        assertThat(done).isInstanceOf(BlueskySignIn.Result.Done::class.java)
        val second = Json.parseToJsonElement(servers.requests("$pds/xrpc/com.atproto.server.createSession").last().body).jsonObject
        assertThat(second["authFactorToken"]!!.jsonPrimitive.content).isEqualTo("ABCDE-12345")
        assertThat(calls).isEqualTo(2)
    }

    @Test
    fun aWrongCodeIsReportedAsSuch() = runBlocking<Unit> {
        servers.json("POST", "$pds/xrpc/com.atproto.server.createSession", """{"error":"AuthFactorTokenRequired"}""", code = 401)

        val e = assertThrows(SignInException::class.java) { runBlocking { signIn().signIn(handle, password, "wrong") } }

        assertThat(e.problem).isEqualTo(SignInProblem.WRONG_CODE)
    }

    @Test
    fun aDocumentThatClaimsAnotherHandleStopsTheSignInBeforeThePassword() = runBlocking<Unit> {
        servers.json("GET", "plc.directory/$did", document("mallory.example.com"))

        val e = assertThrows(SignInException::class.java) { runBlocking { signIn().signIn(handle, password) } }

        assertThat(e.problem).isEqualTo(SignInProblem.HANDLE_MISMATCH)
        assertThat(servers.requests("$pds/xrpc/com.atproto.server.createSession")).isEmpty()
    }

    @Test
    fun aPlainHttpPdsIsRefusedInARelease() = runBlocking<Unit> {
        servers.json("GET", "plc.directory/$did", document(handle, endpoint = "http://$pds"))

        val e = assertThrows(SignInException::class.java) { runBlocking { signIn().signIn(handle, password) } }

        assertThat(e.problem).isEqualTo(SignInProblem.NO_PDS)
        assertThat(servers.seen.none { it.host == pds }).isTrue()
    }

    @Test
    fun anUnknownHandleIsReported() = runBlocking<Unit> {
        val e = assertThrows(SignInException::class.java) { runBlocking { signIn().signIn("nobody.example.com", password) } }

        assertThat(e.problem).isEqualTo(SignInProblem.HANDLE_NOT_FOUND)
    }

    @Test
    fun aWrongPasswordIsReported() = runBlocking<Unit> {
        servers.json("POST", "$pds/xrpc/com.atproto.server.createSession", """{"error":"AuthenticationRequired","message":"Invalid identifier or password"}""", code = 401)

        val e = assertThrows(SignInException::class.java) { runBlocking { signIn().signIn(handle, "s3cret-pass") } }

        assertThat(e.problem).isEqualTo(SignInProblem.WRONG_PASSWORD)
        assertThat(e.message).doesNotContain("s3cret-pass")
        assertThat(e.cause?.message.orEmpty()).doesNotContain("s3cret-pass")
    }

    @Test
    fun inactiveAndTakenDownAccountsGetAClearError() = runBlocking<Unit> {
        session("a", "r", extra = ""","active":false,"status":"deactivated"""")
        val inactive = assertThrows(SignInException::class.java) { runBlocking { signIn().signIn(handle, password) } }
        servers.json("POST", "$pds/xrpc/com.atproto.server.createSession", """{"error":"AccountTakedown","message":"Account has been taken down"}""", code = 401)
        val takenDown = assertThrows(SignInException::class.java) { runBlocking { signIn().signIn(handle, password) } }

        assertThat(inactive.problem).isEqualTo(SignInProblem.ACCOUNT_INACTIVE)
        assertThat(takenDown.problem).isEqualTo(SignInProblem.ACCOUNT_TAKEN_DOWN)
        assertThat(accounts.observe().first()).isEmpty()
    }

    @Test
    fun aProfileThatDoesNotReadDoesNotFailTheSignIn() = runBlocking<Unit> {
        servers.json("GET", "$appView/xrpc/app.bsky.actor.getProfile", """{"error":"InternalServerError"}""", code = 500)

        val done = signIn().signIn(handle, password) as BlueskySignIn.Result.Done

        assertThat(accounts.get(done.accountId)!!.displayName).isEmpty()
        assertThat(accounts.get(done.accountId)!!.avatarUrl).isNull()
    }

    @Test
    fun signingInAgainReplacesTheTokensOfTheSameAccount() = runBlocking<Unit> {
        val first = (signIn().signIn(handle, password) as BlueskySignIn.Result.Done).accountId
        accounts.setState(first, AccountState.NEEDS_SIGN_IN)
        session("access-2", "refresh-2")

        val second = (signIn().signIn(handle, password) as BlueskySignIn.Result.Done).accountId

        assertThat(second).isEqualTo(first)
        assertThat(accounts.observe().first()).hasSize(1)
        assertThat(accounts.readSecret(first)).isEqualTo(BlueskySecret("access-2", "refresh-2"))
        assertThat(accounts.get(first)!!.state).isEqualTo(AccountState.OK)
    }

    @Test
    fun signingInAgainWithTheAccountAcceptsItsOwnDid() = runBlocking<Unit> {
        val first = (signIn().signIn(handle, password) as BlueskySignIn.Result.Done).accountId
        accounts.setState(first, AccountState.NEEDS_SIGN_IN)
        session("access-2", "refresh-2")

        val second = (signIn().signIn(handle, password, accountId = first) as BlueskySignIn.Result.Done).accountId

        assertThat(second).isEqualTo(first)
        assertThat(accounts.get(first)!!.state).isEqualTo(AccountState.OK)
    }

    @Test
    fun signingInAgainAsAnotherDidIsRejectedBeforeThePassword() = runBlocking<Unit> {
        // R5: Bob's account needs sign-in, and the user types Alice's handle in the Sign in again form.
        val bob = accounts.add(
            NewAccount(Network.BLUESKY, "https://$pds", "bob.example.com", "did:plc:bobbobbobbobbobbobbobbob", "Bob", null),
            BlueskySecret("old-access", "old-refresh"),
        )
        accounts.setState(bob, AccountState.NEEDS_SIGN_IN)

        val e = assertThrows(SignInException::class.java) { runBlocking { signIn().signIn(handle, password, accountId = bob) } }

        assertThat(e.problem).isEqualTo(SignInProblem.WRONG_ACCOUNT)
        assertThat(e.mismatch).isEqualTo(AccountMismatch(signedIn = handle, expected = "bob.example.com"))
        assertThat(servers.requests("$pds/xrpc/com.atproto.server.createSession")).isEmpty()
        assertThat(accounts.observe().first().map { it.id }).containsExactly(bob)
        assertThat(accounts.get(bob)!!.state).isEqualTo(AccountState.NEEDS_SIGN_IN)
        assertThat(accounts.readSecret(bob)).isEqualTo(BlueskySecret("old-access", "old-refresh"))
    }

    @Test
    fun aMalformedHandleFailsBeforeAnyRequest() = runBlocking<Unit> {
        for (input in listOf("not a handle", "did:", "alice")) {
            val e = assertThrows(SignInException::class.java) { runBlocking { signIn().signIn(input, password) } }
            assertThat(e.problem).isEqualTo(SignInProblem.INVALID_HANDLE)
        }
        assertThat(servers.seen).isEmpty()
    }
}
