package dev.ludex.retrieval

import dev.ludex.pack.ChunkRef

import dev.ludex.pack.PackSchema
import dev.ludex.pack.map

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
    /**
     * The text with those spans already excised, or null when redaction failed closed.
     *
     * Carried rather than recomputed: the lexical independence test read this exact
     * string, and a second implementation downstream is a second chance to excise
     * different bytes or insert a different marker.
     */
    val redactedText: String?,
    /**
     * Children whose text this candidate already contains, deduplicated into it.
     *
     * Carried so routing can attach what they own — a roll capability keyed to a nested
     * table's ref — to the card that ended up holding their text.
     */
    val absorbed: List<ChunkRef> = emptyList(),
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
        /**
         * Embeds the **rewritten** query, once per contract, if supplied.
         *
         * Given, it replaces [queryVectors] — and it exists because the two are not the
         * same query. Alias expansion is a *query rewrite*: the whole point of storing
         * *seizing* beside *grapple* is that the user's word reaches text the book never
         * uses. Embedding what the user typed hands that bridge to lexical search alone,
         * so a paraphrase that only the canonical would have matched produces no dense
         * candidate and no independent dense evidence — the half of the pipeline the
         * alias table exists to reach.
         */
        embed: ((String, Set<String>) -> Map<String, FloatArray>)? = null,
    ): Retrieved {
        // **Bounded before anything expensive touches it**, and the same bounded string
        // drives both halves. `MAX_TERMS * MAX_PHRASE` is the most tokens that could still
        // compress into a full term budget -- a multi-word alias collapses up to
        // `MAX_PHRASE` tokens into one group -- so nothing that could have survived the
        // budget is cut, and a pasted page stops being tokenized, expanded five ways per
        // token, and pasted into one SQL `IN` list before any bound applies.
        val normalized = QueryNormalizer.bound(
            QueryNormalizer.normalize(rawQuery),
            LexicalQuery.MAX_TERMS * AliasRewriter.MAX_PHRASE,
        )
        // Only the aliases this query could match. See `AliasRewriter.candidatePhrases`.
        val rewritten = AliasRewriter(
            loadAliases(active, AliasRewriter.candidatePhrases(normalized)),
        ).rewrite(normalized)

        // **One capped representation drives both.** Capping the groups and capping the
        // flattened terms independently lets the two disagree: a multi-word alias can put
        // the term cap partway through a concept while the group cap keeps that whole
        // concept and the ones after it, so terms the MATCH never searched for still add
        // unmatched information to the gate's denominator and can gate out a strong hit.
        // Whole concepts are taken until the term budget is spent, and the expression is
        // built from exactly those.
        val groups = mutableListOf<TermGroup>()
        val trimmed = mutableListOf<String>()
        var budget = LexicalQuery.MAX_TERMS
        for (group in rewritten.groups) {
            if (group.terms.size <= budget) {
                groups += group
                budget -= group.terms.size
                continue
            }
            // Too wide to take whole -- several packs defining the same alias with
            // different multi-word canonicals is enough. **The user's own wording is kept
            // and the expansions are dropped**, rather than the concept being dropped
            // with them: an alias that fires wrongly costs some precision, and a rewrite
            // that discards what the user actually typed costs the query. Breaking here
            // was worse still, because a single oversized *first* group left the
            // expression null and skipped lexical retrieval altogether.
            val own = group.alternatives.first()
            if (own.size > budget) break
            groups += TermGroup(listOf(own))
            budget -= own.size
            trimmed += own.joinToString(" ")
        }
        val expression = LexicalQuery.build(groups.flatMap { it.terms })
        // **The same budgeted terms both halves searched for.** This embedded
        // `rewritten.terms` -- every expansion, including the ones the loop above had just
        // dropped for exceeding the budget -- so dense retrieval could return candidates
        // for concepts the lexical query deliberately discarded, and the embedding input
        // grew with the alias table rather than with the question. A pack may define many
        // canonicals for one alias, so that is unbounded in the direction that matters.
        val searched = groups.flatMap { it.terms }
        val vectors = embed?.invoke(searched.joinToString(" "), active.distinctContracts)
            ?: queryVectors
        val gatedOut = mutableListOf<String>()
        trimmed.forEach { gatedOut += "alias expansions dropped for '$it': term budget" }
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
                // Measured over the hits, not over the pack: coverage is only ever asked
                // about chunks that are up for admission, and asking for more is what made
                // the gate's memory a function of the book's size rather than the query's.
                val coverage = InformationGate.measure(
                    pack, groups, active.superseded, hits.map { it.ref.chunkId },
                )
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
        // A query that tokenizes to nothing -- empty, or pure punctuation -- is not a
        // question, and it must not reach dense search. An embedder returns a uniform
        // vector rather than a zero one for text with no tokens, on the pack side for a
        // good reason: a zero vector is undefined under cosine, not weak. But a
        // punctuation-only *chunk* embeds to that same uniform vector, so a
        // punctuation-only query would score cosine 1.0 against it, clear any gate, and
        // answer `???` with whatever unrelated card that chunk belongs to.
        val densable = if (groups.isEmpty()) emptyMap() else vectors
        if (groups.isEmpty() && vectors.isNotEmpty()) {
            gatedOut += "dense: the query has no terms to match on"
        }
        for ((contract, vector) in densable) {
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
        // The **budgeted** terms here too. Nesting asks whether a parent matched
        // independently of its child, and answering that with expansions the budget
        // discarded lets a canonical nobody searched for keep a parent in the generation
        // context -- retained as an independent match on evidence neither lexical nor dense
        // retrieval ever used. Same mistake as embedding the unbudgeted rewrite, in the
        // same function, missed the first time.
        val survivors = Nesting.deduplicate(
            nesting,
            searched,
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
                    redactedText = survivors.getValue(fusedCandidate.ref).redactedText,
                    absorbed = survivors.getValue(fusedCandidate.ref).absorbed,
                )
            }
            .take(MAX_CANDIDATES)

        return Retrieved(normalized, rewritten.terms, candidates, gatedOut)
    }

    // ------------------------------------------------------------------ pack reads

    /**
     * Every active pack's aliases, minus those rooted at a withdrawn chunk.
     *
     * Filtering here rather than only on the entity hit: an alias rooted at a superseded
     * chunk would otherwise still add its canonical to the query and its group to the
     * gate, so withdrawn enrichment keeps broadening searches after the passage it
     * belongs to has been removed from every other route.
     */
    private fun loadAliases(active: ActiveSet, phrases: Set<String>): List<Alias> {
        if (phrases.isEmpty()) return emptyList()
        // Aliases are stored in `Tokenizer.indexForm`, which is what these phrases are, so
        // the comparison is an equality on an indexed column rather than a scan. The
        // phrases come from the query, so they are quoted -- a token cannot contain an
        // apostrophe, since punctuation is a separator, but building SQL from user input
        // without escaping it is not a thing to do on the strength of that.
        val list = phrases.joinToString(",") { "'${it.replace("'", "''")}'" }
        return active.packs.flatMap { pack ->
        pack.db.map("SELECT alias, canonical, chunk_id FROM entities WHERE alias IN ($list)") {
            Alias(pack.packUid, it.string(0), it.string(1), it.longOrNull(2))
        }.filter { alias ->
            alias.chunkId == null || ChunkRef(pack.packUid, alias.chunkId) !in active.superseded
        }
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
