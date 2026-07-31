package dev.rpghelper.retrieval

/**
 * Builds the FTS5 `MATCH` expression.
 *
 * The expression is composed from two untrusted-by-construction sources: what the user
 * typed, and what the pack stored. FTS5 reads `AND`, `OR`, `NOT`, `NEAR`, `*`, `^`, `:`,
 * parentheses and double quotes as syntax, and game vocabulary collides with all of it —
 * `D&D`, `Ars Magica: The Divine`, a hyphenated `fast-cast`, an entity legitimately named
 * `Or`.
 *
 * The result of getting this wrong is not only a `MATCH` syntax error. A canonical term
 * containing `NOT` would turn a widening expansion into an **exclusion**, so the query
 * returns fewer results than it would have without the feature meant to broaden it — a
 * failure that looks like poor retrieval rather than like a bug.
 */
object LexicalQuery {

    /** Terms beyond this are dropped. A longer question is not a more precise one. */
    const val MAX_TERMS = 32

    /**
     * A quoted FTS5 string literal.
     *
     * Quoting neutralizes operators and punctuation together, so **no keyword blocklist is
     * needed and none should be written** — a blocklist is a list somebody has to keep
     * complete, and FTS5's syntax is not this module's to freeze.
     */
    fun literal(term: String): String = "\"" + term.replace("\"", "\"\"") + "\""

    /**
     * Builds a disjunction over [terms], each quoted.
     *
     * Disjunction rather than conjunction: a natural-language question carries a dozen
     * words, most of them incidental, and requiring all of them to co-occur matches
     * nothing. BM25 already weights rare terms above common ones, which is the
     * discrimination a conjunction would be imposing bluntly.
     *
     * Returns null when nothing survives — an empty `MATCH` is a syntax error, and a query
     * of only punctuation is a query with no terms rather than a query for everything.
     */
    fun build(terms: List<String>): String? {
        val usable = terms
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_TERMS)
        if (usable.isEmpty()) return null
        return usable.joinToString(" OR ") { literal(it) }
    }
}
