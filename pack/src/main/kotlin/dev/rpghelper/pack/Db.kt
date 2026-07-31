package dev.rpghelper.pack

/**
 * The whole of this module's contact with SQLite.
 *
 * It exists so the validator can run on the JVM under test today and against a bundled
 * SQLite on Android later without either being a rewrite. That is a real, near-term
 * swap rather than speculative generality: `sqlite-jdbc` ships native desktop
 * libraries and cannot run on a device, and the platform's own SQLite cannot be relied
 * on for FTS5 (see `docs/pack-schema.md`, "SQLite on the device").
 *
 * Deliberately read-only and deliberately tiny. Packs are read-only at runtime, and
 * every query this module issues is a static string, so there is no parameter binding
 * and nothing here for a caller to misuse.
 */
interface Db : AutoCloseable {

    /** Streams rows, so a pass over every vector or every chunk's text retains none of it. */
    fun forEachRow(sql: String, action: (Row) -> Unit)

    /** Names of every table and view in the file, including virtual tables. */
    fun tableNames(): Set<String>
}

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
