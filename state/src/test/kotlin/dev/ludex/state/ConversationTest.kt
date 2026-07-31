package dev.ludex.state

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConversationTest {

    private val root: Path = Files.createTempDirectory("feed")
    private var db = StateDb.open(root.resolve("state.db"))
    private var tick = 0
    private var conversation = Conversation(db) { "2026-07-31T00:00:%02dZ".format(tick++) }

    @AfterTest
    fun cleanUp() {
        db.close()
        root.toFile().deleteRecursively()
    }

    private fun ask(query: String) = conversation.record(query, "CARDS for $query")

    @Test
    fun `the feed is in ask order`() {
        ask("how does seizing work")
        ask("what about at level 5")
        assertEquals(
            listOf("how does seizing work", "what about at level 5"),
            conversation.feed().map { it.query },
        )
    }

    @Test
    fun `normalization sees at most three turns, oldest first`() {
        // Bounded so a four-hour session does not grow an unbounded prompt. Oldest first
        // because a window handed to a model backwards resolves "what about at level 5?"
        // against the wrong question -- a wrong answer with no symptom.
        repeat(5) { ask("question $it") }
        assertEquals(
            listOf("question 2", "question 3", "question 4"),
            conversation.window().map { it.query },
        )
    }

    @Test
    fun `a feed shorter than the window is the whole feed`() {
        ask("only one")
        assertEquals(listOf("only one"), conversation.window().map { it.query })
    }

    @Test
    fun `new topic clears the feed and the context together`() {
        // One control, one meaning, nothing hidden. A clear that emptied the window and
        // left the feed -- or the reverse -- would be the hidden context this design
        // exists not to have.
        repeat(3) { ask("question $it") }
        conversation.newTopic()
        assertEquals(emptyList(), conversation.feed())
        assertEquals(emptyList(), conversation.window())
    }

    @Test
    fun `the feed survives a restart`() {
        // Backgrounding the app mid-lookup at a table must not lose the thread.
        ask("how does seizing work")
        db.close()
        db = StateDb.open(root.resolve("state.db"))
        conversation = Conversation(db)
        assertEquals(listOf("how does seizing work"), conversation.feed().map { it.query })
    }

    @Test
    fun `a quote card round-trips byte for byte through the feed`() {
        // The feed is a second path by which a quotation reaches the screen. The
        // verbatim-identity property covers rendering from the pack and has to cover this
        // too -- including the characters a careless serializer eats.
        val quote = "Difficulty 7 is ordinary — 13 is the edge.\n\n\td6 | Mishap\n1 | \"Shaken\"\\"
        val turn = conversation.record("what is the hardest difficulty", quote)
        assertEquals(quote, conversation.feed().single().cards)
        assertEquals(quote, turn.cards)
    }

    @Test
    fun `a recorded turn carries the time it was asked`() {
        val turn = ask("when")
        assertTrue(turn.askedAt.startsWith("2026-07-31T"), turn.askedAt)
        assertEquals(turn, conversation.feed().single())
    }
}
