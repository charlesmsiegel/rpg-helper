package dev.rpghelper.cli

import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.routing.Answer
import dev.rpghelper.routing.Card
import dev.rpghelper.routing.Chip
import dev.rpghelper.routing.Citation
import dev.rpghelper.routing.asPlainText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two text renderers, held to quoting the same bytes.
 *
 * `renderAnswer` paints for a terminal and `asPlainText` paints for storage, and both are
 * real implementations reachable from here — which is the point. A parity test that
 * reimplemented one of them would be comparing the shared layout to itself and would have
 * passed on the day the two actually diverged.
 *
 * They *may* differ: indentation, label case, whether an offer is worth recording. What
 * they may never differ about is which runs of text are the book's own words, because that
 * is the only claim this product makes and the surfaces are where it is made.
 */
class RenderParityTest {

    private fun citation(id: Long) = Citation(
        packUid = "srd:emberlight", chunkId = id, sourceTitle = "Emberlight", edition = "2e",
        headingPath = "How to Play > Seizing", pageLabelStart = "12", pageLabelEnd = null,
        locatorScheme = "page",
    )

    private val ref = ChunkRef("srd:emberlight", 7)

    private val cards = listOf(
        Card.Verbatim(
            ref = ref, kind = "rules",
            // A newline and an em-dash: the two things that broke a renderer each time.
            body = "A Held creature may not move away.\nIt rolls at −2 — always — to escape.",
            citation = citation(7), rollableRefs = listOf(ref),
        ),
        Card.Derived(
            ref = ChunkRef("srd:emberlight", 8),
            body = "Seizing leaves the target Held. Held creatures cannot move away.",
            chips = listOf(Chip(0, "Seizing leaves the target Held.".length, citation(7))),
            footer = listOf(citation(9)),
        ),
        Card.Generated(
            body = "The Cinder Marches are low country east of the Ember.",
            chips = emptyList(),
            footer = listOf(citation(11)),
        ),
        Card.Empty(activePacks = listOf("srd:emberlight"), canSearchInactive = true),
    )

    /** The quoted text a surface produced, stripped of whatever gutter it used. */
    private fun quoted(rendered: String): List<String> = rendered.lineSequence()
        .filter { it.trimStart().startsWith("\" ") }
        .map { it.trimStart().removePrefix("\" ") }
        .toList()

    @Test
    fun `both surfaces quote exactly the same lines`() {
        for (card in cards) {
            val answer = Answer(listOf(card), diagnostics = emptyList())
            assertEquals(
                quoted(card.asPlainText()),
                quoted(renderAnswer(answer, diagnostics = false)),
                "${card::class.simpleName} is quoted differently by the two surfaces",
            )
        }
    }

    @Test
    fun `a quoted line is the pack's bytes, on both surfaces`() {
        val quote = cards.filterIsInstance<Card.Verbatim>().single()
        val expected = quote.body.lines()
        assertEquals(expected, quoted(quote.asPlainText()))
        assertEquals(
            expected,
            quoted(renderAnswer(Answer(listOf(quote), emptyList()), diagnostics = false)),
        )
    }

    @Test
    fun `neither surface quotes a card that has nothing to quote`() {
        for (card in cards.filterNot { it is Card.Verbatim }) {
            val answer = Answer(listOf(card), diagnostics = emptyList())
            assertTrue(quoted(card.asPlainText()).isEmpty(), "$card")
            assertTrue(quoted(renderAnswer(answer, diagnostics = false)).isEmpty(), "$card")
        }
    }

    @Test
    fun `both surfaces number a chipped claim, and both put the number in the prose`() {
        val derived = cards.filterIsInstance<Card.Derived>().single()
        val terminal = renderAnswer(Answer(listOf(derived), emptyList()), diagnostics = false)
        val stored = derived.asPlainText()
        for (rendered in listOf(terminal, stored)) {
            assertTrue("[1]" in rendered, "no marker at all in: $rendered")
            val proseLine = rendered.lineSequence().first { "Seizing leaves" in it }
            assertTrue("[1]" in proseLine, "the marker is not in the prose it marks: $proseLine")
        }
    }
}
