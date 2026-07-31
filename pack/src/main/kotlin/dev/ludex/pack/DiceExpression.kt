package dev.ludex.pack

/**
 * The closed dice grammar, pinned in `docs/00-pack-schema.md` §6.
 *
 * ```
 * expr     := form modifier?
 * form     := NdS | dS | 'd%'
 * modifier := ('+' | '-') integer
 * N, S     := positive integers
 * ```
 *
 * Case-sensitive, and no whitespace anywhere. Nothing else parses: exploding dice,
 * drop-lowest, and rerolls are not expressible, so a table needing them ships quotable and
 * not rollable. Refusing them is part of the contract — an implementation that helpfully
 * accepted `4d6kh3` would diverge from the builder that rejected it.
 *
 * This lives in `:pack` rather than beside the roller because the *range* is needed to
 * validate a table's row coverage at activation, which happens long before anything rolls.
 */
data class DiceExpression(
    val count: Int,
    val sides: Int,
    val modifier: Int,
    val percentile: Boolean,
) {
    // Long, and bounded by MAX_COUNT/MAX_SIDES above. `2d2147483647` is grammatical
    // under "positive integers" and overflows an Int to a *negative* maximum, which made
    // the coverage check accept an empty row list -- a rollable table where every result
    // has no outcome, arrived at through arithmetic rather than through a missing row.
    val min: Long get() = count.toLong() + modifier
    val max: Long get() = count.toLong() * sides + modifier

    /**
     * Exact probability of each outcome, by convolution.
     *
     * Not by enumeration — `10d10` has 10¹⁰ outcomes — and not by sampling, because a
     * statistical test of a distribution flakes, and a suite that flakes gets disabled.
     *
     * Returned as exact rationals over a common denominator, so `2d6` and `d11+1` are
     * distinguishable by equality rather than by a confidence interval: they share the
     * range 2–12 and differ in every interior probability.
     */
    fun distribution(): Map<Long, Rational>? {
        // **Refused above what it can actually compute.** The grammar admits `100d1000`,
        // whose distribution is ~100,000 outcomes convolved a hundred times over a thousand
        // faces -- on the order of five billion exact-rational operations over growing
        // BigIntegers, which is not slow, it is a hang. Lowering the grammar instead would
        // be the wrong repair: `MAX_COUNT` and `MAX_SIDES` are pinned, conformance vectors
        // and real tables depend on them, and `min`/`max` and coverage -- the parts the app
        // actually runs -- are cheap at any legal size. So the *exact PMF* says where its
        // domain ends, rather than the parser pretending the grammar is smaller than it is.
        if (count.toLong() * count * sides * sides > MAX_DISTRIBUTION_WORK) return null
        // Long throughout, because `min`, `max` and every table row bound already are. The
        // grammar admits a modifier up to `Int.MAX_VALUE`, so `d6+2147483647` summed in Int
        // produced wrapped negative keys -- an exact-PMF API disagreeing with the roller and
        // the coverage logic about what the very same expression can produce.
        var dist = mapOf(0L to Rational.ONE)
        repeat(count) {
            val next = HashMap<Long, Rational>()
            for ((total, probability) in dist) {
                for (face in 1..sides) {
                    val outcome = total + face
                    next[outcome] = (next[outcome] ?: Rational.ZERO) +
                        probability * Rational(1, sides.toLong())
                }
            }
            dist = next
        }
        return dist.entries.associate { (total, p) -> total + modifier to p }
    }

    override fun toString(): String {
        val form = if (percentile) "d%" else "${if (count == 1) "" else "$count"}d$sides"
        return form + when {
            modifier > 0 -> "+$modifier"
            modifier < 0 -> "$modifier"
            else -> ""
        }
    }

    companion object {
        /**
         * `d%` is exactly 1–100. Books printing `00` map to 100 at build time, and the
         * mapping is recorded rather than assumed.
         *
         * `d100` is a separate, legal expression — `dS` with `S = 100`. It is not a
         * spelling of `d%` and carries none of that mapping; they coincide only in range.
         */
        private const val PERCENTILE_SIDES = 100

        /**
         * Ceilings on the operands, part of the pinned grammar.
         *
         * "Positive integers" unbounded is not implementable: the outcome range overflows,
         * and `distribution()` convolves `count` times over `sides` faces, so `100d1000`
         * is already the largest thing worth computing on a phone. A table needing more is
         * not a table anyone printed.
         */
        const val MAX_COUNT = 100
        const val MAX_SIDES = 1000

        /**
         * Roughly how many rational operations [distribution] will attempt before refusing.
         *
         * Ten million is a fraction of a second and covers every expression a book prints;
         * the expressions beyond it are legal, rare, and belong to a function nothing on
         * the device calls. [min], [max] and coverage stay exact at every legal size.
         */
        const val MAX_DISTRIBUTION_WORK = 10_000_000L

        /** Parses under the grammar, or null. Never throws, never guesses. */
        fun parse(text: String): DiceExpression? {
            if (text.isEmpty()) return null

            val dIndex = text.indexOf('d')
            if (dIndex < 0) return null

            val countPart = text.substring(0, dIndex)
            val count = when {
                countPart.isEmpty() -> 1
                else -> positiveInt(countPart) ?: return null
            }

            var rest = text.substring(dIndex + 1)
            var modifier = 0
            val signIndex = rest.indexOfFirst { it == '+' || it == '-' }
            if (signIndex >= 0) {
                val sign = if (rest[signIndex] == '+') 1 else -1
                val magnitude = positiveInt(rest.substring(signIndex + 1)) ?: return null
                modifier = sign * magnitude
                rest = rest.substring(0, signIndex)
            }

            if (rest == "%") {
                // The grammar admits the literal `d%`, not `Nd%`. Accepting `2d%` here
                // would be this parser quietly speaking a larger language than the
                // builder that rejects it -- the cross-implementation disagreement a
                // shared parser exists to prevent. A table rolling two percentile dice
                // is `2d100`, which carries none of `d%`'s 00-to-100 mapping.
                if (countPart.isNotEmpty()) return null
                return DiceExpression(1, PERCENTILE_SIDES, modifier, percentile = true)
            }
            val sides = positiveInt(rest) ?: return null
            if (count > MAX_COUNT || sides > MAX_SIDES) return null
            return DiceExpression(count, sides, modifier, percentile = false)
        }

        /**
         * Digits only, positive, no sign and no whitespace.
         *
         * `toIntOrNull` would accept `+3`, `-3`, and leading/trailing space, none of which
         * the grammar admits — and each would be a place where this parser and a builder's
         * quietly disagree.
         */
        private fun positiveInt(text: String): Int? {
            if (text.isEmpty() || text.any { it !in '0'..'9' }) return null
            val value = text.toIntOrNull() ?: return null
            return if (value > 0) value else null
        }
    }
}

