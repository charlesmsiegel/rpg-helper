package dev.rpghelper.pack

import dev.rpghelper.pack.ViolationCode.CHUNK_KIND_INVALID
import dev.rpghelper.pack.ViolationCode.CHUNK_ORIGIN_INVALID
import dev.rpghelper.pack.ViolationCode.CLAIM_SPAN_INVERTED
import dev.rpghelper.pack.ViolationCode.CLAIM_SPAN_NOT_UTF8_BOUNDARY
import dev.rpghelper.pack.ViolationCode.CLAIM_SPAN_OUT_OF_RANGE
import dev.rpghelper.pack.ViolationCode.CLAIM_SPAN_PARTIAL
import dev.rpghelper.pack.ViolationCode.DANGLING_CHUNK_REFERENCE
import dev.rpghelper.pack.ViolationCode.DERIVATION_TARGET_MISSING
import dev.rpghelper.pack.ViolationCode.DERIVATION_TARGET_NOT_SOURCE
import dev.rpghelper.pack.ViolationCode.DERIVED_CHUNK_HAS_SOURCE_COLUMNS
import dev.rpghelper.pack.ViolationCode.DERIVED_CHUNK_NO_DERIVATION
import dev.rpghelper.pack.ViolationCode.EMBEDDER_DIM_INVALID
import dev.rpghelper.pack.ViolationCode.EMBEDDER_DIM_MISMATCH
import dev.rpghelper.pack.ViolationCode.MISSING_TABLE
import dev.rpghelper.pack.ViolationCode.NESTING_CROSS_SOURCE
import dev.rpghelper.pack.ViolationCode.NESTING_NOT_CONTAINED
import dev.rpghelper.pack.ViolationCode.NESTING_TOO_DEEP
import dev.rpghelper.pack.ViolationCode.PACK_META_NOT_SINGLETON
import dev.rpghelper.pack.ViolationCode.PARENT_CHUNK_MISSING
import dev.rpghelper.pack.ViolationCode.PROBE_VECTOR_MALFORMED
import dev.rpghelper.pack.ViolationCode.PROBE_VECTOR_MISMATCH
import dev.rpghelper.pack.ViolationCode.SIBLING_SPAN_OVERLAP
import dev.rpghelper.pack.ViolationCode.SOURCE_CHUNK_HAS_DERIVATION
import dev.rpghelper.pack.ViolationCode.SOURCE_CHUNK_MISSING_CITATION
import dev.rpghelper.pack.ViolationCode.SOURCE_CHUNK_MISSING_SPAN
import dev.rpghelper.pack.ViolationCode.SPAN_INVERTED
import dev.rpghelper.pack.ViolationCode.SPAN_LENGTH_MISMATCH
import dev.rpghelper.pack.ViolationCode.UNKNOWN_EMBEDDER
import dev.rpghelper.pack.ViolationCode.UNSUPPORTED_SCHEMA_VERSION
import dev.rpghelper.pack.ViolationCode.VECTOR_LENGTH_MISMATCH
import dev.rpghelper.pack.ViolationCode.VECTOR_NON_FINITE
import dev.rpghelper.pack.ViolationCode.VECTOR_ROLE_INVALID
import dev.rpghelper.pack.ViolationCode.VECTOR_WINDOW_INVALID
import dev.rpghelper.pack.ViolationCode.VECTOR_ZERO_NORM

/** Identity and contract fields from `pack_meta`. */
data class PackMeta(
    val schemaVersion: Int,
    val packUid: String,
    val packVersion: String,
    val title: String,
    val rulesetId: String?,
    val embedderId: String,
    val embedderDim: Int,
)

/**
 * Decides whether a pack may be activated.
 *
 * Scope is deliberately "what a pack can prove about itself". Span *boundary*
 * validation and slice equality need the normalized source bytes, which the pack does
 * not ship -- only their hash -- so those stay in the builder, and what crosses to the
 * device is the guarantee that they ran.
 *
 * That line sits further along than `android-app-design.md` originally drew it.
 * Containment, sibling overlap, and claim-span validity were listed as builder-only,
 * but containment and overlap are comparisons between integers already stored in the
 * pack, and a claim span indexes into the derived chunk's own `text`, which ships. All
 * three are checked here, at no cost, because the alternative is trusting a builder
 * nobody in this codebase wrote.
 *
 * Every failure rejects the pack. There is no partial activation: by the time a
 * violation is visible the builder's guarantees have demonstrably not held, and a pack
 * that is wrong in one place has given no reason to be trusted in another.
 *
 * @param supportedEmbedders the contracts whose weights this build actually bundles.
 */
