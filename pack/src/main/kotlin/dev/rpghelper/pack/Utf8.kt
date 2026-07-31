package dev.rpghelper.pack

/**
 * UTF-8 measurements over Kotlin strings.
 *
 * Spans in a pack count UTF-8 bytes, while a Kotlin `String` is UTF-16, so every span
 * check has to cross that boundary. [byteLength] does it without materialising the
 * encoded bytes, because activation walks every chunk in the pack and allocating a
 * copy of a whole book to measure it is not a cost worth paying on a phone.
 */
internal object Utf8 {

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
