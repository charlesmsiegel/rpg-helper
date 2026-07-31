package dev.rpghelper.retrieval

import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.pack.JdbcDb
import dev.rpghelper.pack.PackForge
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The lexical gate's statistics, and which corpus they are counted over.
 *
 * The gate is a *ratio* — the share of the query's information content a chunk holds — so
 * unlike a raw BM25 threshold it has no units to be wrong in. What it can still be wrong
 * about is the corpus the frequencies came from, and this is where that is pinned.
 */
class InformationGateTest {

    private val directory: Path = Files.createTempDirectory("gate")
    private val open = mutableListOf<JdbcDb>()

    @AfterTest
    fun cleanUp() {
        open.forEach { it.close() }
        directory.toFile().deleteRecursively()
    }

    private fun pack(): ActivePack {
        val db = JdbcDb.openReadOnly(PackForge.writePack(directory))
        open += db
        return ActivePack(PackForge.PACK_UID, db)
    }

    /**
     * The forge's chunk 7 is a derived summary that restates chunk 1's rule, so the two
     * hold the same distinctive words — which is exactly the shape an errata pack has.
     */
    private val duplicate = ChunkRef(PackForge.PACK_UID, 7)

    private val query = listOf(TermGroup.of("restrained"), TermGroup.of("ogre"))

    @Test
    fun `a withdrawn chunk is counted in neither the corpus size nor the frequencies`() {
        // A corrected passage and its correction say much the same thing, so a term the
        // errata touched is held by both copies. Counting the withdrawn one halves that
        // term's apparent rarity and shrinks the share the *surviving* chunk is credited
        // with -- so an errata pack could gate out the answer it was published to supply,
        // and the ratio would still look principled.
        val pack = pack()
        val counted = InformationGate.measure(pack, query).of(1)
        val excluded = InformationGate
            .measure(pack, query, SupersededSet(setOf(duplicate)))
            .of(1)

        assertTrue(
            excluded > counted,
            "withdrawing the duplicate must raise the surviving chunk's share, " +
                "got $excluded against $counted",
        )
    }

    @Test
    fun `a withdrawn chunk holds nothing, whatever its text says`() {
        // The postings come from the same exclusion, so a withdrawn chunk cannot clear the
        // gate on its own content and re-enter through a caller that forgot to filter.
        val coverage = InformationGate
            .measure(pack(), query, SupersededSet(setOf(duplicate)))
        assertEquals(0.0, coverage.of(7))
    }

    @Test
    fun `with nothing withdrawn the statistics are the whole pack`() {
        val pack = pack()
        assertEquals(
            InformationGate.measure(pack, query).of(1),
            InformationGate.measure(pack, query, SupersededSet.EMPTY).of(1),
        )
    }
}
