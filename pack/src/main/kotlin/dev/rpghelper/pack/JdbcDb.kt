package dev.rpghelper.pack

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.sql.ResultSet
import org.sqlite.SQLiteConfig

/**
 * [Db] over `sqlite-jdbc`, which bundles SQLite built with FTS5 and `bm25()`.
 *
 * Desktop and test only -- the JAR carries native libraries that do not load on
 * Android. The device implementation lands with the `:app` module.
 */
class JdbcDb private constructor(private val connection: Connection) : Db {

    override fun forEachRow(sql: String, action: (Row) -> Unit) {
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { results ->
                val row = ResultSetRow(results)
                while (results.next()) action(row)
            }
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

        override fun long(column: Int): Long = results.getLong(column + 1)
        override fun string(column: Int): String = results.getString(column + 1)
        override fun bytes(column: Int): ByteArray = results.getBytes(column + 1)
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
