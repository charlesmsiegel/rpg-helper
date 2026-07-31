package dev.rpghelper.pack

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Files
import java.nio.file.Path

/**
 * [Db] over a **bundled** SQLite: the binding this program actually reads packs with, on
 * every platform. Reach it through [Sqlite] rather than naming it here.
 *
 * `JdbcDb` is backed by `sqlite-jdbc`, which ships native desktop libraries and cannot
 * load on Android. This is the same interface over `androidx.sqlite`, whose bundled driver
 * carries its own SQLite build — verified here to have FTS5, since without it a pack's
 * entire lexical half is unreadable and the failure would be per-device rather than
 * per-build.
 *
 * The two implementations are held to returning identical results by `BundledDbTest`,
 * because two bindings of one interface that are never compared are two bindings that
 * agree until the day they do not — and the day they do not, one device quotes a book
 * differently from another.
 */
class BundledDb private constructor(
    private val connection: SQLiteConnection,
) : Db {

    override fun forEachRow(sql: String, action: (Row) -> Unit) {
        try {
            connection.prepare(sql).use { statement ->
                val row = StatementRow(statement)
                while (statement.step()) action(row)
            }
        } catch (e: Exception) {
            // Wrapped, exactly as the JDBC binding wraps `SQLException`: a malformed pack
            // has to reach the validator as a violation rather than as whichever exception
            // hierarchy this platform's driver happens to use.
            throw PackReadException("cannot read: ${e.message}", e)
        }
    }

    override fun execute(sql: String) {
        try {
            connection.prepare(sql).use { it.step() }
        } catch (e: Exception) {
            throw PackReadException("cannot execute: ${e.message}", e)
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

    /**
     * NULL is refused in every required column, exactly as `JdbcDb` refuses it.
     *
     * A pack's DDL declares these NOT NULL and a pack's DDL is whatever its builder wrote,
     * so the guarantee has to come from the reader. Without it `getLong` answers 0 for a
     * NULL `sources.source_id` — a value the validator then reasons about as if it
     * resolved — and `getBlob` answers an empty probe vector, which is a *different*
     * violation from the one that is true. This binding shipped without the guards its
     * twin has, and every one of those divergences was invisible for as long as production
     * only ever ran the other binding.
     */
    private class StatementRow(private val statement: SQLiteStatement) : Row {
        override fun isNull(column: Int): Boolean = statement.isNull(column)

        override fun long(column: Int): Long = required(column) { statement.getLong(column) }

        override fun string(column: Int): String = required(column) { statement.getText(column) }

        override fun bytes(column: Int): ByteArray = required(column) { statement.getBlob(column) }

        override fun double(column: Int): Double = required(column) { statement.getDouble(column) }

        private inline fun <T> required(column: Int, read: () -> T): T {
            if (statement.isNull(column)) {
                throw PackReadException("NULL in a column the format requires (index $column)")
            }
            return read()
        }
    }

    companion object {
        /**
         * Opens [path] read-only.
         *
         * A pack is read-only at runtime by contract, and saying so to SQLite is what makes
         * that true of the file rather than only of the code above it.
         *
         * Existence is checked first, and the failure is the same
         * [IllegalArgumentException] `JdbcDb` raises, because callers upstream distinguish
         * "no such file" from "not a pack" and two bindings that report it differently make
         * that distinction a property of the platform.
         */
        fun openReadOnly(path: Path): BundledDb {
            require(Files.isRegularFile(path)) { "not a file: $path" }
            val connection = try {
                BundledSQLiteDriver().open(
                    path.toAbsolutePath().toString(),
                    // OPEN_READONLY. Named rather than imported so the intent survives a
                    // reader who does not know the constant.
                    0x00000001,
                )
            } catch (e: Exception) {
                throw PackReadException("cannot open as a database: ${e.message}", e)
            }
            return BundledDb(connection)
        }
    }
}
