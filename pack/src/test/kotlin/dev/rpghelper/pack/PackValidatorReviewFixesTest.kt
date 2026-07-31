package dev.rpghelper.pack

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gaps found in review of the first implementation.
 *
 * Each one was a pack the validator would have activated despite it being unusable in a
 * way the design forbids. Kept in their own class because they share a theme -- checks
 * the schema spec implies but the validator did not make -- rather than because they
 * are second-class.
 */
class PackValidatorReviewFixesTest {

    private val supported = setOf(PackForge.EMBEDDER)
    private val directory: Path = Files.createTempDirectory("rpgpack-review")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun validate(mutate: (Connection) -> Unit = {}): ValidationReport =
        Packs.validateFile(PackForge.writePack(directory, mutate), supported)

    private fun assertRejects(code: ViolationCode, mutate: (Connection) -> Unit) {
        val baseline = validate()
        assertTrue(baseline.isValid, "the forged baseline pack must validate, but:\n$baseline")

        val report = validate(mutate)
        assertTrue(code in report.codes, "expected $code, but got:\n$report")
    }

    // ------------------------------------------------------------ the lexical index

    @Test
    fun `rejects a chunks_fts that is an ordinary table wearing the name`() {
        // sqlite_master lists virtual tables as type='table', so the required-table
        // check alone cannot tell these apart -- and MATCH would throw at first search.
        assertRejects(ViolationCode.FTS_INDEX_UNUSABLE) {
            it.exec("DROP TABLE chunks_fts")
            it.exec("CREATE TABLE chunks_fts (text, heading_path)")
        }
    }

    @Test
    fun `rejects an FTS5 index that was never populated`() {
        // The worse failure of the two: no error, just an empty lexical result on every
        // query for the life of the pack.
        assertRejects(ViolationCode.FTS_INDEX_UNUSABLE) {
            it.exec("DELETE FROM chunks_fts")
        }
    }

    @Test
    fun `rejects an FTS5 index missing the chunk the canary came from`() {
        assertRejects(ViolationCode.FTS_INDEX_UNUSABLE) {
            it.exec("DELETE FROM chunks_fts WHERE rowid = 1")
        }
    }

    // ------------------------------------------------------------ exception safety

    @Test
    fun `reports a malformed schema instead of throwing`() {
        // Every required relation name is present, but a column the validator reads is
        // gone. Before this the driver exception escaped the activation gate entirely.
        val report = validate {
            it.exec("DROP TABLE vectors")
            it.exec("CREATE TABLE vectors (chunk_id INTEGER, role TEXT)")
        }
        assertTrue(
            ViolationCode.MALFORMED_SCHEMA in report.codes,
            "expected MALFORMED_SCHEMA, got:\n$report",
        )
    }

    @Test
    fun `reports a malformed pack_meta instead of throwing`() {
        val report = validate {
            it.exec("DROP TABLE pack_meta")
            it.exec("CREATE TABLE pack_meta (id INTEGER PRIMARY KEY, schema_version INTEGER)")
            it.exec("INSERT INTO pack_meta VALUES (1, ${PackSchema.SCHEMA_VERSION})")
        }
        assertTrue(
            ViolationCode.MALFORMED_SCHEMA in report.codes,
            "expected MALFORMED_SCHEMA, got:\n$report",
        )
    }

    // ------------------------------------------------------------ source references

    @Test
    fun `rejects a chunk whose source is not in the pack`() {
        // Its quotation could not name the book it came from, which is the one thing a
        // quotation must be able to do.
        assertRejects(ViolationCode.DANGLING_SOURCE_REFERENCE) {
            it.exec("UPDATE chunks SET source_id = 99 WHERE chunk_id = 1")
        }
    }

    @Test
    fun `rejects a page-label range whose source is not in the pack`() {
        assertRejects(ViolationCode.DANGLING_SOURCE_REFERENCE) {
            it.exec("UPDATE source_page_labels SET source_id = 99 WHERE seq = 0 AND source_id = 2")
        }
    }

    @Test
    fun `rejects a gap whose source is not in the pack`() {
        assertRejects(ViolationCode.DANGLING_SOURCE_REFERENCE) {
            it.exec("UPDATE source_gaps SET source_id = 99 WHERE gap_id = 1")
        }
    }

    // ------------------------------------------------------------ derived nesting

