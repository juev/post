package org.evsyukov.post.accounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.bluesky.XrpcException
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.MutableClock
import org.evsyukov.post.data.accounts.AccountNotFound
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.accounts.RoomAccountsRepository
import org.evsyukov.post.data.db.AccountState
import org.evsyukov.post.data.inMemoryDatabase
import org.evsyukov.post.data.secrets.BlueskySecret
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.secrets.SecretStore
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/** Sessions for publishing (networks.md N5, N15; product.md R5): refresh, rotation, needs sign-in. */
@RunWith(AndroidJUnit4::class)
class AccountSessionsTest {
    private val db = inMemoryDatabase()
    private val accounts = RoomAccountsRepository(db, SecretStore(FakeCipher()), MutableClock(Instant.parse("2026-11-16T08:00:00Z")))
    private val servers = FakeServers()
    private val sessions = AccountSessions(accounts, servers.http)
    private val pds = "pds.example.net"
    private val did = "did:plc:abc"
    private val refreshes = AtomicInteger()

    @After
    fun tearDown() {
        servers.close()
        db.close()
    }

    private suspend fun bluesky(): Long =
        accounts.add(NewAccount(Network.BLUESKY, "https://$pds", "alice.example.com", did, "Alice", null), BlueskySecret("access-1", "refresh-1"))

    private fun refreshAnswers(access: String = "access-2", refresh: String = "refresh-2") =
        servers.route("POST", "$pds/xrpc/com.atproto.server.refreshSession") {
            refreshes.incrementAndGet()
            FakeServers.json("""{"did":"$did","handle":"alice.example.com","accessJwt":"$access","refreshJwt":"$refresh"}""")
        }

    private fun expired() = XrpcException(400, "ExpiredToken", "Token has expired", null)

    @Test
    fun aBlueskyCallGetsTheDidThePdsAndTheAccessToken() = runBlocking<Unit> {
        val id = bluesky()

        val auth = sessions.bluesky(id) { it }

        assertThat(auth.did).isEqualTo(did)
        assertThat(auth.pds.toString()).isEqualTo("https://$pds/")
        assertThat(auth.accessJwt).isEqualTo("access-1")
        assertThat(auth.toString()).doesNotContain("access-1")
        assertThat(servers.seen).isEmpty()
    }

    @Test
    fun anExpiredTokenIsRefreshedStoredAndTheCallRunsOnceMore() = runBlocking<Unit> {
        val id = bluesky()
        refreshAnswers()
        val tokens = mutableListOf<String>()

        val result = sessions.bluesky(id) { auth ->
            tokens += auth.accessJwt
            if (auth.accessJwt == "access-1") throw expired()
            "posted"
        }

        assertThat(result).isEqualTo("posted")
        assertThat(tokens).containsExactly("access-1", "access-2").inOrder()
        val refresh = servers.requests("$pds/xrpc/com.atproto.server.refreshSession").single()
        assertThat(refresh.headers["Authorization"]).isEqualTo("Bearer refresh-1")
        // The rotated tokens are stored (N15).
        assertThat(accounts.readSecret(id)).isEqualTo(BlueskySecret("access-2", "refresh-2"))
        assertThat(accounts.get(id)!!.state).isEqualTo(AccountState.OK)
    }

    @Test
    fun aSecondExpiryAfterTheRefreshIsThrownWithoutAnotherRefresh() = runBlocking<Unit> {
        val id = bluesky()
        refreshAnswers()

        val e = assertThrows(XrpcException::class.java) { runBlocking { sessions.bluesky<Unit>(id) { throw expired() } } }

        assertThat(e.isExpiredToken).isTrue()
        assertThat(refreshes.get()).isEqualTo(1)
    }

