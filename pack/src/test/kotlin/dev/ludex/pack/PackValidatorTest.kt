package dev.ludex.pack

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One test per rejection the app promises to make.
 *
 * `android-app-design.md` §8 asks for "one deliberately broken pack per on-device
 * rejection case". Each test here forges a valid pack, breaks exactly one thing, and
 * asserts the specific violation -- and asserts the *unbroken* pack still validates, so
 * a green result cannot come from a fixture that was broken all along.
 */
class PackValidatorTest {

    private val supported = setOf(PackForge.EMBEDDER)
    private val directory: Path = Files.createTempDirectory("rpgpack-test")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun validate(mutate: (Connection) -> Unit = {}): ValidationReport =
        Packs.validateFile(PackForge.writePack(directory, mutate), supported)

    /**
     * Asserts [code] is reported for a pack broken by [mutate], having first confirmed
     * the pristine pack validates. Without that first assertion a bug in the forge would
     * make every test in this class pass for the wrong reason.
     */
    private fun assertRejects(code: ViolationCode, mutate: (Connection) -> Unit) {
        val baseline = validate()
        assertTrue(baseline.isValid, "the forged baseline pack must validate, but:\n$baseline")

        val report = validate(mutate)
        assertTrue(code in report.codes, "expected $code, but got:\n$report")
    }

    // ---------------------------------------------------------------- baseline

    @Test
    fun `a well-formed pack validates`() {
        val report = validate()
        assertTrue(report.isValid, "forged pack should be valid, but:\n$report")
    }

    // ---------------------------------------------------------------- format and contract

    @Test
    fun `rejects an unrecognised schema version`() {
        assertRejects(ViolationCode.UNSUPPORTED_SCHEMA_VERSION) {
            it.exec("UPDATE pack_meta SET schema_version = 99")
        }
    }

    @Test
    fun `rejects a missing table`() {
        assertRejects(ViolationCode.MISSING_TABLE) { it.exec("DROP TABLE build_report") }
    }

    @Test
    fun `rejects a pack with no pack_meta row`() {
        assertRejects(ViolationCode.PACK_META_NOT_SINGLETON) { it.exec("DELETE FROM pack_meta") }
    }

    @Test
    fun `rejects an embedder this build does not bundle`() {
        // There is no way to embed a query in a space whose weights are absent, so this
        // has to fail at activation rather than degrade at query time.
        assertRejects(ViolationCode.UNKNOWN_EMBEDDER) {
            it.exec("UPDATE pack_meta SET embedder_id = 'some-embedder-nobody-ships'")
        }
    }

    @Test
    fun `rejects a dimension that disagrees with the bundled embedder`() {
        assertRejects(ViolationCode.EMBEDDER_DIM_MISMATCH) {
            it.exec("UPDATE pack_meta SET embedder_dim = 16")
        }
    }

    // ---------------------------------------------------------------- vector layout

    @Test
    fun `rejects a big-endian pack, which byte length cannot detect`() {
        // The case the probe exists for. Every vector in this pack is the right length
        // and every declared dimension matches; only the byte order is wrong.
        assertRejects(ViolationCode.PROBE_VECTOR_MISMATCH) { connection ->
            val canonical = ProbeVector.canonicalBytes()
            val swapped = ByteArray(canonical.size) { i ->
                if (i % 2 == 0) canonical[i + 1] else canonical[i - 1]
            }
            connection.prepare("UPDATE pack_meta SET probe_vector = ?") { it.setBytes(1, swapped) }
        }
    }

    @Test
    fun `rejects a probe written as float32`() {
        assertRejects(ViolationCode.PROBE_VECTOR_MALFORMED) { connection ->
            val wide = ByteArray(ProbeVector.VALUES.size * 4)
            connection.prepare("UPDATE pack_meta SET probe_vector = ?") { it.setBytes(1, wide) }
        }
    }

    @Test
    fun `rejects a vector whose length disagrees with the declared dimension`() {
        assertRejects(ViolationCode.VECTOR_LENGTH_MISMATCH) { connection ->
            connection.prepare(
                "UPDATE vectors SET embedding = ? WHERE chunk_id = 1 AND role = 'content'",
            ) { it.setBytes(1, ByteArray(6)) }
        }
    }

    @Test
    fun `rejects a vector containing NaN`() {
        // NaN compares false against everything, so the chunk either vanishes from
        // retrieval or lands wherever the comparator happens to leave it.
        assertRejects(ViolationCode.VECTOR_NON_FINITE) { connection ->
            val nans = Float16.encodeVector(FloatArray(PackForge.EMBEDDER.dim) { Float.NaN })
            connection.prepare("UPDATE vectors SET embedding = ? WHERE chunk_id = 3") {
                it.setBytes(1, nans)
            }
        }
    }

    @Test
    fun `rejects a vector containing infinity`() {
        assertRejects(ViolationCode.VECTOR_NON_FINITE) { connection ->
            val infinities = Float16.encodeVector(
                FloatArray(PackForge.EMBEDDER.dim) { Float.POSITIVE_INFINITY },
            )
            connection.prepare("UPDATE vectors SET embedding = ? WHERE chunk_id = 3") {
                it.setBytes(1, infinities)
            }
        }
    }

