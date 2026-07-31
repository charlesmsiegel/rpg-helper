package dev.rpghelper.retrieval

import dev.rpghelper.pack.Db
import dev.rpghelper.pack.map

/** One chunk matched lexically, with its score on the **negated** BM25 scale. */
data class LexicalHit(val ref: ChunkRef, val score: Double)

/**
 * Runs the gated lexical query against one pack.
 *
 * One list per pack rather than one list overall, because each pack is a separate SQLite
 * file with its own `chunks_fts`: BM25 comes from **pack-local corpus statistics**, so a
 * term rare in a slim adventure and common in a core rulebook scores differently in each
 * and the numbers were never on a shared scale. Pooling them would let a large pack buy
 * position with volume.
 */
object LexicalSearch {

    /**
     * Hits read per pack before gating.
     *
     * Tunable, and the number the retrieval regression suite measures recall against.
     * Bounded because the disjunction is deliberately wide: with a dozen incidental words
     * ORed together, "every chunk that matched anything" is most of the book.
     */
    const val DEPTH = 50

    /**
     * SQLite's `bm25()` returns a negative number where more negative is better.
     *
     * Negating once, here at the boundary, means every threshold in the spec and every
     * comparison downstream reads the ordinary way. Leaving the sign inverted would make
     * each later comparison a fresh chance to get it backwards.
     */
    fun search(
        pack: ActivePack,
        expression: String,
        superseded: SupersededSet = SupersededSet.EMPTY,
        depth: Int = DEPTH,
    ): List<LexicalHit> {
        // The expression is built by LexicalQuery, which quotes every term. It is
        // interpolated as an SQL string literal here (with quotes doubled) because
        // `Db.forEachRow` takes no bind parameters -- a deliberate limit of that
        // interface, and the reason nothing else in this module builds SQL from input.
        val literal = expression.replace("'", "''")
        return pack.db.map(
            "SELECT rowid, bm25(chunks_fts) FROM chunks_fts " +
                "WHERE chunks_fts MATCH '$literal' ORDER BY rank LIMIT $depth",
        ) { LexicalHit(ChunkRef(pack.packUid, it.long(0)), -it.double(1)) }
            // Supersession is a filter applied *before* scoring rather than a ranking
            // preference: a corrected rule and its original almost never tie, so the
            // superseded text can win outright while priority is never consulted.
            .filter { it.ref !in superseded }
    }
}
