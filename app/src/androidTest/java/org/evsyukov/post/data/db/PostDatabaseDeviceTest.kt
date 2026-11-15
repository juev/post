package org.evsyukov.post.data.db

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import org.evsyukov.post.core.media.Network
import org.evsyukov.post.data.accounts.MastodonApp
import org.evsyukov.post.data.accounts.NewAccount
import org.evsyukov.post.data.accounts.RoomAccountsRepository
import org.evsyukov.post.data.accounts.RoomMastodonAppsRepository
import org.evsyukov.post.data.secrets.KeystoreTokenCipher
import org.evsyukov.post.data.secrets.MastodonSecret
import org.evsyukov.post.data.secrets.SecretStore
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.time.Instant

/** The database on a device: the exported schema opens with the entities, and tokens are sealed on disk (R4). */
@RunWith(AndroidJUnit4::class)
class PostDatabaseDeviceTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @get:Rule
    val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), PostDatabase::class.java)

    @After
    fun tearDown() {
        context.deleteDatabase(NAME)
        KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(ALIAS)
    }

    @Test
    fun aDatabaseOfTheExportedVersionOneOpensWithTheCurrentEntities() = runBlocking<Unit> {
        // Room refuses to open a file whose identity differs from its entities, so this fails when the
        // committed schema no longer matches them.
        helper.createDatabase(NAME, 1).close()

        val db = Room.databaseBuilder(context, PostDatabase::class.java, NAME).build()
        try {
            assertThat(db.scheduleDao().all()).isEmpty()
            assertThat(db.openHelper.readableDatabase.version).isEqualTo(1)
        } finally {
            db.close()
        }
    }

    @Test
    fun theDatabaseFileHoldsNoTokenInTheClear() = runBlocking<Unit> {
        val secrets = SecretStore(KeystoreTokenCipher(ALIAS))
        val db = Room.databaseBuilder(context, PostDatabase::class.java, NAME).build()
        try {
            val accounts = RoomAccountsRepository(db, secrets)
            val id = accounts.add(NewAccount(Network.MASTODON, "https://m.example", "me", "1", "Me", null), MastodonSecret(TOKEN))
            RoomMastodonAppsRepository(db, secrets).put(
                MastodonApp("https://m.example", "client", CLIENT_SECRET, "org.evsyukov.post://oauth/mastodon", "profile", Instant.EPOCH),
            )
            assertThat(accounts.readSecret(id)).isEqualTo(MastodonSecret(TOKEN))
        } finally {
            db.close()
        }

        val path = context.getDatabasePath(NAME)
        val files = listOf(path, File("${path.path}-wal"), File("${path.path}-journal")).filter { it.exists() }
        assertThat(files).isNotEmpty()
        for (file in files) {
            val bytes = String(file.readBytes(), Charsets.ISO_8859_1)
            assertThat(bytes).doesNotContain(TOKEN)
            assertThat(bytes).doesNotContain(CLIENT_SECRET)
        }
    }

    private companion object {
        const val NAME = "post-device-test.db"
        const val ALIAS = "post-secrets-device-test"
        const val TOKEN = "device-access-token-0123456789"
        const val CLIENT_SECRET = "device-client-secret-9876543210"
    }
}
