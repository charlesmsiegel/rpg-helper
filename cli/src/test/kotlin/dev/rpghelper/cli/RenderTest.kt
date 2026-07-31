package dev.rpghelper.cli

import dev.rpghelper.session.BUNDLED
import dev.rpghelper.session.EMBEDDER
import dev.rpghelper.session.GATES
import dev.rpghelper.session.Library
import dev.rpghelper.session.Store

import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.routing.Answer
import dev.rpghelper.routing.Card
import dev.rpghelper.routing.Chip
import dev.rpghelper.routing.Citation
import kotlin.test.Test
import kotlin.test.assertTrue

/** What the terminal has to carry that a phone carries with typography. */
class RenderTest {

    private fun citation(id: Long, title: String) = Citation(
        packUid = "srd:emberlight", chunkId = id, sourceTitle = title, edition = "2e",
        headingPath = "Somewhere", pageLabelStart = "1", pageLabelEnd = "1",
        locatorScheme = "page",
    )

    private fun rendered(card: Card) = renderAnswer(Answer(listOf(card), emptyList()), false)

    @Test
    fun `each chip is tied to the run of text it supports, not listed loose`() {
        // A chip's whole content is *which* run of text a citation supports. Printing the
        // citations as a flat list under the body throws the span away: a card drawing two
        // claims from two books becomes two citations and no way to tell which is which --
        // the one distinction chips exist to carry.
        val body = "Ash falls nine days in ten. Wardens read it like weather."
        val first = body.indexOf("Ash")
        val second = body.indexOf("Wardens")
        val card = Card.Generated(
            body,
            listOf(
                Chip(first, first + "Ash falls nine days in ten.".length, citation(1, "Emberlight")),
                Chip(second, body.length, citation(2, "The Door in the Ember")),
            ),
            emptyList(),
        )

        val text = rendered(card)
        assertTrue("Ash falls nine days in ten. [1]" in text, text)
        assertTrue("Wardens read it like weather. [2]" in text, text)
        assertTrue(text.lines().any { it.trim().startsWith("[1] Emberlight") }, text)
        assertTrue(text.lines().any { it.trim().startsWith("[2] The Door in the Ember") }, text)
    }

    @Test
    fun `a footer says the weaker thing, and says it separately`() {
        // *This card as a whole drew on these*, which is not what a chip says. One list
        // would make the stronger claim on the footer's behalf.
        val card = Card.Generated("The Marches are low country.", emptyList(), listOf(citation(1, "Emberlight")))
        val text = rendered(card)
        assertTrue("drawn as a whole from 1 source" in text, text)
        assertTrue("[1]" !in text, "no chip numbering where there are no chips: $text")
    }

    @Test
    fun `a quote keeps its bytes, including the ones a marker would sit beside`() {
        val body = "Difficulty 7 is ordinary — 13 is at the edge of what a person can do."
        val card = Card.Verbatim(
            ref = ChunkRef("srd:emberlight", 1), kind = "rules", body = body,
            citation = citation(1, "Emberlight"), rollable = false,
        )
        val quoted = rendered(card).lineSequence()
            .filter { it.startsWith("  \" ") }
            .joinToString("\n") { it.removePrefix("  \" ") }
        assertTrue(quoted == body, "got: $quoted")
    }
}
