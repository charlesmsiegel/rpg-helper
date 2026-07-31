package dev.rpghelper.retrieval

/**
 * One vector row's score, and the window of its chunk's text it scored over.
 *
 * `window` is null for an `expansion` vector, which stands for the whole chunk rather
 * than a byte range of it. §9.1 needs the window of the *surviving* vector, which is why
 * it travels with the score rather than being looked up again after the collapse.
 */
data class VectorHit(
    val ref: ChunkRef,
    val role: String,
    val score: Double,
    val window: IntRange?,
)

/** Which signal produced a list. Fusion treats one kind specially; see [Fusion.fuse]. */
enum class ListKind { LEXICAL, DENSE, ENTITY }

/**
 * One ranked list entering fusion, **already gated**.
 *
 * `label` names the pack or the embedder contract the list came from — carried for the
 * diagnostics view, and never used in the arithmetic.
 */
data class RetrievalList(val kind: ListKind, val label: String, val chunks: List<ChunkRef>)

/** A chunk with its fused score, and the lists that put it there. */
data class FusedCandidate(
    val ref: ChunkRef,
    val score: Double,
    val contributions: List<String>,
)

/**
 * Gating, collapsing, and reciprocal rank fusion.
 *
 * RRF needs no score calibration between BM25 and cosine, which is what makes multiple
 * embedder contracts combinable at all. But **rank alone is not sufficient**: every list
 * has a rank 1, including a list with nothing relevant in it, and fusing purely by rank
 * hands that list's best-of-a-bad-lot the same contribution as a genuinely strong hit
 * from the list that actually holds the answer. Activating more books would make results
 * worse, which is exactly backwards.
 *
 * So gating happens first, per pack and per contract — the only places in the pipeline
 * where an absolute measure of match quality exists, because those are the only places
 * the scores are on a shared scale.
 */
object Fusion {

    /**
     * The RRF constant.
     *
     * 60 is the value from the original paper and the one the labelled sets will be
     * measured against. It sets how quickly contribution decays with rank: large enough
     * that ranks 1 and 2 are close, small enough that rank 50 is nearly nothing.
     */
    const val K = 60

    /**
     * Drops hits below [threshold], which is stated on the **negated** BM25 score.
     *
     * SQLite's `bm25()` returns a negative number where more negative is a better match.
     * Negating it once, here at the boundary, means every threshold in the spec and every
     * comparison downstream reads the ordinary way — higher is better. Leaving the sign
     * inverted would make every later comparison a place to get it backwards.
     *
     * A group with nothing above its threshold yields an empty list, which contributes
     * nothing to any chunk: "contributes no list at all" and "contributes an empty list"
     * are the same thing to the arithmetic below.
     */
    fun <T> gate(hits: List<T>, threshold: Double, score: (T) -> Double): List<T> =
        hits.filter { score(it) >= threshold }

    /**
     * Collapses dense hits to one row per chunk, keeping the best-scoring.
     *
     * BM25 ranks chunks; dense search ranks **vector rows**, and a long statblock may own
     * a dozen between content windows and question expansions. Fusing before this either
     * compares identifiers that never match, or lets one chunk occupy a dozen consecutive
     * dense ranks and crowd the fused list with itself — worst for exactly the long,
     * heavily-vectored chunks that matter most.
     *
     * Ties go to `content` over `expansion` and then to the earliest window, so the
     * survivor is deterministic: §9.1 reads the surviving vector's window, and a dedup
     * decision that depends on which of two equal-scoring rows the iteration happened to
     * reach is one no test can pin down.
     */
    fun collapseToChunks(hits: List<VectorHit>): List<VectorHit> = hits
        .groupBy { it.ref }
        .values
        .map { forChunk ->
            forChunk.sortedWith(
                compareByDescending<VectorHit> { it.score }
                    .thenBy { it.role }
                    .thenBy { it.window?.first ?: -1 },
            ).first()
        }
        // Pack before chunk id, because a contract group spans packs and two packs
        // commonly both hold a chunk 1. Ordering by id alone leaves equal-scoring hits
        // from different books comparing as equal, so they inherit whatever order the
        // vector query returned — and RRF then awards them *different* rank
        // contributions, which means the pack-priority tie-break downstream never sees a
        // tie and repeated runs can rank different books first.
        .sortedWith(
            compareByDescending<VectorHit> { it.score }
                .thenBy { it.ref.packUid }
                .thenBy { it.ref.chunkId },
        )

    /**
     * Fuses the gated lists.
     *
     * ```
     * score(c) = Σ  1 / (K + rank_L(c))
     *            L
     * ```
     *
     * Lists a chunk does not appear in contribute nothing — there is no penalty term,
     * which is what lets lists of very different lengths combine without the short ones
     * being punished for their brevity.
     *
     * **Every [ListKind.ENTITY] member sits at rank 1.** An entity match has no internal
     * ordering — an alias n-gram either matched or it did not — so it is a flat
     * `1/(K+1)`, the same weight as topping one other list. Treating the alias boost as a
     * list rather than as a multiplier is deliberate: an entity hit is another piece of
     * evidence, so it belongs in the same arithmetic as the rest of the evidence.
     *
     * **An entity hit reinforces a candidate; it never introduces one.** The entity list
     * passes through no gate, because there is no score to threshold. So it is intersected
     * here with the union of the gated lexical and dense lists. Without that, a query
     * where every pack and every contract gated out could still produce an answer whose
     * entire evidence is a substring match — and "gating left no candidates" would stop
     * being the same statement as "the app refuses".
     *
     * @param priority the pack's ordinal, lower first. Used **only** to break exact ties;
     * it is not a ranking input, because a corrected rule and its original almost never
     * tie and priority would then never be consulted at the moment it mattered.
     */
    fun fuse(
        lists: List<RetrievalList>,
        priority: (String) -> Int = { 0 },
    ): List<FusedCandidate> {
        val gated = lists.filter { it.kind != ListKind.ENTITY }
        val reachable = gated.flatMapTo(mutableSetOf()) { it.chunks }
        if (reachable.isEmpty()) return emptyList()

        val scores = mutableMapOf<ChunkRef, Double>()
        val contributions = mutableMapOf<ChunkRef, MutableList<String>>()

        for (list in lists) {
            val entity = list.kind == ListKind.ENTITY
            // Deduplicated per list: a chunk appearing twice in one list is one piece of
            // evidence recorded twice, and would otherwise pay itself two contributions.
            val seen = mutableSetOf<ChunkRef>()
            list.chunks.forEachIndexed { index, ref ->
                if (entity && ref !in reachable) return@forEachIndexed
                if (!seen.add(ref)) return@forEachIndexed
                val rank = if (entity) 1 else index + 1
                scores[ref] = (scores[ref] ?: 0.0) + 1.0 / (K + rank)
                contributions.getOrPut(ref) { mutableListOf() } += list.label
            }
        }

        return scores.entries
            .map { (ref, score) -> FusedCandidate(ref, score, contributions.getValue(ref)) }
            .sortedWith(
                compareByDescending<FusedCandidate> { it.score }
                    .thenBy { priority(it.ref.packUid) }
                    // A total order, so the same inputs always produce the same list. Two
                    // candidates can tie on score *and* pack when one pack supplies both.
                    .thenBy { it.ref.packUid }
                    .thenBy { it.ref.chunkId },
            )
    }
}