class PackValidator(private val supportedEmbedders: Set<EmbedderContract>) {

    fun validate(db: Db): ValidationReport {
        val violations = mutableListOf<Violation>()

        // Everything below reads from these tables. One clear refusal beats a cascade
        // of secondary failures caused by the first.
        val missing = PackSchema.REQUIRED_TABLES - db.tableNames()
        if (missing.isNotEmpty()) {
            missing.sorted().forEach {
                violations += Violation(MISSING_TABLE, "required table absent: $it")
            }
            return ValidationReport(violations)
        }

        // Read the version alone before anything else: on an unrecognised version the
        // remaining columns may not mean what this code thinks they mean, and a
        // best-effort read of an unknown layout produces confident nonsense.
        val versions = db.map("SELECT schema_version FROM pack_meta") { it.int(0) }
        if (versions.size != 1) {
            violations += Violation(
                PACK_META_NOT_SINGLETON,
                "pack_meta must hold exactly one row, found ${versions.size}",
            )
            return ValidationReport(violations)
        }
        if (versions[0] != PackSchema.SCHEMA_VERSION) {
            violations += Violation(
                UNSUPPORTED_SCHEMA_VERSION,
                "pack declares schema_version ${versions[0]}, " +
                    "this build understands ${PackSchema.SCHEMA_VERSION}",
            )
            return ValidationReport(violations)
        }

        val meta = readMeta(db)
        checkEmbedderContract(db, meta, violations)

        val chunks = loadChunks(db)
        val textLengths = loadTextLengths(db)

        checkChunkShape(chunks, violations)
        checkNesting(chunks, violations)
        checkSiblingSpans(chunks, violations)
        checkSpanLengths(chunks, textLengths, violations)
        checkDerivation(db, chunks, violations)
        checkClaimSpans(db, violations)
        checkVectors(db, meta, chunks, textLengths, violations)
        checkChunkReferences(db, chunks, violations)

        return ValidationReport(violations)
    }

    // ---------------------------------------------------------------- meta

    private fun readMeta(db: Db): PackMeta =
        db.map(
            """
            SELECT schema_version, pack_uid, pack_version, title, ruleset_id,
                   embedder_id, embedder_dim
            FROM pack_meta
            """.trimIndent(),
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
        }.single()

    private fun checkEmbedderContract(db: Db, meta: PackMeta, out: MutableList<Violation>) {
        if (meta.embedderDim <= 0) {
            out += Violation(EMBEDDER_DIM_INVALID, "embedder_dim is ${meta.embedderDim}")
        }

        val contract = supportedEmbedders.firstOrNull { it.id == meta.embedderId }
        if (contract == null) {
            out += Violation(
                UNKNOWN_EMBEDDER,
                "pack needs embedder '${meta.embedderId}', which this build does not bundle",
            )
        } else if (contract.dim != meta.embedderDim) {
            out += Violation(
                EMBEDDER_DIM_MISMATCH,
                "embedder '${meta.embedderId}' is ${contract.dim}-dimensional here, " +
                    "pack declares ${meta.embedderDim}",
            )
        }

        val probe = db.map("SELECT probe_vector FROM pack_meta") { it.bytes(0) }.single()
        if (probe.size != ProbeVector.BYTE_LENGTH) {
            out += Violation(
                PROBE_VECTOR_MALFORMED,
                "probe vector is ${probe.size} bytes, expected ${ProbeVector.BYTE_LENGTH}",
            )
        } else if (!ProbeVector.matches(probe)) {
            // Reached only when the bytes are the right length and still decode wrong:
            // byte order, float32, NaN-boxing, or padding.
            out += Violation(
                PROBE_VECTOR_MISMATCH,
                "probe vector does not decode to its pinned constant; the pack's vector " +
                    "encoding does not match the format (byte order or element width)",
            )
        }
    }

    // ---------------------------------------------------------------- chunks

    private data class ChunkRow(
        val id: Long,
        val kind: String,
        val origin: String,
        val sourceId: Long?,
        val headingPath: String?,
        val pageLabelStart: String?,
        val pageLabelEnd: String?,
        val spanStart: Long?,
        val spanEnd: Long?,
        val stableKey: String?,
        val parentId: Long?,
    ) {
        val isSource: Boolean get() = origin == "source"
    }

