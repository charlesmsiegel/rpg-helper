package dev.rpghelper.cli

import dev.rpghelper.routing.Answer
import dev.rpghelper.routing.Card
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
                card.body.lineSequence().forEach { appendLine("  $it") }
                appendChips(card.chips.map { it.citation }, card.footer)
            }

            is Card.Generated -> {
                appendLine("GENERATED  (on-device model, from setting text only)")
                card.body.lineSequence().forEach { appendLine("  $it") }
                appendChips(card.chips.map { it.citation }, card.footer)
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
 * Chips and footer citations, distinguished.
 *
 * A chip says *this run of text came from here*; a footer says *this card as a whole drew
 * on these*. Printing them as one list would state the stronger claim for both.
 */
private fun StringBuilder.appendChips(chips: List<Citation>, footer: List<Citation>) {
    chips.forEach { appendLine("    ▸ ${render(it)}") }
    if (footer.isNotEmpty()) {
        appendLine("    generated from ${footer.size} source${if (footer.size == 1) "" else "s"}:")
        footer.forEach { appendLine("      — ${render(it)}") }
    }
}
