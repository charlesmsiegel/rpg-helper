package dev.ludex.model

import dev.ludex.pack.EmbedderContract
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EmbedderTest {

    private val embedder = HashingEmbedder(dim = 256)

    @Test
    fun `the same text embeds to the same vector`() {
        // Within a device and a build, which is all the design requires. This one happens
        // to be reproducible across devices too, which for a fixture means a committed
        // expectation stays correct.
        assertTrue(
            embedder.embed("how do I seize a creature")
                .contentEquals(embedder.embed("how do I seize a creature")),
        )
    }

    @Test
    fun `every vector has the contract's dimension and unit length`() {
        for (text in listOf("seizing", "", "   ", "a much longer passage about ash and cinders")) {
            val vector = embedder.embed(text)
            assertEquals(256, vector.size, "'$text'")
            val norm = kotlin.math.sqrt(vector.sumOf { it.toDouble() * it })
            assertTrue(abs(norm - 1.0) < 1e-5, "'$text' has norm $norm")
        }
    }

    @Test
    fun `a query of pure punctuation gets a defined vector, not a zero one`() {
        // Cosine divides by the norm, so a zero vector is not a weak signal but an
        // undefined one. Activation refuses a pack containing one for exactly that reason;
        // producing one on the query side would put the same quantity where nothing checks.
        val vector = embedder.embed("!!! ???")
        assertTrue(vector.any { it != 0f })
        assertTrue(abs(cosine(vector, vector) - 1.0) < 1e-9)
    }

    @Test
    fun `shared word stems score above unrelated text`() {
        // The limit of what a hashed-n-gram stand-in can do, stated as a test so nobody
        // mistakes it for semantics: "seize" and "seizing" are close because they share
        // letters, not because the model knows anything.
        val seizing = embedder.embed("seizing a creature")
        val seized = embedder.embed("the creature was seized")
        val ashfall = embedder.embed("ash falls for nine days in ten")

        assertTrue(
            cosine(seizing, seized) > cosine(seizing, ashfall),
            "inflections cluster; unrelated prose does not",
        )
    }

    @Test
    fun `a synonym the text never uses is not close, which is what the alias table is for`() {
        // Stated rather than left implicit: "grapple" never appears in Emberlight, and no
        // surface-overlap embedder can bridge that. The entities table closes it by
        // construction, which is why the alias rewrite is a query rewrite and not a
        // ranking tweak.
        val seizing = embedder.embed("seizing a creature")
        val grapple = embedder.embed("grapple a creature")
        val unrelated = embedder.embed("ash falls for nine days")
        assertTrue(cosine(seizing, grapple) < 0.9, "shared words only, not shared meaning")
        assertTrue(cosine(seizing, grapple) > cosine(seizing, unrelated))
    }

    @Test
    fun `case and diacritics do not change the vector`() {
        assertTrue(embedder.embed("Váss the Warden").contentEquals(embedder.embed("vass the warden")))
    }

    // ---------------------------------------------------------------- the registry

    @Test
    fun `the registry serves exactly what it bundles`() {
        // There is no way to embed a query in a space whose model you do not have, so a
        // pack naming an unbundled contract is refused at activation rather than retrieved
        // against incorrectly.
        val registry = BundledEmbedders(listOf(HashingEmbedder(64), HashingEmbedder(256)))
        assertEquals(
            setOf(EmbedderContract("dev-hashing-64", 64), EmbedderContract("dev-hashing-256", 256)),
            registry.bundled,
        )
        assertEquals(64, registry.forContract("dev-hashing-64")!!.contract.dim)
        assertNull(registry.forContract("some-real-model-v3"))
    }

    @Test
    fun `two embedders cannot claim one contract id`() {
        assertFailsWith<IllegalArgumentException> {
            BundledEmbedders(listOf(HashingEmbedder(64, "same"), HashingEmbedder(128, "same")))
        }
    }

    @Test
    fun `a query is embedded once per distinct contract, and no more`() {
        // Packs spanning contracts is the ordinary case: a pack built two years ago and one
        // built today can both be valid and name different embedders, and one query
        // embedding cannot serve both -- for differing dimensions the cosine is not even
        // computable. The cost is one inference per contract, which is the real constraint
        // keeping the supported set small.
        var calls = 0
        val counting = object : Embedder {
            override val contract = EmbedderContract("counted", 8)
            override fun embed(text: String): FloatArray {
                calls++
                return FloatArray(8) { 0.5f }
            }
        }
        val registry = BundledEmbedders(listOf(counting, HashingEmbedder(64)))

        val vectors = registry.embedPerContract(
            "how do I seize a creature",
            listOf("counted", "counted", "dev-hashing-64", "counted"),
        )

        assertEquals(setOf("counted", "dev-hashing-64"), vectors.keys)
        assertEquals(1, calls, "three packs on one contract is still one inference")
    }

    @Test
    fun `a pack naming an unbundled contract contributes no vector rather than a wrong one`() {
        val registry = BundledEmbedders(listOf(HashingEmbedder(64)))
        val vectors = registry.embedPerContract("query", listOf("dev-hashing-64", "gone-in-v3"))
        assertEquals(setOf("dev-hashing-64"), vectors.keys)
    }
}
