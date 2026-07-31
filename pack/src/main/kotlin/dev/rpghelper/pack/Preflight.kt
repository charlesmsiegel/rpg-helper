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
    /** One chunk's text. A book's longest chapter is far below this. */
    val maxTextBytes: Long = 4L * 1024 * 1024,
    /** One embedding. A 4096-dimension binary16 vector is 8 KiB. */
    val maxBlobBytes: Long = 64 * 1024,
)

/**
 * Row counts and largest single values, read **without materializing anything**.
 *
 * `count(*)` and `max(length(...))` are answered by SQLite from its own structures, which
 * is the entire point: materializing the values is the failure being prevented, so the
 * check cannot be written in terms of reading them.
 */
object Preflight {

    /** Why [path] exceeds [limits], or null when it is within them. */
    fun check(path: Path, limits: PackLimits = PackLimits()): String? = runCatching {
        JdbcDb.openReadOnly(path).use { db ->
            val checks = listOf(
                Triple("chunks", "SELECT count(*) FROM chunks", limits.maxChunks),
                Triple("vectors", "SELECT count(*) FROM vectors", limits.maxVectors),
                Triple("entities", "SELECT count(*) FROM entities", limits.maxEntities),
                Triple("table rows", "SELECT count(*) FROM table_rows", limits.maxTableRows),
                Triple(
                    "a chunk's text",
                    // Cast to BLOB, because `length()` on TEXT counts *characters*. The
                    // limit and its message are stated in bytes, and every game book that
                    // is not English prose gets its ceiling silently raised otherwise --
                    // by three for CJK, four for emoji -- so the exact packs this bound
                    // exists to catch are the ones that would pass it.
                    "SELECT COALESCE(max(length(CAST(text AS BLOB))), 0) FROM chunks",
                    limits.maxTextBytes,
                ),
                Triple(
                    "an embedding",
                    "SELECT COALESCE(max(length(embedding)), 0) FROM vectors",
                    limits.maxBlobBytes,
                ),
            )
            for ((what, sql, ceiling) in checks) {
                val actual = db.map(sql) { it.long(0) }.single()
                if (actual > ceiling) return@use "$what: $actual exceeds the limit of $ceiling"
            }
            null
        }
    }.getOrElse {
        // A file that cannot be queried at all is not *too large*, it is not a pack -- and
        // the validator says so precisely, where this could only guess. Falling through
        // keeps "too large" meaning too large.
        null
    }
}
