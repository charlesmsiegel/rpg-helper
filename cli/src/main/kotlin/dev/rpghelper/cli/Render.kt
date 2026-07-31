package dev.rpghelper.cli

import dev.rpghelper.session.BUNDLED
import dev.rpghelper.session.EMBEDDER
import dev.rpghelper.session.GATES
import dev.rpghelper.session.Library
import dev.rpghelper.session.Store

import dev.rpghelper.routing.Answer
import dev.rpghelper.routing.Card
import dev.rpghelper.routing.Chip
import dev.rpghelper.routing.Citation
import dev.rpghelper.routing.render

/**
 * Cards as terminal text.
 *
 * The UI spec's requirement is *typographically unmistakable*, and a terminal has less to
 * work with than a phone — no type, no colour worth relying on through a pipe. What it
 * does have is the left margin, so the distinction is carried there: a quote is indented
 * under a `"` gutter and reproduces its bytes exactly, and everything generated or
 * builder-written sits flush left under a label naming what produced it. Nothing is
 * wrapped or re-flowed. Re-flowing a quotation is editing it.
 */
fun renderAnswer(answer: Answer, diagnostics: Boolean): String = buildString {
    for (card in answer.cards) {
        when (card) {
            is Card.Verbatim -> {
                appendLine("QUOTE  (${card.kind})${if (card.rollable) "  [roll available]" else ""}")
                card.body.lineSequence().forEach { appendLine("  \" $it") }
                appendLine("    — ${render(card.citation)}")
            }

            is Card.Derived -> {
                appendLine("SUMMARY  (written by the pack's builder, not quoted)")
                appendAttributed(card.body, card.chips, card.footer)
            }

            is Card.Generated -> {
                appendLine("GENERATED  (on-device model, from setting text only)")
                appendAttributed(card.body, card.chips, card.footer)
            }

            is Card.ModelUnavailable -> {
                appendLine("PASSAGES FOUND")
                appendLine("  ${card.statement}")
                card.wouldHaveUsed.forEach { appendLine("    — ${render(it)}") }
                card.downloadBytes?.let { appendLine("  download: $it bytes") }
            }

            is Card.Empty -> {
                appendLine("NOT FOUND")
                appendLine("  ${card.statement}")
                appendLine("  active: ${card.activePacks.joinToString(", ")}")
                if (card.canSearchInactive) appendLine("  inactive packs could be searched")
            }
        }
        appendLine()
    }

    if (diagnostics) {
        appendLine("why these results")
        answer.diagnostics.forEach { appendLine("  $it") }
    }
}

/**
 * Body, inline chips, and footer citations — each saying what it actually says.
 *
 * A chip's whole content is *which run of text this citation supports*, and the card
 * renders it inline for that reason. Printing the citations as a flat list under the body
 * throws the span away: a card drawing two claims from two books becomes two citations and
 * no way to tell which supports which — the one distinction chips exist to carry. So each
 * chipped run gets a numbered marker in the body and the numbers appear again below.
 *
 * A footer says something weaker — *this card as a whole drew on these* — and is kept
 * separate so the stronger claim is not made on its behalf.
 */
private fun StringBuilder.appendAttributed(
    body: String,
    chips: List<Chip>,
    footer: List<Citation>,
) {
    val bytes = body.toByteArray(Charsets.UTF_8)
    val ordered = chips.sortedBy { it.start }
    val marked = StringBuilder()
    var cursor = 0
    for ((index, chip) in ordered.withIndex()) {
        // Spans have already been validated against these bytes -- attribution discards
        // anything off a UTF-8 boundary or out of range -- so this needs no fail-closed
        // branch. Clamping anyway, because a renderer that throws takes the card with it.
        val start = chip.start.coerceIn(cursor, bytes.size)
        val end = chip.end.coerceIn(start, bytes.size)
        marked.append(String(bytes, cursor, start - cursor, Charsets.UTF_8))
        marked.append(String(bytes, start, end - start, Charsets.UTF_8))
        marked.append(" [${index + 1}]")
        cursor = end
    }
    if (cursor < bytes.size) {
        marked.append(String(bytes, cursor, bytes.size - cursor, Charsets.UTF_8))
    }

    marked.lineSequence().forEach { appendLine("  $it") }
    ordered.forEachIndexed { index, chip -> appendLine("    [${index + 1}] ${render(chip.citation)}") }
    if (footer.isNotEmpty()) {
        appendLine("    drawn as a whole from ${footer.size} source${if (footer.size == 1) "" else "s"}:")
        footer.forEach { appendLine("      — ${render(it)}") }
    }
}
