package dev.rpghelper.capabilities

import dev.rpghelper.pack.Db
import dev.rpghelper.pack.DiceExpression
import dev.rpghelper.pack.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A capability the app dropped, and why. Listed on the Packs surface. */
data class DroppedCapability(val capabilityId: Long, val reason: String)

/** What loading a pack's capabilities produced. */
data class CapabilitySet(
    val tables: List<RollableTable>,
    val dropped: List<DroppedCapability>,
)

/**
 * Loads a pack's capability manifests.
 *
 * **The vocabulary is closed and unknown kinds are ignored**, not refused. A pack built
 * against a newer app carries capabilities an older one does not recognise, and the older
 * app renders everything else normally: the user sees a quotable table with no roll
 * control, which is exactly what they would see for a table that failed validation.
 * Nothing is wrong on screen.
 *
 * That is deliberately different from the dice grammar, where adding a form *is* a
 * `schema_version` bump. An unrecognised capability is a feature that does not appear; an
 * unrecognised dice form would be mis-parsed by an older grammar and roll outside its
 * validated range — a result that is wrong and looks right. Ignorable degradation is
 * versioned by the app; misinterpretable meaning is versioned by the pack.
 *
 * **Manifest content is not structural and does not reject the pack.** A malformed
 * manifest drops that one capability. Refusing a 300-page book over it is the "builder
 * nobody can use" failure applied to activation — but the drop is *reported*, because a
 * missing capability is acceptable and a user left guessing which they got is not.
 */
object Capabilities {

    private val json = Json { ignoreUnknownKeys = true }

    /** The one kind, deliberately. A vocabulary invented ahead of its second entry is a guess. */
    const val ROLL_TABLE = "roll-table"

    /**
     * @param superseded chunk ids this pack's active set has withdrawn. A capability rooted
     * at one is dropped: the supersession cascade reaches capabilities precisely so a
     * correction cannot leave a roller offering outcomes from text retrieval has removed.
     */
    fun load(db: Db, superseded: Set<Long> = emptySet()): CapabilitySet {
        val tables = loadTables(db)
        val rows = loadRows(db)
        val kinds = db.map("SELECT chunk_id, kind, origin FROM chunks") {
            it.long(0) to (it.string(1) to it.string(2))
        }.toMap()

        val loaded = mutableListOf<RollableTable>()
        val dropped = mutableListOf<DroppedCapability>()

        db.map("SELECT capability_id, kind, chunk_id, manifest FROM capabilities") {
            listOf(it.long(0), it.string(1), it.long(2), it.string(3))
        }.forEach { fields ->
            val id = fields[0] as Long
            val kind = fields[1] as String
            val chunkId = fields[2] as Long
            val manifest = fields[3] as String

            if (chunkId in superseded) {
                dropped += DroppedCapability(
                    id,
                    "rooted at chunk $chunkId, which an active correction has withdrawn",
                )
                return@forEach
            }

            if (kind != ROLL_TABLE) {
                dropped += DroppedCapability(id, "unrecognised kind '$kind'; this build ignores it")
                return@forEach
            }

            val parsed = runCatching {
                val obj = json.parseToJsonElement(manifest).jsonObject
                obj.getValue("table_id").jsonPrimitive.content.toLong() to
                    obj.getValue("label").jsonPrimitive.content
            }.getOrElse {
                dropped += DroppedCapability(id, "manifest does not parse: ${it.message}")
                return@forEach
            }
            val (tableId, label) = parsed

            val table = tables[tableId]
            if (table == null) {
                dropped += DroppedCapability(id, "names table $tableId, which is not in this pack")
                return@forEach
            }

            // The manifest must target its own chunk. Requiring only that `table_id`
            // resolves would let a pack attach table B's roller to quote card A -- and it
            // breaks supersession, because the cascade deactivates capabilities rooted at
            // the superseded chunk, so withdrawing B would leave a roller rooted at A
            // still offering to roll on it.
            if (table.first != chunkId) {
                dropped += DroppedCapability(
                    id,
                    "is rooted at chunk $chunkId but table $tableId belongs to chunk ${table.first}",
                )
                return@forEach
            }

            // And that chunk must be a verbatim source table. Outcome text renders under
            // the quotation rule, so attaching validated rows to a `setting` or derived
            // chunk would launder non-verbatim prose into quote styling beneath a real
            // citation -- past the routing partition that exists to stop exactly that.
            val classification = kinds[chunkId]
            if (classification != ("table" to "source")) {
                dropped += DroppedCapability(
                    id,
                    "targets chunk $chunkId, which is ${classification?.first}/" +
                        "${classification?.second} rather than a verbatim source table",
                )
                return@forEach
            }

            val expression = DiceExpression.parse(table.second)
            if (expression == null) {
                // Cannot happen for a pack whose builder used this grammar version, and
                // the app does not guess at expressions it does not recognise.
                dropped += DroppedCapability(
                    id,
                    "table $tableId declares '${table.second}', which the pinned grammar refuses",
                )
                return@forEach
            }

            loaded += RollableTable(
                tableId = tableId,
                chunkId = chunkId,
                expression = expression,
                rows = rows[tableId].orEmpty().sortedBy { it.lo },
                label = label,
            )
        }

        return CapabilitySet(loaded, dropped)
    }

    /** `table_id` to its `(chunk_id, dice_expr)`. */
    private fun loadTables(db: Db): Map<Long, Pair<Long, String>> =
        db.map("SELECT table_id, chunk_id, dice_expr FROM tables") {
            it.long(0) to (it.long(1) to it.string(2))
        }.toMap()

    private fun loadRows(db: Db): Map<Long, List<TableRow>> =
        db.map("SELECT table_id, seq, lo, hi, text FROM table_rows") {
            it.long(0) to TableRow(it.long(1), it.long(2), it.long(3), it.string(4))
        }.groupBy({ it.first }, { it.second })
}
