package dev.rpghelper.pack

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import org.sqlite.SQLiteConfig

/**
 * [Db] over `sqlite-jdbc`, which bundles SQLite built with FTS5 and `bm25()`.
 *
 * Desktop and test only -- the JAR carries native libraries that do not load on
 * Android. The device implementation lands with the `:app` module.
 */
class JdbcDb private constructor(private val connection: Connection) : Db {

    override fun forEachRow(sql: String, action: (Row) -> Unit) {
        try {
            connection.createStatement().use { statement ->
                statement.executeQuery(sql).use { rows ->
                    val row = ResultSetRow(rows)
                    while (rows.next()) action(row)
                }
            }
        } catch (e: SQLException) {
            // A pack is a file someone else built. A query failing against it is a fact
            // about the pack, not an error in this program, so it must reach the
            // validator as something it can report rather than as a driver exception the
            // caller never expected. (`action` is this module's own code and does not
            // raise SQLException, so nothing of the caller's is captured here.)
            throw PackReadException("query failed against this pack: $sql", e)
        }
    }

    override fun tableNames(): Set<String> {
        val names = mutableSetOf<String>()
        forEachRow("SELECT name FROM sqlite_master WHERE type IN ('table', 'view')") {
            names += it.string(0)
        }
        return names
    }

    override fun close() = connection.close()

    private class ResultSetRow(private val results: ResultSet) : Row {
        // JDBC is 1-based; the interface is 0-based. Converting in exactly one place.
        override fun isNull(column: Int): Boolean {
            results.getObject(column + 1)
            return results.wasNull()
        }

        // getLong returns 0 for SQL NULL. Left unchecked, a NULL sources.source_id
        // reads as source 0 -- a value the validator then reasons about as if it
        // resolved, while every runtime join against it finds nothing.
        override fun long(column: Int): Long {
            val value = results.getLong(column + 1)
            if (results.wasNull()) {
                throw PackReadException("NULL in a column the format requires (index $column)")
            }
            return value
        }

        // A pack's own DDL declares these NOT NULL, and a pack's DDL is whatever its
        // builder chose to write. Returning the platform type straight into Kotlin's
        // non-null String would raise a NullPointerException -- which is not a
        // PackReadException, escapes the validator, and puts a corrupt pack past the
        // gate as a crash rather than a refusal.
        override fun string(column: Int): String =
            results.getString(column + 1)
                ?: throw PackReadException("NULL in a column the format requires (index $column)")

        override fun bytes(column: Int): ByteArray =
            results.getBytes(column + 1)
                ?: throw PackReadException("NULL in a column the format requires (index $column)")
    }

    companion object {
        /**
         * Opens [path] read-only.
         *
         * Existence is checked first because SQLite would otherwise happily create an
         * empty database at the path, and the caller would get a pack that is merely
         * missing every table rather than an honest "no such file".
         */
        fun openReadOnly(path: Path): JdbcDb {
            require(Files.isRegularFile(path)) { "not a file: $path" }
            val config = SQLiteConfig().apply { setReadOnly(true) }
            val connection = config.createConnection("jdbc:sqlite:${path.toAbsolutePath()}")
            return JdbcDb(connection)
        }
    }
}
