package org.evsyukov.post.data.db

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
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
 * The schema committed under app/schemas is the one the entities build: a later version must migrate
 * from it, so a change to the entities without a new version shows up here.
 */
@RunWith(AndroidJUnit4::class)
class SchemaTest {
    private val db = inMemoryDatabase()

    @After
    fun tearDown() = db.close()

    // Unit tests run in the module directory.
    private val exported = File("schemas/org.evsyukov.post.data.db.PostDatabase/1.json")

    private val schema by lazy { Json.parseToJsonElement(exported.readText()).jsonObject["database"]!!.jsonObject }

    @Test
    fun theExportedSchemaIsVersionOneAndHasTheIdentityOfTheEntities() {
        assertThat(exported.isFile).isTrue()
        assertThat(schema["version"]!!.jsonPrimitive.int).isEqualTo(1)

        val identity = db.rows("SELECT identity_hash FROM room_master_table").single().single()
        assertThat(identity).isEqualTo(schema["identityHash"]!!.jsonPrimitive.content)
        assertThat(db.openHelper.readableDatabase.version).isEqualTo(1)
    }

    @Test
    fun everyTableAndIndexOfTheDatabaseIsInTheExportedSchema() {
        val entities = schema["entities"]!!.jsonArray.map { it.jsonObject }
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

    // sqlite_master keeps the statement without IF NOT EXISTS.
    private fun String.normalized() = replace("IF NOT EXISTS ", "").replace(Regex("\\s+"), " ").trim()
}
