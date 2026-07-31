package dev.ludex.session

import dev.ludex.builder.CorpusSpec
import dev.ludex.builder.PackBuilder
import dev.ludex.model.Attribution
import dev.ludex.model.Availability
import dev.ludex.model.CardSummary
import dev.ludex.model.GeneratedAnswer
import dev.ludex.model.Generator
import dev.ludex.model.ImageBuffer
import dev.ludex.model.RedactedChunk
import dev.ludex.model.Turn
import dev.ludex.routing.Card
import dev.ludex.state.AnswerCache
import dev.ludex.state.Conversation
import dev.ludex.state.StateDb
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * One question, end to end, with the state around it.
 *
 * The pieces below each have their own tests; what only shows up here is the *order* they
 * are touched in — which is where a cache stops saving anything and a feed starts holding
 * turns for answers that never appeared.
 */
class AskServiceTest {

    private val root: Path = Files.createTempDirectory("ask")
    private val db = StateDb.open(root.resolve("state.db"))
    private var tick = 0
    private val clock = { "2026-07-31T00:00:%02dZ".format(tick++) }
    private val cache = AnswerCache(db, clock = clock)
    private val conversation = Conversation(db, clock)
    private val service = AskService(cache, conversation)

    @AfterTest
    fun cleanUp() {
        db.close()
        root.toFile().deleteRecursively()
    }

    private val pack: Path by lazy {
        val target = root.resolve("srd.rpgpack")
        PackBuilder(CorpusSpec.load(corpusDirectory()), EMBEDDER).buildTo(target).path
    }

