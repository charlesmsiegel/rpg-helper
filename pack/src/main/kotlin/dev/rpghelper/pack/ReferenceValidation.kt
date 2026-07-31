package dev.rpghelper.pack

import dev.rpghelper.pack.ViolationCode.DANGLING_CHUNK_REFERENCE
import dev.rpghelper.pack.ViolationCode.DANGLING_SOURCE_REFERENCE
import dev.rpghelper.pack.ViolationCode.DANGLING_TABLE_REFERENCE
import dev.rpghelper.pack.ViolationCode.DICE_EXPR_UNPARSEABLE
import dev.rpghelper.pack.ViolationCode.TABLE_ROWS_INCOMPLETE
import dev.rpghelper.pack.ViolationCode.ALIAS_NOT_NORMALIZED
import dev.rpghelper.pack.ViolationCode.DUPLICATE_SOURCE_ID
import dev.rpghelper.pack.ViolationCode.DUPLICATE_TABLE_ID
import dev.rpghelper.pack.ViolationCode.DUPLICATE_SOURCE_UID
import dev.rpghelper.pack.ViolationCode.GAP_REASON_INVALID
import dev.rpghelper.pack.ViolationCode.MISSING_CHUNK_REFERENCE
import dev.rpghelper.pack.ViolationCode.TABLE_ROW_RANGE_OVERLAP
import dev.rpghelper.pack.ViolationCode.TABLE_ROW_SPAN_OUTSIDE_CHUNK
import dev.rpghelper.pack.ViolationCode.TABLE_ROW_TEXT_MISMATCH
import dev.rpghelper.pack.ViolationCode.LOCATOR_SCHEME_INVALID
import dev.rpghelper.pack.ViolationCode.PAGE_LABEL_SCHEME_INVALID
import dev.rpghelper.pack.ViolationCode.SUPERSESSION_WITHDRAWS_ITSELF

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
    // `nullable` marks the two relations whose DDL permits a NULL chunk: an alias not
    // governed by a particular passage, and an errata row with no superseding chunk of
    // its own. For the other three the DDL says NOT NULL -- and a pack's DDL is written
    // by its builder, so that has to be checked rather than assumed. A NULL
    // constraints.chunk_id would produce a violation with no passage to cite, which
    // routing then refuses to render at all: a rule that silently stops being reportable.
    val references = listOf(
        Triple("entities", "SELECT entity_id, chunk_id FROM entities", true),
        Triple("tables", "SELECT table_id, chunk_id FROM tables", false),
        Triple("capabilities", "SELECT capability_id, chunk_id FROM capabilities", false),
        Triple("constraints", "SELECT constraint_id, chunk_id FROM constraints", false),
        Triple(
            "supersessions",
            "SELECT supersession_id, superseding_chunk_id FROM supersessions",
            true,
        ),
    )

    for ((table, sql, nullable) in references) {
        db.forEachRow(sql) { row ->
            val chunkId = row.longOrNull(1)
            if (chunkId == null) {
                if (!nullable) {
                    out += Violation(
                        MISSING_CHUNK_REFERENCE,
                        "$table ${row.long(0)} has no chunk_id, which the format requires",
                    )
                }
                return@forEachRow
            }
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
 * A correction must survive its own application.
 *
 * Supersession targets `(source_uid, stable_key)` and applies to every active pack
 * carrying that source — including the pack the errata itself lives in, which is the
 * ordinary case for a book shipping with its own corrections. A row whose superseding
 * chunk *is* one of the chunks it withdraws therefore deactivates the replacement text
 * along with the text it replaces: the rule goes silently missing and nothing explains
 * why, which is strictly worse than shipping no errata at all.
 */
internal fun checkSupersessions(db: Db, out: MutableList<Violation>) {
    val sourceUids = mutableMapOf<Long, String>()
    db.forEachRow("SELECT source_id, source_uid FROM sources") {
        sourceUids[it.long(0)] = it.string(1)
    }

    // (source_uid, stable_key) for each chunk that has both -- the coordinates a
    // supersession names, resolved the way the retrieval filter resolves them.
    val coordinates = mutableMapOf<Long, Pair<String, String>>()
    db.forEachRow(
        "SELECT chunk_id, source_id, stable_key FROM chunks WHERE stable_key IS NOT NULL",
    ) { row ->
        val uid = row.longOrNull(1)?.let { sourceUids[it] } ?: return@forEachRow
        coordinates[row.long(0)] = uid to row.string(2)
    }

    db.forEachRow(
        "SELECT supersession_id, superseding_chunk_id, target_source_uid, target_stable_key " +
            "FROM supersessions",
    ) { row ->
        val superseding = row.longOrNull(1) ?: return@forEachRow
        val target = row.string(2) to row.string(3)
        if (coordinates[superseding] == target) {
            out += Violation(
                SUPERSESSION_WITHDRAWS_ITSELF,
                "supersessions ${row.long(0)} names chunk $superseding as the correction for " +
                    "(${target.first}, ${target.second}), which is what that chunk is",
            )
        }
    }
}

/**
 * `sources.source_uid` must be unique.
 *
 * The DDL declares it, which guarantees nothing about a file someone else built.
 * Supersession targets `(source_uid, stable_key)`, so a duplicate uid makes every erratum
 * aimed at it ambiguous across two books.
 */
internal fun checkSourceUids(db: Db, out: MutableList<Violation>) {
    val byUid = mutableMapOf<String, Long>()
    val ids = mutableSetOf<Long>()
    db.forEachRow("SELECT source_id, source_uid FROM sources") { row ->
        val id = row.long(0)
        val uid = row.string(1)

        val firstWithUid = byUid.putIfAbsent(uid, id)
        if (firstWithUid != null) {
            out += Violation(
                DUPLICATE_SOURCE_UID,
                "sources $firstWithUid and $id share source_uid '$uid'",
            )
        }
        // The PRIMARY KEY declaration is the builder's word, like everything else in the
        // pack's DDL. Two rows sharing an id leave every citation join free to return
        // either book, attributing authoritative text to the wrong source.
        if (!ids.add(id)) {
            out += Violation(DUPLICATE_SOURCE_ID, "two sources rows share source_id $id")
        }
    }
}

/**
 * `entities.alias` must be stored in the form a query is tokenized into.
 *
 * Matching is an indexed lookup against `idx_entities_alias`, not a fold-on-the-fly
 * comparison — that is the point of the index. So an alias holding capitals or diacritics
 * can never match anything, silently, for the life of the pack. It is the same failure
 * tracker keys avoid by normalizing at entry, and the same remedy: reject it at the door.
 */
internal fun checkAliasNormalization(db: Db, out: MutableList<Violation>) {
    db.forEachRow("SELECT entity_id, alias FROM entities") { row ->
        val alias = row.string(1)
        val folded = Utf8.foldForIndex(alias)
        if (alias != folded) {
            out += Violation(
                ALIAS_NOT_NORMALIZED,
                "entities ${row.long(0)} stores alias '$alias'; the indexed form is '$folded'",
            )
        }
    }
}

/**
 * `table_rows` feeds the roller, and the roller renders outcome text under the quotation
 * rule.
 *
 * The capability spec said the app "never has to handle a partial table" because the
 * builder validated it -- a guarantee from the artifact under inspection. Unchecked, a
 * pack can put arbitrary prose in `table_rows.text` and have it rendered in quotation
 * styling beneath the table's own citation.
 *
 * Coverage of the dice expression's full outcome range needs the grammar parser and
 * lands with the roller; everything decidable without it is checked here.
 */
internal fun checkTableRows(db: Db, chunks: Map<Long, ChunkRow>, out: MutableList<Violation>) {
    val tableChunk = mutableMapOf<Long, Long>()
    val expressions = mutableMapOf<Long, DiceExpression>()
    val seenTableIds = mutableSetOf<Long>()
    db.forEachRow("SELECT table_id, chunk_id, dice_expr FROM tables") { row ->
        val tableId = row.long(0)
        // Two rows can share a table_id under a pack's own DDL, and the maps below would
        // keep whichever SQLite returned last: every row would then be validated against
        // one definition while the roller resolved the id to either, rolling on one
        // table's outcomes beneath another table's citation.
        if (!seenTableIds.add(tableId)) {
            out += Violation(
                DUPLICATE_TABLE_ID,
                "two tables rows share table_id $tableId",
            )
        }
        tableChunk[tableId] = row.long(1)
        val expr = row.string(2)
        val parsed = DiceExpression.parse(expr)
        if (parsed == null) {
            // The app does not guess at expressions it does not recognise. A pack built
            // against a newer grammar declares a newer schema_version and is refused
            // before reaching here; anything else is a builder defect.
            out += Violation(
                DICE_EXPR_UNPARSEABLE,
                "table $tableId declares dice_expr '$expr', which the pinned grammar refuses",
            )
        } else {
            expressions[tableId] = parsed
        }
    }

    val ranges = mutableMapOf<Long, MutableList<Triple<Long, Long, Long>>>()

    db.forEachRow(
        "SELECT table_id, seq, lo, hi, span_start, span_end, text FROM table_rows",
    ) { row ->
        val tableId = row.long(0)
        val seq = row.long(1)
        val lo = row.long(2)
        val hi = row.long(3)
        val spanStart = row.long(4)
        val spanEnd = row.long(5)
        val text = row.string(6)
        val where = "table_rows ($tableId, $seq)"

        val chunkId = tableChunk[tableId]
        if (chunkId == null) {
            out += Violation(
                DANGLING_TABLE_REFERENCE,
                "$where names table $tableId, which is not in this pack",
            )
            return@forEachRow
        }
        if (lo > hi) {
            out += Violation(TABLE_ROW_RANGE_OVERLAP, "$where has an inverted range [$lo, $hi]")
        }
        ranges.getOrPut(tableId) { mutableListOf() }.add(Triple(lo, hi, seq))

        val chunk = chunks[chunkId] ?: return@forEachRow
        val chunkStart = chunk.spanStart ?: return@forEachRow
        val chunkEnd = chunk.spanEnd ?: return@forEachRow
        if (spanStart < chunkStart || spanEnd > chunkEnd || spanStart >= spanEnd) {
            out += Violation(
                TABLE_ROW_SPAN_OUTSIDE_CHUNK,
                "$where span [$spanStart, $spanEnd) is not inside chunk $chunkId's span " +
                    "[$chunkStart, $chunkEnd)",
            )
            return@forEachRow
        }
        rowTextMismatch(db, chunkId, spanStart - chunkStart, spanEnd - chunkStart, text)?.let {
            out += Violation(TABLE_ROW_TEXT_MISMATCH, "$where $it")
        }
    }

    for ((tableId, rows) in ranges) {
        val sorted = rows.sortedBy { it.first }
        sorted.zipWithNext { earlier, later ->
            if (later.first <= earlier.second) {
                out += Violation(
                    TABLE_ROW_RANGE_OVERLAP,
                    "table $tableId rows ${earlier.third} [${earlier.first}, ${earlier.second}] " +
                        "and ${later.third} [${later.first}, ${later.second}] overlap",
                )
            }
        }
    }

    // Coverage is driven by the *declared* tables, not by the rows that happen to exist.
    // A rollable table with no rows never appears in `ranges` at all, so keying the check
    // off the rows would let it activate -- a table the app offers to roll on and for
    // which every result has no outcome.
    for ((tableId, expression) in expressions) {
        checkCoverage(tableId, expression, ranges[tableId]?.sortedBy { it.first }.orEmpty(), out)
    }
}

/**
 * A table's rows must cover its expression's outcome range exactly.
 *
 * The roller finds the row containing a result and asserts exactly one will. That
 * assertion was the *builder's*, made about the artifact under inspection: a gap leaves a
 * roll with no row, and an out-of-range row can never come up. Now that the grammar is
 * implemented the range is computable here, so the assertion is the app's own.
 */
private fun checkCoverage(
    tableId: Long,
    expression: DiceExpression,
    sorted: List<Triple<Long, Long, Long>>,
    out: MutableList<Violation>,
) {
    val low = expression.min
    val high = expression.max

    val outside = sorted.filter { it.first < low || it.second > high }
    if (outside.isNotEmpty()) {
        out += Violation(
            TABLE_ROWS_INCOMPLETE,
            "table $tableId rows ${outside.map { it.third }} fall outside " +
                "'$expression' range [$low, $high]",
        )
        return
    }

    var expected = low
    for ((rowLow, rowHigh, _) in sorted) {
        if (rowLow > expected) {
            out += Violation(
                TABLE_ROWS_INCOMPLETE,
                "table $tableId has no row for ${if (rowLow - expected == 1L) "$expected" else
                    "$expected-${rowLow - 1}"} of '$expression' range [$low, $high]",
            )
            return
        }
        if (rowHigh >= expected) expected = rowHigh + 1
    }
    if (expected <= high) {
        out += Violation(
            TABLE_ROWS_INCOMPLETE,
            "table $tableId has no row for ${if (high == expected) "$expected" else
                "$expected-$high"} of '$expression' range [$low, $high]",
        )
    }
}

/** Null when the row's text is the slice its span names, a description otherwise. */
private fun rowTextMismatch(
    db: Db,
    chunkId: Long,
    from: Long,
    to: Long,
    text: String,
): String? {
    var chunkText: String? = null
    db.forEachRow("SELECT text FROM chunks WHERE chunk_id = $chunkId") { chunkText = it.string(0) }
    val bytes = chunkText?.toByteArray(Charsets.UTF_8) ?: return null
    if (from < 0 || to > bytes.size) return "span falls outside the chunk's text"
    val slice = bytes.copyOfRange(from.toInt(), to.toInt())
    return if (slice.contentEquals(text.toByteArray(Charsets.UTF_8))) {
        null
    } else {
        "text is not the slice of chunk $chunkId that its span names"
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
