package dev.ludex.pack

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class Float16Test {

    /**
     * All 65 536 bit patterns, against the JDK's own implementation.
     *
     * Exhaustive is affordable here, and it is the only way to be sure about the parts
     * that are easy to get wrong and rare enough to escape sampling: subnormals, the
     * boundary where subnormal becomes normal, both zeros, both infinities, and the NaN
     * space.
     */
    @Test
    fun `decodes every bit pattern the way the JDK does`() {
        for (pattern in 0..0xFFFF) {
            val bits = pattern.toShort()
            val expected = java.lang.Float.float16ToFloat(bits)
            val actual = Float16.toFloat(bits)
            if (expected.isNaN()) {
                assertTrue(actual.isNaN(), "0x%04X should decode to NaN, got %s".format(pattern, actual))
            } else {
                assertEquals(
                    expected.toRawBits(),
                    actual.toRawBits(),
                    "0x%04X decoded to %s, expected %s".format(pattern, actual, expected),
                )
            }
        }
    }

    @Test
    fun `encodes every bit pattern the way the JDK does`() {
        for (pattern in 0..0xFFFF) {
            val value = java.lang.Float.float16ToFloat(pattern.toShort())
            if (value.isNaN()) continue // any NaN encoding is acceptable; checked below
            val expected = java.lang.Float.floatToFloat16(value)
            assertEquals(
                expected,
                Float16.fromFloat(value),
                "encoding %s (from 0x%04X)".format(value, pattern),
            )
        }
    }

    @Test
    fun `round-trips every representable value`() {
        for (pattern in 0..0xFFFF) {
            val value = Float16.toFloat(pattern.toShort())
            if (value.isNaN()) continue
            assertEquals(
                value.toRawBits(),
                Float16.toFloat(Float16.fromFloat(value)).toRawBits(),
                "0x%04X did not survive a round trip".format(pattern),
            )
        }
    }

    @Test
    fun `encodes NaN as NaN rather than as a finite value`() {
        assertTrue(Float16.toFloat(Float16.fromFloat(Float.NaN)).isNaN())
    }

    @Test
    fun `saturates values beyond binary16 range to infinity`() {
        assertEquals(Float.POSITIVE_INFINITY, Float16.toFloat(Float16.fromFloat(1e30f)))
        assertEquals(Float.NEGATIVE_INFINITY, Float16.toFloat(Float16.fromFloat(-1e30f)))
    }

    @Test
    fun `decodes vectors little-endian`() {
        // 1.0f is 0x3C00, so little-endian puts 0x00 first. A big-endian reader would
        // see 0x003C and produce a subnormal instead.
        val blob = byteArrayOf(0x00, 0x3C)
        assertEquals(1.0f, Float16.decodeVector(blob).single())
    }

    @Test
    fun `vector encoding round-trips`() {
        val values = floatArrayOf(1.0f, -0.5f, 3.140625f, 65504.0f, -6.103515625e-5f)
        assertTrue(Float16.decodeVector(Float16.encodeVector(values)).contentEquals(values))
    }
}
