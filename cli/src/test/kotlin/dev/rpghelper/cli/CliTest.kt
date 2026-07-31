package dev.rpghelper.cli

import dev.rpghelper.builder.CorpusSpec
import dev.rpghelper.builder.PackBuilder
import dev.rpghelper.retrieval.Pipeline
import dev.rpghelper.routing.Card
import dev.rpghelper.routing.LoadedRollables
import dev.rpghelper.routing.PackCitations
import dev.rpghelper.routing.PackDerivations
import dev.rpghelper.routing.Router
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The wiring, end to end: a corpus directory in, cards out.
 *
 * Every module below has its own tests and every one of them injects the module beside
 * it. This is the only place the real seams are crossed — the builder's output read by the
 * app's validator, retrieval's candidates routed with citations resolved from the pack the
 * builder wrote — and it is where a mismatch between two correct halves shows up.
 */
class CliTest {

    private val pack: Path by lazy {
        val target = Files.createTempDirectory("cli").resolve("srd.rpgpack")
        target.toFile().deleteOnExit()
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

    private fun ask(question: String) = Library.open(listOf(pack)).use { library ->
        Router(
            PackCitations(library.packs),
            PackDerivations(library.packs),
            LoadedRollables(library.rollableChunks),
        ).route(
            Pipeline.retrieve(
                question,
                library.active,
                gates = GATES,
                embed = { rewritten, contracts -> BUNDLED.embedPerContract(rewritten, contracts) },
            ),
            generator = null,
            activePacks = library.packs.map { it.packUid },
        )
    }

    @Test
    fun `a rules question answers with the book's own words and a real citation`() {
        // Nothing here is hand-written: the quote is the builder's bytes and the citation
        // is resolved out of the same file's `sources` and page labels.
        val card = ask("how do I grapple someone").cards.first() as Card.Verbatim
        assertTrue(card.body.startsWith("Seizing."), card.body)
        assertEquals("Emberlight", card.citation.sourceTitle)
        assertEquals("How to Play > Seizing", card.citation.headingPath)
        assertTrue(card.copyText().endsWith("p. 2–3"), card.copyText())
    }

    @Test
    fun `a lore question with no model downloaded lists passages rather than paraphrasing`() {
        // The state the app is in before a download, reached through the ordinary path
        // rather than a mode built for the command line -- and the reason it is worth
        // asserting is that the wrong answer here is prose, which would look like success.
        val cards = ask("what lives in the cinder marches").cards
        val listed = cards.filterIsInstance<Card.ModelUnavailable>().single()
        assertTrue(listed.wouldHaveUsed.isNotEmpty())
        assertTrue(cards.none { it is Card.Generated }, "no model, no generated card")
        assertTrue(
            listed.statement.contains("has not been downloaded"),
            listed.statement,
        )
    }

    @Test
    fun `a question the pack cannot answer refuses`() {
        val answer = ask("how much does a warhorse cost")
        assertTrue(answer.refused, "${answer.cards}")
        assertTrue(answer.diagnostics.any { "gated out" in it })
    }

    @Test
    fun `the rendered quote reproduces the stored bytes exactly, gutter aside`() {
        // Byte-exactness that survives the database and dies in the view layer is not
        // byte-exactness, so the renderer is held to it too.
        val card = ask("what is the hardest difficulty").cards.first() as Card.Verbatim
        val rendered = renderAnswer(ask("what is the hardest difficulty"), diagnostics = false)
        val quoted = rendered.lineSequence()
            .filter { it.startsWith("  \" ") }
            .joinToString("\n") { it.removePrefix("  \" ") }
        assertEquals(card.body, quoted)
    }

    @Test
    fun `the roll controls the tool offers are the ones capabilities loaded`() {
        Library.open(listOf(pack)).use { library ->
            val tables = library.rollables.getValue("srd:emberlight")
            assertEquals(setOf("Seizing mishaps", "Marches rumours"), tables.map { it.label }.toSet())
            assertEquals(
                tables.map { it.chunkId }.toSet(),
                library.rollableChunks.map { it.chunkId }.toSet(),
            )
        }
    }

    @Test
    fun `an installed library answers from its active set, and only from that`() {
        // The app's own path: priority as `installed_packs` records it, and a pack absent
        // because the user deactivated it rather than because it was not typed. A newly
        // installed pack arrives inactive, since a pack can claim any uid it likes and
        // arriving active would let an imported file become live content with nobody
        // deciding anything.
        Store(Files.createTempDirectory("store")).use { store ->
            val installed = store.library.install(pack)
            assertTrue(installed is dev.rpghelper.state.InstallResult.Installed, "$installed")
            val id = installed.pack.installId

            Library.openActive(store.library).use { assertTrue(it.packs.isEmpty()) }

            store.library.setActive(id, true)
            Library.openActive(store.library).use { library ->
                assertEquals(listOf("srd:emberlight"), library.packs.map { it.packUid })
            }
        }
    }

    @Test
    fun `two packs claiming one uid cannot be active together`() {
        // Everything downstream identifies a chunk by (pack_uid, chunk_id), so two
        // editions of one book merge on equal chunk ids -- and a card can render one
        // version's text beneath the other version's citation.
        val failure = runCatching { Library.open(listOf(pack, pack)) }.exceptionOrNull()
        assertTrue(failure != null, "a duplicate uid must be refused")
        assertTrue(failure.message!!.contains("srd:emberlight"), failure.message!!)
    }

    @Test
    fun `a file that is not a pack is refused rather than half-opened`() {
        val bogus = Files.createTempFile("not-a-pack", ".rpgpack")
        Files.writeString(bogus, "certainly not SQLite")
        val failure = runCatching { Library.open(listOf(bogus)) }.exceptionOrNull()
        assertTrue(failure != null, "a non-pack must not open")
        Files.deleteIfExists(bogus)
    }
}
