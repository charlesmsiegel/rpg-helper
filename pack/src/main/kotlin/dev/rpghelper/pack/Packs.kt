package dev.rpghelper.pack

import java.nio.file.Path

/** Entry point for the activation gate, and for reading a pack's identity. */
object Packs {

    /**
     * Opens [path] read-only and reports whether it may be activated.
     *
     * @param supportedEmbedders contracts whose weights the calling build bundles.
     */
    fun validateFile(path: Path, supportedEmbedders: Set<EmbedderContract>): ValidationReport =
        JdbcDb.openReadOnly(path).use { PackValidator(supportedEmbedders).validate(it) }

    /**
     * Reads `pack_meta` from [path].
     *
     * Exposed so callers can record a pack's identity without opening its tables
     * themselves: a caller reaching into a pack's schema is a caller that will still be
     * doing so after the schema changes. Throws [PackReadException] if the file cannot
     * answer, which for an unvalidated file is the expected outcome rather than a bug.
     */
    fun readMeta(path: Path): PackMeta =
        JdbcDb.openReadOnly(path).use { db ->
            db.map(
                "SELECT schema_version, pack_uid, pack_version, title, ruleset_id, " +
                    "embedder_id, embedder_dim FROM pack_meta",
            ) {
                PackMeta(
                    schemaVersion = it.int(0),
                    packUid = it.string(1),
                    packVersion = it.string(2),
                    title = it.string(3),
                    rulesetId = it.stringOrNull(4),
                    embedderId = it.string(5),
                    embedderDim = it.int(6),
                )
            }.singleOrNull() ?: throw PackReadException("pack_meta does not hold exactly one row")
        }

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
            JdbcDb.openReadOnly(path).use { db ->
                val meta = db.map(
                    "SELECT schema_version, embedder_id, embedder_dim FROM pack_meta",
                ) { Triple(it.int(0), it.string(1), it.int(2)) }.singleOrNull()
                    ?: return@use "pack_meta does not hold exactly one row"

                val (version, embedderId, dim) = meta
                if (version != PackSchema.SCHEMA_VERSION) {
                    return@use "schema version $version; this build understands " +
                        "${PackSchema.SCHEMA_VERSION}"
                }
                val contract = supportedEmbedders.firstOrNull { it.id == embedderId }
                    ?: return@use "needs embedder '$embedderId', which this build does not bundle"
                if (contract.dim != dim) {
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
