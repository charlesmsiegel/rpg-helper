package dev.ludex.pack

/**
 * UTF-8 measurements over Kotlin strings.
 *
 * Spans in a pack count UTF-8 bytes, while a Kotlin `String` is UTF-16, so every span
 * check has to cross that boundary. [byteLength] does it without materialising the
 * encoded bytes, because activation walks every chunk in the pack and allocating a
 * copy of a whole book to measure it is not a cost worth paying on a phone.
 *
 * Public rather than internal to `:pack`: the byte-offset rule is the *format's*, not the
 * validator's, so retrieval and routing check spans the same way activation does. Two
 * implementations of "is this a UTF-8 boundary" is one more than the number that can be
 * relied upon to agree.
 */
object Utf8 {

    /**
     * The form the pinned `unicode61 remove_diacritics 2` tokenizer reduces text to:
     * NFC, diacritics stripped, lowercased.
     *
     * Used to check that a pack stores its aliases in the form a query will be matched
     * in. Stripping happens through NFD so that a combining mark is separable from the
     * letter it sits on, which is the only way to remove it without a per-character table.
     */
    fun foldForIndex(text: String): String {
        val decomposed = java.text.Normalizer.normalize(text, java.text.Normalizer.Form.NFD)
        val stripped = buildString(decomposed.length) {
            for (c in decomposed) {
                if (Character.getType(c) != Character.NON_SPACING_MARK.toInt()) append(c)
            }
        }
        return java.text.Normalizer
            .normalize(stripped, java.text.Normalizer.Form.NFC)
            .lowercase()
    }

    /**
     * UTF-8 byte length of [text], matching what `String.toByteArray()` would produce.
     *
     * Unpaired surrogates are counted as one byte because that is what the JVM encoder
     * does with them -- it substitutes `?`. Matching the encoder rather than the
     * standard keeps this function's answer equal to the real encoded length, which is
     * the only thing a span can be compared against.
     */
    fun byteLength(text: String): Int {
        var bytes = 0
        var i = 0
        while (i < text.length) {
            val c = text[i]
            bytes += when {
                c.code < 0x80 -> 1
                c.code < 0x800 -> 2
                c.isHighSurrogate() -> {
                    val paired = i + 1 < text.length && text[i + 1].isLowSurrogate()
                    if (paired) {
                        i++
                        4
                    } else {
                        1
                    }
                }
                c.isLowSurrogate() -> 1
                else -> 3
            }
            i++
        }
        return bytes
    }

    /**
     * True when [offset] falls on a UTF-8 sequence boundary of [encoded], where the
     * end of the text counts as a boundary.
     *
     * Continuation bytes are exactly those matching `10xxxxxx`, so this needs no
     * decoding -- which is the same reason the format counts bytes rather than code
     * points in the first place.
     */
    fun isBoundary(encoded: ByteArray, offset: Int): Boolean {
        if (offset < 0 || offset > encoded.size) return false
        if (offset == encoded.size) return true
        return (encoded[offset].toInt() and 0xC0) != 0x80
    }
}
