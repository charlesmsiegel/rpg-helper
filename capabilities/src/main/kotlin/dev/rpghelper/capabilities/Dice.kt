package dev.rpghelper.capabilities

import java.security.SecureRandom

/**
 * A source of uniform integers in `[0, bound)`.
 *
 * An interface so the conformance suite can drive a deterministic one and production can
 * use real entropy, without the roller knowing which it has. The contract is the whole
 * point: **`draw(bound)` returns a value in `[0, bound)` with every value equally
 * likely**, and `face = draw + 1`.
 */
fun interface DiceSource {
    fun draw(bound: Int): Int
}

/**
 * The production source: cryptographic entropy, and no modulo bias.
 *
 * Seeded from [SecureRandom] rather than from the clock — two rolls in the same
 * millisecond must not agree, and a clock-seeded roller at a table is one where the first
 * roll of the session is guessable.
 *
 * `next() % bound` is biased toward low values whenever the generator's range is not a
 * multiple of `bound`: for a 32-bit generator and a d6 the excess is about one part in
 * 700 million, which is invisible in any test anyone would write and real to the one
 * population that cares most about dice being fair. Rejection sampling removes it exactly
 * rather than approximately.
 */
class SecureDiceSource(private val random: SecureRandom = SecureRandom()) : DiceSource {

    override fun draw(bound: Int): Int {
        require(bound > 0) { "a die needs at least one face, got $bound" }
        // The largest multiple of `bound` that fits: anything at or above it is discarded,
        // so every accepted value comes from a range that *is* a multiple of bound.
        val limit = Int.MAX_VALUE - (Int.MAX_VALUE % bound) - 1
        while (true) {
            val candidate = random.nextInt(Int.MAX_VALUE)
            if (candidate <= limit) return candidate % bound
        }
    }
}

/** A source that replays a fixed sequence. What the conformance vectors drive. */
class ScriptedDiceSource(private val draws: List<Int>) : DiceSource {
    private var index = 0

    override fun draw(bound: Int): Int {
        check(index < draws.size) { "the script ran out after $index draws" }
        val value = draws[index++]
        require(value in 0 until bound) { "scripted draw $value is not in [0, $bound)" }
        return value
    }

    /** True when every scripted draw was consumed — a roll that used fewer is a bug. */
    val exhausted: Boolean get() = index == draws.size
}
