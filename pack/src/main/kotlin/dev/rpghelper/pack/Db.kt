package dev.rpghelper.pack

/**
 * The whole of this module's contact with SQLite.
 *
 * It exists so the validator can run on the JVM under test today and against a bundled
 * SQLite on Android later without either being a rewrite. That is a real, near-term
 * swap rather than speculative generality: `sqlite-jdbc` ships native desktop
 * libraries and cannot run on a device, and the platform's own SQLite cannot be relied
 * on for FTS5 (see `docs/00-pack-schema.md`, "SQLite on the device").
 *
 * Deliberately read-only and deliberately tiny. Packs are read-only at runtime, and
 * every query this module issues is a static string, so there is no parameter binding
 * and nothing here for a caller to misuse.
 */
interface Db : AutoCloseable {

    /**
     * Streams rows, so a pass over every vector or every chunk's text retains none of it.
     *
     * @throws PackReadException if the file cannot answer the query -- a missing column,
     * a relation that is not the kind of relation it claimed to be, or corruption.
     */
    fun forEachRow(sql: String, action: (Row) -> Unit)

    /** Names of every table and view in the file, including virtual tables. */
    fun tableNames(): Set<String>

    /**
     * Runs a statement that returns no rows.
     *
     * **This does not make a pack writable.** The connection is opened `SQLITE_OPEN_READONLY`
     * and stays that way; what this reaches is SQLite's `temp` database, which is separate
     * from the file and writable regardless. The only statements this program issues through
     * it build the per-connection `temp.withdrawn` table that supersession is applied from —
     * a table whose alternative was pasting a hundred thousand chunk ids into the text of
     * every query.
     *
     * @throws PackReadException if the statement fails.
     */
    fun execute(sql: String)
}

/**
 * A pack could not be read as the format requires.
 *
 * Driver failures are wrapped in this rather than surfacing `SQLException`, so the
 * validator can turn a malformed pack into a violation without importing JDBC -- and so
 * the Android implementation can report the same condition from an entirely different
 * exception hierarchy.
 */
class PackReadException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/**
 * One row of a result set. Columns are zero-indexed, matching Android's `Cursor`
 * rather than JDBC's 1-based convention -- the device is where this ends up, and a
 * silent off-by-one at the porting step is not a bug worth arranging for.
 */
interface Row {
    fun isNull(column: Int): Boolean
    fun long(column: Int): Long
    fun string(column: Int): String
    fun bytes(column: Int): ByteArray

    /**
     * A REAL column.
     *
     * Nothing the pack *stores* is a REAL — the format has no floating-point columns, and
     * embeddings are BLOBs. This exists for `bm25()`, which is a value SQLite computes
     * rather than one a builder wrote, and is the only place retrieval reads one.
     */
    fun double(column: Int): Double

    fun longOrNull(column: Int): Long? = if (isNull(column)) null else long(column)
    fun stringOrNull(column: Int): String? = if (isNull(column)) null else string(column)
    fun int(column: Int): Int = long(column).toInt()
}

/** Collects mapped rows. For passes whose per-row data is small enough to retain. */
fun <T> Db.map(sql: String, transform: (Row) -> T): List<T> {
    val out = mutableListOf<T>()
    forEachRow(sql) { out += transform(it) }
    return out
}

/**
 * The `CREATE` statement [relation] was declared with, or null if there is no such relation.
 *
 * Used to tell a real FTS5 virtual table from an ordinary table wearing its name. The
 * relation name is a constant from [PackSchema], never user or pack input.
 */
fun Db.declarationOf(relation: String): String? {
    var declaration: String? = null
    forEachRow("SELECT sql FROM sqlite_master WHERE name = '$relation'") {
        declaration = it.stringOrNull(0)
    }
    return declaration
}
