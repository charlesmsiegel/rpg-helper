package dev.rpghelper.session

import dev.rpghelper.builder.CorpusSpec
import dev.rpghelper.builder.PackBuilder
import dev.rpghelper.model.Attribution
import dev.rpghelper.model.Availability
import dev.rpghelper.model.CardSummary
import dev.rpghelper.model.GeneratedAnswer
import dev.rpghelper.model.Generator
import dev.rpghelper.model.ImageBuffer
import dev.rpghelper.model.RedactedChunk
import dev.rpghelper.model.Turn
import dev.rpghelper.routing.Card
import dev.rpghelper.state.AnswerCache
import dev.rpghelper.state.Conversation
import dev.rpghelper.state.StateDb
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
    private class CountingGenerator : Generator {
        var answers = 0
        override val availability = Availability.Ready
        override fun normalize(query: String, history: List<Turn>) = query
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
