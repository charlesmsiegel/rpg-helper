package dev.ludex.pack

/**
 * The fixed vector every pack carries in `pack_meta.probe_vector`, encoded by the
 * builder through the same writer that encodes every real embedding in the pack.
 *
 * `length(blob) == embedder_dim * 2` is a necessary check and the app runs it, but it
 * cannot detect byte order: a big-endian vector is exactly as long as its little-endian
 * counterpart. The probe closes that gap, and because it shares the encoding path with
 * real vectors it also catches a builder that wrote float32, wrote NaN-boxed values, or
 * padded its rows -- layout bugs no per-vector inspection could find, since a real
 * embedding has no expected value to compare against.
 *
 * The constant is 16 elements regardless of `embedder_dim`, so a pack cannot pass by
 * handing back one of its own vectors.
 *
 * Chosen so that the check provably works rather than plausibly works. Every element is
 * exactly representable in binary16; none is zero (which is byte-swap invariant); the
 * sequence is not a palindrome (so element order is checked too); and five elements
 * decode to NaN or infinity if read big-endian. `ProbeVectorTest` asserts each of those
 * properties, so a future edit cannot quietly weaken the probe into something that
 * still looks like a probe.
 */
object ProbeVector {

    /**
     * Element `i`'s little-endian bit pattern is listed beside it, along with what a
     * big-endian reader would see instead.
     */
    val VALUES: FloatArray = floatArrayOf(
        1.0f,             // 0x3C00  -> BE 0x003C, subnormal
        -2.0f,            // 0xC000  -> BE 0x00C0, subnormal
        1.37109375f,      // 0x3D7C  -> BE 0x7C3D, NaN
        -1.37109375f,     // 0xBD7C  -> BE 0x7CBD, NaN
        0.5f,             // 0x3800  -> BE 0x0038, subnormal
        -0.25f,           // 0xB400  -> BE 0x00B4, subnormal
        3.140625f,        // 0x4248  -> BE 0x4842
        -3.140625f,       // 0xC248  -> BE 0x48C2
        65504.0f,         // 0x7BFF  -> BE 0xFF7B, NaN   (largest finite binary16)
        -6.103515625e-5f, // 0x8400  -> BE 0x0084        (smallest normal binary16)
        1.0009765625f,    // 0x3C01  -> BE 0x013C        (mantissa LSB set)
        -1023.5f,         // 0xE3FF  -> BE 0xFFE3, NaN
        0.333251953125f,  // 0x3555  -> BE 0x5535
        -0.66650390625f,  // 0xB955  -> BE 0x55B9
        1.75f,            // 0x3F00  -> BE 0x003F, subnormal
        -1.9990234375f,   // 0xBFFF  -> BE 0xFFBF, NaN
    )

    /** 16 elements x 2 bytes. Independent of `embedder_dim`, by design. */
    val BYTE_LENGTH: Int = VALUES.size * PackSchema.VECTOR_ELEMENT_BYTES

    /** The canonical little-endian encoding a conforming builder must produce. */
    fun canonicalBytes(): ByteArray = Float16.encodeVector(VALUES)

    /**
     * True when [blob] decodes to exactly [VALUES].
     *
     * Compared elementwise after decoding rather than by comparing bytes, so the check
     * exercises the same reader that will decode every real vector in the pack. Exact
     * equality is correct here: binary16 decoding is exact, and every element was
     * chosen to be exactly representable, so there is no rounding to tolerate.
     */
    fun matches(blob: ByteArray): Boolean {
        if (blob.size != BYTE_LENGTH) return false
        val decoded = Float16.decodeVector(blob)
        return decoded.indices.all { decoded[it] == VALUES[it] }
    }
}
