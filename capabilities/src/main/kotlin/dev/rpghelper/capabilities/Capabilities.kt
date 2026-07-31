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
        // Rows are fetched **per surviving table**, not all at once. The supported ceiling
        // is a million of them, and a pack may carry many structured tables with few or no
        // roll-table capabilities -- so materializing every row and its text before any
        // manifest had been looked at made merely activating such a pack retain a very
        // large object graph for data nothing would ever ask for.
        val rows = mutableMapOf<Long, List<TableRow>>()
        fun rowsOf(tableId: Long): List<TableRow> =
            rows.getOrPut(tableId) { loadRowsOf(db, tableId) }
        // Classifications are fetched **per chunk a capability actually names**, for the
        // same reason the rows are. `Library.openActive` runs this on every question the
        // app answers, and a pack near the 500,000-chunk ceiling with no roll tables at all
        // was allocating a half-million nested pairs to classify targets that do not exist.
        val kinds = mutableMapOf<Long, Pair<String, String>?>()
        fun classify(chunkId: Long): Pair<String, String>? = kinds.getOrPut(chunkId) {
            db.map(
                "SELECT kind, origin FROM chunks WHERE chunk_id = $chunkId",
            ) { it.string(0) to it.string(1) }.firstOrNull()
        }

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
                // Typed, not coerced. `JsonPrimitive.content` turns `"1"` into a usable
                // table id and the boolean `false` into the label a user would read on a
                // roll control, so a manifest the schema forbids loads instead of being
                // dropped and reported.
                val id = obj.getValue("table_id").jsonPrimitive
                require(!id.isString) { "table_id must be a JSON number, not \"${id.content}\"" }
                val label = obj.getValue("label").jsonPrimitive
                require(label.isString) { "label must be a JSON string, not ${label.content}" }
                (id.content.toLongOrNull() ?: error("table_id '${id.content}' is not an integer")) to
                    label.content
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
            val classification = classify(chunkId)
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
                rows = rowsOf(tableId).sortedBy { it.lo },
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

    /** One table's rows. The id comes from the pack's own `tables` row, never from input. */
    private fun loadRowsOf(db: Db, tableId: Long): List<TableRow> =
        db.map(
            "SELECT seq, lo, hi, text FROM table_rows WHERE table_id = $tableId ORDER BY seq",
        ) { TableRow(it.long(0), it.long(1), it.long(2), it.string(3)) }
}
