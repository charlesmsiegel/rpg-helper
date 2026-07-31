package dev.rpghelper.pack

import dev.rpghelper.pack.ViolationCode.DANGLING_CHUNK_REFERENCE
import dev.rpghelper.pack.ViolationCode.DANGLING_SOURCE_REFERENCE
import dev.rpghelper.pack.ViolationCode.GAP_REASON_INVALID
import dev.rpghelper.pack.ViolationCode.LOCATOR_SCHEME_INVALID
import dev.rpghelper.pack.ViolationCode.PAGE_LABEL_SCHEME_INVALID

/**
 * Rows that address a chunk or a source directly, never through retrieval.
 *
 * A dangling reference here is not a search-quality problem: the roller, the constraint
 * engine, the alias rewrite, and citation rendering all resolve these by id and would
 * fail at the moment of use.
 */
internal fun checkChunkReferences(
    db: Db,
    chunks: Map<Long, ChunkRow>,
    out: MutableList<Violation>,
) {
    val references = listOf(
        "entities" to "SELECT entity_id, chunk_id FROM entities",
        "tables" to "SELECT table_id, chunk_id FROM tables",
        "capabilities" to "SELECT capability_id, chunk_id FROM capabilities",
        "constraints" to "SELECT constraint_id, chunk_id FROM constraints",
        "supersessions" to "SELECT supersession_id, superseding_chunk_id FROM supersessions",
    )

    for ((table, sql) in references) {
        db.forEachRow(sql) { row ->
            // A NULL reference is legal in entities and supersessions and simply means
            // "not governed by a particular chunk".
            val chunkId = row.longOrNull(1) ?: return@forEachRow
            if (chunkId !in chunks) {
                out += Violation(
                    DANGLING_CHUNK_REFERENCE,
                    "$table ${row.long(0)} references chunk $chunkId, which is not in this pack",
                )
            }
        }
    }
}

/**
 * Every `source_id` must resolve.
 *
 * SQLite's foreign-key declarations do not validate rows inserted while enforcement was
 * off, and packs are built elsewhere, so the declaration in the DDL guarantees nothing
 * about the file in hand. An unresolvable `source_id` on a chunk means its book title,
 * edition, and UID cannot be read when the quotation is rendered -- leaving it
 * uncitable, which for a quotation is the whole point of having it.
 */
internal fun checkSourceReferences(
    db: Db,
    chunks: Map<Long, ChunkRow>,
    out: MutableList<Violation>,
) {
    val sources = mutableSetOf<Long>()
    db.forEachRow("SELECT source_id FROM sources") { sources += it.long(0) }

    for (chunk in chunks.values) {
        val sourceId = chunk.sourceId ?: continue
        if (sourceId !in sources) {
            out += Violation(
                DANGLING_SOURCE_REFERENCE,
                "chunk ${chunk.id} references source $sourceId, which is not in this pack",
            )
        }
    }

    val references = listOf(
        "source_page_labels" to "SELECT seq, source_id FROM source_page_labels",
        "source_gaps" to "SELECT gap_id, source_id FROM source_gaps",
    )
    for ((table, sql) in references) {
        db.forEachRow(sql) { row ->
            val sourceId = row.long(1)
            if (sourceId !in sources) {
                out += Violation(
                    DANGLING_SOURCE_REFERENCE,
                    "$table row ${row.long(0)} references source $sourceId, " +
                        "which is not in this pack",
                )
            }
        }
    }
}

/**
 * The closed vocabularies that the DDL declares as plain `TEXT`.
 *
 * Each of these drives a rendering decision with no defined behaviour outside its set:
 * an unknown locator scheme has no citation format, an unknown page-label scheme has no
 * numbering rule, and an unknown gap reason describes an omission the app cannot
 * explain. Leaving them unchecked would let a pack activate and then produce a citation
 * the app has no way to write.
 */
internal fun checkClosedVocabularies(db: Db, out: MutableList<Violation>) {
    db.forEachRow("SELECT source_id, locator_scheme FROM sources") { row ->
        val scheme = row.string(1)
        if (scheme !in PackSchema.LOCATOR_SCHEMES) {
            out += Violation(
                LOCATOR_SCHEME_INVALID,
                "source ${row.long(0)} declares locator_scheme '$scheme'",
            )
        }
    }

    db.forEachRow("SELECT source_id, seq, scheme FROM source_page_labels") { row ->
        val scheme = row.string(2)
        if (scheme !in PackSchema.PAGE_LABEL_SCHEMES) {
            out += Violation(
                PAGE_LABEL_SCHEME_INVALID,
                "source ${row.long(0)} page-label range ${row.long(1)} declares scheme '$scheme'",
            )
        }
    }

    db.forEachRow("SELECT gap_id, reason FROM source_gaps") { row ->
        val reason = row.string(1)
        if (reason !in PackSchema.GAP_REASONS) {
            out += Violation(
                GAP_REASON_INVALID,
                "source_gaps ${row.long(0)} declares reason '$reason'",
            )
        }
    }
}
