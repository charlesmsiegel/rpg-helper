package dev.rpghelper.pack

/**
 * A chunk's metadata, without its text.
 *
 * Held in memory for every chunk in the pack, which is affordable precisely because
 * `text` is excluded -- the text of a 300-page book is not something to keep resident
 * on a phone in order to compare a few integers.
 */
internal data class ChunkRow(
    val id: Long,
    val kind: String,
    val origin: String,
    val sourceId: Long?,
    val headingPath: String?,
    val pageLabelStart: String?,
    val pageLabelEnd: String?,
    val spanStart: Long?,
    val spanEnd: Long?,
    val stableKey: String?,
    val parentId: Long?,
) {
    val isSource: Boolean get() = origin == "source"
}

/**
 * What one streaming pass over every chunk's text yields.
 *
 * Both results come from the same pass because the text is the expensive part to read
 * and neither survives it: only an integer per chunk, and one short term.
 */
internal class TextPass(
    val lengths: Map<Long, Int>,
    val canary: Canary?,
)

/**
 * A term known to occur in a particular chunk, used to prove the lexical index is real
 * and populated.
 *
 * Restricted to ASCII letters so it survives the pinned `unicode61` tokenizer
 * unchanged apart from case: no diacritic folding to predict, no separator to guess,
 * and nothing that could be mistaken for FTS5 syntax.
 */
internal class Canary(val chunkId: Long, val term: String)

internal fun loadChunks(db: Db): Map<Long, ChunkRow> =
    db.map(
        """
        SELECT chunk_id, kind, origin, source_id, heading_path, page_label_start,
               page_label_end, span_start, span_end, stable_key, parent_chunk_id
        FROM chunks
        """.trimIndent(),
    ) {
        ChunkRow(
            id = it.long(0),
            kind = it.string(1),
            origin = it.string(2),
            sourceId = it.longOrNull(3),
            headingPath = it.stringOrNull(4),
            pageLabelStart = it.stringOrNull(5),
            pageLabelEnd = it.stringOrNull(6),
            spanStart = it.longOrNull(7),
            spanEnd = it.longOrNull(8),
            stableKey = it.stringOrNull(9),
            parentId = it.longOrNull(10),
        )
    }.associateBy { it.id }

internal fun readTextPass(db: Db): TextPass {
    val lengths = HashMap<Long, Int>()
    var canary: Canary? = null
    db.forEachRow("SELECT chunk_id, text FROM chunks ORDER BY chunk_id") { row ->
        val id = row.long(0)
        val text = row.string(1)
        lengths[id] = Utf8.byteLength(text)
        if (canary == null) {
            asciiWord(text)?.let { canary = Canary(id, it) }
        }
    }
    return TextPass(lengths, canary)
}

/**
 * The first run of at least three ASCII letters in [text], lowercased, or null.
 *
 * Three is enough to be a real token and short enough that almost any prose supplies
 * one; a chunk of pure punctuation or non-Latin script simply yields nothing and the
 * search moves to the next chunk.
 */
private fun asciiWord(text: String): String? {
    var start = -1
    for (i in text.indices) {
        val isLetter = text[i] in 'a'..'z' || text[i] in 'A'..'Z'
        if (isLetter) {
            if (start < 0) start = i
            if (i - start + 1 >= 12) return text.substring(start, i + 1).lowercase()
        } else {
            if (start >= 0 && i - start >= 3) return text.substring(start, i).lowercase()
            start = -1
        }
    }
    if (start >= 0 && text.length - start >= 3) return text.substring(start).lowercase()
    return null
}
