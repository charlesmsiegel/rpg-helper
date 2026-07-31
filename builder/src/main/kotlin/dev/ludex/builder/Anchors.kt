package dev.ludex.builder

/**
 * Resolves an anchor pair into a UTF-8 byte span of the source document.
 *
 * The corpus locates chunks by the text they begin and end with rather than by offsets.
 * The pack still carries real spans — but a hand-written offset rots silently the moment
 * a word changes in the source, and a fixture whose spans quietly point at the wrong
 * passage is worse than one that fails to build.
 *
 * So both anchors must be **unique within their scope**. Ambiguity is refused rather than
 * resolved by a rule like "first match": that rule is exactly how a fixture starts
 * pointing somewhere plausible and wrong.
 */
object Anchors {

    class UnresolvedAnchorException(message: String) : Exception(message)

    /** A resolved span, in UTF-8 bytes of the whole document, end-exclusive. */
    data class Span(val start: Int, val end: Int) {
        val length: Int get() = end - start
    }

    /**
     * @param scope the region the anchors must fall inside — the whole document for a
     * top-level chunk, the parent's span for a nested one.
     */
    fun resolve(document: String, from: String, to: String, scope: Span? = null): Span {
        val bytes = document.toByteArray(Charsets.UTF_8)
        val window = scope ?: Span(0, bytes.size)
        val region = String(bytes, window.start, window.length, Charsets.UTF_8)

        val fromChar = onlyOccurrence(region, from, "start anchor")
        val toChar = onlyOccurrence(region, to, "end anchor", after = fromChar)

        val start = window.start + region.substring(0, fromChar).utf8Length()
        val end = window.start + region.substring(0, toChar + to.length).utf8Length()

        if (end <= start) {
            throw UnresolvedAnchorException(
                "anchors '$from' .. '$to' resolve to an empty or inverted span",
            )
        }
        return Span(start, end)
    }

    /** Locates [needle] inside a chunk's own text, for a claim span or a table row. */
    fun within(text: String, needle: String, fromIndex: Int = 0): Span {
        val index = text.indexOf(needle, fromIndex)
        if (index < 0) {
            throw UnresolvedAnchorException("'${needle.take(60)}' does not occur in the text")
        }
        val start = text.substring(0, index).utf8Length()
        return Span(start, start + needle.utf8Length())
    }

    /** Index of [needle] in [region], requiring exactly one occurrence at or after [after]. */
    private fun onlyOccurrence(region: String, needle: String, what: String, after: Int = 0): Int {
        if (needle.isEmpty()) throw UnresolvedAnchorException("$what is empty")

        val occurrences = mutableListOf<Int>()
        var index = region.indexOf(needle, after)
        while (index >= 0) {
            occurrences += index
            index = region.indexOf(needle, index + 1)
        }
        return when (occurrences.size) {
            1 -> occurrences.single()
            0 -> throw UnresolvedAnchorException("$what '${needle.take(60)}' is not in the source")
            else -> throw UnresolvedAnchorException(
                "$what '${needle.take(60)}' occurs ${occurrences.size} times; " +
                    "lengthen it until it is unique rather than letting the build pick one",
            )
        }
    }
}

internal fun String.utf8Length(): Int = toByteArray(Charsets.UTF_8).size
