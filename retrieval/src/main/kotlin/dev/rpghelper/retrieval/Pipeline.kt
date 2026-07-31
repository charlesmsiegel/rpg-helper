package dev.rpghelper.retrieval

import dev.rpghelper.pack.ChunkRef

import dev.rpghelper.pack.PackSchema
import dev.rpghelper.pack.map

/**
 * A pack the retrieval pipeline may read, with everything the query needs to know about
 * it captured **once, at the query's start**.
 *
 * A pack deactivated mid-query does not half-affect its results, which would otherwise
 * produce an answer citing a book the user just switched off.
 */
data class ActiveSet(
    val packs: List<ActivePack>,
    /** `installed_packs.priority`, lower first. Breaks exact ties and nothing else. */
    val priority: Map<String, Int>,
    /** `pack_meta.embedder_id` per pack, so the query is embedded once per contract. */
    val contracts: Map<String, String>,
    val superseded: SupersededSet,
) {
    val distinctContracts: Set<String> get() = contracts.values.toSet()
}

/** Thresholds. Named rather than inlined because these are the numbers CI calibrates. */
data class Gates(
    /**
     * Share of the query's information content a chunk must hold, in `0..1`.
     *
     * A fraction of the *query* rather than a quantity in the pack's units, so it is
     * comparable across a slim pamphlet and a 300-page core book by construction. See
     * [InformationGate] for why the raw BM25 threshold the spec first named was replaced,
     * and what measurement showed.
     */
    val lexical: Double = 0.30,
    /** Cosine, per embedder contract. Not transferable between them. */
    val dense: Map<String, Double> = emptyMap(),
    /**
     * Used when a contract ships no calibrated threshold. Deliberately unreachable rather
     * than permissive: an uncalibrated embedder admitting candidates is an embedder whose
     * gate was never derived from a labelled set, and the refusal rule depends on every
     * gate meaning something.
     */
    val denseDefault: Double = 1.1,
) {
    fun denseFor(contract: String): Double = dense[contract] ?: denseDefault
}

/** A candidate that survived everything, with what routing needs to act on it. */
data class Candidate(
    val ref: ChunkRef,
    val kind: String,
    val origin: String,
    val stableKey: String?,
    val sourceUid: String?,
    val headingPath: String?,
    val text: String,
    val score: Double,
    val contributions: List<String>,
    val entityHit: Boolean,
    val denseWindow: IntRange?,
    /** Spans routing must excise before generation, or null when it cannot be done safely. */
    val redact: List<IntRange>?,
) {
    /** Whether this renders as a quotation: verbatim-eligible kind *and* source origin. */
    val verbatim: Boolean
        get() = kind in PackSchema.VERBATIM_ELIGIBLE_KINDS && origin == "source"
}

/** Everything one query produced. An empty [candidates] is the refusal. */
data class Retrieved(
    val normalizedQuery: String,
    val terms: List<String>,
    val candidates: List<Candidate>,
    val gatedOut: List<String>,
) {
    /**
     * **The app refuses when gating leaves no candidates.** Not when a fused score is low.
     *
     * RRF scores are functions of rank, so the top candidate scores about `1/(k+1)`
     * whether it is the right answer or the least-wrong of a list of junk; thresholding it
     * would measure position, not relevance. The gates already carry that information, and
     * they are applied per pack and per contract where the scales are comparable.
     */
    val refused: Boolean get() = candidates.isEmpty()
}

/**
 * The whole read path: normalize, rewrite, search, gate, fuse, deduplicate, cap.
 *
 * Deliberately a function of an [ActiveSet] rather than of a database. The active set is
 * an input, so the pipeline is testable against packs with no app state in the picture at
 * all — which is also why `:retrieval` does not depend on `:state`.
 */
object Pipeline {

    /** Retrieval hands over at most this many. Routing consumes the top five. */
    const val MAX_CANDIDATES = 10

