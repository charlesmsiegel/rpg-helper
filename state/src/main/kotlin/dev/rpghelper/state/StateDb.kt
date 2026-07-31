package dev.rpghelper.state

import androidx.sqlite.SQLiteConnection
import androidx.sqlite.SQLiteStatement
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.sqlite.execSQL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * The app's state database: opened, migrated, and written through here.
 *
 * Read-only pack access lives in `:pack` behind its own interface. This one writes, so it
 * is deliberately separate rather than a widening of that one — a pack must never be
 * writable by construction, and sharing an interface is how that stops being true.
 *
 * **Bound to the same SQLite the packs use**, for the same reason. This was JDBC, and
 * `:app` excludes `sqlite-jdbc` because its native libraries cannot load on Android — so
 * constructing `AskViewModel` reached `DriverManager` and the app died at startup with *no
 * suitable driver*, before the Ask screen could draw. Fixing the read path and leaving the
 * write path on the desktop driver was the same mistake one layer up: a binding chosen
 * where a database happens to be opened rather than once, for the program.
 */
class StateDb private constructor(
    private val connection: SQLiteConnection,
    val path: Path,
) : AutoCloseable {

    /**
     * Serializes every use of the connection.
     *
     * `autoCommit` and the current transaction are **connection-wide state**, not
     * per-caller state, so two threads sharing this object are not two independent
     * writers. Without the lock, a background pack install overlapping a document edit
     * pulls that edit into the install's transaction — and loses it when the install
     * rolls back. The two `transaction` calls would also restore `autoCommit` underneath
     * each other, committing partial work.
     *
     * Reentrant because a store legitimately queries inside its own transaction.
     */
    private val lock = ReentrantLock()

    /** Depth of enclosing transactions, so a nested one joins rather than commits early. */
    private var depth = 0

    fun <T> query(sql: String, vararg args: Any?, read: (Row) -> T): List<T> = lock.withLock {
        prepare(sql, args).use { statement ->
            val out = mutableListOf<T>()
            val row = StatementRow(statement)
            while (statement.step()) out += read(row)
            out
        }
    }

    /** @return rows changed, as SQLite counts them. */
    fun execute(sql: String, vararg args: Any?): Int = lock.withLock {
        prepare(sql, args).use { it.step() }
        prepare("SELECT changes()", emptyArray()).use {
            it.step()
            it.getLong(0).toInt()
        }
    }

    /**
     * Runs [body] in a transaction, rolling back if it throws.
     *
     * A nested call joins the enclosing transaction rather than opening its own: SQLite
     * has no nested transactions, and committing the inner one would commit the outer's
     * work early — the opposite of what the caller asked for.
     */
    fun <T> transaction(body: () -> T): T = lock.withLock {
        if (depth > 0) return@withLock body()

        // Explicit statements rather than a driver's `autoCommit` flag: this binding has
        // no such flag, and SQLite's own BEGIN/COMMIT is what `autoCommit` was standing in
        // for anyway. The nesting rule above is unchanged and is what makes one BEGIN
        // correct.
        connection.execSQL("BEGIN")
        depth = 1
        try {
            val result = body()
            connection.execSQL("COMMIT")
            result
        } catch (e: Throwable) {
            runCatching { connection.execSQL("ROLLBACK") }
            throw e
        } finally {
            depth = 0
        }
    }

    /** The id SQLite assigned to the most recent insert on this connection. */
    fun lastInsertId(): Long = query("SELECT last_insert_rowid()") { it.long(0) }.single()

    override fun close() = lock.withLock { connection.close() }

    /** Bind indices are 1-based here and column indices are 0-based, as SQLite has them. */
    private fun prepare(sql: String, args: Array<out Any?>): SQLiteStatement {
        val statement = connection.prepare(sql)
        args.forEachIndexed { index, value ->
            val slot = index + 1
            when (value) {
                null -> statement.bindNull(slot)
                is Int -> statement.bindLong(slot, value.toLong())
                is Long -> statement.bindLong(slot, value)
                is Double -> statement.bindDouble(slot, value)
                is Boolean -> statement.bindLong(slot, if (value) 1L else 0L)
                is ByteArray -> statement.bindBlob(slot, value)
                else -> statement.bindText(slot, value.toString())
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

    private class StatementRow(private val statement: SQLiteStatement) : Row {
        override fun isNull(column: Int): Boolean = statement.isNull(column)
        override fun long(column: Int): Long = statement.getLong(column)
        override fun double(column: Int): Double = statement.getDouble(column)
        override fun string(column: Int): String = statement.getText(column)
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
            Files.createDirectories(path.toAbsolutePath().parent)
            val connection = BundledSQLiteDriver().open(
                path.toAbsolutePath().toString(),
                // OPEN_READWRITE | OPEN_CREATE. Named rather than imported so the intent
                // survives a reader who does not know the constants — and worth stating
                // because this is the one database in the program that is meant to be
                // writable at all.
                0x00000002 or 0x00000004,
            )
            // Both are per-connection and both are off by default. Without foreign_keys
            // the ON DELETE CASCADE clauses are decoration, and deleting a document would
            // orphan its trackers silently and permanently.
            connection.execSQL("PRAGMA foreign_keys = ON")
            // `journal_mode` answers with a row, so it is stepped rather than executed:
            // a pragma whose result is never read is a pragma some builds never apply.
            connection.prepare("PRAGMA journal_mode = WAL").use { it.step() }
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
        // Both bounds. A damaged or externally restored file can report a negative
        // version, and an upper-bound-only check would let the loop start at target 0 and
        // index MIGRATIONS[-1] -- aborting every startup from then on, with no path out
        // that does not involve deleting the user's characters.
        require(current in 0..StateSchema.VERSION) {
            "database is at version $current; this build understands 0..${StateSchema.VERSION}"
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
