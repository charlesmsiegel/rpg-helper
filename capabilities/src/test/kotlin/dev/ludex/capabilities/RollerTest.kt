package dev.ludex.capabilities

import dev.ludex.pack.DiceExpression
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RollerTest {

    private fun table(
        expr: String,
        rows: List<TableRow> = (1L..6L).map { TableRow(it - 1, it, it, "outcome $it") },
    ) = RollableTable(1, 42, DiceExpression.parse(expr)!!, rows, "Mishaps")

    private fun rollWith(expr: String, draws: List<Int>, rows: List<TableRow>? = null) =
        Roller(ScriptedDiceSource(draws)).roll(rows?.let { table(expr, it) } ?: table(expr))

    // ---------------------------------------------------------------- the sampler contract

    @Test
    fun `every sampler vector reproduces its dice and its total`() {
        // The vectors pin that NdS draws N times, in order, rather than once over the whole
        // range -- the difference between a triangular distribution and a flat one, which
        // no coverage check can see because every outcome stays reachable.
        val vectors = Json.parseToJsonElement(Files.readString(conformance()))
            .jsonObject.getValue("sampler").jsonArray

        assertTrue(vectors.size >= 7, "expected the full sampler set, saw ${vectors.size}")
        for (element in vectors) {
            val vector = element.jsonObject
            val expr = vector.getValue("expr").jsonPrimitive.content
            val draws = vector.getValue("draws").jsonArray.map { it.jsonPrimitive.content.toInt() }
            val dice = vector.getValue("dice").jsonArray.map { it.jsonPrimitive.content.toInt() }
            val total = vector.getValue("total").jsonPrimitive.content.toLong()

            val parsed = DiceExpression.parse(expr)!!
            val source = ScriptedDiceSource(draws)
            // Rolled through a table wide enough to hold any outcome, so the vector is
            // testing the sampler rather than the table lookup.
            val wide = RollableTable(
                1, 42, parsed,
                listOf(TableRow(0, parsed.min, parsed.max, "any")), "wide",
            )
            val result = Roller(source).roll(wide).getOrThrow()

            assertEquals(dice, result.dice, "'$expr' dice from draws $draws")
            assertEquals(total, result.total, "'$expr' total")
            assertTrue(source.exhausted, "'$expr' used fewer draws than the vector supplies")
        }
    }

    @Test
    fun `face is draw plus one, and N draws are taken for NdS`() {
        val result = rollWith("2d6", listOf(0, 5), listOf(TableRow(0, 2, 12, "any"))).getOrThrow()
        assertEquals(listOf(1, 6), result.dice)
        assertEquals(7L, result.total)
    }

    @Test
    fun `a modifier applies once to the sum, not to each die`() {
        val result = Roller(ScriptedDiceSource(listOf(2, 2))).roll(
            RollableTable(1, 42, DiceExpression.parse("2d6+3")!!,
                listOf(TableRow(0, 5, 15, "any")), "x"),
        ).getOrThrow()
        assertEquals(listOf(3, 3), result.dice)
        assertEquals(9L, result.total, "6 + 3, not 3+3 and 3+3")
    }

    @Test
    fun `a scripted draw outside the die's faces is refused`() {
        // The script is a fixture, and a fixture that quietly rolls a 7 on a d6 would make
        // every vector below it meaningless.
        assertFailsWith<IllegalArgumentException> {
            rollWith("d6", listOf(6), listOf(TableRow(0, 1, 6, "any")))
        }
    }

    // ---------------------------------------------------------------- table lookup

    @Test
    fun `the matching row is the one whose range contains the result`() {
        val rows = listOf(
            TableRow(0, 1, 2, "low"),
            TableRow(1, 3, 4, "middle"),
            TableRow(2, 5, 6, "high"),
        )
        assertEquals("low", rollWith("d6", listOf(0), rows).getOrThrow().row.text)
        assertEquals("middle", rollWith("d6", listOf(2), rows).getOrThrow().row.text)
        assertEquals("high", rollWith("d6", listOf(5), rows).getOrThrow().row.text)
    }

    @Test
    fun `a result matching no row fails closed rather than guessing`() {
        // Activation establishes that exactly one row will match. A defence that depends on
        // an earlier check having run is not a defence, and the cost of the belt is one
        // comparison.
        val gapped = listOf(TableRow(0, 1, 2, "low"), TableRow(1, 5, 6, "high"))
        val failure = rollWith("d6", listOf(2), gapped)
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("matches no row"))
    }

    @Test
    fun `a result matching two rows fails closed as well`() {
        val overlapping = listOf(TableRow(0, 1, 4, "a"), TableRow(1, 3, 6, "b"))
        val failure = rollWith("d6", listOf(2), overlapping)
        assertTrue(failure.isFailure)
        assertTrue(failure.exceptionOrNull()!!.message!!.contains("matches 2 rows"))
    }

    @Test
    fun `a result carries the chunk its outcome text is quoted from`() {
        // The outcome renders under the quotation rule, so it needs the table's own
        // citation -- not the citation of whatever card the user was looking at.
        assertEquals(42L, rollWith("d6", listOf(0)).getOrThrow().chunkId)
    }

    // ---------------------------------------------------------------- randomness

    @Test
    fun `the secure source stays inside the die's faces`() {
        val source = SecureDiceSource()
        repeat(20_000) { assertTrue(source.draw(6) in 0..5) }
        repeat(2_000) { assertTrue(source.draw(100) in 0..99) }
        assertTrue(source.draw(1) == 0, "a one-sided die has exactly one outcome")
    }

    @Test
    fun `the secure source reaches every face`() {
        // Not a distribution test -- those flake, and a suite that flakes gets disabled.
        // This asserts only that no face is unreachable, which a bounds bug would break.
        val source = SecureDiceSource()
        val seen = mutableSetOf<Int>()
        repeat(2_000) { seen += source.draw(20) }
        assertEquals((0..19).toSet(), seen)
    }

    @Test
    fun `two rollers do not agree`() {
        // Seeded from cryptographic entropy rather than the clock: two rolls in the same
        // millisecond must not agree, and a clock-seeded roller makes the first roll of a
        // session guessable.
        val first = (1..40).map { SecureDiceSource().draw(1000) }
        val second = (1..40).map { SecureDiceSource().draw(1000) }
        assertTrue(first != second, "forty draws from two fresh sources matched exactly")
    }

    @Test
    fun `a die with no faces is refused rather than dividing by zero`() {
        assertFailsWith<IllegalArgumentException> { SecureDiceSource().draw(0) }
    }

    private fun conformance(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null) {
            val file = candidate.resolve("conformance/dice-vectors.json")
            if (Files.isRegularFile(file)) return file
            candidate = candidate.parent
        }
        error("conformance/dice-vectors.json not found")
    }
}
