package dev.ludex.routing

import dev.ludex.pack.ChunkRef
import dev.ludex.pack.JdbcDb
import dev.ludex.pack.PackForge
import dev.ludex.pack.exec
import dev.ludex.retrieval.ActivePack
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The resolvers, against a real pack rather than a lambda.
 *
 * Every card carries a citation, and until these existed the resolver interfaces had no
 * implementation but a test double — which is a comfortable place for a design to sit,
 * because the awkward cases never come up.
 */
class PackResolversTest {

    private val directory: Path = Files.createTempDirectory("resolvers")
    private val open = mutableListOf<JdbcDb>()

    @AfterTest
    fun cleanUp() {
        open.forEach { it.close() }
        directory.toFile().deleteRecursively()
    }

    private fun pack(mutate: (java.sql.Connection) -> Unit = {}): ActivePack {
        val db = JdbcDb.openReadOnly(PackForge.writePack(directory, mutate))
        open += db
        return ActivePack(PackForge.PACK_UID, db)
    }

    private fun ref(id: Long) = ChunkRef(PackForge.PACK_UID, id)

    @Test
    fun `a quotable chunk resolves to its book, heading and page range`() {
        val citation = PackCitations(listOf(pack())).resolve(ref(1))!!
        assertEquals("Test Core Rulebook", citation.sourceTitle)
        assertEquals("Combat > Grappling", citation.headingPath)
        assertEquals("42", citation.pageLabelStart)
        assertEquals("43", citation.pageLabelEnd)
        assertEquals("page", citation.locatorScheme)
        assertEquals("Test Core Rulebook (1e), Combat > Grappling, p. 42–43", render(citation))
    }

    @Test
    fun `a derived chunk has no citation of its own`() {
        // Its citation must come from the chunks it was built from. Answering here would
        // let one drift from them, which is the failure `chunk_derivation` exists to
        // prevent -- so the derived card resolves through its cited chunks instead.
        assertNull(PackCitations(listOf(pack())).resolve(ref(7)))
    }

    @Test
    fun `a ref naming a pack that is not active resolves to nothing`() {
        // The active set can change between a query and the render of its answer, and a
        // citation that survived that would name a book the user has since deactivated.
        val citations = PackCitations(listOf(pack()))
        assertNull(citations.resolve(ChunkRef("some:other:pack", 1)))
    }

    @Test
    fun `a chunk that is not in the pack resolves to nothing rather than a partial citation`() {
        assertNull(PackCitations(listOf(pack())).resolve(ref(999)))
    }

    @Test
    fun `derivations come back in a stable order, claim spans and all`() {
        // The order decides the footer's order on screen, and a citation list that
        // reshuffles between two renders of one answer reads as two different answers.
        val rows = PackDerivations(listOf(pack())).derivationsOf(ref(7))
        assertEquals(2, rows.size)
        assertTrue(rows.any { it.claimStart != null }, "one row anchors a claim")
        assertTrue(rows.any { it.claimStart == null }, "one row is a whole-chunk footer")
        assertEquals(rows, PackDerivations(listOf(pack())).derivationsOf(ref(7)))
    }

    @Test
    fun `a chunk with no derivation rows yields none`() {
        assertEquals(emptyList(), PackDerivations(listOf(pack())).derivationsOf(ref(1)))
    }

    @Test
    fun `the whole route renders from the pack, with no hand-written citation anywhere`() {
        // The end-to-end shape: a quotable chunk in, a card with a real citation out.
        val active = pack()
        val router = Router(
            PackCitations(listOf(active)),
            PackDerivations(listOf(active)),
            LoadedRollables(setOf(ref(2))),
        )
        val answer = router.route(
            dev.ludex.retrieval.Retrieved(
                "how does grappling work",
                listOf("grappling"),
                listOf(
                    dev.ludex.retrieval.Candidate(
                        ref = ref(1), kind = "rules", origin = "source", stableKey = "core:grapple",
                        sourceUid = PackForge.CORE_SOURCE_UID, headingPath = "Combat > Grappling",
                        text = "Grappling.", score = 1.0, contributions = listOf("lexical"),
                        entityHit = false, denseWindow = null, redact = emptyList(),
                        redactedText = "Grappling.",
                    ),
                ),
                emptyList(),
            ),
            generator = null,
            activePacks = listOf(PackForge.PACK_UID),
        )
        val card = answer.cards.single() as Card.Verbatim
        assertEquals("Test Core Rulebook", card.citation.sourceTitle)
        assertTrue(card.copyText().endsWith("— ${render(card.citation)}"))
    }

    @Test
    fun `a chunk whose source row is missing is not cited at all`() {
        // Fail closed rather than render a card naming an unknown book: an attributed card
        // with a broken citation is indistinguishable to a user from a working one.
        val active = pack { c -> c.exec("UPDATE chunks SET source_id = NULL WHERE chunk_id = 1") }
        assertNull(PackCitations(listOf(active)).resolve(ref(1)))
    }
}