    private fun corpusDirectory(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null) {
            val corpus = candidate.resolve("corpus/srd")
            if (Files.isDirectory(corpus)) return corpus
            candidate = candidate.parent
        }
        error("corpus/srd not found above ${Path.of("").toAbsolutePath()}")
    }

    /** Counts calls, so "the model did not run" is asserted rather than assumed. */
    private class CountingGenerator(
        /** What `normalize` resolves an elliptical follow-up to. */
        private val resolveTo: String? = null,
    ) : Generator {
        var answers = 0
        var normalized: Pair<String, List<Turn>>? = null
        override val availability = Availability.Ready
        override fun normalize(query: String, history: List<Turn>): String {
            normalized = query to history
            return resolveTo ?: query
        }
        override fun residualIntent(query: String, covered: List<CardSummary>) = query
        override fun answer(residual: String, context: List<RedactedChunk>): GeneratedAnswer {
            answers++
            return GeneratedAnswer("The Marches are low country.", emptyList<Attribution>())
        }
        override fun describeImage(image: ImageBuffer): String = error("not under test")
    }

    private fun <T> withLibrary(body: (Library) -> T): T =
        Library.open(listOf(pack)).use(body)

    @Test
    fun `a rules question answers and lands in the feed`() {
        val asked = withLibrary { service.ask("how do I grapple someone", it) }
        assertTrue(asked.answer!!.cards.first() is Card.Verbatim)
        assertEquals(listOf("how do I grapple someone"), conversation.feed().map { it.query })
        assertEquals(asked.rendered, conversation.feed().single().cards)
    }

    @Test
    fun `a rules answer is not cached, because a quote card is a database read`() {
        withLibrary { service.ask("how do I grapple someone", it) }
        assertEquals(0, cache.size())
    }

    @Test
    fun `the second identical setting question does not wake the model`() {
        // The point of the cache, and the reason it is consulted *before* routing: routing
        // is what invokes the model, so a cache checked afterwards has already paid the
        // cost it exists to avoid.
        val generator = CountingGenerator()
        withLibrary {
            service.ask("what lives in the cinder marches", it, generator, modelId = "gemma-q4")
        }
        assertEquals(1, generator.answers)
        assertEquals(1, cache.size())

        val again = withLibrary {
            service.ask("what lives in the cinder marches", it, generator, modelId = "gemma-q4")
        }
        assertEquals(1, generator.answers, "the model ran once, for two identical questions")
        assertTrue(again.fromCache)
        assertNull(again.answer, "a hit returns the render stored at the time, not fresh cards")
    }

    @Test
    fun `a hit renders exactly what the miss rendered`() {
        // A cache hit and a fresh generation have to be indistinguishable on screen.
        val generator = CountingGenerator()
        val first = withLibrary {
            service.ask("what lives in the cinder marches", it, generator, modelId = "gemma-q4")
        }
        val second = withLibrary {
            service.ask("what lives in the cinder marches", it, generator, modelId = "gemma-q4")
        }
        assertEquals(first.rendered, second.rendered)
        assertEquals(2, conversation.feed().size, "and both turns are in the feed")
    }

    @Test
    fun `a different model is a different entry`() {
        val generator = CountingGenerator()
        withLibrary {
            service.ask("what lives in the cinder marches", it, generator, modelId = "gemma-q4")
        }
        withLibrary {
            service.ask("what lives in the cinder marches", it, generator, modelId = "gemma-q8")
        }
        assertEquals(2, generator.answers, "the same weights quantized differently answer differently")
        assertEquals(2, cache.size())
    }

    @Test
    fun `nothing is cached when there is no model to save the cost of`() {
        withLibrary { service.ask("what lives in the cinder marches", it, generator = null) }
        assertEquals(0, cache.size())
        assertEquals(1, conversation.feed().size, "but the turn is still in the feed")
    }

    @Test
    fun `a refusal is recorded in the feed like any other turn`() {
        // The feed is the conversational window, and a question that found nothing is
        // still a question the next follow-up resolves against.
        val asked = withLibrary { service.ask("how much does a warhorse cost", it) }
        assertTrue(asked.answer!!.refused)
        assertEquals(1, conversation.feed().size)
    }

    // ---------------------------------------------------------------- follow-ups

    @Test
    fun `an elliptical follow-up is resolved against the window before retrieval`() {
        // "what about seizing?" is not a query; it is a query minus its subject, and the
        // subject is in the feed. Sending it unresolved searches a fragment -- and stores
        // that fragment as the resolved query in the cache key, so the same wording asked
        // after a different conversation can be served the first conversation's card.
        val generator = CountingGenerator(resolveTo = "how do I grapple someone")
        withLibrary { service.ask("what is the hardest difficulty", it, generator) }
        val asked = withLibrary { service.ask("what about seizing?", it, generator) }

        val (query, history) = generator.normalized!!
        assertEquals("what about seizing?", query)
        assertEquals(
            listOf("what is the hardest difficulty"),
            history.map { it.query },
            "resolved against the window, which is the feed",
        )
        assertTrue(
            asked.answer!!.cards.any { it is Card.Verbatim },
            "and the resolved query is what retrieval ran: ${asked.answer!!.cards}",
        )
    }

    @Test
    fun `a self-contained question never wakes the model to rewrite it`() {
        val generator = CountingGenerator()
        withLibrary { service.ask("how do I grapple someone", it, generator) }
        withLibrary { service.ask("what is the hardest difficulty", it, generator) }
        assertNull(generator.normalized, "neither question is elliptical")
    }

    @Test
    fun `the first question is never a follow-up, however it is phrased`() {
        // With an empty feed there is nothing to resolve *against*, so a rewrite would be
        // the model inventing a subject.
        val generator = CountingGenerator(resolveTo = "something invented")
        withLibrary { service.ask("what about at level 5?", it, generator) }
        assertNull(generator.normalized)
    }

    @Test
    fun `with no model the follow-up is searched as typed`() {
        // A rewrite is a generative call. Without one the honest behaviour is to search
        // what the user actually typed, not to guess at what they meant.
        withLibrary { service.ask("how do I grapple someone", it) }
        val asked = withLibrary { service.ask("what about seizing?", it) }
        assertEquals("what about seizing?", asked.resolvedQuery)
    }

    @Test
    fun `the window is the last three turns of the feed`() {
        withLibrary { library ->
            repeat(4) { service.ask("question $it", library) }
        }
        assertEquals(4, conversation.feed().size)
        assertEquals(
            listOf("question 1", "question 2", "question 3"),
            conversation.window().map { it.query },
        )
    }
}
