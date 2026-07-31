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
            if (Character.isLetterOrDigit(codePoint)) {
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
     * The form a token is stored and matched in: NFC, diacritics stripped, lowercased.
     *
     * Stripping goes through NFD so a combining mark is separable from the letter it sits
     * on — the only way to remove one without a per-character table.
     */
    fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val stripped = buildString(decomposed.length) {
            var at = 0
            while (at < decomposed.length) {
                val codePoint = decomposed.codePointAt(at)
                at += Character.charCount(codePoint)
                if (Character.getType(codePoint) != Character.NON_SPACING_MARK.toInt()) {
                    appendCodePoint(codePoint)
                }
            }
        }
        return Normalizer.normalize(stripped, Normalizer.Form.NFC).lowercase()
    }
}
