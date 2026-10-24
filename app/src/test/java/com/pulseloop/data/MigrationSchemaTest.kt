package com.pulseloop.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v29 → v30 must create exactly the tables Room expects, and touch nothing else; v30 → v31 only
 * appends the diary's nullable `supplements` column.
 *
 * There is no Robolectric/instrumented harness in this repo, so the check is against the schema
 * Room itself generated: `PulseLoopDatabase_Impl.createAllTables` holds the canonical CREATE
 * statements (Room validates an opened database against the same TableInfo), and every v0.2 one
 * must equal [PulseLoopDatabase.MIGRATION_29_30_SQL] once [PulseLoopDatabase.MIGRATION_30_31_SQL] is applied.
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

    /** What a CREATE reads like after an `ALTER TABLE … ADD COLUMN` (SQLite appends the column). */
    private fun applyAddColumn(create: String, alter: String): String {
        val (table, column) = Regex("^ALTER TABLE `([A-Za-z0-9_]+)` ADD COLUMN (.+)$").find(alter)!!.destructured
        if (!create.startsWith("CREATE TABLE IF NOT EXISTS `$table`")) return create
        val pk = create.lastIndexOf(", PRIMARY KEY(")
        assertTrue(create, pk > 0)
        return create.substring(0, pk) + ", " + column + create.substring(pk)
    }

    /** The v29 schema after 29→30 then 30→31, as CREATE statements. */
    private val migratedToV31: List<String> by lazy {
        PulseLoopDatabase.MIGRATION_29_30_SQL.map { create ->
            PulseLoopDatabase.MIGRATION_30_31_SQL.fold(create) { acc, alter -> applyAddColumn(acc, alter) }
        }
    }

    @Test
    fun `29 to 30 to 31 yields the Room schema byte for byte for every context table`() {
        val expected = generated.filter(::touchesContextTable)
        assertEquals(19, expected.size)   // 7 tables + 12 indices
        assertEquals(expected.toSet(), migratedToV31.toSet())
    }

    @Test
    fun `30 to 31 only appends the nullable supplements column to the diary`() {
        assertEquals(listOf("ALTER TABLE `diary_entries` ADD COLUMN `supplements` TEXT"), PulseLoopDatabase.MIGRATION_30_31_SQL)
        for (sql in PulseLoopDatabase.MIGRATION_30_31_SQL) {
            // Nullable, no default: existing rows read as unknown and nothing is rewritten.
            for (word in listOf("NOT NULL", "DEFAULT", "DROP", "DELETE", "UPDATE", "INSERT", "RENAME")) assertFalse(sql, sql.contains(word))
        }
        val diaryV30 = PulseLoopDatabase.MIGRATION_29_30_SQL.single { it.startsWith("CREATE TABLE IF NOT EXISTS `diary_entries`") }
        val diaryV31 = generated.single { it.startsWith("CREATE TABLE IF NOT EXISTS `diary_entries`") }
        assertFalse(diaryV30.contains("`supplements`"))
        assertEquals(diaryV30.replace(", PRIMARY KEY(`id`))", ", `supplements` TEXT, PRIMARY KEY(`id`))"), diaryV31)
    }

    @Test
    fun `29 to 30 only creates - it never drops, deletes or alters existing data`() {
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
