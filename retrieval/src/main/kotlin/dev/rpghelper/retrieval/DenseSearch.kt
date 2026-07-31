package dev.rpghelper.retrieval

import dev.rpghelper.pack.ChunkRef

import dev.rpghelper.pack.Float16
import dev.rpghelper.pack.map
import kotlin.math.sqrt

/**
 * Cosine scoring over a pack's vectors.
 *
 * Takes a **query vector**, not an embedder: `:retrieval` owns no weights and depends on
 * no model. The caller has already grouped active packs by contract and embedded the
 * query once per distinct contract, which is the only correct thing to do — vectors from
 * two contracts live in different spaces, and where the dimensions differ the cosine is
 * not even computable.
 *
 * A brute-force scan over every active pack's vectors, which is the thing that degrades
 * first as the active set grows. Stated rather than hidden: it is the measurement the
 * performance suite tracks.
 */
object DenseSearch {

    /** Hits read per pack before gating, matching the lexical side. */
    const val DEPTH = 50

    fun search(
        pack: ActivePack,
        query: FloatArray,
        superseded: SupersededSet = SupersededSet.EMPTY,
        depth: Int = DEPTH,
    ): List<VectorHit> {
        val hits = mutableListOf<VectorHit>()
        val queryNorm = norm(query)
        if (queryNorm < 1e-12) return emptyList()

        // Streamed, not materialized. `map` would build the whole vector corpus -- every
        // embedding ByteArray of a 300-page book -- in heap before scoring any of it, for
        // every query, to keep fifty. Scoring row by row keeps only the best hit per chunk
        // and lets each BLOB be collected immediately.
        val best = HashMap<Long, VectorHit>()
        pack.db.forEachRow(
            "SELECT chunk_id, role, subchunk_index, embedding, window_start, window_end " +
                "FROM vectors",
        ) { row ->
            val chunkId = row.long(0)
            val ref = ChunkRef(pack.packUid, chunkId)
            // Superseded chunks are filtered before scoring, not after: a correction
            // removes what it corrects everywhere, and a filter applied to a ranked list
            // is a filter that already let the withdrawn text win.
            if (ref in superseded) return@forEachRow

            val embedding = Float16.decodeVector(row.bytes(3))
            if (embedding.size != query.size) return@forEachRow

            val role = row.string(1)
            val start = row.longOrNull(4)
            val end = row.longOrNull(5)
            val hit = VectorHit(
                ref = ref,
                role = role,
                score = cosine(query, queryNorm, embedding),
                window = if (start == null || end == null) null else start.toInt() until end.toInt(),
            )
            // Same survivor rule as `Fusion.collapseToChunks`, applied as we go: best
            // score, then content over expansion, then the earliest window.
            val incumbent = best[chunkId]
            if (incumbent == null || betterThan(hit, incumbent)) best[chunkId] = hit
        }
        hits += best.values

        return Fusion.collapseToChunks(hits).take(depth)
    }

    /** The collapse rule, applied incrementally so no chunk keeps more than one hit. */
    private fun betterThan(candidate: VectorHit, incumbent: VectorHit): Boolean = when {
        candidate.score != incumbent.score -> candidate.score > incumbent.score
        candidate.role != incumbent.role -> candidate.role < incumbent.role
        else -> (candidate.window?.first ?: -1) < (incumbent.window?.first ?: -1)
    }

    private fun cosine(query: FloatArray, queryNorm: Double, other: FloatArray): Double {
        var dot = 0.0
        var otherSquared = 0.0
        for (i in query.indices) {
            dot += query[i].toDouble() * other[i]
            otherSquared += other[i].toDouble() * other[i]
        }
        val denominator = queryNorm * sqrt(otherSquared)
        return if (denominator < 1e-12) 0.0 else dot / denominator
    }

    private fun norm(values: FloatArray): Double {
        var sum = 0.0
        for (value in values) sum += value.toDouble() * value
        return sqrt(sum)
    }
}
