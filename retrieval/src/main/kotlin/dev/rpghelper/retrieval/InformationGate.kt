package dev.rpghelper.retrieval

import dev.rpghelper.pack.map
import kotlin.math.ln

/**
 * One concept in the query, and every wording that counts as it.
 *
 * The alias rewrite adds *seizing* beside the user's *grapple*. Scored as two independent
 * terms, a query is penalised for the rewrite: the rare word the user typed goes unmatched
 * and dominates the denominator, while the canonical that did match is common enough to
 * contribute little. Grouping says what the rewrite already meant — these are one thing,
 * and matching either is matching it.
 */
data class TermGroup(val terms: List<String>)

/**
 * The lexical gate, as **how much of the query's information content this pack matched**.
 *
 * `04-retrieval-spec.md` §7.3 specifies a threshold on the negated BM25 score and names
 * its weakness: BM25's IDF term varies with corpus size, so one constant is only
 * approximately comparable across a slim pamphlet and a 300-page core book. It also names
 * the fallback for when measurement shows that sensitivity, and measurement did:
 *
 * > On the corpus, negative queries — *how much does a warhorse cost*, *how does
 * > spellcasting work* — scored **inside** the positive queries' BM25 range. Not near
 * > them: inside. A raw-score gate that refused the negatives refused real questions too,
 * > because on a small corpus the IDF of an ordinary English word is not small.
 *
 * So the gate is the ratio instead. A query's information content is the summed IDF of
 * its term groups; a chunk's score is the share of that content it actually contains.
 * Comparable across packs **by construction**, because it is a fraction of the query
 * rather than a quantity in the pack's units — which is the property the raw threshold
 * only approximated.
 *
 * *Warhorse* and *cost* appear in no chunk of a game with no economy, so they carry most
 * of the query's information and none of it is ever matched, and the ratio stays near
 * zero however many times *how* and *much* turn up. That is the refusal, arrived at by
 * measuring the question rather than by tuning a constant until the examples passed.
 *
 * The cost is one document-frequency query per term per pack, which is why this was not
 * the default: paying it on every query to fix a problem that might not appear is the
 * wrong order to do things in. It appeared.
 *
 * **What it still cannot separate, stated rather than tuned around.** On the corpus, the
 * positive query *what does held mean* scores 0.278 and the negative *how much does a
 * warhorse cost* scores 0.283. The overlap is real and its cause is the corpus's size:
 * across twenty chunks the word *mean* occurs nowhere, so it carries maximum IDF and
 * counts as unmatched information, exactly as *warhorse* does. Telling the two apart needs
 * a function-word list or a model, and on a 300-page book the problem does not arise
 * because *mean* appears throughout it.
 *
 * The threshold is therefore set **above both**, which loses that one question and holds
 * every refusal. That is the conservative direction on purpose: refusing something the
 * pack could have answered costs a search the user retries with different words, and
 * answering something it cannot is the failure the whole design is built to prevent.
 */
object InformationGate {

    /** Which chunks hold each term, and how much each term is worth. */
    class Coverage(
        private val postings: Map<String, Set<Long>>,
        private val idf: Map<String, Double>,
        private val groups: List<TermGroup>,
    ) {
        /** Summed IDF of every group, matched or not. The query's total information. */
        val queryInformation: Double =
            groups.sumOf { group -> group.terms.maxOfOrNull { idf[it] ?: 0.0 } ?: 0.0 }

        /**
         * Share of [queryInformation] that [chunkId] contains, in `0..1`.
         *
         * A group counts as matched when **any** of its wordings appears, valued at the
         * group's own weight — the rarest wording in it, since that is what the concept is
         * worth to the query regardless of which spelling the book happened to use.
         */
        fun of(chunkId: Long): Double {
            if (queryInformation <= 0.0) return 0.0
            var matched = 0.0
            for (group in groups) {
                val weight = group.terms.maxOfOrNull { idf[it] ?: 0.0 } ?: 0.0
                if (group.terms.any { chunkId in (postings[it] ?: emptySet()) }) matched += weight
            }
            return matched / queryInformation
        }
    }

    /**
     * Asks the index which chunks hold each term.
     *
     * One `MATCH` per term rather than tokenizing the chunks here: the index's answer is
     * correct by construction, where a second tokenizer is one more place to diverge from
     * the pinned `unicode61 remove_diacritics 2` — silently, and only for the words where
     * the two disagree.
     */
    fun measure(pack: ActivePack, groups: List<TermGroup>): Coverage {
        val total = pack.db.map("SELECT count(*) FROM chunks") { it.long(0) }.single()
        val postings = mutableMapOf<String, Set<Long>>()
        val idf = mutableMapOf<String, Double>()

        for (term in groups.flatMap { it.terms }.distinct()) {
            val literal = LexicalQuery.literal(term).replace("'", "''")
            val holders = pack.db.map(
                "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH '$literal'",
            ) { it.long(0) }.toSet()
            postings[term] = holders
            idf[term] = idf(holders.size.toLong(), total)
        }
        return Coverage(postings, idf, groups)
    }

    /**
     * BM25's IDF, which stays positive for a term in every document.
     *
     * The `+ 1` inside the logarithm matters here in a way it does not for ranking: a term
     * present in every chunk would otherwise be worth a *negative* amount, and a query
     * made of such words would have negative information content — a denominator that
     * turns the ratio inside out.
     */
    fun idf(documentFrequency: Long, corpusSize: Long): Double {
        if (corpusSize <= 0) return 0.0
        return ln((corpusSize - documentFrequency + 0.5) / (documentFrequency + 0.5) + 1.0)
    }
}