/**
 * An exact rational, so distributions compare by equality rather than by tolerance.
 *
 * Backed by [java.math.BigInteger] rather than `Long`. `10d10` has a denominator of
 * 10^10, which fits in a `Long` — but the intermediate products inside an unreduced
 * addition do not, and the overflow is silent: it produces a plausible-looking wrong
 * fraction rather than an error. The conformance suite caught it, which is the argument
 * for the suite existing.
 */
class Rational private constructor(
    val numerator: java.math.BigInteger,
    val denominator: java.math.BigInteger,
) {

    operator fun plus(other: Rational): Rational = of(
        numerator * other.denominator + other.numerator * denominator,
        denominator * other.denominator,
    )

    operator fun times(other: Rational): Rational =
        of(numerator * other.numerator, denominator * other.denominator)

    override fun toString(): String = "$numerator/$denominator"

    override fun equals(other: Any?): Boolean =
        other is Rational && numerator == other.numerator && denominator == other.denominator

    override fun hashCode(): Int = 31 * numerator.hashCode() + denominator.hashCode()

    companion object {
        val ZERO = of(java.math.BigInteger.ZERO, java.math.BigInteger.ONE)
        val ONE = of(java.math.BigInteger.ONE, java.math.BigInteger.ONE)

        operator fun invoke(numerator: Long, denominator: Long): Rational =
            of(numerator.toBigInteger(), denominator.toBigInteger())

        /** Always reduced and sign-normalized, so equality is structural. */
        private fun of(
            numerator: java.math.BigInteger,
            denominator: java.math.BigInteger,
        ): Rational {
            require(denominator.signum() != 0) { "zero denominator" }
            val divisor = numerator.gcd(denominator).max(java.math.BigInteger.ONE)
            val sign = if (denominator.signum() < 0) -1 else 1
            val n = numerator / divisor
            val d = denominator / divisor
            return Rational(
                if (sign < 0) n.negate() else n,
                if (sign < 0) d.negate() else d,
            )
        }

        /** Parses `"a/b"`, the form the conformance vectors use. */
        fun parse(text: String): Rational {
            val parts = text.split("/")
            require(parts.size == 2) { "not a rational: $text" }
            return of(parts[0].toBigInteger(), parts[1].toBigInteger())
        }
    }
}