    private fun loadChunks(db: Db): Map<Long, ChunkRow> =
        db.map(
            """
            SELECT chunk_id, kind, origin, source_id, heading_path, page_label_start,
                   page_label_end, span_start, span_end, stable_key, parent_chunk_id
            FROM chunks
            """.trimIndent(),
        ) {
            ChunkRow(
                id = it.long(0),
                kind = it.string(1),
                origin = it.string(2),
                sourceId = it.longOrNull(3),
                headingPath = it.stringOrNull(4),
                pageLabelStart = it.stringOrNull(5),
                pageLabelEnd = it.stringOrNull(6),
                spanStart = it.longOrNull(7),
                spanEnd = it.longOrNull(8),
                stableKey = it.stringOrNull(9),
                parentId = it.longOrNull(10),
            )
        }.associateBy { it.id }

    /**
     * UTF-8 length of every chunk's text, keyed by id.
     *
     * Streamed so the pack's full text is never resident at once; only one integer per
     * chunk survives the pass. Three later checks need these lengths, and reading the
     * text three times would be the obvious alternative.
     */
    private fun loadTextLengths(db: Db): Map<Long, Int> {
        val lengths = HashMap<Long, Int>()
        db.forEachRow("SELECT chunk_id, text FROM chunks") {
            lengths[it.long(0)] = Utf8.byteLength(it.string(1))
        }
        return lengths
    }

    private fun checkChunkShape(chunks: Map<Long, ChunkRow>, out: MutableList<Violation>) {
        for (chunk in chunks.values) {
            if (chunk.kind !in PackSchema.KINDS) {
                out += Violation(CHUNK_KIND_INVALID, "chunk ${chunk.id} has kind '${chunk.kind}'")
            }
            if (chunk.origin !in PackSchema.ORIGINS) {
                out += Violation(
                    CHUNK_ORIGIN_INVALID,
                    "chunk ${chunk.id} has origin '${chunk.origin}'",
                )
                continue // the remaining shape rules are stated per origin
            }

            if (chunk.isSource) {
                val absent = buildList {
                    if (chunk.sourceId == null) add("source_id")
                    if (chunk.headingPath == null) add("heading_path")
                    if (chunk.pageLabelStart == null) add("page_label_start")
                    if (chunk.pageLabelEnd == null) add("page_label_end")
                    // Supersession targets (source_uid, stable_key), so a source chunk
                    // without one cannot be amended by an errata pack.
                    if (chunk.stableKey == null) add("stable_key")
                }
                if (absent.isNotEmpty()) {
                    out += Violation(
                        SOURCE_CHUNK_MISSING_CITATION,
                        "chunk ${chunk.id} is origin='source' but has no ${absent.joinToString()}",
                    )
                }
                if (chunk.spanStart == null || chunk.spanEnd == null) {
                    out += Violation(
                        SOURCE_CHUNK_MISSING_SPAN,
                        "chunk ${chunk.id} is origin='source' but has no span",
                    )
                } else if (chunk.spanStart >= chunk.spanEnd) {
                    out += Violation(
                        SPAN_INVERTED,
                        "chunk ${chunk.id} span [${chunk.spanStart}, ${chunk.spanEnd}) is empty " +
                            "or inverted",
                    )
                }
            } else {
                // Derived text has no location in any source document. Offsets here
                // could only be fabricated, and a fabricated offset is worse than none
                // because it looks checkable.
                val present = buildList {
                    if (chunk.sourceId != null) add("source_id")
                    if (chunk.headingPath != null) add("heading_path")
                    if (chunk.pageLabelStart != null) add("page_label_start")
                    if (chunk.pageLabelEnd != null) add("page_label_end")
                    if (chunk.spanStart != null) add("span_start")
                    if (chunk.spanEnd != null) add("span_end")
                    if (chunk.stableKey != null) add("stable_key")
                }
                if (present.isNotEmpty()) {
                    out += Violation(
                        DERIVED_CHUNK_HAS_SOURCE_COLUMNS,
                        "chunk ${chunk.id} is origin='derived' but sets ${present.joinToString()}",
                    )
                }
            }
        }
    }

