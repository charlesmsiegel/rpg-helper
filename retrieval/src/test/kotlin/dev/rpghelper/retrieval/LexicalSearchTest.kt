package dev.rpghelper.retrieval

import dev.rpghelper.pack.JdbcDb
import dev.rpghelper.pack.PackForge
import dev.rpghelper.pack.exec
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LexicalSearchTest {

    private val directory: Path = Files.createTempDirectory("lexical")
    private val opened = mutableListOf<JdbcDb>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        directory.toFile().deleteRecursively()
    }

    private fun pack(uid: String = "core", mutate: (Connection) -> Unit = {}): ActivePack {
        val db = JdbcDb.openReadOnly(PackForge.writePack(directory, mutate))
        opened += db
        return ActivePack(uid, db)
    }

    private fun search(
        pack: ActivePack,
        vararg terms: String,
        superseded: SupersededSet = SupersededSet.EMPTY,
        depth: Int = LexicalSearch.DEPTH,
    ) = LexicalSearch.search(pack, LexicalQuery.build(terms.toList())!!, superseded, depth)

    @Test
    fun `a term finds the chunk holding it`() {
        val hits = search(pack(), "grapple")
        assertEquals(listOf(1L), hits.map { it.ref.chunkId })
    }

    @Test
    fun `scores are on the negated scale, where higher is better`() {
        // SQLite's bm25() is negative and more negative is better. Negating once at the
        // boundary means every later comparison reads the ordinary way; leaving the sign
        // inverted would make each of them a fresh chance to get it backwards.
        val hits = search(pack(), "restrained")
        assertTrue(hits.isNotEmpty())
        assertTrue(hits.all { it.score > 0 }, "a match scores positive: ${hits.map { it.score }}")
        assertEquals(
            hits.map { it.score }.sortedDescending(),
            hits.map { it.score },
            "and the list is best-first",
        )
    }

    @Test
    fun `a disjunction widens rather than narrowing`() {
        // Requiring a dozen incidental words to co-occur matches nothing, which is why the
        // expression is an OR. BM25 already weights rare terms above common ones.
        val pack = pack()
        val narrow = search(pack, "grapple").map { it.ref.chunkId }.toSet()
        val wide = search(pack, "grapple", "underdark", "ogre").map { it.ref.chunkId }.toSet()
        assertTrue(narrow.all { it in wide }, "widening never loses a hit")
        assertTrue(wide.size > narrow.size)
    }

    @Test
    fun `a term that is FTS5 syntax is searched for as a word`() {
        // The dangerous case: an unescaped NOT would turn a widening expansion into an
        // exclusion, returning fewer results than no expansion at all.
        val pack = pack { c ->
            c.exec("UPDATE chunks_fts SET text = text || ' NOT applicable' WHERE rowid = 3")
        }
        val hits = search(pack, "grapple", "NOT")
        assertTrue(hits.any { it.ref.chunkId == 1L }, "the grapple rule is still found")
        assertTrue(hits.any { it.ref.chunkId == 3L }, "and so is the chunk containing 'NOT'")
    }

    @Test
    fun `a superseded chunk cannot be reached by search`() {
        // The filter is applied before scoring rather than as a ranking preference: a
        // corrected rule and its original almost never tie, so the superseded text could
        // win outright while priority was never consulted.
        val pack = pack()
        val withoutFilter = search(pack, "grapple").map { it.ref.chunkId }
        assertTrue(1L in withoutFilter)

        val filtered = search(
            pack, "grapple",
            superseded = SupersededSet(setOf(ChunkRef("core", 1))),
        )
        assertTrue(1L !in filtered.map { it.ref.chunkId })
    }

    @Test
    fun `superseded chunks do not consume the depth budget`() {
        // Filtering after the LIMIT lets withdrawn passages crowd out surviving ones: a
        // common term matching more than `depth` corrected chunks pushes every good hit
        // below the limit, producing a refusal on a query the pack can answer.
        val pack = pack()
        val topTwo = search(pack, "restrained", "the", "a", depth = 2).map { it.ref.chunkId }
        assertEquals(2, topTwo.size)

        val withoutTop = search(
            pack, "restrained", "the", "a",
            superseded = SupersededSet(topTwo.map { ChunkRef("core", it) }.toSet()),
            depth = 2,
        )
        assertEquals(2, withoutTop.size, "the budget refills from below rather than shrinking")
        assertTrue(withoutTop.none { it.ref.chunkId in topTwo })
    }

    @Test
    fun `depth bounds what one pack contributes`() {
        val hits = search(pack(), "grapple", "underdark", "ogre", "cavern", depth = 2)
        assertEquals(2, hits.size)
    }

    @Test
    fun `hits carry the pack they came from`() {
        // Chunk ids are only unique within a pack, so a bare id is ambiguous the moment a
        // second book is active -- and the second book is the ordinary case.
        val hits = search(pack(uid = "adventure"), "grapple")
        assertTrue(hits.all { it.ref.packUid == "adventure" })
    }

    @Test
    fun `a query matching nothing returns nothing rather than everything`() {
        assertTrue(search(pack(), "thaumaturgy", "vinculum").isEmpty())
    }
}
