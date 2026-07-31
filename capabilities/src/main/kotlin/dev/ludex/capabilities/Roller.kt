package dev.ludex.capabilities

import dev.ludex.pack.DiceExpression

/** One row of a rollable table. */
data class TableRow(val seq: Long, val lo: Long, val hi: Long, val text: String)

/** A table the app may roll on, with the chunk its outcome text is quoted from. */
data class RollableTable(
    val tableId: Long,
    val chunkId: Long,
    val expression: DiceExpression,
    val rows: List<TableRow>,
    val label: String,
)

/** What a roll produced. */
data class RollResult(
    val expression: DiceExpression,
    /** Each die's face, in order. Shown so a user can confirm the app rolled what it said. */
    val dice: List<Int>,
    val modifier: Int,
    val total: Long,
    val row: TableRow,
    /** The chunk the outcome text is a validated span of, for the citation. */
    val chunkId: Long,
)

/** A roll that produced nothing, and why. Never a partial result. */
data class RollFailure(val reason: String)

/**
 * Rolls on a validated table.
 *
 * The builder validates coverage, spans, and round-trip rendering against the source, and
 * activation re-checks everything decidable on-device. **The roller still fails closed**
 * when a result matches no row: a defence that depends on an earlier check having run is
 * not a defence, and the cost of the belt is one comparison.
 */
class Roller(private val source: DiceSource) {

    fun roll(table: RollableTable): Result<RollResult> {
        val expression = table.expression

        // Each die independently, then the modifier. NOT one uniform draw over the range:
        // `2d6` and `d11+1` share 2-12 and are not interchangeable -- the first is
        // triangular and peaks at 7, the second is flat. Collapsing NdS into a single draw
        // returns outcomes at wrong frequencies forever, a bug no coverage check can see
        // because every outcome remains reachable, and one no user reports because a
        // single roll looks fine.
        val dice = (1..expression.count).map { source.draw(expression.sides) + 1 }
        val total = dice.sumOf { it.toLong() } + expression.modifier

        val matching = table.rows.filter { total in it.lo..it.hi }
        if (matching.size != 1) {
            return Result.failure(
                RollException(
                    "'$expression' rolled $total, which ${
                        if (matching.isEmpty()) "matches no row" else "matches ${matching.size} rows"
                    } of table ${table.tableId}; the capability is unusable",
                ),
            )
        }

        return Result.success(
            RollResult(
                expression = expression,
                dice = dice,
                modifier = expression.modifier,
                total = total,
                row = matching.single(),
                chunkId = table.chunkId,
            ),
        )
    }
}

class RollException(message: String) : Exception(message)
