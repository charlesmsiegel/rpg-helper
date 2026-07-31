package dev.ludex.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How large a context window a device can afford.
 *
 * The 8 GB the design assumed was a floor for the phone nobody could test on, not a ceiling
 * for the phone somebody has. What this must never do is pick a window that gets the process
 * killed: on Android the KV cache is native allocation against a per-app limit, and the
 * symptom of overreach is not a slow answer — it is the app vanishing mid-question, which to
 * a user is indistinguishable from a crash.
 */
class ContextBudgetTest {

    private val gb = 1024L * 1024 * 1024
    private val e2bAt4Bits = 3L * gb

    @Test
    fun `a larger device gets a larger window`() {
        // The whole point. Same model, three phones.
        val small = ContextBudget.forDevice(8 * gb, e2bAt4Bits)!!
        val large = ContextBudget.forDevice(16 * gb, e2bAt4Bits)!!
        assertTrue(large > small, "16 GB should not be held to the 8 GB window: $small vs $large")
    }

    @Test
    fun `windows are powers of two`() {
        listOf(6, 8, 12, 16, 24, 32).forEach { ram ->
            val tokens = ContextBudget.forDevice(ram * gb, e2bAt4Bits) ?: return@forEach
            assertEquals(
                0,
                tokens and (tokens - 1),
                "$ram GB chose $tokens, which is not a power of two",
            )
        }
    }

    @Test
    fun `a device with no room to spare gets no window at all`() {
        // Better than a tiny one: below the minimum the app cannot hold a retrieval context
        // *and* a follow-up window, and the honest move is to decline the model.
        assertNull(ContextBudget.forDevice(4 * gb, e2bAt4Bits), "4 GB cannot hold a 3 GB model")
        assertNull(ContextBudget.forDevice(0, e2bAt4Bits))
    }

    @Test
    fun `a request is honoured up to what the device can hold`() {
        // Someone asking for 32k on a device that cannot survive it is asking for a crash.
        // Obeying silently is not respect for the request.
        val ceiling = ContextBudget.forDevice(8 * gb, e2bAt4Bits)!!
        val asked = ContextBudget.forDevice(8 * gb, e2bAt4Bits, requested = ContextBudget.MAXIMUM_TOKENS)!!
        assertEquals(ceiling, asked, "clamped to the measurement, not to the wish")

        val modest = ContextBudget.forDevice(16 * gb, e2bAt4Bits, requested = 4_096)!!
        assertEquals(4_096, modest, "and a smaller request is simply obeyed")
    }

    @Test
    fun `a request below the usable minimum is raised to it`() {
        // A 512-token window is not a small window, it is a broken one.
        val chosen = ContextBudget.forDevice(16 * gb, e2bAt4Bits, requested = 512)!!
        assertEquals(ContextBudget.MINIMUM_TOKENS, chosen)
    }

    @Test
    fun `the chosen window fits in the memory it was measured against`() {
        // The property that matters, stated as arithmetic rather than as a number: whatever
        // is chosen, the KV cache it implies is inside the share of spare memory allowed.
        listOf(8, 12, 16, 24).forEach { ram ->
            val tokens = ContextBudget.forDevice(ram * gb, e2bAt4Bits) ?: return@forEach
            val kv = tokens * ContextBudget.DEFAULT_KV_BYTES_PER_TOKEN
            val spare = ram * gb - e2bAt4Bits - ContextBudget.SYSTEM_RESERVE_BYTES
            assertTrue(
                kv <= spare * ContextBudget.SPARE_SHARE,
                "$ram GB chose $tokens tokens = $kv bytes, over its share of $spare",
            )
        }
    }

    @Test
    fun `a bigger model on the same device gets a smaller window`() {
        val onE2b = ContextBudget.forDevice(16 * gb, 3 * gb)!!
        val onE4b = ContextBudget.forDevice(16 * gb, 8 * gb)!!
        assertTrue(onE4b <= onE2b, "the weights come out of the same memory: $onE2b vs $onE4b")
    }

    @Test
    fun `a model whose shape is known overrides the estimate`() {
        // The default is a middling guess for a Gemma-class model. A runtime that can read
        // the real layer count should pass it rather than inherit the guess.
        val perToken = ContextBudget.kvBytesPerToken(layers = 26, keyValueHeads = 4, headDim = 256)
        assertEquals(2L * 26 * 4 * 256 * 2, perToken)

        val roomy = ContextBudget.forDevice(16 * gb, e2bAt4Bits, kvBytesPerToken = perToken / 4)!!
        val tight = ContextBudget.forDevice(16 * gb, e2bAt4Bits, kvBytesPerToken = perToken)!!
        assertTrue(roomy >= tight, "a cheaper cache buys a longer window")
    }

    @Test
    fun `what it tells the user names the device rather than the arithmetic`() {
        assertTrue("16 GB" in ContextBudget.describe(8_192, 16 * gb))
        assertTrue("does not have room" in ContextBudget.describe(null, 4 * gb))
    }
}
