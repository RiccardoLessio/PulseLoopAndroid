package com.pulseloop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v29 → v30 must create exactly the tables Room expects, and touch nothing else.
 *
 * There is no Robolectric/instrumented harness in this repo, so the check is against the schema
 * Room itself generated: `PulseLoopDatabase_Impl.createAllTables` holds the canonical CREATE
 * statements (Room validates an opened database against the same TableInfo), and every v0.2 one
 * must appear verbatim in [PulseLoopDatabase.MIGRATION_29_30_SQL].
 */
class MigrationSchemaTest {

    private val generated: List<String> by lazy {
        val impl = File("build/generated/ksp/debug/java/com/pulseloop/data/PulseLoopDatabase_Impl.java")
        assertTrue("Room-generated database code not found at ${impl.absolutePath}", impl.isFile)
        Regex("""execSQL\("((?:[^"\\]|\\.)*)"\)""").findAll(impl.readText())
            .map { it.groupValues[1].replace("\\\"", "\"").replace("\\\\", "\\") }
            .filter { it.startsWith("CREATE") }
            .toList()
    }

    private fun touchesContextTable(sql: String) =
        PulseLoopDatabase.CONTEXT_TABLES.any { sql.contains("`$it`") }

    @Test
    fun `migration statements are byte-identical to the Room schema for every new table`() {
        val expected = generated.filter(::touchesContextTable)
        assertEquals(19, expected.size)   // 7 tables + 12 indices
        assertEquals(expected.toSet(), PulseLoopDatabase.MIGRATION_29_30_SQL.toSet())
    }

    @Test
    fun `migration only creates - it never drops, deletes or alters existing data`() {
        for (sql in PulseLoopDatabase.MIGRATION_29_30_SQL) {
            assertTrue(sql, sql.startsWith("CREATE TABLE IF NOT EXISTS") || sql.startsWith("CREATE INDEX IF NOT EXISTS") ||
                sql.startsWith("CREATE UNIQUE INDEX IF NOT EXISTS"))
            for (word in listOf("DROP", "DELETE", "ALTER", "UPDATE", "INSERT")) assertFalse(sql, sql.contains(word))
            assertTrue(sql, touchesContextTable(sql))
        }
    }

    @Test
    fun `context tables stay out of the archive restore list but are known to the wipe`() {
        for (table in PulseLoopDatabase.CONTEXT_TABLES) {
            assertFalse(table, table in PulseLoopDatabase.ALL_TABLES)
        }
        // Every generated table is either an archive table or a context table.
        val tables = generated.filter { it.startsWith("CREATE TABLE") && !it.contains("room_master_table") }
            .map { Regex("`([A-Za-z0-9_]+)`").find(it)!!.groupValues[1] }
        assertEquals(tables.toSet(), (PulseLoopDatabase.ALL_TABLES + PulseLoopDatabase.CONTEXT_TABLES).toSet())
    }
}
