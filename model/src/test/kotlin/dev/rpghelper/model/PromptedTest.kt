package dev.rpghelper.model

import dev.rpghelper.pack.ChunkRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The generative half, tested without weights.
 *
 * Everything a runtime does that can be wrong *in a way that matters* happens here: what the
 * model is asked, and what is believed of the reply. A backend supplies bytes; if the rules
 * lived in the backend they would be written three times and reviewed none.
 *
 * The recurring theme is that **model output is untrusted input**, exactly like a pack. The
 * failure mode of believing it is not a crash — it is a confident answer citing a passage
 * that does not exist.
 */
class PromptedTest {

    // Through the factory, because the constructor is private: a `RedactedChunk` that was
    // never redacted is the one thing this type exists to make unrepresentable.
    private val marches = RedactedChunk.of(
        ChunkRef("srd:emberlight", 11),
        "The Cinder Marches",
        "Ash falls in the Marches for most of the year.",
    )!!
    private val hounds = RedactedChunk.of(
        ChunkRef("srd:emberlight", 12),
        "What Follows",
        "Cinder hounds hunt in pairs.",
    )!!
    private val context = listOf(marches, hounds)

    /** Replies with whatever it is told to, and records what it was asked. */
    private class Canned(private val reply: String) : TextCompletion {
        var prompt: String? = null
        override fun complete(prompt: String, maxTokens: Int, stop: List<String>): String {
            this.prompt = prompt
            return reply
        }
    }

    // ---------------------------------------------------------------- what is asked

    @Test
    fun `the answer prompt carries the passages and their ids`() {
        val canned = Canned("""{"sentences":[]}""")
        PromptedGenerator(canned).answer("what lives there", context)

        val prompt = canned.prompt!!
        assertTrue("srd:emberlight#11" in prompt, "each passage is addressable, or a citation cannot name one")
        assertTrue("Ash falls in the Marches" in prompt)
        assertTrue("ONLY the passages below" in prompt, "the instruction is about provenance")
    }

    @Test
    fun `the rewrite prompt is a pronoun resolver, not an answerer`() {
        val canned = Canned("What is the weather in the Cinder Marches?")
        PromptedGenerator(canned).normalize(
            "what about there?",
            listOf(Turn("where do cinder hounds hunt", "In the Cinder Marches.")),
        )
        val prompt = canned.prompt!!
        assertTrue("Add nothing that is not implied" in prompt)
        assertTrue("In the Cinder Marches." in prompt, "the history is what a pronoun resolves against")
    }

    // ---------------------------------------------------------------- spans

    @Test
    fun `spans are computed from the assembled text, not read from the model`() {
        // Asking a model for `[start, end)` is asking it to count UTF-8 bytes. It cannot, and
        // a wrong offset is a chip anchored to corrupted text -- so the model names sentences
        // and this code does the arithmetic.
        val reply = """
            {"sentences":[
              {"text":"Ash falls for most of the year.","source":"srd:emberlight#11"},
              {"text":"Cinder hounds hunt in pairs.","source":"srd:emberlight#12"}]}
        """.trimIndent()
        val answer = PromptedGenerator(Canned(reply)).answer("what is it like", context)

        assertEquals(
            "Ash falls for most of the year. Cinder hounds hunt in pairs.",
            answer.text,
        )
        val bytes = answer.text.toByteArray(Charsets.UTF_8)
        answer.attributions.forEach { attribution ->
            assertTrue(attribution.start in 0..bytes.size && attribution.end in 0..bytes.size)
            assertTrue(attribution.start < attribution.end, "no empty spans")
            // On a boundary: a continuation byte is 10xxxxxx.
            listOf(attribution.start, attribution.end).forEach { at ->
                if (at < bytes.size) {
                    assertTrue(
                        (bytes[at].toInt() and 0xC0) != 0x80,
                        "offset $at splits a multi-byte character",
                    )
                }
            }
        }
        assertEquals(
            "Ash falls for most of the year.",
            String(bytes, answer.attributions[0].start, answer.attributions[0].end - answer.attributions[0].start),
        )
    }

