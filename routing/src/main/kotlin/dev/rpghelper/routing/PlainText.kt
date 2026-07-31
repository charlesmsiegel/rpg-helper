package dev.rpghelper.routing

/**
 * An answer as plain text that **keeps the distinction the cards carry**.
 *
 * Needed wherever cards have to become a string: the answer cache stores a rendering, the
 * conversation feed stores a rendering, and a restored turn shows one. The default for
 * those was `cards.joinToString()` — Kotlin's data-class `toString`, so a feed restored
 * after process death read `Verbatim(ref=ChunkRef(packUid=…), citation=Citation(…))`, and
 * the same text was destined to become the context a follow-up resolves against.
 *
 * The quote gutter is not decoration here either. This string is the *only* thing a
 * restored turn has, so if it did not mark which lines were the book's own words, the one
 * guarantee the product makes would hold on screen and fail in storage. Every line of a
 * quotation carries the marker, not just the first: an outcome or a rule with a newline in
 * it had its later lines flush left, where nothing said they were quoted.
 */
fun Answer.asPlainText(): String = cards.joinToString("\n\n") { it.asPlainText() }

fun Card.asPlainText(): String = when (this) {
    is Card.Verbatim -> quoted(body) + "\n  — " + render(citation) +
        if (rollable) "\n  [roll available]" else ""

    is Card.Derived -> "Summary — written by this pack's builder, not quoted:\n" +
        indented(body) + footerLines(footer, chips)

    is Card.Generated -> "Generated on this device from setting text:\n" +
        indented(body) + footerLines(footer, chips)

    is Card.ModelUnavailable -> statement +
        if (wouldHaveUsed.isEmpty()) "" else {
            "\n  It would have used:" + wouldHaveUsed.joinToString("") { "\n    — " + render(it) }
        }

    is Card.Empty -> statement +
        (if (activePacks.isEmpty()) "" else "\n  Searched: " + activePacks.joinToString(", ")) +
        (if (canSearchInactive) "\n  Inactive packs are installed and were not searched." else "")
}

/** Every line, so no line of a quotation is ever flush left. */
private fun quoted(body: String) = body.lineSequence().joinToString("\n") { "  \" $it" }

private fun indented(body: String) = body.lineSequence().joinToString("\n") { "  $it" }

private fun footerLines(footer: List<Citation>, chips: List<Chip>): String {
    val marked = chips.mapIndexed { index, chip -> "\n  [${index + 1}] " + render(chip.citation) }
    return marked.joinToString("") + footer.joinToString("") { "\n  — " + render(it) }
}
