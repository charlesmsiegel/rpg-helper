package dev.rpghelper.builder

import dev.rpghelper.pack.JdbcDb
import dev.rpghelper.pack.Packs
import dev.rpghelper.pack.map
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.jsonObject
import java.nio.file.Files
import kotlin.test.assertTrue

/**
 * The corpus, assembled and put through the gate the app uses.
 *
 * This is the test that makes the fixture worth having: emitting a pack the app would
 * refuse is the one bug a builder must not be able to ship, and the only way to know it
 * cannot is to run the real validator over the real output.
 */
class CorpusBuildTest {

    private fun db() = JdbcDb.openReadOnly(CorpusPack.path)

    @Test
    fun `the corpus builds into a pack the activation gate accepts`() {
        val report = Packs.validateFile(CorpusPack.path, setOf(CorpusPack.EMBEDDER.contract))
        assertTrue(report.isValid, "the corpus must produce a valid pack, but:\n$report")
    }

    @Test
    fun `the pack declares the identity the corpus does`() {
        val meta = Packs.readMeta(CorpusPack.path)
        assertEquals("srd:emberlight", meta.packUid)
        assertEquals("emberlight-2e", meta.rulesetId)
        assertEquals(CorpusPack.EMBEDDER.contract.id, meta.embedderId)
    }

    @Test
    fun `every chunk's text is exactly the slice of the source its span names`() {
        // The property everything else rests on. A quotation is only a quotation if the
        // bytes came out of the book, and an anchor resolving one character wide produces
        // text that reads correctly and is not what the source says.
        val sources = CorpusPack.spec.sources.associate { it.sourceUid to CorpusPack.spec.textOf(it) }
        db().use { database ->
            val rows = database.map(
                "SELECT c.text, c.span_start, c.span_end, s.source_uid FROM chunks c " +
                    "JOIN sources s ON s.source_id = c.source_id WHERE c.origin = 'source'",
            ) { listOf(it.string(0), it.long(1).toInt(), it.long(2).toInt(), it.string(3)) }

            assertTrue(rows.isNotEmpty())
            for ((text, start, end, uid) in rows) {
                val bytes = (sources.getValue(uid as String)).toByteArray(Charsets.UTF_8)
                val slice = String(
                    bytes, start as Int, (end as Int) - start, Charsets.UTF_8,
                )
                assertEquals(text, slice, "chunk at [$start, $end) of $uid")
            }
        }
    }

