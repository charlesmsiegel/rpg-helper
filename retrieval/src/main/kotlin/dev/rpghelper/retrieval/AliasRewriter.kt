package dev.rpghelper.retrieval

import dev.rpghelper.pack.Tokenizer

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
    /** Aliases skipped because their canonical tokenizes to nothing, for the same view. */
    val unusable: List<Alias>,
    /**
     * The query's concepts, each with every wording that counts as it.
     *
     * The information gate scores a chunk by the share of the query's content it holds,
     * and an alias-expanded concept has to count once. Scored as independent terms, the
     * rare word the user typed goes unmatched and dominates, while the canonical that did
     * match is common enough to contribute nothing — so the rewrite would make a query
     * score *worse*.
     */
    val groups: List<TermGroup>,
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
        val groups = mutableListOf<TermGroup>()
        val unusable = mutableListOf<Alias>()

        // Greedy, longest-first, non-overlapping. Without the non-overlap rule
        // "blade of the fallen" fires as itself, as "the fallen", and as "fallen",
        // weighting one concept three times.
        var index = 0
        while (index < tokens.size) {
            var consumed = 0
            for (length in minOf(maxPhrase, tokens.size - index) downTo 1) {
                val phrase = tokens.subList(index, index + length).joinToString(" ")
                val hits = byAlias[phrase] ?: continue
                // The user's wording is ONE alternative -- the whole phrase, all of it --
                // and each canonical is another. Flattening them into interchangeable
                // tokens would let a chunk holding only "the" claim the concept, and with
                // it the weight of the rare canonical the alias expands to.
                val alternatives = mutableListOf(tokens.subList(index, index + length).toList())
                for (hit in hits) {
                    val canonical = Tokenizer.tokenize(hit.canonical)
                    // A canonical of pure punctuation tokenizes to nothing. Added as an
                    // alternative it would be satisfied by every chunk and award the whole
                    // concept's weight to unrelated hits, so the alias is dropped instead.
                    if (canonical.isEmpty()) {
                        unusable += hit
                        continue
                    }
                    matched += hit
                    canonicals += canonical
                    alternatives += canonical
                    hit.chunkId?.let { entityHits += hit.packUid to it }
                }
                if (matched.isEmpty() && alternatives.size == 1 && hits.isNotEmpty()) {
                    // Every alias at this position was unusable; the user's own tokens
                    // still stand for themselves.
                    groups += TermGroup(alternatives.distinct())
                    consumed = length
                    break
                }
                groups += TermGroup(alternatives.distinct())
                consumed = length
                break
            }
            if (consumed == 0) groups += TermGroup.of(tokens[index])
            index += if (consumed > 0) consumed else 1
        }

        // The user's own tokens are never dropped. An alias that fires wrongly costs some
        // precision; a rewrite that replaces the user's wording costs the query.
        return RewrittenQuery(tokens + canonicals, entityHits, matched, unusable, groups)
    }
}
