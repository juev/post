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

/** The database on a device: a version 1 file opens with the entities, and tokens are sealed on disk (R4). */
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
        // MigrationTestHelper builds the file from the schema JSON packed into the test APK. The Room
        // plugin rewrites that JSON from the entities, and depending on the order of the tasks the APK
        // gets the old or the new one. Both cases fail here: a rewritten JSON no longer has the pinned
        // identity, and an old one does not open, because Room checks the identity against the entities.
        helper.createDatabase(NAME, 1).use { file ->
            file.query("SELECT identity_hash FROM room_master_table").use { cursor ->
                assertThat(cursor.moveToFirst()).isTrue()
                assertThat(cursor.getString(0)).isEqualTo(VERSION_1_IDENTITY)
            }
        }

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

        /** The identity of version 1 as released; the JVM SchemaTest pins the same value. */
        const val VERSION_1_IDENTITY = "7cd92fd81f0bb17c9c302716c7245eb8"
    }
}