    @Test
    fun `rejects a derived chunk that declares a parent`() {
        // Two derived chunks linked as parent and child pass every nesting check
        // vacuously: both have NULL source ids and NULL spans, so cross-source and
        // containment both skip.
        assertRejects(ViolationCode.DERIVED_CHUNK_NESTED) { connection ->
            connection.prepare(
                "INSERT INTO chunks (chunk_id, kind, origin, text, parent_chunk_id) " +
                    "VALUES (8, 'glossary', 'derived', ?, 7)",
            ) { it.setString(1, "A second derived note nested under the first.") }
            connection.exec(
                "INSERT INTO chunk_derivation (derivation_id, derived_chunk_id, source_chunk_id) " +
                    "VALUES (3, 8, 1)",
            )
            connection.exec(
                "INSERT INTO chunks_fts (rowid, text, heading_path) " +
                    "SELECT chunk_id, text, '' FROM chunks WHERE chunk_id = 8",
            )
            connection.prepare(
                "INSERT INTO vectors (chunk_id, role, subchunk_index, embedding, " +
                    "window_start, window_end) VALUES (8, 'content', 0, ?, 0, ?)",
            ) {
                it.setBytes(1, Float16.encodeVector(FloatArray(PackForge.EMBEDDER.dim) { 0.25f }))
                it.setInt(2, "A second derived note nested under the first.".length)
            }
        }
    }

    // ------------------------------------------------------------ stable keys

    @Test
    fun `rejects two chunks in one source sharing a stable key`() {
        // Supersession targets (source_uid, stable_key); a duplicate would filter an
        // unrelated passage alongside the one an erratum meant to correct.
        assertRejects(ViolationCode.DUPLICATE_STABLE_KEY) {
            it.exec("UPDATE chunks SET stable_key = 'core:grapple' WHERE chunk_id = 3")
        }
    }

    @Test
    fun `accepts the same stable key in two different sources`() {
        // Identity is scoped to the source, so this is not a collision.
        val report = validate {
            it.exec("UPDATE chunks SET stable_key = 'shared:key' WHERE chunk_id IN (1, 5)")
        }
        assertTrue(report.isValid, "stable keys are per-source, but:\n$report")
    }

    // ------------------------------------------------------------ sibling overlap

    @Test
    fun `reports every overlapping sibling pair, not only adjacent ones`() {
        // One long sibling enclosing two short ones overlaps both. Comparing only
        // adjacent spans after sorting finds the first pair and misses the second,
        // which would make the promise to report every violation false.
        val report = validate {
            it.exec(
                """
                UPDATE chunks
                SET span_start = (SELECT span_start FROM chunks WHERE chunk_id = 1) + 10,
                    span_end   = (SELECT span_start FROM chunks WHERE chunk_id = 1) + 20
                WHERE chunk_id = 3
                """.trimIndent(),
            )
            it.exec(
                """
                UPDATE chunks
                SET span_start = (SELECT span_start FROM chunks WHERE chunk_id = 1) + 30,
                    span_end   = (SELECT span_start FROM chunks WHERE chunk_id = 1) + 40
                WHERE chunk_id = 4
                """.trimIndent(),
            )
        }

        val overlaps = report.violations.filter {
            it.code == ViolationCode.SIBLING_SPAN_OVERLAP
        }
        assertEquals(
            2,
            overlaps.size,
            "chunk 1 encloses both 3 and 4, so both pairs must be reported:\n$report",
        )
        assertTrue(overlaps.any { it.detail.contains("chunks 1 ") && it.detail.contains(" 3 ") })
        assertTrue(overlaps.any { it.detail.contains("chunks 1 ") && it.detail.contains(" 4 ") })
    }

    // ------------------------------------------------------------ closed vocabularies

    @Test
    fun `rejects an unknown locator scheme`() {
        // Citation rendering has no defined format outside the closed set.
        assertRejects(ViolationCode.LOCATOR_SCHEME_INVALID) {
            it.exec("UPDATE sources SET locator_scheme = 'chapter' WHERE source_id = 1")
        }
    }

    @Test
    fun `rejects an unknown page-label scheme`() {
        assertRejects(ViolationCode.PAGE_LABEL_SCHEME_INVALID) {
            it.exec("UPDATE source_page_labels SET scheme = 'greek' WHERE source_id = 1 AND seq = 0")
        }
    }

    @Test
    fun `rejects an unknown gap reason`() {
        assertRejects(ViolationCode.GAP_REASON_INVALID) {
            it.exec("UPDATE source_gaps SET reason = 'because' WHERE gap_id = 1")
        }
    }

    // ------------------------------------------------------------ ruleset binding

    @Test
    fun `rejects constraints with no ruleset to bind them to`() {
        // Documents load constraints only from packs matching their ruleset binding, and
        // an unbound document validates nothing -- so these could never load at all.
        assertRejects(ViolationCode.CONSTRAINTS_WITHOUT_RULESET) {
            it.exec("UPDATE pack_meta SET ruleset_id = NULL")
        }
    }

    @Test
    fun `accepts a setting-only pack with no ruleset and no constraints`() {
        val report = validate {
            it.exec("DELETE FROM constraints")
            it.exec("UPDATE pack_meta SET ruleset_id = NULL")
        }
        assertTrue(report.isValid, "a setting-only pack needs no ruleset, but:\n$report")
    }
}
