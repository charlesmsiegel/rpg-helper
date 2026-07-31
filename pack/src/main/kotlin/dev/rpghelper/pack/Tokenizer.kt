package dev.rpghelper.pack

import java.text.Normalizer

/**
 * App-side tokenization matching the pack's pinned `unicode61 remove_diacritics 2`.
 *
 * Parity is not cosmetic. The index was built by that tokenizer, and any divergence here
 * produces a query term that cannot match text the index holds — silently, and only for
 * the words where the two disagree, which is the hardest kind of retrieval bug to notice.
 *
 * `unicode61` splits on everything that is not a letter or a digit, folds case, and with
 * `remove_diacritics 2` strips combining marks. Digits are token characters, which is why
 * `2d6damage` is one token rather than a number beside a word.
 */
object Tokenizer {

    /**
     * The form an alias must be stored in: tokenized, then joined by single spaces.
     *
     * The rewriter looks an alias up by joining query tokens that way, so `fast-cast` and
     * `D&D` stored as written can never match anything — on a pack that passes a
     * fold-only normalization check, because folding is idempotent on them.
     */
    fun indexForm(text: String): String = tokenize(text).joinToString(" ")


    /** Splits [text] into the tokens the index would have produced. */
    fun tokenize(text: String): List<String> {
        val folded = fold(text)
        val tokens = mutableListOf<String>()
        val current = StringBuilder()
        // By code point, not by Char. A supplementary-plane letter is two UTF-16
        // surrogates and `Char.isLetterOrDigit` rejects both, while `unicode61` indexes
        // the code point as a letter -- so the term would vanish from the query and could
        // never match text the index demonstrably holds.
        var index = 0
        while (index < folded.length) {
            val codePoint = folded.codePointAt(index)
            index += Character.charCount(codePoint)
            if (isTokenCharacter(codePoint)) {
                current.appendCodePoint(codePoint)
            } else if (current.isNotEmpty()) {
                tokens += current.toString()
                current.setLength(0)
            }
        }
        if (current.isNotEmpty()) tokens += current.toString()
        return tokens
    }

    /**
     * What `unicode61` counts as part of a token: every `L*` and `N*` category, and `Co`.
     *
     * `Character.isLetterOrDigit` is *narrower*, and the gap is not exotic: it rejects the
     * Roman numeral `Ⅳ` (`Nl`), the superscript `²` (`No`), and every private-use glyph
     * (`Co`) — all of which the index tokenizes and stores. A query consisting of one of
     * them tokenized to nothing and was refused, against text the index demonstrably
     * holds. Verified against the bundled SQLite rather than inferred, in `TokenizerTest`.
     */
    internal fun isTokenCharacter(codePoint: Int): Boolean = when (Character.getType(codePoint)) {
        Character.UPPERCASE_LETTER.toInt(),
        Character.LOWERCASE_LETTER.toInt(),
        Character.TITLECASE_LETTER.toInt(),
        Character.MODIFIER_LETTER.toInt(),
        Character.OTHER_LETTER.toInt(),
        Character.DECIMAL_DIGIT_NUMBER.toInt(),
        Character.LETTER_NUMBER.toInt(),
        Character.OTHER_NUMBER.toInt(),
        Character.PRIVATE_USE.toInt(),
        -> true
        else -> false
    }

    /**
     * The form a token is stored and matched in: NFC, **Latin** diacritics stripped,
     * lowercased.
     *
     * `remove_diacritics 2` is not "strip every combining mark", which is what this used
     * to do. SQLite folds `café` to `cafe` and leaves `άλφα` exactly as written — the
     * tonos stays. Stripping it here rewrote an exact query for indexed Greek into
     * `αλφα`, which matches no token in the index, so lexical lookup failed for accented
     * non-Latin content and failed silently.
     *
     * Decided by the script of the **base** character, since a mark carries no script of
     * its own: marks following a Latin or ASCII base are dropped, marks following anything
     * else are kept and recomposed. That is an approximation of SQLite's per-codepoint
     * table, and the one that matters — it agrees on the two cases that differ, which is
     * what `TokenizerTest` pins against the real tokenizer.
     */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val stripped = buildString(decomposed.length) {
            var at = 0
            var baseIsLatin = false
            while (at < decomposed.length) {
                val codePoint = decomposed.codePointAt(at)
                at += Character.charCount(codePoint)
                if (Character.getType(codePoint) == Character.NON_SPACING_MARK.toInt()) {
                    if (!baseIsLatin) appendCodePoint(codePoint)
                    continue
                }
                baseIsLatin = isLatin(codePoint)
                appendCodePoint(codePoint)
            }
        }
        return Normalizer.normalize(stripped, Normalizer.Form.NFC).lowercase()
    }

    /** Latin script, or the ASCII and punctuation that `COMMON` covers alongside it. */
    private fun isLatin(codePoint: Int): Boolean =
        when (Character.UnicodeScript.of(codePoint)) {
            Character.UnicodeScript.LATIN, Character.UnicodeScript.COMMON -> true
            else -> false
        }
}