    @Test
    fun aRejectedRefreshMarksTheAccountAsNeedingSignIn() = runBlocking<Unit> {
        val id = bluesky()
        servers.json("POST", "$pds/xrpc/com.atproto.server.refreshSession", """{"error":"ExpiredToken","message":"Token has been revoked"}""", code = 400)

        val e = assertThrows(NeedsSignInException::class.java) { runBlocking { sessions.bluesky<Unit>(id) { throw expired() } } }

        assertThat(e.accountId).isEqualTo(id)
        assertThat(accounts.get(id)!!.state).isEqualTo(AccountState.NEEDS_SIGN_IN)
        // R5: the next call stops before any request.
        val before = servers.seen.size
        assertThrows(NeedsSignInException::class.java) { runBlocking { sessions.bluesky(id) { "never" } } }
        assertThat(servers.seen.size).isEqualTo(before)
    }

    @Test
    fun aRefreshThatCannotReachTheServerLeavesTheAccountSignedIn() = runBlocking<Unit> {
        val id = bluesky()
        servers.json("POST", "$pds/xrpc/com.atproto.server.refreshSession", """{"error":"InternalServerError"}""", code = 502)

        val e = assertThrows(XrpcException::class.java) { runBlocking { sessions.bluesky<Unit>(id) { throw expired() } } }

        assertThat(e.status).isEqualTo(502)
        assertThat(accounts.get(id)!!.state).isEqualTo(AccountState.OK)
        assertThat(accounts.readSecret(id)).isEqualTo(BlueskySecret("access-1", "refresh-1"))
    }

    @Test
    fun concurrentExpiriesRefreshOnceBecauseRefreshTokensRotate() = runBlocking<Unit> {
        val id = bluesky()
        refreshAnswers()
        val bothFailed = CompletableDeferred<Unit>()
        val failed = AtomicInteger()
        val used = Collections.synchronizedList(mutableListOf<String>())

        val results = (1..2).map { n ->
            async(kotlinx.coroutines.Dispatchers.IO) {
                sessions.bluesky(id) { auth ->
                    used += auth.accessJwt
                    if (auth.accessJwt == "access-1") {
                        if (failed.incrementAndGet() == 2) bothFailed.complete(Unit)
                        bothFailed.await()
                        throw expired()
                    }
                    n
                }
            }
        }.awaitAll()

        assertThat(results).containsExactly(1, 2)
        assertThat(refreshes.get()).isEqualTo(1)
        assertThat(used.count { it == "access-2" }).isEqualTo(2)
    }

    @Test
    fun anUnreadableBlueskySecretMarksTheAccountAsNeedingSignIn() = runBlocking<Unit> {
        val id = bluesky()
        db.accountDao().updateSecret(id, byteArrayOf(1, 2, 3))

        assertThrows(NeedsSignInException::class.java) { runBlocking { sessions.bluesky(id) { "never" } } }

        assertThat(accounts.get(id)!!.state).isEqualTo(AccountState.NEEDS_SIGN_IN)
    }

    @Test
    fun aMastodonAccountGivesItsInstanceAndToken() = runBlocking<Unit> {
        val id = accounts.add(NewAccount(Network.MASTODON, "https://mastodon.example", "me@mastodon.example", "42", "Me", null), MastodonSecret("token"))

        val auth = sessions.mastodon(id)

        assertThat(auth.origin.toString()).isEqualTo("https://mastodon.example/")
        assertThat(auth.token).isEqualTo("token")
        assertThat(auth.toString()).doesNotContain("token=token")
    }

    @Test
    fun anUnreadableMastodonSecretMarksTheAccountAsNeedingSignIn() = runBlocking<Unit> {
        val id = accounts.add(NewAccount(Network.MASTODON, "https://mastodon.example", "me@mastodon.example", "42", "Me", null), MastodonSecret("token"))
        db.accountDao().updateSecret(id, byteArrayOf(9, 9, 9))

        val e = assertThrows(NeedsSignInException::class.java) { runBlocking { sessions.mastodon(id) } }

        assertThat(e.accountId).isEqualTo(id)
        assertThat(accounts.get(id)!!.state).isEqualTo(AccountState.NEEDS_SIGN_IN)
    }

    @Test
    fun aRemovedAccountIsNotFound() = runBlocking<Unit> {
        assertThrows(AccountNotFound::class.java) { runBlocking { sessions.mastodon(99) } }
        assertThrows(AccountNotFound::class.java) { runBlocking { sessions.bluesky(99) { it } } }
    }
}
