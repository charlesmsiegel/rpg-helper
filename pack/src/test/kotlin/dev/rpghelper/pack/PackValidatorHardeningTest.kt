package dev.rpghelper.pack

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Gaps found by adversarial review of the spec set.
 *
 * Every one shares a shape: the documents state that a pack is untrusted input and that
 * the app validates every property the DDL declares, and then the validator trusted the
 * builder at exactly the points where untrusted data feeds the app's two most
 * authoritative renderings — the generation context, and quotation-styled text.
 */
class PackValidatorHardeningTest {

    private val supported = setOf(PackForge.EMBEDDER)
    private val directory: Path = Files.createTempDirectory("rpgpack-hardening")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun validate(mutate: (Connection) -> Unit = {}): ValidationReport =
        Packs.validateFile(PackForge.writePack(directory, mutate), supported)

    /**
     * Rebuilds [table] without its constraints, preserving rows.
     *
     * The canonical DDL declares NOT NULL and UNIQUE, so a defect that violates one
     * cannot be injected with an UPDATE. That is the point of these tests: a pack ships
     * its own DDL, so those declarations describe what its builder chose to write and
     * guarantee nothing about the file in hand.
     */
    private fun Connection.relax(table: String) {
        exec("CREATE TABLE ${table}_lax AS SELECT * FROM $table")
        exec("DROP TABLE $table")
        exec("ALTER TABLE ${table}_lax RENAME TO $table")
    }

    private fun assertRejects(code: ViolationCode, mutate: (Connection) -> Unit) {
        val baseline = validate()
        assertTrue(baseline.isValid, "the forged baseline pack must validate, but:\n$baseline")
        val report = validate(mutate)
        assertTrue(code in report.codes, "expected $code, but got:\n$report")
    }

    // ---------------------------------------------------- nested text agreement

    @Test
    fun `rejects a child whose text is not what its parent holds at that offset`() {
        // Containment and span-length both pass. Only comparing the child's text against
        // the parent's own bytes catches it — and without that, redaction excises the
        // wrong region and the child's real text travels into the generation context.
        assertRejects(ViolationCode.NESTED_TEXT_MISMATCH) {
            // Same byte length, different content: no other check can see this.
            it.exec("UPDATE chunks SET text = 'X' || substr(text, 2) WHERE chunk_id = 2")
        }
    }

    @Test
    fun `accepts a nested child whose text matches its parent slice`() {
        val report = validate()
        assertTrue(report.isValid, "the fixture's nested table must agree with its parent:\n$report")
    }

    // ---------------------------------------------------- NULL in required columns

    @Test
    fun `reports a NULL in a required column instead of throwing`() {
        // The pack's own DDL says NOT NULL, and a pack's DDL is whatever its builder
        // wrote. Before this the reader raised NullPointerException, which is not a
        // PackReadException, escaped validate(), and put a corrupt pack past the gate as
        // a crash rather than a refusal.
        val report = validate {
            it.relax("chunks")
            it.exec("UPDATE chunks SET text = NULL WHERE chunk_id = 3")
        }
        assertTrue(
            ViolationCode.MALFORMED_SCHEMA in report.codes,
            "expected MALFORMED_SCHEMA, got:\n$report",
        )
    }

    @Test
    fun `reports a NULL probe vector instead of throwing`() {
        val report = validate {
            it.relax("pack_meta")
            it.exec("UPDATE pack_meta SET probe_vector = NULL")
        }
        assertTrue(
            ViolationCode.MALFORMED_SCHEMA in report.codes,
            "expected MALFORMED_SCHEMA, got:\n$report",
        )
    }

    @Test
    fun `rejects a constraint with no chunk to cite`() {
        // A violation with no passage behind it is one routing refuses to render at all,
        // so the rule silently stops being reportable.
        assertRejects(ViolationCode.MISSING_CHUNK_REFERENCE) {
            it.relax("constraints")
            it.exec("UPDATE constraints SET chunk_id = NULL")
        }
    }

    // ---------------------------------------------------- source identity

    @Test
    fun `rejects two sources sharing a source_uid`() {
        // The canonical DDL declares UNIQUE, which guarantees nothing about a file
        // someone else built — so the fixture rebuilds the table without it, exactly as
        // a laxer builder would. Supersession targets (source_uid, stable_key), so a
        // duplicate makes every erratum aimed at it ambiguous across two books.
        assertRejects(ViolationCode.DUPLICATE_SOURCE_UID) {
            it.relax("sources")
            it.exec("UPDATE sources SET source_uid = 'test:core:1e' WHERE source_id = 2")
        }
    }

    // ---------------------------------------------------- structured table rows

    @Test
    fun `rejects a table row whose text is not the slice its span names`() {
        // The roller renders outcome text under the quotation rule. Unchecked, this
        // launders arbitrary prose into quotation styling beneath the table's citation.
        assertRejects(ViolationCode.TABLE_ROW_TEXT_MISMATCH) {
            it.exec("UPDATE table_rows SET text = 'Roll again and take the best' WHERE seq = 0")
        }
    }

    @Test
    fun `rejects a table row whose span falls outside its table chunk`() {
        assertRejects(ViolationCode.TABLE_ROW_SPAN_OUTSIDE_CHUNK) {
            it.exec("UPDATE table_rows SET span_start = 0, span_end = 8 WHERE seq = 0")
        }
    }

    @Test
    fun `rejects overlapping outcome ranges`() {
        // Two rows matching one roll means the result is whichever the query returned
        // first, which is not a rule anyone printed.
        assertRejects(ViolationCode.TABLE_ROW_RANGE_OVERLAP) {
            it.exec("UPDATE table_rows SET hi = 3 WHERE seq = 0")
        }
    }

    @Test
    fun `rejects an inverted outcome range`() {
        assertRejects(ViolationCode.TABLE_ROW_RANGE_OVERLAP) {
            it.exec("UPDATE table_rows SET lo = 5, hi = 2 WHERE seq = 0")
        }
    }

    @Test
    fun `rejects table rows naming a table that is not in the pack`() {
        assertRejects(ViolationCode.DANGLING_TABLE_REFERENCE) {
            it.exec("UPDATE table_rows SET table_id = 99")
        }
    }

    // ---------------------------------------------------- the FTS canary

    @Test
    fun `canary is a complete token, never a truncated one`() {
        // A 16-letter first word must not be clipped: FTS5 matches whole tokens, so a
        // substring finds nothing and a perfectly valid pack is rejected.
        assertEquals("acknowledgements", asciiWord("Acknowledgements and credits"))
    }

    @Test
    fun `canary skips tokens containing digits`() {
        // unicode61 treats digits as token characters, so the token here is "2d6damage";
        // taking the letter run "damage" would search for something not in the index.
        assertEquals("rounds", asciiWord("2d6damage rounds"))
    }

    @Test
    fun `canary skips tokens containing non-ASCII letters`() {
        // The indexed token is "cafe" after diacritic folding; the ASCII run "Caf" is not
        // a token at all.
        assertEquals("terrace", asciiWord("Café terrace"))
    }

    @Test
    fun `canary rejects text with no usable token`() {
        assertNull(asciiWord("— 12 34 ——"))
        assertNull(asciiWord("a b c"))
    }

    @Test
    fun `canary takes the first usable token`() {
        assertEquals("grappling", asciiWord("Grappling. To grapple a creature…"))
    }
}
