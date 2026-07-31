package dev.ludex.retrieval

import dev.ludex.pack.ChunkRef
import dev.ludex.pack.JdbcDb
import dev.ludex.pack.PackForge
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

    /**
     * The chunks up for admission, which is all coverage is ever asked about.
     *
     * Passed explicitly because the gate no longer materializes a posting list over the
     * whole pack: a valid book may hold half a million chunks, and a 32-term query would
     * retain millions of boxed longs to answer a question about fifty candidates.
     */
    private val upForAdmission = listOf(1L, 4L, 6L, 7L)

    @Test
    fun `a withdrawn chunk is counted in neither the corpus size nor the frequencies`() {
        // A corrected passage and its correction say much the same thing, so a term the
        // errata touched is held by both copies. Counting the withdrawn one halves that
        // term's apparent rarity and shrinks the share the *surviving* chunk is credited
        // with -- so an errata pack could gate out the answer it was published to supply,
        // and the ratio would still look principled.
        val pack = pack()
        val counted = InformationGate.measure(
            pack, query, candidates = upForAdmission,
        ).of(1)
        val excluded = InformationGate
            .measure(pack, query, SupersededSet(setOf(duplicate)), upForAdmission)
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
            .measure(pack(), query, SupersededSet(setOf(duplicate)), upForAdmission)
        assertEquals(0.0, coverage.of(7))
    }

    @Test
    fun `a chunk outside the candidate scope is never credited with anything`() {
        // Coverage is asked only about chunks up for admission. Anything else scores zero
        // by construction rather than by a filter someone downstream has to remember --
        // and asking for more is what made the gate's memory a function of the book's size
        // rather than the query's.
        val coverage = InformationGate.measure(pack(), query, candidates = listOf(4L))
        assertEquals(0.0, coverage.of(1), "chunk 1 holds 'restrained' but was not a candidate")
        assertTrue(coverage.of(4) > 0.0, "the one that was is scored normally")
    }

    @Test
    fun `with nothing withdrawn the statistics are the whole pack`() {
        val pack = pack()
        assertEquals(
            InformationGate.measure(pack, query, candidates = upForAdmission).of(1),
            InformationGate.measure(pack, query, SupersededSet.EMPTY, upForAdmission).of(1),
        )
    }
}
