package com.danidipp.sneakymail

import java.io.File
import java.sql.DriverManager
import kotlin.test.assertEquals

/** Recreates SneakyMail's schema in a throwaway PostgreSQL from this jar's db/migrations scripts. */
object TestSchema {
    private val MIGRATION = Regex("""V0*(\d+)__[A-Za-z0-9_-]+\.sql""")

    val scripts: List<File> by lazy {
        val folder = File(javaClass.getResource("/db/migrations")!!.toURI())
        val files = folder.listFiles()!!.sortedBy { MIGRATION.matchEntire(it.name)!!.groupValues[1].toInt() }
        assertEquals((1..files.size).toList(), files.map { MIGRATION.matchEntire(it.name)!!.groupValues[1].toInt() }, "numbered from 1 without gaps")
        files
    }

    /**
     * Drops and recreates the schema (the operator's step), with the old journal table Live has, then applies every
     * script in order, one transaction per script, as SneakyDB does.
     */
    fun reset(url: String) {
        DriverManager.getConnection(url).use { c ->
            c.createStatement().use { s ->
                s.execute("DROP SCHEMA IF EXISTS sneakymail CASCADE")
                s.execute("CREATE SCHEMA sneakymail")
                s.execute("CREATE TABLE sneakymail.command_requests (source_key text PRIMARY KEY, request_id text NOT NULL UNIQUE, payload jsonb NOT NULL)")
            }
            c.autoCommit = false
            for (script in scripts) {
                c.createStatement().use { it.execute(script.readText()) }
                c.commit()
            }
        }
    }
}