    @Test
    fun `rejects an all-zero vector`() {
        // The ordinary result of an embedding call that failed and went unchecked.
        assertRejects(ViolationCode.VECTOR_ZERO_NORM) { connection ->
            val zeros = ByteArray(PackForge.EMBEDDER.dim * PackSchema.VECTOR_ELEMENT_BYTES)
            connection.prepare("UPDATE vectors SET embedding = ? WHERE chunk_id = 4") {
                it.setBytes(1, zeros)
            }
        }
    }

    @Test
    fun `rejects a content vector with no window`() {
        assertRejects(ViolationCode.VECTOR_WINDOW_INVALID) {
            it.exec("UPDATE vectors SET window_start = NULL WHERE role = 'content'")
        }
    }

    @Test
    fun `rejects an expansion vector that declares a window`() {
        assertRejects(ViolationCode.VECTOR_WINDOW_INVALID) {
            it.exec("UPDATE vectors SET window_start = 0, window_end = 5 WHERE role = 'expansion'")
        }
    }

    @Test
    fun `rejects a content window reaching past the end of its chunk`() {
        assertRejects(ViolationCode.VECTOR_WINDOW_INVALID) {
            it.exec("UPDATE vectors SET window_end = 100000 WHERE role = 'content'")
        }
    }

    // ---------------------------------------------------------------- chunk shape

    @Test
    fun `rejects an unrecognised kind`() {
        assertRejects(ViolationCode.CHUNK_KIND_INVALID) {
            it.exec("UPDATE chunks SET kind = 'lore' WHERE chunk_id = 3")
        }
    }

    @Test
    fun `rejects a span whose length disagrees with its text`() {
        // Catches truncation and offset drift without needing the source bytes.
        assertRejects(ViolationCode.SPAN_LENGTH_MISMATCH) {
            it.exec("UPDATE chunks SET span_end = span_end + 5 WHERE chunk_id = 4")
        }
    }

    @Test
    fun `rejects a source chunk with no stable key`() {
        // Supersession targets (source_uid, stable_key); without one, no errata pack
        // could ever amend this passage.
        assertRejects(ViolationCode.SOURCE_CHUNK_MISSING_CITATION) {
            it.exec("UPDATE chunks SET stable_key = NULL WHERE chunk_id = 1")
        }
    }

    @Test
    fun `rejects a source chunk with no page labels`() {
        assertRejects(ViolationCode.SOURCE_CHUNK_MISSING_CITATION) {
            it.exec("UPDATE chunks SET page_label_start = NULL WHERE chunk_id = 5")
        }
    }

    @Test
    fun `rejects a derived chunk carrying source columns`() {
        // Its text exists in no source document, so any offset here is fabricated -- and
        // a fabricated offset is worse than none, because it looks checkable.
        assertRejects(ViolationCode.DERIVED_CHUNK_HAS_SOURCE_COLUMNS) {
            it.exec("UPDATE chunks SET span_start = 0, span_end = 10 WHERE chunk_id = 7")
        }
    }

    // ---------------------------------------------------------------- nesting

    @Test
    fun `rejects a child whose span escapes its parent`() {
        assertRejects(ViolationCode.NESTING_NOT_CONTAINED) {
            it.exec(
                """
                UPDATE chunks
                SET span_start = (SELECT span_end FROM chunks WHERE chunk_id = 1) + 1,
                    span_end   = (SELECT span_end FROM chunks WHERE chunk_id = 1) + 2
                WHERE chunk_id = 2
                """.trimIndent(),
            )
        }
    }

    @Test
    fun `rejects nesting more than one level deep`() {
        assertRejects(ViolationCode.NESTING_TOO_DEEP) {
            it.exec("UPDATE chunks SET parent_chunk_id = 2 WHERE chunk_id = 3")
        }
    }

    @Test
    fun `rejects a child in a different source from its parent`() {
        assertRejects(ViolationCode.NESTING_CROSS_SOURCE) {
            it.exec("UPDATE chunks SET parent_chunk_id = 5 WHERE chunk_id = 2")
        }
    }

    @Test
    fun `rejects a parent that is not in the pack`() {
        assertRejects(ViolationCode.PARENT_CHUNK_MISSING) {
            it.exec("UPDATE chunks SET parent_chunk_id = 999 WHERE chunk_id = 2")
        }
    }

    @Test
    fun `rejects overlapping siblings`() {
        // Both offsets move together, so the span still matches its text length and only
        // the overlap rule fires.
        assertRejects(ViolationCode.SIBLING_SPAN_OVERLAP) {
            it.exec(
                "UPDATE chunks SET span_start = span_start - 40, span_end = span_end - 40 " +
                    "WHERE chunk_id = 4",
            )
        }
    }

    // ---------------------------------------------------------------- derivation

    @Test
    fun `rejects a derived chunk that cites nothing`() {
        assertRejects(ViolationCode.DERIVED_CHUNK_NO_DERIVATION) {
            it.exec("DELETE FROM chunk_derivation WHERE derived_chunk_id = 7")
        }
    }

