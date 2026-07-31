package dev.ludex.model

import dev.ludex.pack.EmbedderContract
import java.text.Normalizer
import kotlin.math.sqrt

/**
 * A deterministic embedder with no weights: hashed character n-grams, L2-normalized.
 *
 * **This is not a semantic model and is not presented as one.** It captures surface
 * overlap — shared word stems, shared spellings — and nothing else. *Seizing* and
 * *grappling* are unrelated to it, which is exactly the gap the `entities` alias table
 * exists to close, and one reason the alias rewrite is a query rewrite rather than a
 * ranking tweak.
 *
 * It earns its place by making the **structural tier** of the test suite complete: every
 * part of the app that does not measure retrieval *quality* — activation, routing,
 * redaction, capabilities, constraints, the whole fusion and dedup pipeline — can run
 * end to end against a real pack with real vectors, on the JVM, in milliseconds, before
 * any weights exist. The semantic tier swaps in a real contract and measures recall.
 *
 * Making that split explicit also stops a structural test quietly depending on embedding
 * quality, which would make it flaky for reasons unrelated to what it tests.
 *
 * Deterministic across devices as well as within one, which a real embedder is not
 * required to be. Nothing in the design depends on that — the answer cache keys on query
 * text rather than on vectors — but for a fixture it means a vector committed in an
 * expectation stays correct.
 */
class HashingEmbedder(dim: Int = 256, private val id: String = "dev-hashing-$dim") : Embedder {

    override val contract: EmbedderContract = EmbedderContract(id = id, dim = dim)

    /**
     * n-gram widths.
     *
     * 3 to 5 characters, over whitespace-padded tokens, so a word contributes both its
     * stem and its inflections: *seize*, *seizing*, and *seized* share most of their
     * grams while *seize* and *summit* share none.
     */
    private val widths = 3..5

    override fun embed(text: String): FloatArray {
        val accumulator = DoubleArray(contract.dim)
        val folded = fold(text)

        for (token in folded.split(' ').filter { it.isNotEmpty() }) {
            val padded = " $token "
            for (width in widths) {
                if (padded.length < width) continue
                for (start in 0..(padded.length - width)) {
                    val gram = padded.substring(start, start + width)
                    val hash = gram.hash()
                    val bucket = ((hash % contract.dim) + contract.dim) % contract.dim
                    // A sign drawn from a different bit of the same hash, so two grams
                    // colliding into one bucket cancel as often as they reinforce. Without
                    // it, collisions only ever inflate similarity.
                    accumulator[bucket] += if ((hash ushr 31) and 1 == 1) -1.0 else 1.0
                }
            }
        }

        return normalize(accumulator)
    }

    /**
     * L2-normalized, so the dot product *is* the cosine.
     *
     * A vector of exactly zero — the honest answer for a query of pure punctuation — is
     * returned as a small uniform vector instead. A zero vector is not a weak signal but
     * an undefined one: cosine divides by the norm, and activation refuses a pack
     * containing one for that reason. Producing one here would put the same undefined
     * quantity on the query side, where nothing checks for it.
     */
    private fun normalize(values: DoubleArray): FloatArray {
        var sum = 0.0
        for (value in values) sum += value * value
        val norm = sqrt(sum)
        if (norm < 1e-12) {
            val uniform = (1.0 / sqrt(values.size.toDouble())).toFloat()
            return FloatArray(values.size) { uniform }
        }
        return FloatArray(values.size) { (values[it] / norm).toFloat() }
    }

    /** NFC, diacritics stripped, lowercased, non-alphanumerics to spaces. */
    private fun fold(text: String): String {
        val decomposed = Normalizer.normalize(text, Normalizer.Form.NFD)
        val stripped = buildString(decomposed.length) {
            for (c in decomposed) {
                if (Character.getType(c) == Character.NON_SPACING_MARK.toInt()) continue
                append(if (c.isLetterOrDigit()) c else ' ')
            }
        }
        return Normalizer.normalize(stripped, Normalizer.Form.NFC).lowercase()
    }

    /**
     * FNV-1a, written out rather than taken from `String.hashCode`.
     *
     * `hashCode` is specified for `String` and stable in practice, but a fixture's vectors
     * are only reproducible if the hash is *this* project's, pinned here where a change to
     * it is a visible commit rather than a platform detail.
     */
    private fun String.hash(): Int {
        var hash = -0x7ee3623b // 2166136261 as a signed Int
        for (c in this) {
            hash = hash xor c.code
            hash *= 0x01000193
        }
        return hash
    }
}

/** Cosine of two vectors of equal length. Both sides are unit-length, so this is a dot product. */
fun cosine(a: FloatArray, b: FloatArray): Double {
    require(a.size == b.size) { "cannot compare a ${a.size}-vector with a ${b.size}-vector" }
    var dot = 0.0
    var normA = 0.0
    var normB = 0.0
    for (i in a.indices) {
        dot += a[i].toDouble() * b[i]
        normA += a[i].toDouble() * a[i]
        normB += b[i].toDouble() * b[i]
    }
    val denominator = sqrt(normA) * sqrt(normB)
    return if (denominator < 1e-12) 0.0 else dot / denominator
}
