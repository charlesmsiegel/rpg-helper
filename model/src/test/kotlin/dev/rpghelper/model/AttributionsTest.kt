package dev.rpghelper.model

import dev.rpghelper.pack.ChunkRef

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AttributionsTest {

    private val marches = ChunkRef("srd:emberlight", 8)
    private val ashfall = ChunkRef("srd:emberlight", 9)
    private val elsewhere = ChunkRef("other:pack", 3)

    private fun chunk(ref: ChunkRef, text: String) =
        RedactedChunk.of(ref, "The Cinder Marches", text)!!

    private val context = listOf(
        chunk(marches, "The Cinder Marches are the low country east of the Ember."),
        chunk(ashfall, "Ash falls for roughly nine days in ten."),
    )

    private fun answer(text: String, vararg attributions: Attribution) =
        Attributions.validate(GeneratedAnswer(text, attributions.toList()), context)

    /** Byte span of a substring, which is what the model is supposed to be producing. */
    private fun span(text: String, needle: String, chunk: ChunkRef): Attribution {
        val index = text.indexOf(needle)
        require(index >= 0)
        val start = text.substring(0, index).toByteArray(Charsets.UTF_8).size
        return Attribution(start, start + needle.toByteArray(Charsets.UTF_8).size, chunk)
    }

    // ---------------------------------------------------------------- membership

    @Test
    fun `citing a chunk that was not in the context suppresses the card`() {
        // Not a bad span -- the model has cited something it was never shown, and nothing
        // in the answer can be trusted after that. Same rule as an unresolvable citation.
        val text = "The Marches burn quietly."
        val result = answer(text, Attribution(0, 25, elsewhere))
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("not in the context"))
    }

    // ---------------------------------------------------------------- span validity

    @Test
    fun `an empty or inverted span is discarded and its region falls back`() {
        val text = "Ash falls most days."
        for (bad in listOf(Attribution(5, 5, marches), Attribution(10, 4, marches))) {
            val validated = answer(text, bad).getOrThrow()
            assertFalse(validated.hasChips, "the bad attribution did not survive")
            assertEquals(1, validated.discarded.size)
            assertTrue(
                validated.regions.single() is SupportedRegion.Contextual,
                "and the whole answer falls back to the context",
            )
        }
    }

    @Test
    fun `an out-of-range span is discarded`() {
        val text = "Ash falls most days."
        val validated = answer(text, Attribution(0, 500, marches)).getOrThrow()
        assertFalse(validated.hasChips)
        assertTrue(validated.discarded.single().contains("outside"))
    }

    @Test
    fun `a span splitting a multi-byte character is discarded`() {
        // In range, and still wrong: the chip anchors to corrupted text, or converting the
        // offset for display throws. Model offsets get the treatment claim spans get.
        val text = "The Ember—it burns—is not a mountain."
        val emDashStart = "The Ember".toByteArray(Charsets.UTF_8).size
        val validated = answer(text, Attribution(0, emDashStart + 1, marches)).getOrThrow()
        assertFalse(validated.hasChips)
        assertTrue(validated.discarded.single().contains("UTF-8"))
    }

    @Test
    fun `a well-formed span becomes a chip over exactly its bytes`() {
        val text = "The Marches are low country. Ash falls most days."
        val validated = answer(text, span(text, "Ash falls most days.", ashfall)).getOrThrow()

        val cited = validated.regions.filterIsInstance<SupportedRegion.Cited>().single()
        assertEquals(ashfall, cited.chunk)
        assertEquals("Ash falls most days.", Attributions.slice(text, cited))
    }

    // ---------------------------------------------------------------- coverage

    @Test
    fun `an unattributed region is checked against the whole context, not left untested`() {
        // Partial coverage is the common case. If only *zero* attributions triggered the
        // fallback, the remaining claims would render with no chip and reach the harness
        // with no cited chunk to check them against.
        val text = "The Marches are low country. Something else entirely."
        val validated = answer(text, span(text, "The Marches are low country.", marches)).getOrThrow()

        assertEquals(2, validated.regions.size)
        assertTrue(validated.regions[0] is SupportedRegion.Cited)
        val rest = validated.regions[1] as SupportedRegion.Contextual
        assertEquals(listOf(marches, ashfall), rest.context)
        assertEquals(" Something else entirely.", Attributions.slice(text, rest))
    }

    @Test
    fun `no usable attributions is the limiting case, not a special one`() {
        // Footer citations, no chips, and the harness evaluates the whole answer against
        // the whole context. Nothing about the contract is special at zero -- which is what
        // stops the boundary between "some" and "none" from being undefined.
        val text = "The Marches are low country."
        val validated = answer(text).getOrThrow()

        val only = validated.regions.single() as SupportedRegion.Contextual
        assertEquals(0, only.start)
        assertEquals(text.toByteArray(Charsets.UTF_8).size, only.end)
        assertEquals(listOf(marches, ashfall), only.context)
        assertFalse(validated.hasChips)
    }

    @Test
    fun `every byte of the answer belongs to exactly one region`() {
        // The property the fallback exists to guarantee: no claim renders unattributed and
        // none reaches the harness with nothing to check it against.
        val text = "One. Two. Three. Four."
        val validated = answer(
            text,
            span(text, "Two.", marches),
            span(text, "Four.", ashfall),
        ).getOrThrow()

        var cursor = 0
        for (region in validated.regions) {
            assertEquals(cursor, region.start, "regions are contiguous")
            cursor = region.end
        }
        assertEquals(text.toByteArray(Charsets.UTF_8).size, cursor, "and reach the end")
    }

    @Test
    fun `overlapping attributions are resolved rather than rendered twice`() {
        // Two chips over the same bytes render as one region with two citations, which is
        // exactly the "which chunk actually supports this" ambiguity chips exist to remove.
        val text = "The Marches are low country and burn quietly."
        val validated = answer(
            text,
            Attribution(0, 30, marches),
            Attribution(10, 40, ashfall),
        ).getOrThrow()

        assertEquals(1, validated.regions.filterIsInstance<SupportedRegion.Cited>().size)
        assertTrue(validated.discarded.single().contains("overlaps"))
    }

    // ---------------------------------------------------------------- the type barrier

    @Test
    fun `a chunk that could not be redacted cannot become context`() {
        // Nesting.redactedText returns null when redaction is not safely possible, and
        // there is no constructor here that skips it. Fail closed, enforced by the type.
        assertEquals(null, RedactedChunk.of(marches, null, null))
        assertEquals(null, RedactedChunk.of(marches, null, "   "))
    }

    @Test
    fun `an empty answer suppresses the card rather than rendering nothing`() {
        val result = Attributions.validate(GeneratedAnswer("", emptyList()), context)
        assertTrue(result.isFailure)
    }

    @Test
    fun `a whitespace-only answer is refused too`() {
        // It would otherwise produce one non-empty contextual region, render as a card with
        // nothing in it, and reach the claim harness as zero claims -- which scores 100%
        // support and passes the grounding gate on an answer that says nothing.
        for (blank in listOf("   ", "\n\n", "\t ")) {
            assertTrue(
                Attributions.validate(GeneratedAnswer(blank, emptyList()), context).isFailure,
                "'\u0024blank' must be refused",
            )
        }
    }
}
