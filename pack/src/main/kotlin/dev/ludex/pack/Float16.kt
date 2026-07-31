package dev.ludex.pack

/**
 * IEEE 754 binary16 codec.
 *
 * Written out rather than delegating to `java.lang.Float.float16ToFloat`, which
 * arrived in JDK 20 and does not exist on Android. This module is bound for a phone,
 * and a dependency on a desktop-only intrinsic would need unpicking at exactly the
 * moment it is least convenient.
 *
 * `Float16Test` checks this against the JDK implementation across all 65 536 bit
 * patterns, so hand-rolling it costs nothing in confidence.
 */
object Float16 {

    /** 2^-24: the value of the least significant subnormal bit, exactly representable. */
    private const val SUBNORMAL_UNIT = 5.9604645e-8f

    fun toFloat(bits: Short): Float {
        val b = bits.toInt() and 0xFFFF
        val negative = (b and 0x8000) != 0
        val exponent = (b ushr 10) and 0x1F
        val mantissa = b and 0x03FF

        val magnitude = when (exponent) {
            0 -> mantissa.toFloat() * SUBNORMAL_UNIT
            0x1F -> if (mantissa == 0) Float.POSITIVE_INFINITY else Float.NaN
            else -> Float.fromBits(((exponent - 15 + 127) shl 23) or (mantissa shl 13))
        }
        // NaN has no sign worth preserving, and negating it would only obscure that.
        return if (negative && !magnitude.isNaN()) -magnitude else magnitude
    }

    /**
     * Encodes with round-half-to-even, matching the JDK. Only the test forge writes
     * float16 -- the app never produces vectors -- but a decoder-only "codec" is the
     * odd artifact, and sharing one exhaustive test across both directions is cheaper
     * than maintaining a second encoder beside the fixtures.
     */
    fun fromFloat(value: Float): Short {
        val bits = value.toRawBits()
        val sign = (bits ushr 16) and 0x8000
        var exponent = ((bits ushr 23) and 0xFF) - 127 + 15
        val mantissa = bits and 0x007FFFFF

        if (value.isNaN()) return (sign or 0x7E00).toShort()
        if (exponent >= 0x1F) return (sign or 0x7C00).toShort()

        if (exponent <= 0) {
            // Subnormal, or rounds away to zero. Reinstate the implicit leading bit and
            // shift it down into subnormal position.
            if (exponent < -10) return sign.toShort()
            val withImplicit = mantissa or 0x00800000
            val shift = 14 - exponent
            val truncated = withImplicit ushr shift
            val remainder = withImplicit and ((1 shl shift) - 1)
            val halfway = 1 shl (shift - 1)
            val rounded = when {
                remainder > halfway -> truncated + 1
                remainder < halfway -> truncated
                else -> truncated + (truncated and 1) // ties to even
            }
            return (sign or rounded).toShort()
        }

        var half = mantissa ushr 13
        val remainder = mantissa and 0x1FFF
        if (remainder > 0x1000 || (remainder == 0x1000 && (half and 1) == 1)) {
            half++
            if (half == 0x400) { // mantissa overflowed into the exponent
                half = 0
                exponent++
                if (exponent >= 0x1F) return (sign or 0x7C00).toShort()
            }
        }
        return (sign or (exponent shl 10) or half).toShort()
    }

    /**
     * Decodes a contiguous little-endian float16 BLOB.
     *
     * Endianness is pinned by the format rather than inherited from the platform:
     * Java's `ByteBuffer` defaults to big-endian while most builder toolchains write
     * native little-endian, and a byte-swapped vector is exactly as long as a correct
     * one. See [ProbeVector] for how that is caught.
     */
    fun decodeVector(blob: ByteArray): FloatArray {
        require(blob.size % 2 == 0) { "float16 blob has odd length ${blob.size}" }
        val out = FloatArray(blob.size / 2)
        for (i in out.indices) {
            val lo = blob[i * 2].toInt() and 0xFF
            val hi = blob[i * 2 + 1].toInt() and 0xFF
            out[i] = toFloat(((hi shl 8) or lo).toShort())
        }
        return out
    }

    /** Encodes to the same contiguous little-endian layout [decodeVector] reads. */
    fun encodeVector(values: FloatArray): ByteArray {
        val out = ByteArray(values.size * 2)
        for (i in values.indices) {
            val bits = fromFloat(values[i]).toInt() and 0xFFFF
            out[i * 2] = (bits and 0xFF).toByte()
            out[i * 2 + 1] = ((bits ushr 8) and 0xFF).toByte()
        }
        return out
    }
}
