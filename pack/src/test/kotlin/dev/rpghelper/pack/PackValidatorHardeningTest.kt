package dev.rpghelper.pack

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

    // ---------------------------------------------------- lexical index coverage

    @Test
    fun `rejects an index populated for some chunks but not others`() {
        // The canary alone cannot see this: it searches a term from the lowest-numbered
        // chunk, which is still indexed. Every other chunk would be invisible to lexical
        // search for the life of the pack, silently.
        assertRejects(ViolationCode.FTS_INDEX_UNUSABLE) {
            it.exec("DELETE FROM chunks_fts WHERE rowid = 5")
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
    fun `canary is not fooled by a supplementary-plane letter`() {
        // A UTF-16 Char scan reads both halves of a surrogate pair as separators, so it
        // picked `abc` while the index stored the single token `\uD800\uDF00abc` -- the probe then
        // found nothing and refused a correctly populated pack.
        assertEquals("rounds", asciiWord("\uD800\uDF00abc rounds"))
    }

    @Test
    fun `canary skips a token unicode61 would keep whole`() {
        // `\u2163` is a letter number and a token character to the index, so `iv` is not a
        // token that exists in it.
        assertEquals("rounds", asciiWord("\u2163iv rounds"))
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

    // ---------------------------------------------------- second review round

    @Test
    fun `reports a NULL numeric column instead of reading it as zero`() {
        // getLong returns 0 for SQL NULL, so a NULL source_id would read as source 0 --
        // a value the validator reasons about as resolved while every join finds nothing.
        val report = validate {
            it.relax("chunks")
            it.exec("UPDATE chunks SET chunk_id = NULL WHERE rowid = 3")
        }
        assertTrue(
            ViolationCode.MALFORMED_SCHEMA in report.codes,
            "expected MALFORMED_SCHEMA, got:\n$report",
        )
    }

    @Test
    fun `rejects two sources sharing a source_id`() {
        // The PRIMARY KEY declaration is the builder's word. A duplicate leaves every
        // citation join free to return either book.
        assertRejects(ViolationCode.DUPLICATE_SOURCE_ID) {
            it.relax("sources")
            it.exec("UPDATE sources SET source_id = 1 WHERE source_id = 2")
        }
    }

    @Test
    fun `rejects an index that omits a chunk while holding a stray document`() {
        // Equal cardinality, unequal sets: the count check alone passes and the canary,
        // drawn from another chunk, still succeeds.
        assertRejects(ViolationCode.FTS_INDEX_UNUSABLE) {
            it.exec("DELETE FROM chunks_fts WHERE rowid = 5")
            it.exec("INSERT INTO chunks_fts (rowid, text, heading_path) VALUES (999, 'x', '')")
        }
    }

    @Test
    fun `rejects an alias that is not in indexed form`() {
        // Matching is an indexed lookup, so this alias could never fire.
        assertRejects(ViolationCode.ALIAS_NOT_NORMALIZED) {
            it.exec("UPDATE entities SET alias = 'Fireball' WHERE entity_id = 1")
        }
    }

    @Test
    fun `rejects an alias carrying diacritics the tokenizer folds away`() {
        assertRejects(ViolationCode.ALIAS_NOT_NORMALIZED) {
            it.exec("UPDATE entities SET alias = 'v\u00e1ss' WHERE entity_id = 1")
        }
    }

    @Test
    fun `rejects a non-verbatim child of a verbatim-class parent`() {
        // An exact slice of rule text, reclassified as setting: retrieved alone it goes
        // to generation, because a child candidate has no ancestor span redacted from it.
        assertRejects(ViolationCode.NONVERBATIM_CHILD_OF_VERBATIM_PARENT) {
            it.exec("UPDATE chunks SET kind = 'setting' WHERE chunk_id = 2")
        }
    }

    @Test
    fun `rejects a blank pack uid`() {
        // A blank uid enters the unique install namespace, so every other blank-uid pack
        // looks like a replacement for it.
        assertRejects(ViolationCode.PACK_UID_INVALID) {
            it.exec("UPDATE pack_meta SET pack_uid = '   '")
        }
    }

    @Test
    fun `rejects an overlong pack uid`() {
        assertRejects(ViolationCode.PACK_UID_INVALID) {
            it.exec("UPDATE pack_meta SET pack_uid = printf('%.*c', 500, 'x')")
        }
    }

    @Test
    fun `folds text the way the pinned tokenizer does`() {
        assertEquals("cafe", Utf8.foldForIndex("Caf\u00e9"))
        assertEquals("vass", Utf8.foldForIndex("V\u00e1ss"))
        assertEquals("grapple", Utf8.foldForIndex("Grapple"))
        assertEquals("grapple", Utf8.foldForIndex("grapple"))
    }

    // ---------------------------------------------------- table coverage

    @Test
    fun `rejects a table with a gap in its outcome range`() {
        // The roller asserts exactly one row contains a result. That assertion used to be
        // the builder's, made about the artifact under inspection; a gap leaves a roll
        // with no row at all.
        assertRejects(ViolationCode.TABLE_ROWS_INCOMPLETE) {
            it.exec("DELETE FROM table_rows WHERE seq = 3")
        }
    }

    @Test
    fun `refuses an out-of-range schema version rather than truncating it`() {
        // 4294967297 narrows to exactly 1 in an Int. The version is the one claim the gate
        // must read literally, because on an unrecognised schema the remaining columns may
        // not mean what this code thinks they mean.
        assertRejects(ViolationCode.UNSUPPORTED_SCHEMA_VERSION) {
            it.exec("UPDATE pack_meta SET schema_version = 4294967297")
        }
    }

    @Test
    fun `rejects an index built with a different tokenizer`() {
        // The app folds query terms as unicode61 remove_diacritics 2. An index built any
        // other way holds `café` while every query carries `cafe`, and the content is
        // silently unretrievable for the life of the pack -- no canary catches it, because
        // a canary is ASCII by construction.
        assertRejects(ViolationCode.FTS_INDEX_UNUSABLE) {
            it.exec("DROP TABLE chunks_fts")
            it.exec(
                "CREATE VIRTUAL TABLE chunks_fts USING fts5(text, heading_path, " +
                    "content='chunks', content_rowid='chunk_id', tokenize='unicode61')",
            )
            it.exec(
                "INSERT INTO chunks_fts (rowid, text, heading_path) " +
                    "SELECT chunk_id, text, COALESCE(heading_path, '') FROM chunks",
            )
        }
    }

    @Test
    fun `rejects an index holding documents no chunk owns`() {
        // An orphan can win a search, consume the depth budget, and resolve to nothing --
        // so a query refuses with usable chunks sitting just below the limit.
        assertRejects(ViolationCode.FTS_INDEX_UNUSABLE) {
            it.exec("INSERT INTO chunks_fts (rowid, text, heading_path) VALUES (999, 'ghost', '')")
        }
    }

    @Test
    fun `rejects two tables sharing a table_id`() {
        // Every row would be validated against one definition while the roller resolved
        // the id to either -- rolling on one table's outcomes beneath the other's citation.
        assertRejects(ViolationCode.DUPLICATE_TABLE_ID) {
            it.relax("tables")
            it.exec("INSERT INTO tables (table_id, chunk_id, dice_expr) VALUES (1, 1, 'd6')")
        }
    }

    @Test
    fun `rejects two chunks sharing a chunk_id`() {
        // A duplicate does not merely break lookups at runtime: it shrinks the validator's
        // own view of the pack, so the checks that would have caught the rest of the
        // damage never see the missing rows.
        assertRejects(ViolationCode.DUPLICATE_CHUNK_ID) {
            it.relax("chunks")
            it.exec("INSERT INTO chunks SELECT * FROM chunks WHERE chunk_id = 3")
        }
    }

    @Test
    fun `refuses a dice expression whose range would overflow`() {
        // `2d2147483647` is grammatical under unbounded "positive integers" and overflows
        // a 32-bit maximum to a negative number, at which point coverage accepts a table
        // with no rows at all.
        assertRejects(ViolationCode.DICE_EXPR_UNPARSEABLE) {
            it.exec("UPDATE tables SET dice_expr = '2d2147483647' WHERE table_id = 1")
        }
        assertRejects(ViolationCode.DICE_EXPR_UNPARSEABLE) {
            it.exec("UPDATE tables SET dice_expr = '101d6' WHERE table_id = 1")
        }
    }

    @Test
    fun `rejects an erratum that withdraws its own correction`() {
        // Supersession applies to every active pack carrying the targeted source,
        // including the pack the erratum lives in -- so a row naming its own target takes
        // the replacement down with what it replaces. The rule goes silently missing and
        // nothing explains why, which is worse than shipping no errata.
        assertRejects(ViolationCode.SUPERSESSION_WITHDRAWS_ITSELF) {
            it.exec(
                "UPDATE supersessions SET target_source_uid = 'test:core:1e', " +
                    "target_stable_key = 'core:grapple'",
            )
        }
    }

    @Test
    fun `rejects a rollable table with no rows at all`() {
        // The empty table reaches no row, so a coverage check keyed off the rows that
        // exist never looks at it -- and it activates as a table the app offers to roll
        // on where every result has no outcome.
        assertRejects(ViolationCode.TABLE_ROWS_INCOMPLETE) {
            it.exec("DELETE FROM table_rows WHERE table_id = 1")
        }
    }

    @Test
    fun `rejects a table whose rows stop short of its range`() {
        assertRejects(ViolationCode.TABLE_ROWS_INCOMPLETE) {
            it.exec("DELETE FROM table_rows WHERE seq = 5")
        }
    }

    @Test
    fun `rejects a row outside the expression's range`() {
        // An outcome that can never be rolled.
        assertRejects(ViolationCode.TABLE_ROWS_INCOMPLETE) {
            it.exec("UPDATE table_rows SET lo = 7, hi = 7 WHERE seq = 5")
        }
    }

    @Test
    fun `rejects a dice expression the grammar refuses`() {
        assertRejects(ViolationCode.DICE_EXPR_UNPARSEABLE) {
            it.exec("UPDATE tables SET dice_expr = '4d6kh3'")
        }
    }

    @Test
    fun `accepts a table whose rows cover its range exactly`() {
        val report = validate()
        assertTrue(report.isValid, "the fixture's d6 table covers 1-6:\n$report")
    }

    // ---------------------------------------------------- widths the pack chooses

    @Test
    fun `an embedder dimension whose low 32 bits match is still refused`() {
        // `4294967424` narrows to 128, and a 128-dimensional contract is bundled here. The
        // open-time check read the field as a Long and rejected it while the full validator
        // narrowed it and accepted -- so `install` and `verify` reported that the pack
        // activates, and the very first borrow deactivated it as unreadable. Two checks of
        // one field disagreeing is worse than either of them alone.
        val report = validate {
            it.relax("pack_meta")
            it.exec("UPDATE pack_meta SET embedder_dim = 4294967424")
        }
        assertFalse(report.isValid, "expected a refusal, got:\n$report")
    }

    @Test
    fun `a schema version whose low 32 bits match is still refused`() {
        val report = validate {
            it.relax("pack_meta")
            it.exec("UPDATE pack_meta SET schema_version = 4294967297")
        }
        assertFalse(report.isValid, "expected a refusal, got:\n$report")
    }
}
