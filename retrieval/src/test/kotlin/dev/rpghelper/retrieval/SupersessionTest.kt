package dev.rpghelper.retrieval

import dev.rpghelper.pack.JdbcDb
import dev.rpghelper.pack.PackForge
import dev.rpghelper.pack.exec
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SupersessionTest {

    private val directory: Path = Files.createTempDirectory("supersession")
    private val open = mutableListOf<JdbcDb>()

    @AfterTest
    fun cleanUp() {
        open.forEach { it.close() }
        directory.toFile().deleteRecursively()
    }

    /**
     * A pack under [uid], with the forge's baseline content and an optional mutation.
     *
     * The `pack_uid` is supplied by the caller rather than read from the file because the
     * app knows a pack's identity from `installed_packs` and never has two active
     * installs sharing one; forging distinct files is not what these tests are about.
     */
    private fun pack(uid: String, mutate: (Connection) -> Unit = {}): ActivePack {
        val db = JdbcDb.openReadOnly(PackForge.writePack(directory, mutate))
        open += db
        return ActivePack(uid, db)
    }

    /** Makes this pack's own books distinct, so it can only supersede *other* packs. */
    private fun asErrata(target: String, stableKey: String): (Connection) -> Unit = { c ->
        c.exec("UPDATE sources SET source_uid = 'errata:' || source_uid")
        c.exec(
            "UPDATE supersessions SET target_source_uid = '$target', " +
                "target_stable_key = '$stableKey'",
        )
    }

    // ---------------------------------------------------------------- targeting

    @Test
    fun `an errata pack whose target is not installed is inert`() {
        // The forge's baseline supersession names a book this pack does not contain, and
        // no other pack supplies it. That is a valid standalone errata pack with nothing
        // to amend, not a defect -- the citation snapshot is what makes its notice
        // readable anyway.
        val superseded = Supersession.compute(listOf(pack("core")))
        assertEquals(0, superseded.size)
    }

    @Test
    fun `a correction removes the chunk it targets`() {
        val core = pack("core")
        val errata = pack("errata", asErrata(PackForge.CORE_SOURCE_UID, "core:grapple"))

        val superseded = Supersession.compute(listOf(core, errata))

        assertTrue(superseded.contains("core", GRAPPLE), "the corrected rule is out")
        assertFalse(superseded.contains("core", UNDERDARK), "its neighbours are not")
    }

    @Test
    fun `targeting is by source and key together`() {
        // A stable key that exists, in a book that is not the one named, must not fire.
        // Keys are only unique within a source, so matching on the key alone would let an
        // errata for one publisher's book silently withdraw another's.
        val core = pack("core")
        val errata = pack("errata", asErrata(PackForge.ADVENTURE_SOURCE_UID, "core:grapple"))

        assertEquals(0, Supersession.compute(listOf(core, errata)).size)
    }

    @Test
    fun `a correction reaches every active copy of the book it names`() {
        // Two packs carrying the same source: the same passage, cited the same way. A
        // correction is about a passage in a book, not about a file.
        val first = pack("first")
        val second = pack("second")
        val errata = pack("errata", asErrata(PackForge.CORE_SOURCE_UID, "core:grapple"))

        val superseded = Supersession.compute(listOf(first, second, errata))

        assertTrue(superseded.contains("first", GRAPPLE))
        assertTrue(superseded.contains("second", GRAPPLE))
    }

    @Test
    fun `the superseding chunk itself survives`() {
        // Otherwise the correction withdraws the correction.
        val core = pack("core")
        val errata = pack("errata", asErrata(PackForge.CORE_SOURCE_UID, "core:grapple"))

        val superseded = Supersession.compute(listOf(core, errata))
        assertFalse(superseded.contains("errata", GRAPPLE), "the errata's own text stands")
    }

    // ---------------------------------------------------------------- the derivation cascade

    @Test
    fun `a summary of a corrected rule goes with it`() {
        // The forged derived chunk cites chunk 1 (the rule) and chunk 6 (the glossary). Its
        // entire claim to authority is those citations, so once one is withdrawn it is
        // stale prose carrying a pointer to text the app has agreed not to show -- and
        // unlike a superseded quote it still retrieves and still renders.
        val core = pack("core")
        val errata = pack("errata", asErrata(PackForge.CORE_SOURCE_UID, "core:grapple"))

        val superseded = Supersession.compute(listOf(core, errata))
        assertTrue(superseded.contains("core", DERIVED), "the summary goes with its source")
    }

    @Test
    fun `any cited chunk is enough, not all of them`() {
        // Chunk 6 is the derived chunk's *other* citation, in the other book entirely. A
        // summary of three rules, one of which was corrected, is wrong in exactly the
        // place someone would rely on it. Losing a summary is cheap.
        val core = pack("core")
        val errata = pack(
            "errata",
            asErrata(PackForge.ADVENTURE_SOURCE_UID, "adv:restrained"),
        )

        val superseded = Supersession.compute(listOf(core, errata))

        assertTrue(superseded.contains("core", GLOSSARY), "the corrected glossary entry")
        assertTrue(superseded.contains("core", DERIVED), "and the summary citing it")
        assertFalse(superseded.contains("core", GRAPPLE), "the rule it also cites stands")
    }

    @Test
    fun `the cascade runs to a fixpoint`() {
        // A summary of a summary. One pass deactivates the inner summary and leaves the
        // outer one standing on it, still rendering, still citing withdrawn text.
        val core = pack("core") { c ->
            c.exec(
                "INSERT INTO chunks (chunk_id, kind, origin, text) " +
                    "VALUES (8, 'glossary', 'derived', 'Grappling, in brief.')",
            )
            c.exec(
                "INSERT INTO chunk_derivation (derivation_id, derived_chunk_id, " +
                    "source_chunk_id) VALUES (3, 8, 7)",
            )
        }
        val errata = pack("errata", asErrata(PackForge.CORE_SOURCE_UID, "core:grapple"))

        val superseded = Supersession.compute(listOf(core, errata))

        assertTrue(superseded.contains("core", DERIVED), "the summary")
        assertTrue(superseded.contains("core", 8L), "and the summary built on it")
    }

    // ---------------------------------------------------------------- degenerate inputs

    @Test
    fun `no active packs supersede nothing`() {
        assertEquals(0, Supersession.compute(emptyList()).size)
    }

    @Test
    fun `a pack does not supersede itself by carrying both the book and its errata`() {
        // A single pack shipping a rulebook and its own errata is legal, and the errata
        // targets the book by uid -- which that same pack contains. The rule really is
        // withdrawn, in that pack, and nothing about it being one file changes that.
        val combined = pack("combined") { c ->
            // The correction is a different chunk from the one it withdraws — a row
            // naming its own target is refused at activation, since it would take the
            // replacement text down with the text it replaces.
            c.exec(
                "UPDATE supersessions SET superseding_chunk_id = $GLOSSARY, " +
                    "target_source_uid = '${PackForge.CORE_SOURCE_UID}', " +
                    "target_stable_key = 'core:grapple'",
            )
        }

        val superseded = Supersession.compute(listOf(combined))
        assertTrue(superseded.contains("combined", GRAPPLE))
    }

    private companion object {
        // Chunk ids the forge assigns; see PackForge.populate.
        const val GRAPPLE = 1L
        const val UNDERDARK = 3L
        const val GLOSSARY = 6L
        const val DERIVED = 7L
    }
}
