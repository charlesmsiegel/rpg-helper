package dev.rpghelper.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Properties the probe constant must have to do its job.
 *
 * Without these, "the pack carries a probe vector" is a claim about a magic number
 * nobody can check. A probe of all 1.0s would look entirely reasonable in review and
 * would be invisible to byte-swapping, since 0x3C00 and its swap are both perfectly
 * decodable numbers -- it would just silently stop catching the thing it exists for.
 */
class ProbeVectorTest {

    private fun bits(value: Float): Int = Float16.fromFloat(value).toInt() and 0xFFFF

    private fun byteSwap(pattern: Int): Int = ((pattern and 0xFF) shl 8) or (pattern ushr 8)

    private fun isNonFinite(pattern: Int): Boolean = ((pattern ushr 10) and 0x1F) == 0x1F

    @Test
    fun `every element is exactly representable in binary16`() {
        for (value in ProbeVector.VALUES) {
            assertEquals(
                value,
                Float16.toFloat(Float16.fromFloat(value)),
                "$value is not exactly representable, so the comparison would need a tolerance",
            )
        }
    }

    @Test
    fun `reading the probe big-endian produces non-finite values`() {
        // This is the property the whole mechanism rests on. Byte length cannot detect
        // byte order, so the probe must decode to something unmistakably wrong.
        val nonFinite = ProbeVector.VALUES.count { isNonFinite(byteSwap(bits(it))) }
        assertTrue(nonFinite > 0, "no element becomes NaN or infinity when byte-swapped")
    }

    @Test
    fun `no element survives a byte swap unchanged`() {
        for (value in ProbeVector.VALUES) {
            val pattern = bits(value)
            assertFalse(
                pattern == byteSwap(pattern),
                "$value is byte-swap invariant and contributes nothing to the check",
            )
        }
    }

    @Test
    fun `contains no zero, which would be invisible to byte order`() {
        assertFalse(ProbeVector.VALUES.any { it == 0.0f || it == -0.0f })
    }

    @Test
    fun `is not a palindrome, so element order is checked too`() {
        assertFalse(ProbeVector.VALUES.contentEquals(ProbeVector.VALUES.reversedArray()))
    }

    @Test
    fun `length does not depend on embedder dimension`() {
        // A dim-sized probe could be satisfied by handing back one of the pack's own
        // vectors; a fixed 16 elements cannot.
        assertEquals(32, ProbeVector.BYTE_LENGTH)
        assertEquals(ProbeVector.BYTE_LENGTH, ProbeVector.canonicalBytes().size)
    }

    @Test
    fun `accepts its own canonical encoding`() {
        assertTrue(ProbeVector.matches(ProbeVector.canonicalBytes()))
    }

    @Test
    fun `rejects a byte-swapped encoding`() {
        val swapped = ProbeVector.canonicalBytes().let { bytes ->
            ByteArray(bytes.size) { i -> if (i % 2 == 0) bytes[i + 1] else bytes[i - 1] }
        }
        assertFalse(ProbeVector.matches(swapped))
    }

    @Test
    fun `rejects a truncated encoding`() {
        assertFalse(ProbeVector.matches(ProbeVector.canonicalBytes().copyOf(30)))
    }
}