    fun retrieve(
        rawQuery: String,
        active: ActiveSet,
        queryVectors: Map<String, FloatArray> = emptyMap(),
        gates: Gates = Gates(),
    ): Retrieved {
        val normalized = QueryNormalizer.normalize(rawQuery)
        val rewritten = AliasRewriter(loadAliases(active)).rewrite(normalized)
        val expression = LexicalQuery.build(rewritten.terms)
        val gatedOut = mutableListOf<String>()
        val lists = mutableListOf<RetrievalList>()

        // One lexical list per pack: BM25 comes from pack-local corpus statistics, so a
        // term rare in a slim adventure and common in a core rulebook scores differently
        // in each and the numbers were never on a shared scale.
        if (expression != null) {
            for (pack in active.packs) {
                val hits = LexicalSearch.search(pack, expression, active.superseded)
                // BM25 still does the ranking -- it is the better ordering, and rank is all
                // fusion consumes. The information ratio decides only who is *in*, which is
                // the one judgment a raw score could not make comparably.
                val coverage = InformationGate.measure(pack, rewritten.groups)
                val kept = hits.filter { coverage.of(it.ref.chunkId) >= gates.lexical }
                if (kept.isEmpty()) {
                    if (hits.isNotEmpty()) gatedOut += "lexical:${pack.packUid}"
                    continue
                }
                lists += RetrievalList(ListKind.LEXICAL, pack.packUid, kept.map { it.ref })
            }
        }

        // One dense list per contract group, gated at that contract's own threshold: a
        // cosine of 0.6 means different things in different spaces, which is the same
        // reason the scores could not be pooled to begin with.
        val windows = mutableMapOf<ChunkRef, IntRange?>()
        val roles = mutableMapOf<ChunkRef, String>()
        for ((contract, vector) in queryVectors) {
            val inGroup = active.packs.filter { active.contracts[it.packUid] == contract }
            val hits = inGroup.flatMap { DenseSearch.search(it, vector, active.superseded) }
            val kept = Fusion.gate(hits, gates.denseFor(contract)) { it.score }
            if (kept.isEmpty()) {
                if (hits.isNotEmpty()) gatedOut += "dense:$contract"
                continue
            }
            val ordered = Fusion.collapseToChunks(kept)
            ordered.forEach {
                windows[it.ref] = it.window
                roles[it.ref] = it.role
            }
            lists += RetrievalList(ListKind.DENSE, contract, ordered.map { it.ref })
        }

        // The entity list is intersected with the gated lists inside `fuse`, so an alias
        // can reinforce a candidate but never introduce one.
        val entityHits = rewritten.entityHits.map { (pack, chunk) -> ChunkRef(pack, chunk) }
            .filterNot { it in active.superseded }
        if (entityHits.isNotEmpty()) {
            lists += RetrievalList(ListKind.ENTITY, "alias", entityHits)
        }

        val fused = Fusion.fuse(lists) { pack -> active.priority[pack] ?: Int.MAX_VALUE }
        if (fused.isEmpty()) {
            return Retrieved(normalized, rewritten.terms, emptyList(), gatedOut)
        }

        val rows = loadRows(active, fused.map { it.ref }.toSet())
        val nesting = fused.mapNotNull { candidate ->
            rows[candidate.ref]?.toNestingCandidate(
                candidate.ref, windows[candidate.ref], roles[candidate.ref],
            )
        }
        val survivors = Nesting.deduplicate(
            nesting,
            rewritten.terms,
            verbatimEligible = { it.kind in PackSchema.VERBATIM_ELIGIBLE_KINDS && it.origin == "source" },
            nestedChildren = { ref -> childrenOf(active, ref) },
        ).associateBy { it.candidate.ref }

        val entitySet = entityHits.toSet()
        val candidates = fused
            .filter { it.ref in survivors }
            .map { fusedCandidate ->
                val row = rows.getValue(fusedCandidate.ref)
                Candidate(
                    ref = fusedCandidate.ref,
                    kind = row.kind,
                    origin = row.origin,
                    stableKey = row.stableKey,
                    sourceUid = row.sourceUid,
                    headingPath = row.headingPath,
                    text = row.text,
                    score = fusedCandidate.score,
                    contributions = fusedCandidate.contributions,
                    entityHit = fusedCandidate.ref in entitySet,
                    denseWindow = windows[fusedCandidate.ref],
                    redact = survivors.getValue(fusedCandidate.ref).redact,
                )
            }
            .take(MAX_CANDIDATES)

        return Retrieved(normalized, rewritten.terms, candidates, gatedOut)
    }

