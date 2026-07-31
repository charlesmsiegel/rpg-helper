package dev.rpghelper.capabilities

import dev.rpghelper.pack.JdbcDb
import dev.rpghelper.pack.PackForge
import dev.rpghelper.pack.Packs
import dev.rpghelper.pack.exec
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CapabilitiesTest {

    private val directory: Path = Files.createTempDirectory("capabilities")
    private val opened = mutableListOf<JdbcDb>()

    @AfterTest
    fun cleanUp() {
        opened.forEach { it.close() }
        directory.toFile().deleteRecursively()
    }

    private fun load(
        superseded: Set<Long> = emptySet(),
        mutate: (Connection) -> Unit = {},
    ): CapabilitySet {
        val path = PackForge.writePack(directory, mutate)
        val db = JdbcDb.openReadOnly(path)
        opened += db
        return Capabilities.load(db, superseded)
    }

    /** A pack whose capabilities are malformed must still activate. */
    private fun stillActivates(mutate: (Connection) -> Unit) {
        val path = PackForge.writePack(directory, mutate)
        val report = Packs.validateFile(path, setOf(PackForge.EMBEDDER))
        assertTrue(report.isValid, "a malformed manifest must not reject the book:\n$report")
    }

    @Test
    fun `a well-formed roll-table manifest loads`() {
        val set = load()
        val table = set.tables.single()
        assertEquals(1L, table.tableId)
        assertEquals(2L, table.chunkId, "rooted at the table chunk it rolls on")
        assertEquals("d6", table.expression.toString())
        assertEquals(6, table.rows.size)
        assertTrue(set.dropped.isEmpty())
    }

    @Test
    fun `rows arrive ordered by their range, whatever order they were stored in`() {
        val rows = load().tables.single().rows
        assertEquals(rows.sortedBy { it.lo }, rows)
        assertEquals(1L, rows.first().lo)
        assertEquals(6L, rows.last().hi)
    }

    // ---------------------------------------------------------------- dropping, not refusing

    @Test
    fun `an unrecognised kind is ignored, and the pack is untouched`() {
        // A pack built against a newer app carries capabilities this one does not know.
        // The user sees a quotable table with no roll control -- exactly what they would
        // see for a table that failed validation. Nothing is wrong on screen.
        val mutate: (Connection) -> Unit = {
            it.exec("UPDATE capabilities SET kind = 'roll-initiative'")
        }
        val set = load(mutate = mutate)
        assertTrue(set.tables.isEmpty())
        assertTrue(set.dropped.single().reason.contains("unrecognised kind"))
        stillActivates(mutate)
    }

    @Test
    fun `a manifest that does not parse drops one capability, not the book`() {
        val mutate: (Connection) -> Unit = {
            it.exec("UPDATE capabilities SET manifest = '{not json'")
        }
        val set = load(mutate = mutate)
        assertTrue(set.tables.isEmpty())
        assertTrue(set.dropped.single().reason.contains("does not parse"))
        stillActivates(mutate)
    }

    @Test
    fun `a manifest naming a table that is not in the pack is dropped`() {
        val mutate: (Connection) -> Unit = {
            it.exec("""UPDATE capabilities SET manifest = '{"table_id":99,"label":"x"}'""")
        }
        val set = load(mutate = mutate)
        assertTrue(set.dropped.single().reason.contains("not in this pack"))
        stillActivates(mutate)
    }

    @Test
    fun `a manifest missing a required field is dropped`() {
        val mutate: (Connection) -> Unit = {
            it.exec("""UPDATE capabilities SET manifest = '{"label":"no table id"}'""")
        }
        assertTrue(load(mutate = mutate).dropped.single().reason.contains("does not parse"))
        stillActivates(mutate)
    }

    @Test
    fun `a roller rooted at a chunk other than its table's is dropped`() {
        // Requiring only that table_id resolves would let a pack attach table B's roller to
        // quote card A -- and it breaks supersession, because withdrawing B leaves a roller
        // rooted at A still offering to roll on it.
        val mutate: (Connection) -> Unit = { it.exec("UPDATE capabilities SET chunk_id = 1") }
        val set = load(mutate = mutate)
        assertTrue(set.tables.isEmpty())
        assertTrue(set.dropped.single().reason.contains("is rooted at chunk 1"))
    }

    @Test
    fun `a capability rooted at a superseded chunk is withdrawn with it`() {
        // The cascade reaches capabilities precisely so a correction cannot leave a roller
        // offering outcomes from text every other route has already removed.
        val set = load(superseded = setOf(2L))
        assertTrue(set.tables.isEmpty())
        assertTrue(set.dropped.single().reason.contains("withdrawn"))
    }

    @Test
    fun `a roller targeting a non-table chunk is dropped`() {
        // Outcome text renders under the quotation rule. Attaching validated rows to a
        // setting or derived chunk would launder non-verbatim prose into quote styling
        // beneath a real citation, past the routing partition that exists to stop it.
        val set = load(mutate = {
            it.exec("UPDATE tables SET chunk_id = 3")
            it.exec("UPDATE capabilities SET chunk_id = 3")
        })
        assertTrue(set.tables.isEmpty())
        assertTrue(set.dropped.single().reason.contains("rather than a verbatim source table"))
    }

    @Test
    fun `every drop is reported, so a missing control is never a mystery`() {
        // A missing capability is acceptable; a user left guessing which they got is not.
        val set = load(mutate = { it.exec("UPDATE capabilities SET manifest = '{}'") })
        assertEquals(1, set.dropped.size)
        assertEquals(1L, set.dropped.single().capabilityId, "named, so the Packs surface can list it")
    }

    // ---------------------------------------------------------------- end to end

    @Test
    fun `a loaded capability rolls and lands on a real outcome`() {
        val table = load().tables.single()
        for (draw in 0..5) {
            val result = Roller(ScriptedDiceSource(listOf(draw))).roll(table).getOrThrow()
            assertEquals((draw + 1).toLong(), result.total)
            assertTrue(result.row.text.isNotEmpty())
            assertEquals(table.chunkId, result.chunkId)
        }
    }

    @Test
    fun `every outcome of the table is reachable and exactly one row matches each`() {
        // The property activation checks and the roller re-checks. Asserted here over a
        // real pack so a change to either side shows up as this test rather than as a roll
        // failing at someone's table.
        val table = load().tables.single()
        val seen = (table.expression.min..table.expression.max).map { total ->
            table.rows.count { total in it.lo..it.hi }
        }
        assertTrue(seen.all { it == 1 }, "coverage is exactly one row per outcome: $seen")
    }
}
