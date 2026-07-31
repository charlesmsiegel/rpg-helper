package dev.rpghelper.retrieval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FusionTest {

    private fun ref(chunkId: Long, pack: String = "core") = ChunkRef(pack, chunkId)

    private fun lexical(vararg chunkIds: Long, label: String = "core") =
        RetrievalList(ListKind.LEXICAL, label, chunkIds.map { ref(it, label) })

    private fun dense(vararg chunkIds: Long, label: String = "e8", pack: String = "core") =
        RetrievalList(ListKind.DENSE, label, chunkIds.map { ref(it, pack) })

    private fun entity(vararg chunkIds: Long, pack: String = "core") =
        RetrievalList(ListKind.ENTITY, "alias", chunkIds.map { ref(it, pack) })

    // ---------------------------------------------------------------- gating

    @Test
    fun `a group with nothing above its threshold contributes no list`() {
        // Every list has a rank 1, including a list with nothing relevant in it. Fusing
        // purely by rank would hand that list's best-of-a-bad-lot the same contribution
        // as a genuinely strong hit -- so activating more books would make results worse.
        val hits = listOf(0.31 to 1L, 0.28 to 2L)
        val kept = Fusion.gate(hits, threshold = 0.4) { it.first }
        assertTrue(kept.isEmpty())
    }

    @Test
    fun `gating is on score, not rank`() {
        // A large pack must not be able to buy position with volume.
        val hits = (1..50).map { 0.1 to it.toLong() } + (0.9 to 99L)
        val kept = Fusion.gate(hits, threshold = 0.5) { it.first }
        assertEquals(listOf(99L), kept.map { it.second })
    }

    // ---------------------------------------------------------------- collapsing

    @Test
    fun `a chunk enters the dense list once, at its best vector`() {
        // Otherwise one heavily-vectored statblock occupies a dozen consecutive dense
        // ranks and crowds the fused list with itself.
        val hits = listOf(
            VectorHit(ref(1), "content", 0.71, 0 until 100),
            VectorHit(ref(1), "content", 0.88, 80 until 200),
            VectorHit(ref(1), "expansion", 0.64, null),
            VectorHit(ref(2), "content", 0.80, 0 until 50),
        )
        val collapsed = Fusion.collapseToChunks(hits)

        assertEquals(listOf(ref(1), ref(2)), collapsed.map { it.ref })
        assertEquals(0.88, collapsed.first().score)
        assertEquals(80 until 200, collapsed.first().window, "the surviving window travels")
    }

    @Test
    fun `the collapse survivor is deterministic under ties`() {
        // Section 9.1 reads the surviving vector's window, so a dedup decision that
        // depended on iteration order would be untestable.
        val hits = listOf(
            VectorHit(ref(1), "content", 0.5, 300 until 400),
            VectorHit(ref(1), "content", 0.5, 0 until 100),
        )
        repeat(5) {
            assertEquals(0 until 100, Fusion.collapseToChunks(hits.shuffled()).single().window)
        }
    }

    // ---------------------------------------------------------------- fusion

    @Test
    fun `a chunk found by two signals outranks one found by either alone`() {
        val fused = Fusion.fuse(listOf(lexical(2, 1), dense(1, 3)))
        assertEquals(ref(1), fused.first().ref, "the only chunk both signals found")
        // Rank 2 lexically, rank 1 densely.
        assertEquals(1.0 / 62 + 1.0 / 61, fused.first().score, 1e-12)
    }

    @Test
    fun `a list a chunk is absent from costs it nothing`() {
        // No penalty term, which is what lets lists of very different lengths combine
        // without the short ones being punished for their brevity.
        val alone = Fusion.fuse(listOf(lexical(1))).single().score
        val beside = Fusion.fuse(listOf(lexical(1), dense(9)))
            .single { it.ref == ref(1) }.score
        assertEquals(alone, beside)
    }

    @Test
    fun `contribution decays with rank`() {
        val fused = Fusion.fuse(listOf(lexical(1, 2, 3)))
        assertEquals(listOf(1.0 / 61, 1.0 / 62, 1.0 / 63), fused.map { it.score })
    }

    @Test
    fun `a repeated chunk within one list is one piece of evidence`() {
        val once = Fusion.fuse(listOf(lexical(1))).single().score
        val twice = Fusion.fuse(listOf(lexical(1, 1))).single().score
        assertEquals(once, twice, "evidence recorded twice is still one piece of evidence")
    }

    // ---------------------------------------------------------------- the entity list

    @Test
    fun `every entity member sits at rank 1`() {
        // An entity match has no internal ordering -- an alias n-gram either matched or it
        // did not -- so it is a flat contribution, the same weight as topping one list.
        val fused = Fusion.fuse(listOf(lexical(1, 2), entity(2, 1)))
        val first = fused.single { it.ref == ref(1) }.score
        val second = fused.single { it.ref == ref(2) }.score
        assertEquals(1.0 / 61 + 1.0 / 61, first, 1e-12)
        assertEquals(1.0 / 62 + 1.0 / 61, second, 1e-12)
    }

    @Test
    fun `an entity hit reinforces a candidate and never introduces one`() {
        // The entity list passes through no gate, because there is no score to threshold.
        // If it could contribute candidates of its own, a query where every pack and every
        // contract gated out could still answer from a substring match alone.
        val fused = Fusion.fuse(listOf(lexical(1), entity(1, 7)))
        assertEquals(listOf(ref(1)), fused.map { it.ref }, "chunk 7 was found by nothing else")
    }

    @Test
    fun `gating out everything leaves nothing, entity list or not`() {
        // "Gating left no candidates" and "the app refuses" have to stay the same
        // statement, which they only do if the ungated list cannot stand on its own.
        assertTrue(Fusion.fuse(listOf(entity(1, 2, 3))).isEmpty())
        assertTrue(Fusion.fuse(emptyList()).isEmpty())
    }

    // ---------------------------------------------------------------- ordering

    @Test
    fun `priority breaks exact ties and nothing else`() {
        val lists = listOf(
            RetrievalList(ListKind.LEXICAL, "a", listOf(ref(1, "a"))),
            RetrievalList(ListKind.LEXICAL, "b", listOf(ref(1, "b"))),
        )
        val order = Fusion.fuse(lists) { pack -> if (pack == "b") 0 else 1 }
        assertEquals(listOf("b", "a"), order.map { it.ref.packUid })
    }

    @Test
    fun `priority does not override a difference in evidence`() {
        // A corrected rule and its original almost never tie, which is why supersession is
        // a filter rather than a priority preference -- and why priority must not be able
        // to reorder candidates that actually scored differently.
        val lists = listOf(
            RetrievalList(ListKind.LEXICAL, "a", listOf(ref(2, "a"), ref(1, "b"))),
            RetrievalList(ListKind.DENSE, "e8", listOf(ref(1, "b"))),
        )
        val order = Fusion.fuse(lists) { pack -> if (pack == "a") 0 else 9 }
        assertEquals(ref(1, "b"), order.first().ref, "two signals beat a higher priority")
    }

    @Test
    fun `the order is total, so the same inputs give the same list`() {
        val lists = listOf(lexical(3, 1, 2), dense(2, 3, 1))
        val once = Fusion.fuse(lists).map { it.ref }
        repeat(5) { assertEquals(once, Fusion.fuse(lists).map { it.ref }) }
    }

    @Test
    fun `a candidate records which lists found it`() {
        val fused = Fusion.fuse(listOf(lexical(1, label = "core"), dense(1, label = "e8")))
        assertEquals(listOf("core", "e8"), fused.single().contributions.sorted())
    }
}