    // ------------------------------------------------------------------ pack reads

    private fun loadAliases(active: ActiveSet): List<Alias> = active.packs.flatMap { pack ->
        pack.db.map("SELECT alias, canonical, chunk_id FROM entities") {
            Alias(pack.packUid, it.string(0), it.string(1), it.longOrNull(2))
        }
    }

    private class Row(
        val kind: String,
        val origin: String,
        val stableKey: String?,
        val sourceUid: String?,
        val headingPath: String?,
        val text: String,
        val parent: ChunkRef?,
        val spanStart: Int?,
        val spanEnd: Int?,
    )

    private fun Row.toNestingCandidate(
        ref: ChunkRef,
        window: IntRange?,
        role: String?,
    ) = NestingCandidate(
        ref = ref,
        kind = kind,
        origin = origin,
        parent = parent,
        text = text,
        spanStart = spanStart,
        spanEnd = spanEnd,
        denseWindow = window,
        denseRole = role,
    )

    private fun loadRows(active: ActiveSet, wanted: Set<ChunkRef>): Map<ChunkRef, Row> {
        val out = mutableMapOf<ChunkRef, Row>()
        for (pack in active.packs) {
            val ids = wanted.filter { it.packUid == pack.packUid }.map { it.chunkId }
            if (ids.isEmpty()) continue
            pack.db.map(
                "SELECT c.chunk_id, c.kind, c.origin, c.stable_key, s.source_uid, " +
                    "c.heading_path, c.text, c.parent_chunk_id, c.span_start, c.span_end " +
                    "FROM chunks c LEFT JOIN sources s ON s.source_id = c.source_id " +
                    "WHERE c.chunk_id IN (${ids.joinToString(",")})",
            ) { row ->
                val ref = ChunkRef(pack.packUid, row.long(0))
                ref to Row(
                    kind = row.string(1),
                    origin = row.string(2),
                    stableKey = row.stringOrNull(3),
                    sourceUid = row.stringOrNull(4),
                    headingPath = row.stringOrNull(5),
                    text = row.string(6),
                    parent = row.longOrNull(7)?.let { ChunkRef(pack.packUid, it) },
                    spanStart = row.longOrNull(8)?.toInt(),
                    spanEnd = row.longOrNull(9)?.toInt(),
                )
            }.forEach { (ref, row) -> out[ref] = row }
        }
        return out
    }

    /**
     * Every child of [ref] in its own pack, matched or not.
     *
     * A lookup on the pack rather than a filter on the candidates: a lore query that never
     * retrieved the nested table still has to redact it, or the rule reaches generation
     * through the front door.
     */
    private fun childrenOf(active: ActiveSet, ref: ChunkRef): List<NestingCandidate> {
        val pack = active.packs.firstOrNull { it.packUid == ref.packUid } ?: return emptyList()
        return pack.db.map(
            "SELECT chunk_id, kind, origin, text, span_start, span_end FROM chunks " +
                "WHERE parent_chunk_id = ${ref.chunkId}",
        ) {
            NestingCandidate(
                ref = ChunkRef(pack.packUid, it.long(0)),
                kind = it.string(1),
                origin = it.string(2),
                parent = ref,
                text = it.string(3),
                spanStart = it.longOrNull(4)?.toInt(),
                spanEnd = it.longOrNull(5)?.toInt(),
                denseWindow = null,
                denseRole = null,
            )
        }
    }
}