    private fun checkNesting(chunks: Map<Long, ChunkRow>, out: MutableList<Violation>) {
        for (chunk in chunks.values) {
            val parentId = chunk.parentId ?: continue
            val parent = chunks[parentId]
            if (parent == null) {
                out += Violation(
                    PARENT_CHUNK_MISSING,
                    "chunk ${chunk.id} names parent $parentId, which is not in this pack",
                )
                continue
            }
            if (parent.parentId != null) {
                // Deeper structures signal the parent was chunked too coarsely.
                out += Violation(
                    NESTING_TOO_DEEP,
                    "chunk ${chunk.id} nests under $parentId, which is itself nested",
                )
            }
            if (chunk.sourceId != parent.sourceId) {
                // Spans are only comparable within one source, so a cross-source parent
                // would make containment meaningless rather than merely wrong.
                out += Violation(
                    NESTING_CROSS_SOURCE,
                    "chunk ${chunk.id} (source ${chunk.sourceId}) nests under $parentId " +
                        "(source ${parent.sourceId})",
                )
                continue
            }

            val childStart = chunk.spanStart
            val childEnd = chunk.spanEnd
            val parentStart = parent.spanStart
            val parentEnd = parent.spanEnd
            if (childStart == null || childEnd == null || parentStart == null || parentEnd == null) {
                continue // already reported as a missing span
            }
            if (childStart < parentStart || childEnd > parentEnd) {
                out += Violation(
                    NESTING_NOT_CONTAINED,
                    "chunk ${chunk.id} span [$childStart, $childEnd) is not inside " +
                        "parent $parentId span [$parentStart, $parentEnd)",
                )
            }
        }
    }

    /**
     * Siblings must not overlap.
     *
     * The group key is `(source_id, parent_chunk_id)` with NULL as an ordinary group
     * value. Grouping by parent alone breaks both ways: every book's opening chunk
     * would collide with every other book's, and an implementation using SQL
     * `NULL = NULL` would skip top-level chunks entirely -- which looks like a passing
     * build.
     */
    private fun checkSiblingSpans(chunks: Map<Long, ChunkRow>, out: MutableList<Violation>) {
        chunks.values
            .filter { it.isSource && it.spanStart != null && it.spanEnd != null }
            .groupBy { it.sourceId to it.parentId }
            .forEach { (_, siblings) ->
                siblings
                    .sortedWith(compareBy({ it.spanStart!! }, { it.spanEnd!! }))
                    .zipWithNext { earlier, later ->
                        if (later.spanStart!! < earlier.spanEnd!!) {
                            out += Violation(
                                SIBLING_SPAN_OVERLAP,
                                "chunks ${earlier.id} [${earlier.spanStart}, ${earlier.spanEnd}) " +
                                    "and ${later.id} [${later.spanStart}, ${later.spanEnd}) " +
                                    "are siblings and overlap",
                            )
                        }
                    }
            }
    }

    /**
     * `span_end - span_start` must equal the UTF-8 byte length of `text`.
     *
     * The app cannot re-slice the source -- it has only the hash -- but it can check
     * that the stored text is the size the span claims. That catches truncation and
     * offset drift, which are the corruptions most likely to survive a build and a
     * transfer intact enough to look valid.
     */
    private fun checkSpanLengths(
        chunks: Map<Long, ChunkRow>,
        textLengths: Map<Long, Int>,
        out: MutableList<Violation>,
    ) {
        for (chunk in chunks.values) {
            if (!chunk.isSource) continue
            val start = chunk.spanStart ?: continue
            val end = chunk.spanEnd ?: continue
            if (start >= end) continue // already reported
            val declared = end - start
            val actual = textLengths[chunk.id]?.toLong() ?: continue
            if (declared != actual) {
                out += Violation(
                    SPAN_LENGTH_MISMATCH,
                    "chunk ${chunk.id} span covers $declared bytes but its text is $actual",
                )
            }
        }
    }

    // ---------------------------------------------------------------- derivation

