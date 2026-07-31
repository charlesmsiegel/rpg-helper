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
}
