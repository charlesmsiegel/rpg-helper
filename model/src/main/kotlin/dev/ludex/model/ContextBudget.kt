package dev.ludex.model

/**
 * How large a context window this device can actually afford.
 *
 * The 8 GB figure the design assumed was a **floor for the phone we could not test on**, not
 * a ceiling for the phone somebody has. A 16 GB device running a 4-bit E2B has gigabytes
 * spare, and pinning it to the small-device window throws away the thing context is for:
 * more retrieved passages in front of the model, and a follow-up window that survives more
 * than a couple of turns.
 *
 * The sizing is deliberately crude, because the inputs are crude. What it must not do is
 * pick a window that gets the app killed: on Android the KV cache is native allocation
 * against a per-app limit, and the symptom of overreach is not a slow answer but the process
 * disappearing mid-session — which looks to a user exactly like the app crashing when they
 * asked a question.
 *
 * ```
 *   spare        = total RAM − what the weights need − what the rest of the system needs
 *   kv per token ≈ 2 (K and V) × layers × head-dim × bytes-per-element
 *   tokens       = spare × share ÷ kv per token, clamped and rounded down to a power of two
 * ```
 */
object ContextBudget {

    /**
     * The smallest window worth running.
     *
     * Below this the app cannot hold a retrieval context *and* a follow-up window, and the
     * honest answer is to refuse the model rather than to run it in a state where every
     * second question drops the first one's subject.
     */
    const val MINIMUM_TOKENS: Int = 2_048

    /**
     * The largest this code will choose on its own.
     *
     * Not a technical limit — attention cost grows with the window, and past this a phone
     * answers slowly and gets hot for context nobody asked for. A user who wants more can
     * say so; nothing here decides that for them.
     */
    const val MAXIMUM_TOKENS: Int = 32_768

    /**
     * Share of the spare memory a context window may take.
     *
     * A third, because the KV cache is not the only thing that grows during generation and
     * the process is not the only thing on the device. This is the number to lower if a
     * device turns out to be killed while answering.
     */
    const val SPARE_SHARE: Double = 0.33

    /**
     * What the rest of the system needs, over and above the weights.
     *
     * On a phone this is the launcher, the keyboard, the messaging app the user came from,
     * and this app's own heap and Compose tree. Reserved rather than assumed available: the
     * memory that gets a process killed is memory something else was already using.
     */
    const val SYSTEM_RESERVE_BYTES: Long = 2L * 1024 * 1024 * 1024

    /**
     * Bytes of KV cache per token, per the model's own shape.
     *
     * `2 × layers × headDim × heads × bytesPerElement`. Callers that know the architecture
     * pass it; [forDevice] takes a per-token figure directly, because a manifest does not
     * carry a model's layer count and inventing one would be worse than taking an estimate
     * from the caller who can measure it.
     */
    fun kvBytesPerToken(layers: Int, keyValueHeads: Int, headDim: Int, bytesPerElement: Int = 2): Long =
        2L * layers * keyValueHeads * headDim * bytesPerElement

    /**
     * The window to open on a device with [totalRamBytes], given the weights' size.
     *
     * @param requested a user's explicit choice, honoured **up to what the device can hold**.
     * A person who asks for 32k on a device that cannot survive it is asking for a crash, and
     * silently obeying is not respect for the request. Clamping and saying so is.
     * @return null when there is not enough memory for even [MINIMUM_TOKENS] — the caller
     * should decline to load the model rather than open a window too small to be useful.
     */
    fun forDevice(
        totalRamBytes: Long,
        weightsBytes: Long,
        kvBytesPerToken: Long = DEFAULT_KV_BYTES_PER_TOKEN,
        requested: Int? = null,
    ): Int? {
        if (totalRamBytes <= 0 || weightsBytes < 0 || kvBytesPerToken <= 0) return null
        val spare = totalRamBytes - weightsBytes - SYSTEM_RESERVE_BYTES
        if (spare <= 0) return null

        val affordable = (spare * SPARE_SHARE / kvBytesPerToken).toLong()
        if (affordable < MINIMUM_TOKENS) return null

        val ceiling = minOf(affordable, MAXIMUM_TOKENS.toLong()).toInt()
        // A requested window is clamped, never expanded past what was measured -- and never
        // below the minimum, because a request for 512 is a request for a broken window.
        val chosen = requested?.coerceIn(MINIMUM_TOKENS, ceiling) ?: ceiling
        return roundDownToPowerOfTwo(chosen)
    }

    /**
     * Windows are powers of two.
     *
     * Not superstition: llama.cpp allocates the KV cache in blocks and models are trained at
     * these lengths, so 6,000 buys the cost of 8,192 and the behaviour of 4,096. Rounding
     * down rather than up keeps the estimate conservative in the direction that matters.
     */
    private fun roundDownToPowerOfTwo(tokens: Int): Int {
        var value = MINIMUM_TOKENS
        while (value * 2 <= tokens && value * 2 <= MAXIMUM_TOKENS) value *= 2
        return value
    }

    /**
     * A middling estimate for a small quantized model — Gemma-class E2B/E4B at 4 bits.
     *
     * Roughly 2 × 30 layers × 4 KV heads × 256 head-dim × 2 bytes. Wrong for any specific
     * model, and wrong in the safe direction for most: a runtime that can read the real
     * shape out of the file should pass it rather than use this.
     */
    const val DEFAULT_KV_BYTES_PER_TOKEN: Long = 2L * 30 * 4 * 256 * 2

    /** What the Models surface shows about a choice, in one sentence. */
    fun describe(tokens: Int?, totalRamBytes: Long): String = when (tokens) {
        null -> "This device does not have room to run it: " +
            "${totalRamBytes / (1024 * 1024 * 1024)} GB total, and a window needs " +
            "more than what is left after the weights."
        else -> "$tokens-token context, chosen for ${totalRamBytes / (1024 * 1024 * 1024)} GB of RAM."
    }
}
