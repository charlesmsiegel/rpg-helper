package dev.rpghelper.retrieval

import dev.rpghelper.pack.JdbcDb
import dev.rpghelper.pack.PackForge
import dev.rpghelper.pack.exec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the pipeline does when the query itself is the awkward part.
 *
 * Both cases here are pack data or user input that is entirely valid and that the earlier
 * code turned into a silent no-op — a search that ran against nothing, or a match against
 * a vector that means nothing.
 */
class PipelineBudgetTest {

    private val directory: Path = Files.createTempDirectory("budget")
    private val open = mutableListOf<JdbcDb>()

    @AfterTest
    fun cleanUp() {
        open.forEach { it.close() }
        directory.toFile().deleteRecursively()
    }

    /** A pack whose `grapple` alias expands to more terms than the whole budget. */
    private fun packWithWideAlias(): ActiveSet {
        val wide = (1..LexicalQuery.MAX_TERMS).joinToString(" ") { "canonical$it" }
        val path = PackForge.writePack(directory) { c ->
            c.exec("DELETE FROM entities")
            c.exec(
                "INSERT INTO entities (entity_id, canonical, alias, kind, chunk_id) " +
                    "VALUES (1, '$wide', 'grapple', 'rule', 1)",
            )
        }
        val db = JdbcDb.openReadOnly(path)
        open += db
        val pack = ActivePack(PackForge.PACK_UID, db)
        return ActiveSet(
            listOf(pack),
            mapOf(PackForge.PACK_UID to 0),
            mapOf(PackForge.PACK_UID to PackForge.EMBEDDER.id),
            SupersededSet.EMPTY,
        )
    }

    @Test
    fun `an alias too wide to expand keeps the user's own wording`() {
        // Several active packs defining one alias with different multi-word canonicals is
        // enough to blow the term budget on the first concept, and breaking there left the
        // expression null and skipped lexical retrieval entirely -- a search that silently
        // did not run. The expansions go; what the user typed stays.
        val retrieved = Pipeline.retrieve("grapple", packWithWideAlias())

        assertTrue(
            retrieved.gatedOut.any { "alias expansions dropped" in it && "grapple" in it },
            "the drop is recorded rather than absorbed: ${retrieved.gatedOut}",
        )
        assertTrue(
            retrieved.candidates.any { it.stableKey == "core:grapple" },
            "the rule is still found by the word the user typed: ${retrieved.candidates}",
        )
    }

    @Test
    fun `a query with no terms never reaches dense search`() {
        // An embedder returns a uniform vector rather than a zero one for text with no
        // tokens, because a zero vector is undefined under cosine rather than weak. A
        // punctuation-only *chunk* embeds to that same uniform vector, so without this a
        // query of "???" scores cosine 1.0 against it and answers with whatever card that
        // chunk belongs to.
        val active = packWithWideAlias()
        val uniform = FloatArray(PackForge.EMBEDDER.dim) {
            (1.0 / kotlin.math.sqrt(PackForge.EMBEDDER.dim.toDouble())).toFloat()
        }
        val retrieved = Pipeline.retrieve(
            "???",
            active,
            queryVectors = mapOf(PackForge.EMBEDDER.id to uniform),
            gates = Gates(dense = mapOf(PackForge.EMBEDDER.id to 0.5)),
        )

        assertEquals(emptyList(), retrieved.candidates)
        assertTrue(
            retrieved.gatedOut.any { "no terms to match on" in it },
            "and it says so: ${retrieved.gatedOut}",
        )
    }
}