    @Test
    fun `a multi-byte answer still lands on character boundaries`() {
        // The case the byte-offset rule exists for. Every one of these is multi-byte in UTF-8.
        val reply = """{"sentences":[{"text":"Café — naïve … 日本","source":"srd:emberlight#11"}]}"""
        val answer = PromptedGenerator(Canned(reply)).answer("q", context)
        val attribution = answer.attributions.single()
        assertEquals(
            answer.text,
            String(
                answer.text.toByteArray(Charsets.UTF_8),
                attribution.start,
                attribution.end - attribution.start,
            ),
        )
    }

    // ---------------------------------------------------------------- untrusted output

    @Test
    fun `a citation naming a chunk that was not in the context is dropped`() {
        // The spec's rule: such an attribution is a generation failure. The sentence itself
        // is not evidence of anything worse, so it stays as unattributed text -- dropping it
        // would edit the answer.
        val reply = """
            {"sentences":[
              {"text":"Ash falls for most of the year.","source":"srd:emberlight#11"},
              {"text":"The Marches were settled in the ninth age.","source":"srd:other#999"}]}
        """.trimIndent()
        val answer = PromptedGenerator(Canned(reply)).answer("q", context)

        assertTrue("ninth age" in answer.text, "the sentence is kept")
        assertEquals(1, answer.attributions.size, "and carries no chip")
        assertEquals(ChunkRef("srd:emberlight", 11), answer.attributions.single().chunk)
    }

    @Test
    fun `a reply that is not JSON becomes one unattributed answer`() {
        // The defined limiting case, not an error path: footer citations instead of chips,
        // and the claim-support harness checks the whole answer against the whole context.
        val answer = PromptedGenerator(Canned("The Marches are ashen year-round.")).answer("q", context)
        assertEquals("The Marches are ashen year-round.", answer.text)
        assertTrue(answer.attributions.isEmpty())
    }

    @Test
    fun `JSON wrapped in prose or a code fence is still read`() {
        val reply = """
            Sure! Here is the answer:
            ```json
            {"sentences":[{"text":"Ash falls.","source":"srd:emberlight#11"}]}
            ```
        """.trimIndent()
        val answer = PromptedGenerator(Canned(reply)).answer("q", context)
        assertEquals("Ash falls.", answer.text)
        assertEquals(1, answer.attributions.size)
    }

    @Test
    fun `an empty context is never sent to the model`() {
        // Routing should not get here, and if it does, inventing prose from nothing is the
        // exact failure this product exists to prevent.
        val canned = Canned("anything at all")
        val answer = PromptedGenerator(canned).answer("q", emptyList())
        assertEquals("", answer.text)
        assertEquals(null, canned.prompt, "the model was not woken")
    }

    // ---------------------------------------------------------------- degradation

    @Test
    fun `an empty or rambling rewrite degrades to what the user typed`() {
        // A rewrite is an improvement to a query, never a precondition for one.
        val history = listOf(Turn("where do hounds hunt", "In the Marches."))
        assertEquals("what about there?", PromptedGenerator(Canned("   ")).normalize("what about there?", history))
        assertEquals(
            "what about there?",
            PromptedGenerator(Canned("x".repeat(5_000))).normalize("what about there?", history),
        )
    }

    @Test
    fun `NONE means the quotations answered it, and route 3 is skipped`() {
        val covered = listOf(CardSummary("rules", "How to Play > Seizing"))
        assertEquals(null, PromptedGenerator(Canned("NONE")).residualIntent("how do I grapple", covered))
        assertEquals(
            "What happens on a failed attempt?",
            PromptedGenerator(Canned("What happens on a failed attempt?"))
                .residualIntent("how do I grapple", covered),
        )
    }

    @Test
    fun `a text-only runtime refuses camera input rather than faking it`() {
        // Returning "" would send retrieval an empty query and produce a refusal card that
        // blames the books for a missing feature.
        assertFailsWith<UnsupportedOperationException> {
            PromptedGenerator(Canned("a sword")).describeImage(ImageBuffer(ByteArray(0), "image/png"))
        }
    }
}
