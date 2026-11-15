package org.evsyukov.post.data.accounts

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.data.FakeCipher
import org.evsyukov.post.data.containsBytes
import org.evsyukov.post.data.inMemoryDatabase
import org.evsyukov.post.data.rows
import org.evsyukov.post.data.secrets.SecretStore
import org.evsyukov.post.data.secrets.SecretUnreadable
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** The app registration per Mastodon instance (N4), with its client secret sealed like a token. */
@RunWith(AndroidJUnit4::class)
class MastodonAppsRepositoryTest {
    private val db = inMemoryDatabase()
    private val repo = RoomMastodonAppsRepository(db, SecretStore(FakeCipher()))

    @After
    fun tearDown() = db.close()

    private val app = MastodonApp(
        origin = "https://mastodon.social",
        clientId = "client-id-1",
        clientSecret = "client-secret-abc",
        redirectUri = "org.evsyukov.post://oauth/mastodon",
        scopes = "write:statuses write:media profile read:statuses",
        createdAt = Instant.parse("2026-11-16T08:00:00Z"),
    )

    @Test
    fun aRegistrationIsStoredPerOriginAndReplacedByANewOne() = runBlocking<Unit> {
        assertThat(repo.get(app.origin)).isNull()

        repo.put(app)
        repo.put(app.copy(origin = "https://fosstodon.org", clientId = "other"))
        assertThat(repo.get(app.origin)).isEqualTo(app)

        val again = app.copy(clientId = "client-id-2", clientSecret = "client-secret-def")
        repo.put(again)
        assertThat(repo.get(app.origin)).isEqualTo(again)
        assertThat(repo.get("https://fosstodon.org")!!.clientId).isEqualTo("other")

        assertThat(repo.delete(app.origin)).isTrue()
        assertThat(repo.get(app.origin)).isNull()
        assertThat(repo.delete(app.origin)).isFalse()
    }

    @Test
    fun theClientSecretIsSealedAtRestAndNeverPrinted() = runBlocking<Unit> {
        repo.put(app)

        val blob = db.rows("SELECT clientSecret FROM mastodon_apps").single().single() as ByteArray
        assertThat(blob.containsBytes("client-secret-abc".toByteArray())).isFalse()
        assertThat(repo.get(app.origin).toString()).doesNotContain("client-secret-abc")
    }

    @Test
    fun aSecretThatDoesNotOpenIsReportedSoTheAppRegistersAgain() = runBlocking<Unit> {
        repo.put(app)
        db.openHelper.writableDatabase.execSQL("UPDATE mastodon_apps SET clientSecret = X'0102'")

        assertThrows(SecretUnreadable::class.java) { runBlocking { repo.get(app.origin) } }
    }
}
