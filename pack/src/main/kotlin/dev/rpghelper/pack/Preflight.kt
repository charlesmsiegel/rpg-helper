package dev.rpghelper.pack

import java.nio.file.Path

/**
 * Ceilings that bound what a malformed or hostile pack can do before it can be refused.
 *
 * A file-size limit alone is not one. A pack well under two gigabytes can hold millions of
 * chunks or aliases, or one text value of a gigabyte, and the validator scans and
 * materializes all of it *before* any check could reject it — so the refusal arrives as an
 * out-of-memory kill rather than as a refusal.
 *
 * These live in `:pack` rather than beside the install path because they are a property of
 * the format, and because the protection has to hold at every entry point. It used to hold
 * at one.
 */
data class PackLimits(
    val maxChunks: Long = 500_000,
    val maxVectors: Long = 5_000_000,
    val maxEntities: Long = 500_000,
    val maxTableRows: Long = 1_000_000,
    /**
     * Any single cell the loaders can materialize. A book's longest chapter is far below
     * this, and so is the longest alias, canonical name, capability manifest and
     * constraint payload anyone would write on purpose.
     */
    val maxTextBytes: Long = 4L * 1024 * 1024,
    /** One embedding. A 4096-dimension binary16 vector is 8 KiB. */
    val maxBlobBytes: Long = 64 * 1024,
)

/**
 * Row counts and largest single values, read **without materializing anything**.
 *
 * `count(*)` and `max(length(...))` are answered by SQLite from the file, which is the
 * entire point: materializing the values is the failure being prevented, so the check
 * cannot be written in terms of reading them.
 */
object Preflight {

    /**
     * Relations whose cells later code turns into Kotlin objects.
     *
     * Every required table except `chunks_fts`, whose stored content is the external-content
     * index rather than values any loader reads — retrieval takes `rowid` and a computed
     * `bm25()` from it and nothing else. Scanning it would bound the shadow tables' own
     * blobs, which are legitimately large and are never handed to `Row.string`.
     */
    private val SCANNED: List<String> = (PackSchema.REQUIRED_TABLES - "chunks_fts").sorted()

    /** Why [path] exceeds [limits], or null when it is within them. */
    fun check(path: Path, limits: PackLimits = PackLimits()): String? = runCatching {
        Sqlite.openReadOnly(path).use { check(it, limits) }
    }.getOrElse {
        // A file that cannot be queried at all is not *too large*, it is not a pack -- and
        // the validator says so precisely, where this could only guess. Falling through
        // keeps "too large" meaning too large.
        null
    }

    /**
     * The same check against an already-open pack.
     *
     * This is the form callers should prefer: a caller that preflights a path, then
     * validates the path, then opens the path has checked three files that are only
     * probably the same one.
     */
    fun check(db: Db, limits: PackLimits = PackLimits()): String? {
        val counts = listOf(
            Triple("chunks", "chunks", limits.maxChunks),
            Triple("vectors", "vectors", limits.maxVectors),
            Triple("entities", "entities", limits.maxEntities),
            Triple("table rows", "table_rows", limits.maxTableRows),
        )
        // Counts first. They are the cheap half, and a pack that fails one of them is a
        // pack whose per-column scans below would be the expensive way to learn the same
        // thing.
        for ((what, table, ceiling) in counts) {
            val actual = aggregate(db, "SELECT count(*) FROM $table") ?: continue
            if (actual > ceiling) return "$what: $actual exceeds the limit of $ceiling"
        }

        // The tightest ceiling in the format, kept as its own check because the general
        // one below cannot express a per-column bound.
        val embedding = aggregate(db, "SELECT COALESCE(max(length(embedding)), 0) FROM vectors")
        if (embedding != null && embedding > limits.maxBlobBytes) {
            return "an embedding: $embedding exceeds the limit of ${limits.maxBlobBytes}"
        }

        // A file that is not a database at all answers nothing, and that is the validator's
        // sentence to pass, not this one's.
        val present = runCatching { db.tableNames() }.getOrElse { return null }
        for (table in SCANNED) {
            if (table !in present) continue // Absent tables are the validator's refusal to make.
            val fault = widestCell(db, table, limits.maxTextBytes)
            if (fault != null) return fault
        }
        return null
    }

    /**
     * One aggregate, or null if this pack cannot answer it.
     *
     * A missing table or column is a **refusal the validator makes**, in the words the
     * format uses, and it runs immediately after this. Letting the read throw out of here
     * would replace that refusal with a driver exception the caller never expected — and
     * a per-check null rather than one runCatching around everything is what keeps a
     * single unreadable column from quietly disabling every remaining bound.
     */
    private fun aggregate(db: Db, sql: String): Long? =
        runCatching { db.map(sql) { it.long(0) }.single() }.getOrNull()

    /**
     * The largest cell in [table], in bytes, across **every** column in one scan.
     *
     * Enumerating the columns the loaders happen to read today would leave the next column
     * anyone adds unbounded, and the bound would be a fact about this function rather than
     * about the format. Asking the file which columns it has covers the ones nobody wrote
     * down — and a pack with an extra column is exactly the pack this runs before refusing.
     *
     * **Declared type is not consulted.** SQLite affinity is advisory and a pack's DDL is
     * whatever its builder wrote, so an `INTEGER` column can hold a gigabyte of text; using
     * the declaration to decide what to measure would trust the artifact under inspection.
     * Measuring every column costs one scan per table and an integer's `CAST` is five bytes.
     */
    private fun widestCell(db: Db, table: String, ceiling: Long): String? {
        val columns = runCatching {
            db.map("SELECT name FROM pragma_table_info('$table')") { it.string(0) }
        }.getOrElse { return null }
        if (columns.isEmpty()) return null

        // `length()` on TEXT counts *characters*, so every game book that is not English
        // prose would get its ceiling silently raised -- by three for CJK, four for emoji.
        // The limit and its message are in bytes, so the CAST is what makes them the same
        // unit, and the packs this bound exists to catch are precisely the ones that would
        // otherwise pass it.
        val widest = columns.joinToString(", ") {
            "COALESCE(max(length(CAST(${quote(it)} AS BLOB))), 0)"
        }
        val row = runCatching {
            db.map("SELECT $widest FROM $table") { row -> columns.indices.map { row.long(it) } }
                .single()
        }.getOrElse { return null }

        val over = columns.indices.firstOrNull { row[it] > ceiling } ?: return null
        return "$table.${columns[over]}: ${row[over]} bytes exceeds the limit of $ceiling"
    }

    /** A column name out of the pack's own schema, as a SQL identifier. */
    private fun quote(identifier: String) = "\"" + identifier.replace("\"", "\"\"") + "\""
}
