package dev.rpghelper.retrieval

import java.text.Normalizer

/**
 * The deterministic normalization every query gets, whatever mode it arrived in.
 *
 * Table lookup and string work, and no model. That is the point: a typed one-word lookup
 * reaches the index having invoked nothing, which is the guarantee the design claimed and
 * the reason the app is usable before any download completes.
 */
object QueryNormalizer {

    /**
     * Curly punctuation to its ASCII equivalent.
     *
     * A phone keyboard produces `’` and a book is set with `'`, so without this a query
     * for *a creature's speed* misses text that says exactly that. The dashes matter for
     * the same reason in the other direction: books set ranges with en-dashes.
     */
    private val PUNCTUATION = mapOf(
        '‘' to '\'', '’' to '\'', '‚' to '\'', '‛' to '\'',
        '“' to '"', '”' to '"', '„' to '"', '‟' to '"',
        '‐' to '-', '‑' to '-', '‒' to '-', '–' to '-',
        '—' to '-', '―' to '-', '−' to '-',
        '…' to ' ', // an ellipsis is a pause, not a term
    )

    fun normalize(query: String): String {
        val composed = Normalizer.normalize(query, Normalizer.Form.NFC)
        val folded = buildString(composed.length) {
            for (c in composed) append(PUNCTUATION[c] ?: c)
        }
        return folded.lowercase().split(WHITESPACE).filter { it.isNotEmpty() }.joinToString(" ")
    }

    /**
     * [normalized], truncated to [tokens] whitespace-separated tokens.
     *
     * The term budget used to be applied to *groups*, after the alias rewrite — which is
     * after the expensive part. A pasted page of text was fully tokenized, expanded into up
     * to five candidate phrases per token, and serialized into a single `IN (...)` list, so
     * the advertised bound constrained none of the allocation or SQL that actually costs
     * anything. Bounding the token stream first makes the same budget cover the whole
     * pipeline, and the bound is the one the tail of a long query was going to hit anyway.
     */
    fun bound(normalized: String, tokens: Int): String =
        normalized.split(' ').asSequence().filter { it.isNotEmpty() }.take(tokens).joinToString(" ")

    private val WHITESPACE = Regex("\\s+")

    /**
     * Openers that make a query meaningless on its own.
     *
     * The cheap deterministic check that runs **before the model is considered**: a
     * follow-up needs rewriting only if it opens with a pronoun, a demonstrative, or an
     * ellipsis, *and* there is a conversation to resolve it against. Nothing else
     * triggers it — a self-contained question never wakes the model, however it is
     * phrased, which is what keeps the model off the common path.
     */
    private val DEPENDENT_OPENERS = setOf(
        "it", "its", "they", "them", "their", "he", "him", "his", "she", "her", "hers",
        "this", "that", "these", "those",
    )

    private val ELLIPTICAL_PREFIXES = listOf(
        "what about", "how about", "and ", "but ", "or what",
    )

    /**
     * Whether [query] carries a reference only the conversation can resolve.
     *
     * @param conversationTurns turns available to resolve against. With none, there is
     * nothing to resolve *to*, and rewriting would be the model inventing a subject.
     */
    fun needsRewrite(query: String, conversationTurns: Int): Boolean {
        if (conversationTurns <= 0) return false

        // Read before normalization, which folds the ellipsis away. A leading `…` or `...`
        // is the most explicit statement a user can make that the subject is elsewhere,
        // and normalizing first turns "…for wizards?" into "for wizards?" — a query that
        // matches no opener and goes to retrieval having lost the thing it was about.
        val leading = query.trimStart()
        if (leading.startsWith("…") || leading.startsWith("...")) return true

        val normalized = normalize(query)
        if (normalized.isEmpty()) return false
        if (ELLIPTICAL_PREFIXES.any { normalized.startsWith(it) }) return true
        // "and" and "but" as whole one-word queries, which the prefixes above miss.
        val first = normalized.substringBefore(' ').trim('?', '.', ',', '!')
        return first in DEPENDENT_OPENERS || first == "and" || first == "but"
    }
}