    @Test
    fun `the source digest pins the bytes the spans index into`() {
        // A source edited after a build produces a pack whose every offset is subtly wrong
        // and whose every quotation is subtly not what the book says.
        db().use { database ->
            val digests = database.map("SELECT source_uid, text_sha256 FROM sources") {
                it.string(0) to it.string(1)
            }.toMap()
            for (source in CorpusPack.spec.sources) {
                val expected = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(CorpusPack.spec.textOf(source).toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                assertEquals(expected, digests[source.sourceUid])
            }
        }
    }

    @Test
    fun `the nested tables land inside their parents`() {
        // One inside a verbatim rule and one inside non-verbatim lore. The second is the
        // case redaction exists for, and a corpus without it would leave that path
        // exercised only by hand-forged fixtures.
        db().use { database ->
            val nested = database.map(
                "SELECT c.stable_key, p.stable_key, p.kind FROM chunks c " +
                    "JOIN chunks p ON p.chunk_id = c.parent_chunk_id",
            ) { Triple(it.string(0), it.string(1), it.string(2)) }

            assertEquals(
                setOf(
                    Triple("srd:core:seizing-mishaps", "srd:core:seizing", "rules"),
                    Triple("srd:core:marches-rumours", "srd:core:marches", "setting"),
                ),
                nested.toSet(),
            )
        }
    }

    @Test
    fun `every table's rows cover its expression exactly`() {
        // Validated by the gate above; asserted here so a corpus edit that breaks it
        // reports the table rather than a generic activation failure.
        db().use { database ->
            val coverage = database.map(
                "SELECT t.table_id, t.dice_expr, count(r.seq), min(r.lo), max(r.hi) " +
                    "FROM tables t JOIN table_rows r ON r.table_id = t.table_id " +
                    "GROUP BY t.table_id",
            ) { listOf(it.long(0), it.string(1), it.long(2), it.long(3), it.long(4)) }

            assertEquals(2, coverage.size)
            for ((_, expr, count, lo, hi) in coverage) {
                assertEquals("d6", expr)
                assertEquals(6L, count)
                assertEquals(1L, lo)
                assertEquals(6L, hi)
            }
        }
    }

    @Test
    fun `the derived summary cites real chunks and anchors one claim`() {
        db().use { database ->
            val rows = database.map(
                "SELECT d.claim_span_start, d.claim_span_end, s.stable_key, c.origin " +
                    "FROM chunk_derivation d " +
                    "JOIN chunks s ON s.chunk_id = d.source_chunk_id " +
                    "JOIN chunks c ON c.chunk_id = d.derived_chunk_id",
            ) { listOf(it.longOrNull(0), it.longOrNull(1), it.string(2), it.string(3)) }

            assertEquals(2, rows.size, "two citations: one claim-scoped, one whole-chunk")
            assertTrue(rows.all { it[3] == "derived" })
            assertEquals(1, rows.count { it[0] != null }, "exactly one anchors a claim")
            assertEquals(
                setOf("srd:core:seizing", "srd:door:held"),
                rows.map { it[2] }.toSet(),
            )
        }
    }

    @Test
    fun `every alias is stored in the form the index can match`() {
        db().use { database ->
            val aliases = database.map("SELECT alias FROM entities") { it.string(0) }
            assertTrue(aliases.isNotEmpty())
            for (alias in aliases) {
                assertEquals(dev.rpghelper.pack.Utf8.foldForIndex(alias), alias)
            }
        }
    }

    @Test
    fun `the errata row targets a book this pack does not carry`() {
        // Valid standalone, and inert until someone installs the first edition. Having the
        // case in the corpus is what stops it being tested only by hand-forged packs.
        db().use { database ->
            val targets = database.map(
                "SELECT target_source_uid FROM supersessions",
            ) { it.string(0) }
            val present = database.map("SELECT source_uid FROM sources") { it.string(0) }.toSet()
            assertEquals(listOf("srd:emberlight:core-1e"), targets)
            assertTrue(targets.none { it in present })
        }
    }

    @Test
    fun `every byte of every chunk falls inside a content window`() {
        // The coverage the validator checks; asserted here too because the windowing is
        // the builder's decision and a gap in it is a passage nothing can retrieve densely.
        db().use { database ->
            val lengths = database.map("SELECT chunk_id, text FROM chunks") {
                it.long(0) to it.string(1).toByteArray(Charsets.UTF_8).size
            }.toMap()
            val windows = database.map(
                "SELECT chunk_id, window_start, window_end FROM vectors WHERE role = 'content' " +
                    "ORDER BY chunk_id, window_start",
            ) { Triple(it.long(0), it.long(1).toInt(), it.long(2).toInt()) }

            for ((chunkId, length) in lengths) {
                val mine = windows.filter { it.first == chunkId }
                assertTrue(mine.isNotEmpty(), "chunk $chunkId has no content window")
                var covered = 0
                for ((_, start, end) in mine) {
                    assertTrue(start <= covered, "chunk $chunkId has a gap at $covered")
                    covered = maxOf(covered, end)
                }
                assertEquals(length, covered, "chunk $chunkId is not covered to its end")
            }
        }
    }

    @Test
    fun `a paragraph in no chunk and no gap fails the build`() {
        // Activation can never discover this: the source bytes are not in the pack, so
        // nothing downstream can tell a passage deliberately skipped from one lost. The
        // builder is the only place it is knowable.
        val corpus = CorpusSpec.load(CorpusPack.directory)
        val trimmed = trimLastGap(corpus)
        val failure = kotlin.runCatching {
            PackBuilder(trimmed, CorpusPack.EMBEDDER)
                .buildTo(Files.createTempDirectory("holes").resolve("x.rpgpack"))
        }.exceptionOrNull()
        assertTrue(failure != null, "a hole must fail the build")
        assertTrue(failure.message!!.contains("in no chunk and no declared gap"), failure.message!!)
    }

    @Test
    fun `every alias is stored in the form the rewriter looks it up by`() {
        // Folding alone leaves `fast-cast` and `D&D` intact, while the rewriter joins query
        // tokens with single spaces -- so those could never match, on a pack that passed a
        // fold-only normalization check because folding is idempotent on them.
        db().use { database ->
            for (alias in database.map("SELECT alias FROM entities") { it.string(0) }) {
                assertEquals(dev.rpghelper.pack.Tokenizer.indexForm(alias), alias)
            }
        }
    }

    @Test
    fun `capability manifests are encoded, not concatenated`() {
        // A label carrying a newline or a tab produces invalid JSON under hand-written
        // quoting; the pack still activates and the roll control silently disappears.
        db().use { database ->
            val manifests = database.map("SELECT manifest FROM capabilities") { it.string(0) }
            assertTrue(manifests.isNotEmpty())
            for (manifest in manifests) {
                val parsed = kotlinx.serialization.json.Json
                    .parseToJsonElement(manifest).jsonObject
                assertTrue("table_id" in parsed && "label" in parsed, manifest)
            }
        }
    }

    @Test
    fun `an anchor that is not unique fails the build rather than picking one`() {
        // "First match" is exactly how a fixture starts pointing somewhere plausible and
        // wrong, so ambiguity is refused instead of resolved.
        val document = "Nothing further goes wrong. And later: Nothing further goes wrong."
        val failure = kotlin.runCatching {
            Anchors.resolve(document, "Nothing further", "goes wrong.")
        }.exceptionOrNull()
        assertTrue(failure is Anchors.UnresolvedAnchorException, "got $failure")
        assertTrue(failure.message!!.contains("occurs 2 times"))
    }

    @Test
    fun `an anchor that no longer matches fails the build`() {
        val failure = kotlin.runCatching {
            Anchors.resolve("some text", "a phrase that was edited away", "some text")
        }.exceptionOrNull()
        assertTrue(failure is Anchors.UnresolvedAnchorException, "got $failure")
    }
}

/** The corpus with one source's last gap removed, leaving a real hole. */
private fun trimLastGap(corpus: CorpusSpec): CorpusSpec {
    val json = kotlinx.serialization.json.Json.parseToJsonElement(
        java.nio.file.Files.readString(corpus.root.resolve("pack.json")),
    ).jsonObject
    val sources = json.getValue("sources").let { it as kotlinx.serialization.json.JsonArray }
    val first = sources.first().jsonObject
    val gaps = first.getValue("gaps").let { it as kotlinx.serialization.json.JsonArray }
    val trimmedSource = kotlinx.serialization.json.JsonObject(
        first.toMutableMap().apply {
            put("gaps", kotlinx.serialization.json.JsonArray(gaps.dropLast(1)))
        },
    )
    val trimmed = kotlinx.serialization.json.JsonObject(
        json.toMutableMap().apply {
            put(
                "sources",
                kotlinx.serialization.json.JsonArray(listOf(trimmedSource) + sources.drop(1)),
            )
        },
    )
    return CorpusSpec(corpus.root, trimmed)
}
