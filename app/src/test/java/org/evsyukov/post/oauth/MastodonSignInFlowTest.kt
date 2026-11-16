package org.evsyukov.post.oauth

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.evsyukov.post.accounts.AccountMismatch
import org.evsyukov.post.accounts.FakeServers
import org.evsyukov.post.accounts.MastodonSignIn
import org.evsyukov.post.accounts.ServerPolicy
import org.evsyukov.post.accounts.SignInProblem
import org.evsyukov.post.core.mastodon.REDIRECT_URI
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.accounts.RoomAccountsRepository
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.accounts.RoomMastodonAppsRepository
import org.evsyukov.post.data.inMemoryDatabase
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.secrets.SecretStore
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** The sign-in as the screens see it: browser, redirect, a stray redirect, cancel. */
@RunWith(AndroidJUnit4::class)
class MastodonSignInFlowTest {
    private val db = inMemoryDatabase()
    private val secrets = SecretStore(FakeCipher())
    private val servers = FakeServers()
    private val file = File(ApplicationProvider.getApplicationContext<android.content.Context>().noBackupFilesDir, "flow-${System.nanoTime()}.json")
    private val store = FilePendingSignInStore(file)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val accounts = RoomAccountsRepository(db, secrets)
    private val flow = MastodonSignInFlow(
        MastodonSignIn(accounts, RoomMastodonAppsRepository(db, secrets), store, servers.http, ServerPolicy(false)),
        scope,
    )
    private val host = "mastodon.example"

    init {
        servers.json("GET", "$host/api/v2/instance", """{"domain":"$host"}""")
        servers.json("POST", "$host/api/v1/apps", """{"client_id":"c","client_secret":"s"}""")
        servers.json("POST", "$host/oauth/token", """{"access_token":"t"}""")
        servers.json("GET", "$host/api/v1/accounts/verify_credentials", """{"id":"1","username":"me","acct":"me"}""")
    }

    @After
    fun tearDown() {
        scope.cancel()
        servers.close()
        db.close()
        file.delete()
    }

    private suspend fun next(predicate: (MastodonFlowState) -> Boolean) = withTimeout(10_000) { flow.state.first(predicate) }

    @Test
    fun aStrayRedirectLeavesTheWaitingSignInAloneAndTheRealOneCompletesIt() = runBlocking<Unit> {
        flow.start(host)
        val open = next { it is MastodonFlowState.OpenBrowser } as MastodonFlowState.OpenBrowser
        flow.browserOpened()
        assertThat(flow.state.value).isEqualTo(MastodonFlowState.InBrowser)

        flow.onRedirect("$REDIRECT_URI?code=x&state=forged").join()
        assertThat(flow.state.value).isEqualTo(MastodonFlowState.InBrowser)

        flow.onRedirect("$REDIRECT_URI?code=x&state=${open.url.queryParameter("state")}").join()
        assertThat(flow.state.value).isInstanceOf(MastodonFlowState.Done::class.java)
    }

    @Test
    fun aRedirectNobodyWaitsForIsReportedAsUnknown() = runBlocking<Unit> {
        flow.onRedirect("$REDIRECT_URI?code=x&state=forged").join()

        assertThat(flow.state.value).isEqualTo(MastodonFlowState.Failed(SignInProblem.UNKNOWN_STATE))
        assertThat(servers.seen).isEmpty()
    }

    @Test
    fun cancellingForgetsThePendingSignIn() = runBlocking<Unit> {
        flow.start(host)
        next { it is MastodonFlowState.OpenBrowser }
        assertThat(store.get()).isNotNull()

        flow.cancel()

        assertThat(flow.state.value).isEqualTo(MastodonFlowState.Idle)
        withTimeout(5_000) { while (store.get() != null) kotlinx.coroutines.delay(10) }
    }

    @Test
    fun withoutABrowserTheSignInFails() = runBlocking<Unit> {
        flow.start(host)
        next { it is MastodonFlowState.OpenBrowser }

        flow.browserMissing()

        assertThat(flow.state.value).isEqualTo(MastodonFlowState.Failed(SignInProblem.NO_BROWSER))
    }

    @Test
    fun signingInAgainAsAnotherAccountNamesBothAccounts() = runBlocking<Unit> {
        val alice = accounts.add(
            NewAccount(Network.MASTODON, "https://$host", "alice@$host", remoteId = "7", displayName = "Alice", avatarUrl = null),
            MastodonSecret("old"),
        )
        flow.start(host, accountId = alice)
        val open = next { it is MastodonFlowState.OpenBrowser } as MastodonFlowState.OpenBrowser
        flow.browserOpened()

        flow.onRedirect("$REDIRECT_URI?code=x&state=${open.url.queryParameter("state")}").join()

        assertThat(flow.state.value).isEqualTo(
            MastodonFlowState.Failed(SignInProblem.WRONG_ACCOUNT, AccountMismatch(signedIn = "me@$host", expected = "alice@$host")),
        )
    }
}
