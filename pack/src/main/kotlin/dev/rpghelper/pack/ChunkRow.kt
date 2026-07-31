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

/**
 * Every chunk, keyed by id, plus any id that appeared more than once.
 *
 * The duplicates travel with the map because a map cannot represent them: `associateBy`
 * keeps whichever row it saw last, so every downstream shape, nesting, stable-key, and
 * reference check would be validating a view with rows silently missing from it.
 */
internal class ChunkTable(val byId: Map<Long, ChunkRow>, val duplicateIds: Set<Long>)

internal fun loadChunks(db: Db): ChunkTable {
    val rows = readChunkRows(db)
    val seen = mutableSetOf<Long>()
    val duplicates = mutableSetOf<Long>()
    for (row in rows) if (!seen.add(row.id)) duplicates += row.id
    return ChunkTable(rows.associateBy { it.id }, duplicates)
}

private fun readChunkRows(db: Db): List<ChunkRow> =
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
    }

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
 * A complete token from [text] that is safe to search for, or null.
 *
 * FTS5 phrase matching compares whole tokens, and the pinned `unicode61` tokenizer
 * treats letters *and digits* as token characters while folding diacritics. So a
 * candidate is only usable when it is a maximal token that survives that tokenizer
 * unchanged apart from case: pure ASCII letters, with no digit or non-ASCII letter
 * adjacent to it.
 *
 * Taking a letter run and truncating it would produce a substring rather than a token --
 * `Acknowledgements` clipped to twelve characters matches nothing, and so do the
 * `damage` inside `2d6damage` and the `caf` inside `Café`. Each would reject a
 * perfectly valid pack.
 */
internal fun asciiWord(text: String): String? {
    var i = 0
    while (i < text.length) {
        if (!text[i].isLetterOrDigit()) {
            i++
            continue
        }
        var end = i
        while (end < text.length && text[end].isLetterOrDigit()) end++
        val token = text.substring(i, end)
        val plainAscii = token.all { it in 'a'..'z' || it in 'A'..'Z' }
        if (plainAscii && token.length in 3..20) return token.lowercase()
        i = end
    }
    return null
}
