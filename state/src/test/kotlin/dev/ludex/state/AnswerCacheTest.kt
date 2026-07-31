package dev.ludex.state

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cache, and the reason it needs no invalidation logic.
 *
 * Every test here is a way the *key* must change. Each one that did not would be a cached
 * card served for a question it does not answer — with well-formed citations, and
 * indistinguishable on screen from a fresh generation.
 */
class AnswerCacheTest {

    private val root: Path = Files.createTempDirectory("cache")
    private val db = StateDb.open(root.resolve("state.db"))

    private var tick = 0
    private val cache = AnswerCache(db, clock = { "2026-07-31T00:00:%02dZ".format(tick++) })

    @AfterTest
    fun cleanUp() {
        db.close()
        root.toFile().deleteRecursively()
    }

    private val core = CachedPack("srd:emberlight", "a".repeat(64))
    private val adventure = CachedPack("srd:door", "b".repeat(64))

    private fun key(
        query: String = "what lives in the cinder marches",
        packs: List<CachedPack> = listOf(core, adventure),
        modelId: String = "gemma-3n-e2b-q4",
        contract: Int = 1,
    ) = CacheKey(query, packs, modelId, contract)

    @Test
    fun `a card comes back under the key it went in under`() {
        cache.put(key(), "GENERATED: low country, ash falls")
        assertEquals("GENERATED: low country, ash falls", cache.get(key()))
    }

    @Test
    fun `a different resolved query is a different entry`() {
        // The key is the query retrieval *ran*, not the one typed. Two follow-ups reading
        // "what about at level 5?" resolve differently after a grappling question and
        // after a stealth one, and keying on the typed text serves the second user the
        // first conversation's card.
        cache.put(key(), "marches")
        assertNull(cache.get(key(query = "how does seizing work")))
    }

    @Test
    fun `replacing a pack with a rebuild that kept its version is a different entry`() {
        // The case the declared version cannot see: installing a pack whose uid already
        // exists replaces it, and nothing requires a corrected rebuild to bump
        // pack_version. file_sha256 changes whenever the bytes do.
        cache.put(key(), "marches")
        val corrected = core.copy(fileSha256 = "c".repeat(64))
        assertNull(cache.get(key(packs = listOf(corrected, adventure))))
    }

    @Test
    fun `deactivating a pack, and reordering priority, each change the key`() {
        cache.put(key(), "marches")
        assertNull(cache.get(key(packs = listOf(core))), "a deactivated pack")
        assertNull(cache.get(key(packs = listOf(adventure, core))), "a reordered one")
    }

    @Test
    fun `an errata pack that supersedes nothing visible still changes the key`() {
        // Supersession is a function of the active set, so the fingerprint carries it:
        // answers generated before a correction cannot be served after it, with no
        // invalidation logic anywhere.
        cache.put(key(), "the uncorrected answer")
        val errata = CachedPack("srd:emberlight:errata", "d".repeat(64))
        assertNull(cache.get(key(packs = listOf(core, adventure, errata))))
    }

    @Test
    fun `a new model, or a new quantization, is a different entry`() {
        cache.put(key(), "marches")
        assertNull(cache.get(key(modelId = "gemma-3n-e4b-q4")))
        assertNull(cache.get(key(modelId = "gemma-3n-e2b-q8")), "the same weights, quantized differently")
    }

    @Test
    fun `bumping the contract version strands every entry`() {
        // Cards are stored already rendered, so without this an upgraded app keeps serving
        // answers built under rules it no longer follows. Everything else in the key
        // describes the inputs; this is the only thing describing the code.
        cache.put(key(), "rendered under contract 1")
        assertNull(cache.get(key(contract = 2)))
    }

    @Test
    fun `two keys cannot collide by moving a separator`() {
        // Joined by a delimiter, a pack uid or a query containing that delimiter lets two
        // different keys encode identically -- a collision that serves one question's card
        // for another, which is exactly what the key exists to prevent.
        val a = CacheKey("x", listOf(CachedPack("p:q", "0".repeat(64))), "m", 1)
        val b = CacheKey("x", listOf(CachedPack("p", "q" + "0".repeat(63))), "m", 1)
        assertNotEquals(a.hash(), b.hash())
    }

    @Test
    fun `the least recently used entry goes first, and ties break the same way twice`() {
        val bounded = AnswerCache(
            db,
            AnswerCache.Limits(entries = 2, bytes = Long.MAX_VALUE),
            clock = { "2026-07-31T00:00:%02dZ".format(tick++) },
        )
        bounded.put(key(query = "first"), "1")
        bounded.put(key(query = "second"), "2")
        bounded.get(key(query = "first")) // touched, so "second" is now the oldest
        bounded.put(key(query = "third"), "3")

        assertEquals(2, bounded.size())
        assertEquals("1", bounded.get(key(query = "first")))
        assertEquals("3", bounded.get(key(query = "third")))
        assertNull(bounded.get(key(query = "second")))
    }

    @Test
    fun `the byte bound is enforced as well as the count`() {
        // A count alone lets a few enormous cards fill the disk. Both dimensions matter,
        // and neither is the interesting one on its own.
        val bounded = AnswerCache(
            db,
            AnswerCache.Limits(entries = 100, bytes = 300),
            clock = { "2026-07-31T00:00:%02dZ".format(tick++) },
        )
        repeat(5) { bounded.put(key(query = "query $it"), "x".repeat(100)) }
        assertTrue(bounded.bytes() <= 300, "held to ${bounded.bytes()} bytes")
        assertEquals(3, bounded.size())
    }

    @Test
    fun `writing the same key twice replaces rather than accumulates`() {
        cache.put(key(), "first render")
        cache.put(key(), "second render")
        assertEquals(1, cache.size())
        assertEquals("second render", cache.get(key()))
    }

    @Test
    fun `clearing empties it`() {
        cache.put(key(), "marches")
        cache.clear()
        assertEquals(0, cache.size())
        assertNull(cache.get(key()))
    }
}
