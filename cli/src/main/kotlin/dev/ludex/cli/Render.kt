package dev.ludex.cli

import dev.ludex.routing.Answer
import dev.ludex.routing.Block
import dev.ludex.routing.Citation
import dev.ludex.routing.layout
import dev.ludex.routing.render

/**
 * Cards as terminal text.
 *
 * The UI spec's requirement is *typographically unmistakable*, and a terminal has less to
 * work with than a phone — no type, no colour worth relying on through a pipe. What it does
 * have is the left margin, so the distinction is carried there: a quotation is indented
 * under a `"` gutter and reproduces its bytes exactly, and everything generated or
 * builder-written sits flush left under a label naming what produced it. Nothing is wrapped
 * or re-flowed. Re-flowing a quotation is editing it.
 *
 * **Which of those a block is, this file does not decide.** `Card.layout()` does, once, for
 * every surface. This one used to make that call itself and to carry its own copy of the
 * chip-span walk — the third copy in the repository, and the reason the stored rendering
 * could quietly ship without one at all.
 */
fun renderAnswer(answer: Answer, diagnostics: Boolean): String = buildString {
    for (card in answer.cards) {
        card.layout().forEach { append(it.terminal()) }
        appendLine()
    }

    if (diagnostics) {
        appendLine("why these results")
        answer.diagnostics.forEach { appendLine("  $it") }
    }
}

private fun Block.terminal(): String = when (this) {
    is Block.Label -> text.uppercase() + "\n"
    // The gutter goes on every line. An outcome or a rule carrying a newline had its later
    // lines printed flush left, where nothing marks them as the book's own words.
    is Block.Quotation -> text.lineSequence().joinToString("") { "  \" $it\n" }
    is Block.Prose -> text.lineSequence().joinToString("") { "  $it\n" }
    is Block.Statement -> "  $text\n"
    is Block.Cited -> "    " + (marker?.let { "[$it] " } ?: "— ") + render(citation) + "\n"
    is Block.Footer -> footer(citations)
    is Block.Action -> "  [$label]\n"
}

/**
 * A footer says something weaker than a marked citation — *this card as a whole drew on
 * these* — and is worded so the stronger claim is not made on its behalf.
 */
private fun footer(citations: List<Citation>): String =
    "    drawn as a whole from ${citations.size} " +
        "source${if (citations.size == 1) "" else "s"}:\n" +
        citations.joinToString("") { "      — ${render(it)}\n" }
