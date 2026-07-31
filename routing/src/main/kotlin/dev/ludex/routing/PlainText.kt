package dev.ludex.routing

/**
 * An answer as plain text that **keeps the distinction the cards carry**.
 *
 * Needed wherever cards have to become a string: the answer cache stores a rendering, the
 * conversation feed stores a rendering, and a restored turn shows one. The default for
 * those was `cards.joinToString()` — Kotlin's data-class `toString`, so a feed restored
 * after process death read `Verbatim(ref=ChunkRef(…), citation=Citation(…))`, and the same
 * text was destined to become the context a follow-up resolves against.
 *
 * The quote gutter is not decoration here either. This string is the *only* thing a
 * restored turn has, so if it did not mark which lines were the book's own words, the one
 * guarantee the product makes would hold on screen and fail in storage. Every line of a
 * quotation carries the marker, not just the first: a rule or an outcome with a newline in
 * it had its later lines flush left, where nothing said they were quoted.
 *
 * Painting only — every decision about *what* a block is comes from [layout].
 */
fun Answer.asPlainText(): String = cards.joinToString("\n\n") { it.asPlainText() }

fun Card.asPlainText(): String = layout().joinToString("\n") { it.asPlainText() }

private fun Block.asPlainText(): String = when (this) {
    is Block.Label -> text
    // The gutter, on every line. A surface that marks only the first line of a quotation
    // has marked the quotation and not the quoted text.
    is Block.Quotation -> text.lineSequence().joinToString("\n") { "  \" $it" }
    is Block.Prose -> text.lineSequence().joinToString("\n") { "  $it" }
    is Block.Statement -> "  $text"
    is Block.Cited -> "    " + (marker?.let { "[$it] " } ?: "— ") + render(citation)
    is Block.Footer -> "    drawn as a whole from ${citations.size} " +
        "source${if (citations.size == 1) "" else "s"}:" +
        citations.joinToString("") { "\n      — " + render(it) }
    // Stored text is read, never tapped. Recorded as what was offered rather than as
    // something to take, so a restored turn does not look like a live control.
    is Block.Action -> "  [$label]"
}

/**
 * [body] with a numbered marker after each chipped run.
 *
 * Offsets are UTF-8 byte spans, so the string is walked as bytes and decoded back —
 * indexing a Kotlin `String` by them would place markers wrongly the moment a card
 * contains an em-dash, which game text does constantly.
 *
 * **Shared by every renderer**, because a chip's entire content is *which run of text* a
 * citation supports. A surface that lists `[1] Source` under prose containing no `[1]` has
 * kept the citation and thrown away the claim it was about — which for a card drawing two
 * claims from two books is the distinction inline chips exist to carry. There were three
 * copies of this walk and one surface with none.
 */
fun markChips(body: String, chips: List<Chip>): String {
    if (chips.isEmpty()) return body
    val bytes = body.toByteArray(Charsets.UTF_8)
    val out = StringBuilder()
    var cursor = 0
    for ((index, chip) in chips.sortedBy { it.start }.withIndex()) {
        val start = chip.start.coerceIn(cursor, bytes.size)
        val end = chip.end.coerceIn(start, bytes.size)
        out.append(String(bytes, cursor, end - cursor, Charsets.UTF_8))
        out.append(" [${index + 1}]")
        cursor = end
    }
    if (cursor < bytes.size) {
        out.append(String(bytes, cursor, bytes.size - cursor, Charsets.UTF_8))
    }
    return out.toString()
}