    private fun checkDerivation(
        db: Db,
        chunks: Map<Long, ChunkRow>,
        out: MutableList<Violation>,
    ) {
        val cited = mutableSetOf<Long>()
        db.forEachRow(
            "SELECT derivation_id, derived_chunk_id, source_chunk_id FROM chunk_derivation",
        ) { row ->
            val derivationId = row.long(0)
            val derivedId = row.long(1)
            val sourceId = row.long(2)
            cited += derivedId

            if (derivedId !in chunks) {
                out += Violation(
                    DANGLING_CHUNK_REFERENCE,
                    "chunk_derivation $derivationId names derived chunk $derivedId, " +
                        "which is not in this pack",
                )
            }
            val target = chunks[sourceId]
            if (target == null) {
                out += Violation(
                    DERIVATION_TARGET_MISSING,
                    "chunk_derivation $derivationId cites chunk $sourceId, " +
                        "which is not in this pack",
                )
            } else if (!target.isSource) {
                // One hop is all it takes, and it needs no source bytes -- just a join.
                out += Violation(
                    DERIVATION_TARGET_NOT_SOURCE,
                    "chunk_derivation $derivationId cites chunk $sourceId, which is itself " +
                        "derived; a citation chain must terminate at origin='source'",
                )
            }
        }

        for (chunk in chunks.values) {
            when {
                !chunk.isSource && chunk.id !in cited ->
                    out += Violation(
                        DERIVED_CHUNK_NO_DERIVATION,
                        "chunk ${chunk.id} is origin='derived' but cites no source chunks",
                    )
                chunk.isSource && chunk.id in cited ->
                    out += Violation(
                        SOURCE_CHUNK_HAS_DERIVATION,
                        "chunk ${chunk.id} is origin='source' but has chunk_derivation rows",
                    )
            }
        }
    }

    /**
     * Claim spans index into the derived chunk's own `text`, which the pack ships -- so
     * unlike source spans, these can be fully validated here.
     *
     * Skipping them would leave the app anchoring an inline citation chip to offsets
     * that are out of range or land mid-character: a chip in the wrong place, or a
     * crash converting the offset for display, from a pack that passed every other
     * check. Overlap between claim spans is legal and deliberately not checked -- two
     * sources supporting one sentence is corroboration.
     */
    private fun checkClaimSpans(db: Db, out: MutableList<Violation>) {
        db.forEachRow(
            """
            SELECT cd.derivation_id, cd.claim_span_start, cd.claim_span_end, c.text
            FROM chunk_derivation cd
            JOIN chunks c ON c.chunk_id = cd.derived_chunk_id
            WHERE cd.claim_span_start IS NOT NULL OR cd.claim_span_end IS NOT NULL
            """.trimIndent(),
        ) { row ->
            val derivationId = row.long(0)
            val start = row.longOrNull(1)
            val end = row.longOrNull(2)

            if (start == null || end == null) {
                out += Violation(
                    CLAIM_SPAN_PARTIAL,
                    "chunk_derivation $derivationId sets only one end of its claim span",
                )
                return@forEachRow
            }
            if (start >= end) {
                out += Violation(
                    CLAIM_SPAN_INVERTED,
                    "chunk_derivation $derivationId claim span [$start, $end) is empty or inverted",
                )
                return@forEachRow
            }

            val encoded = row.string(3).toByteArray(Charsets.UTF_8)
            if (start < 0 || end > encoded.size) {
                out += Violation(
                    CLAIM_SPAN_OUT_OF_RANGE,
                    "chunk_derivation $derivationId claim span [$start, $end) falls outside " +
                        "its derived text of ${encoded.size} bytes",
                )
                return@forEachRow
            }
            if (!Utf8.isBoundary(encoded, start.toInt()) || !Utf8.isBoundary(encoded, end.toInt())) {
                out += Violation(
                    CLAIM_SPAN_NOT_UTF8_BOUNDARY,
                    "chunk_derivation $derivationId claim span [$start, $end) splits a " +
                        "multi-byte character",
                )
            }
        }
    }

    // ---------------------------------------------------------------- vectors

