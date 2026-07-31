package dev.rpghelper.pack

import java.nio.file.Path

/** Entry point for the activation gate, and for reading a pack's identity. */
object Packs {

    /**
     * Opens [path] read-only and reports whether it may be activated.
     *
     * @param supportedEmbedders contracts whose weights the calling build bundles.
     */
    fun validateFile(
        path: Path,
        supportedEmbedders: Set<EmbedderContract>,
        limits: PackLimits = PackLimits(),
    ): ValidationReport =
        Sqlite.openReadOnly(path).use { validate(it, supportedEmbedders, limits) }

    /**
     * The gate, against a pack that is **already open**.
     *
     * Preferred over [validateFile] wherever the caller goes on to read the pack, because
     * a caller that validates a path and then opens the path has validated one file and
     * read another that is only probably the same. See `Library.openLeased`.
     */
    fun validate(
        db: Db,
        supportedEmbedders: Set<EmbedderContract>,
        limits: PackLimits = PackLimits(),
    ): ValidationReport {
        // **The preflight runs here, not only where a pack is installed.** The validator
        // materializes chunk metadata and reads every text value, so a malformed file well
        // under any file-size ceiling can hold millions of rows or one enormous cell and
        // exhaust the heap *before* it can be refused -- an out-of-memory kill rather than
        // a refusal. `PackLibrary.install` ran this first; `verify`, `build` and every
        // direct `Library.open` reached the validator without it, which made the protection
        // a property of one call site rather than of the gate.
        val fault = Preflight.check(db, limits)
        if (fault != null) {
            return ValidationReport(listOf(Violation(ViolationCode.PACK_EXCEEDS_LIMITS, fault)))
        }
        return PackValidator(supportedEmbedders).validate(db)
    }

    /**
     * Reads `pack_meta` from [path].
     *
     * Exposed so callers can record a pack's identity without opening its tables
     * themselves: a caller reaching into a pack's schema is a caller that will still be
     * doing so after the schema changes. Throws [PackReadException] if the file cannot
     * answer, which for an unvalidated file is the expected outcome rather than a bug.
     */
    fun readMeta(path: Path): PackMeta = Sqlite.openReadOnly(path).use { readMeta(it) }

    /** [readMeta] against a pack that is already open. */
    fun readMeta(db: Db): PackMeta =
        db.map(
            "SELECT schema_version, pack_uid, pack_version, title, ruleset_id, " +
                "embedder_id, embedder_dim FROM pack_meta",
        ) {
            PackMeta(
                schemaVersion = it.long(0),
                packUid = it.string(1),
                packVersion = it.string(2),
                title = it.string(3),
                rulesetId = it.stringOrNull(4),
                embedderId = it.string(5),
                embedderDim = it.long(6),
            )
        }.singleOrNull() ?: throw PackReadException("pack_meta does not hold exactly one row")

    /**
     * The **open-time subset**: schema version, embedder contract, probe vector.
     *
     * Runs every time a pack is handed to a reader, where the full gate cannot: hashing a
     * hundred megabytes per pack on every launch puts seconds on cold start, and a check
     * that makes the app feel broken is a check that gets removed. This is the cheap half
     * of the bargain `01-app-state-spec.md` strikes — gross damage caught immediately at
     * open, silent bit-rot caught by the background digest pass.
     *
     * Deliberately **not** a substitute for either. It cannot see a flipped bit in
     * `chunks.text`, which is the failure that would quote mutated text as byte-exact, and
     * saying so is the reason the digest exists.
     *
     * @return null if the pack passes, or why it did not.
     */
    fun openCheck(path: Path, supportedEmbedders: Set<EmbedderContract>): String? =
        runCatching {
            Sqlite.openReadOnly(path).use { db ->
                // Read as Long, never narrowed to Int. `4294967297` truncates to 1 and
                // `4294967424` truncates to 128, so a damaged or substituted file could
                // declare exactly the gross metadata corruption this check exists to
                // catch and be lent out anyway. The full validator reads
                // `schema_version` as a Long for the same reason.
                val meta = db.map(
                    "SELECT schema_version, embedder_id, embedder_dim FROM pack_meta",
                ) { Triple(it.long(0), it.string(1), it.long(2)) }.singleOrNull()
                    ?: return@use "pack_meta does not hold exactly one row"

                val (version, embedderId, dim) = meta
                if (version != PackSchema.SCHEMA_VERSION.toLong()) {
                    return@use "schema version $version; this build understands " +
                        "${PackSchema.SCHEMA_VERSION}"
                }
                val contract = supportedEmbedders.firstOrNull { it.id == embedderId }
                    ?: return@use "needs embedder '$embedderId', which this build does not bundle"
                if (contract.dim.toLong() != dim) {
                    return@use "embedder '$embedderId' is ${contract.dim}-dimensional here, " +
                        "pack declares $dim"
                }
                val probe = db.map("SELECT probe_vector FROM pack_meta") { it.bytes(0) }.single()
                if (!ProbeVector.matches(probe)) {
                    return@use "the probe vector does not decode to its pinned constant"
                }
                null
            }
        }.getOrElse { "cannot be read: ${it.message}" }
}