    @Test
    fun `rejects a derivation chain that does not terminate at a source chunk`() {
        // Checking only that the row resolves would pass this, and the app would then
        // render an attributed card whose citation has no page fields behind it.
        assertRejects(ViolationCode.DERIVATION_TARGET_NOT_SOURCE) {
            it.exec("UPDATE chunk_derivation SET source_chunk_id = 7 WHERE derivation_id = 1")
        }
    }

    @Test
    fun `rejects a derivation citing a chunk that is absent`() {
        assertRejects(ViolationCode.DERIVATION_TARGET_MISSING) {
            it.exec("UPDATE chunk_derivation SET source_chunk_id = 999 WHERE derivation_id = 1")
        }
    }

    @Test
    fun `rejects a source chunk with derivation rows`() {
        assertRejects(ViolationCode.SOURCE_CHUNK_HAS_DERIVATION) {
            it.exec(
                "INSERT INTO chunk_derivation (derivation_id, derived_chunk_id, source_chunk_id) " +
                    "VALUES (99, 1, 6)",
            )
        }
    }

    // ---------------------------------------------------------------- claim spans

    @Test
    fun `rejects a claim span reaching past the derived text`() {
        assertRejects(ViolationCode.CLAIM_SPAN_OUT_OF_RANGE) {
            it.exec("UPDATE chunk_derivation SET claim_span_end = 100000 WHERE derivation_id = 1")
        }
    }

    @Test
    fun `rejects a claim span that splits a multi-byte character`() {
        // Would otherwise anchor an inline citation chip mid-character, or crash while
        // converting the offset for display.
        assertRejects(ViolationCode.CLAIM_SPAN_NOT_UTF8_BOUNDARY) { connection ->
            val text = connection.queryString("SELECT text FROM chunks WHERE chunk_id = 7")
            val encoded = text.toByteArray(Charsets.UTF_8)
            val interior = encoded.indices.first { (encoded[it].toInt() and 0xC0) == 0x80 }
            connection.exec(
                "UPDATE chunk_derivation SET claim_span_start = 0, claim_span_end = $interior " +
                    "WHERE derivation_id = 1",
            )
        }
    }

    @Test
    fun `rejects a claim span with only one end set`() {
        assertRejects(ViolationCode.CLAIM_SPAN_PARTIAL) {
            it.exec("UPDATE chunk_derivation SET claim_span_end = NULL WHERE derivation_id = 1")
        }
    }

    @Test
    fun `rejects an inverted claim span`() {
        assertRejects(ViolationCode.CLAIM_SPAN_INVERTED) {
            it.exec(
                "UPDATE chunk_derivation SET claim_span_start = 10, claim_span_end = 10 " +
                    "WHERE derivation_id = 1",
            )
        }
    }

    // ---------------------------------------------------------------- references

    @Test
    fun `rejects a capability pointing at a chunk that is absent`() {
        assertRejects(ViolationCode.DANGLING_CHUNK_REFERENCE) {
            it.exec("UPDATE capabilities SET chunk_id = 999")
        }
    }

    @Test
    fun `rejects a constraint pointing at a chunk that is absent`() {
        // The engine loads these directly and never consults retrieval, so a dangling
        // row would fail at the moment a character was validated.
        assertRejects(ViolationCode.DANGLING_CHUNK_REFERENCE) {
            it.exec("UPDATE constraints SET chunk_id = 999")
        }
    }

    @Test
    fun `rejects an entity alias pointing at a chunk that is absent`() {
        assertRejects(ViolationCode.DANGLING_CHUNK_REFERENCE) {
            it.exec("UPDATE entities SET chunk_id = 999 WHERE entity_id = 1")
        }
    }

    @Test
    fun `accepts an entity alias with no chunk, which means ungoverned`() {
        val report = validate { it.exec("UPDATE entities SET chunk_id = NULL") }
        assertTrue(report.isValid, "a NULL chunk_id is legal in entities, but:\n$report")
    }

    // ---------------------------------------------------------------- reporting

    @Test
    fun `reports every violation rather than stopping at the first`() {
        // The Packs screen has to explain a refusal to someone who may be able to fix
        // the pack, and one defect at a time is a poor way to learn there are several.
        val report = validate {
            it.exec("UPDATE chunks SET kind = 'lore' WHERE chunk_id = 3")
            it.exec("UPDATE chunks SET span_end = span_end + 5 WHERE chunk_id = 4")
            it.exec("UPDATE capabilities SET chunk_id = 999")
        }
        assertEquals(
            setOf(
                ViolationCode.CHUNK_KIND_INVALID,
                ViolationCode.SPAN_LENGTH_MISMATCH,
                ViolationCode.DANGLING_CHUNK_REFERENCE,
            ),
            report.codes,
        )
    }
}

private fun Connection.queryString(sql: String): String =
    createStatement().use { statement ->
        statement.executeQuery(sql).use { results ->
            results.next()
            results.getString(1)
        }
    }
