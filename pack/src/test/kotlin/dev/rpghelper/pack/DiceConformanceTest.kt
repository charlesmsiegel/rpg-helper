package dev.rpghelper.pack

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Runs `conformance/dice-vectors.json`.
 *
 * The builder is a separate project in a different language, and dice notation is a folk
 * grammar with no standard. Without a shared file this is a specification two teams can
 * each believe they implement, and the disagreement surfaces as a roll landing outside its
 * validated range at someone's table. The vectors are the contract; this is one side
 * running them.
 *
 * Parsed by hand rather than with a JSON library: `:pack` has no JSON dependency, and
 * adding one so a test can read a fixture would put it on the device.
 */
class DiceConformanceTest {

    private val vectors: String by lazy { Files.readString(locate()) }

    @Test
    fun `every distribution vector parses to its stated range`() {
        var checked = 0
        forEachVector { expr, min, max, _ ->
            val parsed = DiceExpression.parse(expr)
            assertTrue(parsed != null, "'$expr' should parse")
            assertEquals(min.toLong(), parsed.min, "'$expr' minimum")
            assertEquals(max.toLong(), parsed.max, "'$expr' maximum")
            checked++
        }
        assertTrue(checked >= 18, "expected the full vector set, saw $checked")
    }

    @Test
    fun `every distribution matches exactly`() {
        forEachVector { expr, _, _, pmf ->
            if (pmf.isEmpty()) return@forEachVector
            val computed = DiceExpression.parse(expr)!!.distribution()
            assertEquals(pmf.size, computed.size, "'$expr' outcome count")
            for ((outcome, probability) in pmf) {
                assertEquals(
                    probability,
                    computed[outcome],
                    "'$expr' probability of $outcome",
                )
            }
        }
    }

    @Test
    fun `every distribution sums to one`() {
        forEachVector { expr, _, _, pmf ->
            if (pmf.isEmpty()) return@forEachVector
            val total = pmf.values.fold(Rational.ZERO) { acc, p -> acc + p }
            assertEquals(Rational.ONE, total, "'$expr' probabilities must sum to 1")
        }
    }

    @Test
    fun `every unparseable expression is refused`() {
        val listed = section("unparseable")
        assertTrue(listed.size >= 20, "expected the full refusal list, saw ${listed.size}")
        for (expr in listed) {
            assertNull(DiceExpression.parse(expr), "'$expr' must not parse")
        }
    }

    @Test
    fun `2d6 and d11+1 share a range and differ in every interior probability`() {
        // The pair exists precisely so a roller that confuses them fails. Both are
        // triangular-versus-flat over 2..12, and no coverage check can see the difference
        // because every outcome remains reachable.
        val triangular = DiceExpression.parse("2d6")!!
        val flat = DiceExpression.parse("d11+1")!!
        assertEquals(triangular.min to triangular.max, flat.min to flat.max)
        assertNotEquals(triangular.distribution(), flat.distribution())
        assertEquals(Rational(1, 6), triangular.distribution()[7], "2d6 peaks at 7")
        assertEquals(Rational(1, 11), flat.distribution()[7], "d11+1 is flat")
    }

    @Test
    fun `d100 is a legal expression and is not a spelling of d percent`() {
        val hundred = DiceExpression.parse("d100")!!
        val percentile = DiceExpression.parse("d%")!!
        assertEquals(1L to 100L, hundred.min to hundred.max)
        assertEquals(1L to 100L, percentile.min to percentile.max)
        assertTrue(percentile.percentile)
        assertTrue(!hundred.percentile, "d100 carries none of d%'s 00 mapping")
    }

    // ---------------------------------------------------------------- parsing helpers

    private fun forEachVector(body: (String, Int, Int, Map<Int, Rational>) -> Unit) {
        val block = vectors.substringAfter("\"vectors\": [").substringBefore("\n  ],")
        // Objects are separated at brace depth 0; each is one expression's vector.
        var depth = 0
        val current = StringBuilder()
        for (c in block) {
            if (c == '{') depth++
            if (depth > 0) current.append(c)
            if (c == '}') {
                depth--
                if (depth == 0) {
                    parseVector(current.toString(), body)
                    current.setLength(0)
                }
            }
        }
    }

    private fun parseVector(
        text: String,
        body: (String, Int, Int, Map<Int, Rational>) -> Unit,
    ) {
        val expr = Regex("\"expr\":\\s*\"([^\"]*)\"").find(text)!!.groupValues[1]
        val min = Regex("\"min\":\\s*(-?\\d+)").find(text)!!.groupValues[1].toInt()
        val max = Regex("\"max\":\\s*(-?\\d+)").find(text)!!.groupValues[1].toInt()
        val pmf = Regex("\\[\\s*\"(-?\\d+)\",\\s*\"(\\d+/\\d+)\"\\s*]")
            .findAll(text)
            .associate { it.groupValues[1].toInt() to Rational.parse(it.groupValues[2]) }
        body(expr, min, max, pmf)
    }

    private fun section(name: String): List<String> =
        vectors.substringAfter("\"$name\": [").substringBefore("]")
            .split(",")
            .map { it.trim() }
            .filter { it.startsWith("\"") }
            .map { it.removeSurrounding("\"").replace("\\\"", "\"") }

    private fun locate(): Path {
        var directory: Path? = Path.of("").toAbsolutePath()
        while (directory != null) {
            val candidate = directory.resolve("conformance/dice-vectors.json")
            if (Files.isRegularFile(candidate)) return candidate
            directory = directory.parent
        }
        error("could not find conformance/dice-vectors.json")
    }
}
