package dev.rpghelper.state

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.ResultSet

/**
 * The app's state database: opened, migrated, and written through here.
 *
 * Read-only pack access lives in `:pack` behind its own interface. This one writes, so it
 * is deliberately separate rather than a widening of that one — a pack must never be
 * writable by construction, and sharing an interface is how that stops being true.
 */
class StateDb private constructor(
    private val connection: Connection,
    val path: Path,
) : AutoCloseable {

    fun <T> query(sql: String, vararg args: Any?, read: (Row) -> T): List<T> {
        prepare(sql, args).use { statement ->
            statement.executeQuery().use { results ->
                val out = mutableListOf<T>()
                val row = ResultSetRow(results)
                while (results.next()) out += read(row)
                return out
            }
        }
    }

    fun execute(sql: String, vararg args: Any?): Int =
        prepare(sql, args).use { it.executeUpdate() }

    /** Runs [body] in a transaction, rolling back if it throws. */
    fun <T> transaction(body: () -> T): T {
        val restore = connection.autoCommit
        connection.autoCommit = false
        try {
            val result = body()
            connection.commit()
            return result
        } catch (e: Throwable) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = restore
        }
    }

    /** The id SQLite assigned to the most recent insert on this connection. */
    fun lastInsertId(): Long = query("SELECT last_insert_rowid()") { it.long(0) }.single()

    override fun close() = connection.close()

    private fun prepare(sql: String, args: Array<out Any?>): PreparedStatement {
        val statement = connection.prepareStatement(sql)
        args.forEachIndexed { index, value ->
            when (value) {
                null -> statement.setObject(index + 1, null)
                is Int -> statement.setInt(index + 1, value)
                is Long -> statement.setLong(index + 1, value)
                is Double -> statement.setDouble(index + 1, value)
                is Boolean -> statement.setInt(index + 1, if (value) 1 else 0)
                is ByteArray -> statement.setBytes(index + 1, value)
                else -> statement.setString(index + 1, value.toString())
            }
        }
        return statement
    }

    interface Row {
        fun isNull(column: Int): Boolean
        fun long(column: Int): Long
        fun double(column: Int): Double
        fun string(column: Int): String
        fun longOrNull(column: Int): Long?
        fun doubleOrNull(column: Int): Double?
        fun stringOrNull(column: Int): String?
        fun boolean(column: Int): Boolean = long(column) != 0L
        fun int(column: Int): Int = long(column).toInt()
    }

    private class ResultSetRow(private val results: ResultSet) : Row {
        override fun isNull(column: Int): Boolean {
            results.getObject(column + 1)
            return results.wasNull()
        }

        override fun long(column: Int): Long = results.getLong(column + 1)
        override fun double(column: Int): Double = results.getDouble(column + 1)
        override fun string(column: Int): String = results.getString(column + 1)
        override fun longOrNull(column: Int): Long? = if (isNull(column)) null else long(column)
        override fun doubleOrNull(column: Int): Double? =
            if (isNull(column)) null else double(column)

        override fun stringOrNull(column: Int): String? =
            if (isNull(column)) null else string(column)
    }

    companion object {

        /**
         * Opens (creating if absent) and migrates to [StateSchema.VERSION].
         *
         * @param backup where to copy the database before a migration runs. A migration
         * that corrupts data is a bug that ships occasionally; one with no way back ends
         * the app's usefulness, because these rows are the user's characters.
         */
        fun open(path: Path, backup: (Path) -> Unit = { defaultBackup(it) }): StateDb {
            val connection = DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}")
            connection.createStatement().use { statement ->
                // Both are per-connection and both are off by default. Without
                // foreign_keys the ON DELETE CASCADE clauses are decoration, and deleting
                // a document would orphan its trackers silently and permanently.
                statement.execute("PRAGMA foreign_keys = ON")
                statement.execute("PRAGMA journal_mode = WAL")
            }
            val db = StateDb(connection, path)
            // A migration that throws leaves the caller with no handle to close, so the
            // connection would hold the database and its WAL locked for the rest of the
            // process — precisely while the app is trying to restore the backup that
            // failure just made relevant.
            try {
                db.migrate(backup)
            } catch (e: Throwable) {
                runCatching { db.close() }
                throw e
            }
            return db
        }

        private fun defaultBackup(path: Path) {
            if (!Files.exists(path)) return
            Files.copy(
                path,
                path.resolveSibling("${path.fileName}.pre-migration"),
                StandardCopyOption.REPLACE_EXISTING,
            )
        }
    }

    private fun migrate(backup: (Path) -> Unit) {
        val current = query("PRAGMA user_version") { it.int(0) }.single()
        if (current == StateSchema.VERSION) return
        require(current <= StateSchema.VERSION) {
            "database is at version $current; this build understands ${StateSchema.VERSION}"
        }

        // Fold the write-ahead log into the database file before the backup is taken. In
        // WAL mode a committed page can live only in `state.db-wal`, so copying
        // `state.db` alone would capture a database missing the most recent commits the
        // backup claims to preserve — and the case where that happens, a previous
        // process ending abruptly with an unchecked-in WAL, is exactly the case where
        // someone reaches for the backup.
        val busy = query("PRAGMA wal_checkpoint(TRUNCATE)") { it.long(0) }.singleOrNull()
        check(busy == 0L) {
            "could not check the write-ahead log into $path before migrating; " +
                "another connection is holding it"
        }
        backup(path)

        for (target in (current + 1)..StateSchema.VERSION) {
            // PRAGMA user_version does not accept a bound parameter, and `target` is a
            // loop index over an internal constant rather than anything a caller supplies.
            transaction {
                StateSchema.MIGRATIONS[target - 1]
                    .split(";")
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach { execute(it) }
                execute("PRAGMA user_version = $target")
            }
        }
    }
}