    /**
     * Layout and numeric usability, in one pass over data that has to be read anyway.
     *
     * Layout checks say the bytes were arranged correctly; they say nothing about
     * whether the numbers mean anything. A NaN propagates through the dot product and
     * compares false against everything, so the chunk either vanishes from retrieval or
     * lands wherever the comparator leaves it; an infinity outranks every real result
     * for every query; a zero norm makes cosine a division by zero. None of these is
     * exotic -- a zero vector is the ordinary result of an embedding call that failed
     * and was not checked.
     */
    private fun checkVectors(
        db: Db,
        meta: PackMeta,
        chunks: Map<Long, ChunkRow>,
        textLengths: Map<Long, Int>,
        out: MutableList<Violation>,
    ) {
        val expectedBytes = meta.embedderDim * PackSchema.VECTOR_ELEMENT_BYTES

        db.forEachRow(
            """
            SELECT chunk_id, role, subchunk_index, embedding, window_start, window_end
            FROM vectors
            """.trimIndent(),
        ) { row ->
            val chunkId = row.long(0)
            val role = row.string(1)
            val index = row.long(2)
            val where = "vector (chunk $chunkId, $role, $index)"

            if (chunkId !in chunks) {
                out += Violation(
                    DANGLING_CHUNK_REFERENCE,
                    "$where references a chunk that is not in this pack",
                )
            }
            if (role !in PackSchema.VECTOR_ROLES) {
                out += Violation(VECTOR_ROLE_INVALID, "$where has an unrecognised role")
                return@forEachRow
            }

            val blob = row.bytes(3)
            if (blob.size != expectedBytes) {
                // Decoding past this point would compare arbitrary numbers.
                out += Violation(
                    VECTOR_LENGTH_MISMATCH,
                    "$where is ${blob.size} bytes, expected $expectedBytes " +
                        "(${meta.embedderDim} float16 elements)",
                )
                return@forEachRow
            }

            val values = Float16.decodeVector(blob)
            if (values.any { !it.isFinite() }) {
                out += Violation(VECTOR_NON_FINITE, "$where contains NaN or infinity")
            } else {
                var sumOfSquares = 0.0
                for (value in values) sumOfSquares += value.toDouble() * value.toDouble()
                if (kotlin.math.sqrt(sumOfSquares) <= PackSchema.MIN_VECTOR_NORM) {
                    out += Violation(
                        VECTOR_ZERO_NORM,
                        "$where has effectively zero norm; cosine similarity is undefined for it",
                    )
                }
            }

            val windowStart = row.longOrNull(4)
            val windowEnd = row.longOrNull(5)
            when (role) {
                "content" -> {
                    val textLength = textLengths[chunkId]?.toLong()
                    val invalid = windowStart == null || windowEnd == null ||
                        windowStart >= windowEnd || windowStart < 0 ||
                        (textLength != null && windowEnd > textLength)
                    if (invalid) {
                        // Nesting deduplication asks whether a parent matched because of
                        // its child or independently of it, and answers with these
                        // offsets. Without usable ones the rule cannot be implemented.
                        out += Violation(
                            VECTOR_WINDOW_INVALID,
                            "$where is role='content' but its window [$windowStart, $windowEnd) " +
                                "is absent or outside the chunk's text",
                        )
                    }
                }
                "expansion" -> if (windowStart != null || windowEnd != null) {
                    // An expansion embeds a generated question, not a span of the chunk.
                    out += Violation(
                        VECTOR_WINDOW_INVALID,
                        "$where is role='expansion' but declares a window",
                    )
                }
            }
        }
    }

    // ---------------------------------------------------------------- references

    /**
     * Rows that address a chunk directly, never through retrieval.
     *
     * A dangling reference here is not a search-quality problem: the roller, the
     * constraint engine, and the alias rewrite all resolve these by id and would fail
     * at the moment of use.
     */
    private fun checkChunkReferences(
        db: Db,
        chunks: Map<Long, ChunkRow>,
        out: MutableList<Violation>,
    ) {
        data class Reference(val table: String, val sql: String)

        val references = listOf(
            Reference("entities", "SELECT entity_id, chunk_id FROM entities"),
            Reference("tables", "SELECT table_id, chunk_id FROM tables"),
            Reference("capabilities", "SELECT capability_id, chunk_id FROM capabilities"),
            Reference("constraints", "SELECT constraint_id, chunk_id FROM constraints"),
            Reference(
                "supersessions",
                "SELECT supersession_id, superseding_chunk_id FROM supersessions",
            ),
        )

        for (reference in references) {
            db.forEachRow(reference.sql) { row ->
                // A NULL reference is legal in entities and supersessions and simply
                // means "not governed by a particular chunk".
                val chunkId = row.longOrNull(1) ?: return@forEachRow
                if (chunkId !in chunks) {
                    out += Violation(
                        DANGLING_CHUNK_REFERENCE,
                        "${reference.table} ${row.long(0)} references chunk $chunkId, " +
                            "which is not in this pack",
                    )
                }
            }
        }
    }
}
