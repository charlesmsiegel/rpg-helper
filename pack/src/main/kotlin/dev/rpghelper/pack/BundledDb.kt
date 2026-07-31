package dev.rpghelper.pack

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import java.nio.file.Path

/**
 * [Db] over a **bundled** SQLite, which is the one that runs on a phone.
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

    override fun tableNames(): Set<String> {
        val names = mutableSetOf<String>()
        forEachRow("SELECT name FROM sqlite_master WHERE type IN ('table', 'view')") {
            names += it.string(0)
        }
        return names
    }

    override fun close() = connection.close()

    private class StatementRow(private val statement: SQLiteStatement) : Row {
        override fun isNull(column: Int): Boolean = statement.isNull(column)
        override fun long(column: Int): Long = statement.getLong(column)
        override fun string(column: Int): String = statement.getText(column)
        override fun bytes(column: Int): ByteArray = statement.getBlob(column)
        override fun double(column: Int): Double = statement.getDouble(column)
    }

    companion object {
        /**
         * Opens [path] read-only.
         *
         * A pack is read-only at runtime by contract, and saying so to SQLite is what makes
         * that true of the file rather than only of the code above it.
         */
        fun openReadOnly(path: Path): BundledDb {
            val connection = BundledSQLiteDriver().open(
                path.toAbsolutePath().toString(),
                // OPEN_READONLY. Named rather than imported so the intent survives a
                // reader who does not know the constant.
                0x00000001,
            )
            return BundledDb(connection)
        }
    }
}
