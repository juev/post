package org.evsyukov.post.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.evsyukov.post.data.inMemoryDatabase
import org.evsyukov.post.data.rows
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The schema of every database version is fixed once it is committed: an installed copy of that version
 * must open, or migrate, with the entities of a later release.
 *
 * The Room Gradle plugin rewrites `app/schemas/.../<version>.json` from the entities on every build, so
 * comparing the entities with that file alone proves nothing: both change together. The identity hash
 * of each version is therefore pinned here. Changing the entities without a new database version
 * changes the identity Room computes, and [theEntitiesHaveTheIdentityPinnedForTheirVersion] fails.
 */
@RunWith(AndroidJUnit4::class)
class SchemaTest {
    private val db = inMemoryDatabase()

    @After
    fun tearDown() = db.close()

    @Test
    fun theEntitiesHaveTheIdentityPinnedForTheirVersion() {
        val version = db.openHelper.readableDatabase.version
        val identity = db.rows("SELECT identity_hash FROM room_master_table").single().single()

        assertThat(PINNED_IDENTITIES).containsKey(version)
        assertWithMessage(
            "the identity of the entities of version $version; a schema change needs a new database " +
                "version, a migration and its own entry in PINNED_IDENTITIES, never a new hash for $version",
        ).that(identity).isEqualTo(PINNED_IDENTITIES.getValue(version))
    }

    @Test
    fun theExportedSchemaOfEveryVersionHasItsPinnedIdentity() {
        for ((version, identity) in PINNED_IDENTITIES) {
            val schema = exported(version)
            assertThat(schema["version"]!!.jsonPrimitive.int).isEqualTo(version)
            assertThat(schema["identityHash"]!!.jsonPrimitive.content).isEqualTo(identity)
        }
    }

    @Test
    fun everyTableAndIndexOfTheDatabaseIsInTheExportedSchema() {
        val entities = exported(db.openHelper.readableDatabase.version)["entities"]!!.jsonArray.map { it.jsonObject }
        val expected = entities.associate { entity ->
            val table = entity["tableName"]!!.jsonPrimitive.content
            table to entity["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table)
        }
        val expectedIndices = entities.flatMap { entity ->
            val table = entity["tableName"]!!.jsonPrimitive.content
            entity["indices"]?.jsonArray.orEmpty().map { it.jsonObject["createSql"]!!.jsonPrimitive.content.replace("\${TABLE_NAME}", table) }
        }

        val tables = db.rows(
            "SELECT name, sql FROM sqlite_master WHERE type = 'table' " +
                "AND name NOT IN ('room_master_table', 'android_metadata', 'sqlite_sequence')",
        ).associate { (name, sql) -> name as String to sql as String }
        val indices = db.rows("SELECT sql FROM sqlite_master WHERE type = 'index' AND sql IS NOT NULL").map { it.single() as String }

        assertThat(tables.keys).containsExactly(
            "accounts", "mastodon_apps", "posts", "attachments", "variants", "deliveries", "history", "schedule_slots",
        )
        for ((table, sql) in tables) {
            assertThat(sql.normalized()).isEqualTo(expected.getValue(table).normalized())
        }
        assertThat(indices.map { it.normalized() }).containsExactlyElementsIn(expectedIndices.map { it.normalized() })
    }

    // Unit tests run in the module directory.
    private fun exported(version: Int): JsonObject {
        val file = File("schemas/org.evsyukov.post.data.db.PostDatabase/$version.json")
        assertThat(file.isFile).isTrue()
        return Json.parseToJsonElement(file.readText()).jsonObject["database"]!!.jsonObject
    }

    // sqlite_master keeps the statement without IF NOT EXISTS.
    private fun String.normalized() = replace("IF NOT EXISTS ", "").replace(Regex("\\s+"), " ").trim()

    private companion object {
        /**
         * The identity hash of each database version as released. An entry is added with a new version
         * and never changed afterwards.
         */
        val PINNED_IDENTITIES = mapOf(
            1 to "7cd92fd81f0bb17c9c302716c7245eb8",
            // Version 2 keeps the tables of version 1; its migration only writes the default schedule.
            2 to "7cd92fd81f0bb17c9c302716c7245eb8",
        )
    }
}
