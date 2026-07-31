package dev.rpghelper.retrieval

/** An alias row from a pack's `entities`. */
data class Alias(
    val packUid: String,
    val alias: String,
    val canonical: String,
    val chunkId: Long?,
)

/** What the rewrite produced. */
data class RewrittenQuery(
    /** Every term to search for: the user's own tokens plus each matched canonical. */
    val terms: List<String>,
    /** Chunks named by a matched alias — a ranking signal, never a candidate source. */
    val entityHits: Set<Pair<String, Long>>,
    /** Which aliases fired, for diagnostics. */
    val matched: List<Alias>,
)

/**
 * Rewrites a query through a pack's alias table.
 *
 * `chunks_fts` indexes only `text` and `heading_path`, so an alias sitting in `entities`
 * has no effect on lexical search by itself: someone typing *wrestle* still misses the
 * grapple rule, which is the entire case this feature exists to handle. The join is a
 * **query rewrite plus a ranking signal**, never an index-time injection — writing aliases
 * into the index would pollute BM25 term statistics with text that does not appear in the
 * book, and make an alias change require an FTS rebuild.
 */
class AliasRewriter(aliases: List<Alias>) {

    /**
     * Longest n-gram considered. Beyond this an "alias" is a sentence, and every extra
     * length multiplies the match attempts across every position in the query.
     */
    private val maxPhrase = 5

    private val byAlias: Map<String, List<Alias>> = aliases.groupBy { it.alias }

    fun rewrite(query: String): RewrittenQuery {
        val tokens = Tokenizer.tokenize(query)
        val matched = mutableListOf<Alias>()
        val entityHits = mutableSetOf<Pair<String, Long>>()
        val canonicals = mutableListOf<String>()

        // Greedy, longest-first, non-overlapping. Without the non-overlap rule
        // "blade of the fallen" fires as itself, as "the fallen", and as "fallen",
        // weighting one concept three times.
        var index = 0
        while (index < tokens.size) {
            var consumed = 0
            for (length in minOf(maxPhrase, tokens.size - index) downTo 1) {
                val phrase = tokens.subList(index, index + length).joinToString(" ")
                val hits = byAlias[phrase] ?: continue
                for (hit in hits) {
                    matched += hit
                    canonicals += Tokenizer.tokenize(hit.canonical)
                    hit.chunkId?.let { entityHits += hit.packUid to it }
                }
                consumed = length
                break
            }
            index += if (consumed > 0) consumed else 1
        }

        // The user's own tokens are never dropped. An alias that fires wrongly costs some
        // precision; a rewrite that replaces the user's wording costs the query.
        return RewrittenQuery(tokens + canonicals, entityHits, matched)
    }
}
