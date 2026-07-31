package dev.ludex.retrieval

import dev.ludex.pack.ChunkRef
import dev.ludex.pack.Db
import dev.ludex.pack.PackForge
import dev.ludex.pack.Sqlite
import dev.ludex.pack.map
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Supersession reaches SQL through a relation, not through the text of the query.
 *
 * A user who accepts a broad replacement can leave a valid active set holding hundreds of
 * thousands of superseded chunks. Every lexical search used to paste that whole set into a
 * `NOT IN (...)` literal, and `InformationGate.measure` rebuilt the same literal twice per
 * term — so a 32-term question built and reparsed a multi-megabyte SQL string sixty-four
 * times, and retrieval got slower in proportion to how many corrections the user had
 * accepted. That is the pack library working exactly as designed and making the app
 * unusable, which is why the size is the test rather than the correctness.
 */
class SupersessionScaleTest {

    private val directory: Path = Files.createTempDirectory("supersession-scale")
    private val opened = mutableListOf<Db>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        directory.toFile().deleteRecursively()
    }

    private fun pack(): ActivePack {
        val db = Sqlite.openReadOnly(PackForge.writePack(directory))
        opened += db
        return ActivePack("core", db)
    }

    /** A withdrawal set far larger than the pack, as an accepted replacement edition is. */
    private val huge = SupersededSet(
        (1L..200_000L).mapTo(mutableSetOf()) { ChunkRef("core", it) },
    )

    @Test
    fun `a six-figure withdrawal set still answers, and still withdraws`() {
        val pack = pack()
        val query = LexicalQuery.build(listOf("grapple"))!!
        assertEquals(listOf(1L), LexicalSearch.search(pack, query).map { it.ref.chunkId })
        assertTrue(
            LexicalSearch.search(pack, query, huge).isEmpty(),
            "a withdrawn chunk must not surface, however many others are withdrawn with it",
        )
    }

    @Test
    fun `the set is materialized as a relation, once`() {
        // What matters is that the *second* query costs nothing extra: the table is rebuilt
        // only when the active set changes, which is the thing an inline literal could not
        // express -- it was rebuilt per query, and per term within a query.
        val pack = pack()
        val query = LexicalQuery.build(listOf("creature"))!!
        val first = LexicalSearch.search(pack, query, huge)
        val second = LexicalSearch.search(pack, query, huge)
        assertEquals(first.map { it.ref }, second.map { it.ref })
        assertEquals(
            200_000L,
            pack.db.map("SELECT count(*) FROM temp.withdrawn") { it.long(0) }.single(),
            "the withdrawn ids belong in a relation, not in the query string",
        )
    }

    @Test
    fun `an empty withdrawal set builds no relation at all`() {
        // The ordinary case, which is every user who has installed no errata: no DDL, no
        // subquery, and a `WHERE` clause identical to the one that existed before any of
        // this.
        val pack = pack()
        LexicalSearch.search(pack, LexicalQuery.build(listOf("grapple"))!!)
        assertEquals(
            0L,
            pack.db.map("SELECT count(*) FROM sqlite_temp_master WHERE name = 'withdrawn'") {
                it.long(0)
            }.single(),
        )
    }

    @Test
    fun `the gate counts over the same corpus the search does`() {
        // Withdrawn chunks leave the statistics, not only the results: a corrected passage
        // and its correction say much the same thing, so counting the withdrawn copy halves
        // the term's apparent rarity and shrinks the share the surviving chunk is credited
        // with -- letting an errata pack gate out the answer it was published to supply.
        val pack = pack()
        val groups = listOf(TermGroup(listOf(listOf("grapple"))))
        val whole = InformationGate.measure(pack, groups, candidates = listOf(1L))
        val corrected = InformationGate.measure(pack, groups, huge, candidates = listOf(1L))

        assertTrue(whole.queryInformation > 0.0, "the term carries information in the corpus")
        assertEquals(
            0.0,
            corrected.queryInformation,
            "with every chunk withdrawn there is no corpus left to be informative about",
        )
    }
}
