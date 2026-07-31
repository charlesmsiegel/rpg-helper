package dev.ludex.routing

import dev.ludex.model.Availability
import dev.ludex.pack.ChunkRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The invariant, checked once for every card kind: **a quotation is exactly the pack's
 * bytes, and nothing else is ever a quotation.**
 *
 * The product's whole guarantee is that a reader can tell what the book says from what a
 * machine said about it. Before this the guarantee was a property of three renderers that
 * had never been compared, and the difference between them was already visible: two carried
 * their own copy of the chip-span walk and one carried none, the roll control existed on one
 * surface, the refusal card's remedy on another. Each looked like a small omission. All of
 * them were the same omission.
 *
 * This checks the *layout*, which is what the surfaces now share. `RenderParityTest` in
 * `:cli` checks the two text renderers against each other, where both are importable — a
 * parity test that reimplemented one of the renderers to compare against would be comparing
 * the layout to itself.
 */
class CardLayoutTest {

    private val ref = ChunkRef("srd:emberlight", 7)

    private fun citation(id: Long) = Citation(
        packUid = "srd:emberlight", chunkId = id, sourceTitle = "Emberlight", edition = "2e",
        headingPath = "How to Play > Seizing", pageLabelStart = "12", pageLabelEnd = null,
        locatorScheme = "page",
    )

    private val quote = Card.Verbatim(
        ref = ref, kind = "rules",
        body = "A Held creature may not move away.\nIt rolls at −2 to escape.",
        citation = citation(7), rollableRefs = listOf(ref),
    )

    private val derived = Card.Derived(
        ref = ChunkRef("srd:emberlight", 8),
        body = "Seizing leaves the target Held. Held creatures cannot move away.",
        chips = listOf(Chip(0, "Seizing leaves the target Held.".length, citation(7))),
        footer = listOf(citation(9)),
    )

    private val generated = Card.Generated(
        body = "The Cinder Marches are low country east of the Ember.",
        chips = emptyList(),
        footer = listOf(citation(11)),
    )

    private val unavailable = Card.ModelUnavailable(
        statement = "Found passages, but the model is not downloaded.",
        wouldHaveUsed = listOf(citation(12)),
        downloadBytes = 1_200_000,
        availability = Availability.NotDownloaded,
    )

    private val empty = Card.Empty(
        activePacks = listOf("srd:emberlight"),
        canSearchInactive = true,
    )

    private val everyCard = listOf(quote, derived, generated, unavailable, empty)

    // ---------------------------------------------------------------- the invariant

    @Test
    fun `only a verbatim card produces a quotation block`() {
        for (card in everyCard) {
            val quotations = card.layout().filterIsInstance<Block.Quotation>()
            if (card is Card.Verbatim) {
                assertEquals(listOf(card.body), quotations.map { it.text })
            } else {
                assertTrue(quotations.isEmpty(), "${card::class.simpleName} produced $quotations")
            }
        }
    }

    @Test
    fun `a quotation block is the pack's bytes, unaltered`() {
        // Not "starts with" or "contains". A renderer may add a gutter around it; nothing
        // may change what is inside.
        val body = quote.layout().filterIsInstance<Block.Quotation>().single().text
        assertEquals(quote.body, body)
    }

    @Test
    fun `every card says what produced it, before it says anything else`() {
        for (card in everyCard) {
            val blocks = card.layout()
            assertTrue(
                blocks.first() is Block.Label,
                "${card::class.simpleName} leads with ${blocks.first()}",
            )
        }
    }

    @Test
    fun `the model-unavailable card cites what it would have used and quotes none of it`() {
        // These are setting chunks the redaction step emptied or refused. A quotation block
        // here would put the original passage on screen through the one door the
        // fail-closed rule left open.
        val blocks = unavailable.layout()
        assertTrue(blocks.none { it is Block.Quotation })
        assertEquals(
            listOf(citation(12)),
            blocks.filterIsInstance<Block.Footer>().single().citations,
        )
    }

    @Test
    fun `a chip's marker appears in the prose it marks`() {
        val blocks = derived.layout()
        val prose = blocks.filterIsInstance<Block.Prose>().single().text
        val marker = blocks.filterIsInstance<Block.Cited>().single().marker
        assertEquals("1", marker)
        assertTrue("[1]" in prose, "the marker has to be in the text it marks: $prose")
    }

    @Test
    fun `an unchipped card numbers nothing`() {
        // A number with nothing to point at is worse than no number: it implies a claim-level
        // attribution the card does not have.
        val blocks = generated.layout()
        assertTrue("[1]" !in blocks.filterIsInstance<Block.Prose>().single().text)
        assertTrue(blocks.filterIsInstance<Block.Cited>().isEmpty())
    }

    @Test
    fun `the refusal card carries its remedy, not only its sentence`() {
        val blocks = empty.layout()
        assertTrue(blocks.any { it is Block.Statement && "srd:emberlight" in it.text })
        assertTrue(blocks.any { it is Block.Action && it.searchInactive })
    }
}
