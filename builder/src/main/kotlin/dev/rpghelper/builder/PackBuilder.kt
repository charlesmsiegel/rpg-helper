package dev.rpghelper.builder

import dev.rpghelper.model.Embedder
import dev.rpghelper.pack.Float16
import dev.rpghelper.pack.DiceExpression
import dev.rpghelper.pack.PackSchema
import dev.rpghelper.pack.ProbeVector
import dev.rpghelper.pack.Tokenizer
import dev.rpghelper.pack.Utf8
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement
import java.sql.Types

/** Something the builder dropped rather than shipped, recorded in `build_report`. */
data class BuildNote(
    val severity: String,
    val subjectKind: String,
    val subjectId: String?,
    val validation: String,
    val detail: String,
)

/** What a build produced. */
data class BuildOutcome(val path: Path, val notes: List<BuildNote>)

/**
 * Assembles a `.rpgpack` from a [CorpusSpec].
 *
 * Writes the **canonical DDL from `:pack`** rather than its own copy: a builder with a
 * private schema keeps producing files after the real one changes, and the app is what
 * refuses a pack it cannot read. For the same reason the caller is expected to run the
 * result back through the activation gate — emitting a pack the app would refuse is the
 * one bug a builder must not be able to ship.
 */
class PackBuilder(
    private val spec: CorpusSpec,
    private val embedder: Embedder,
    /**
     * Adjudicates whether a derived summary is supported by the chunks it cites.
     *
     * A derived chunk is model-written prose the app renders with citations — the
     * identical trust claim as a generated answer, made by a different model at a
     * different time. Its `chunk_derivation` rows guarantee the cited chunks *exist* and
     * nothing else, so a summary that invents a detail carries a well-formed citation,
     * renders as attributed text, and passes every validation the format has. The builder
     * is the natural home for the check: it already has the frontier model, it has the
     * source, and it has no latency budget.
     *
     * Null skips the check and records that it was skipped, which is honest. Silently
     * shipping unchecked summaries is what this exists to stop.
     */
    private val judge: dev.rpghelper.model.Judge? = null,
    private val claimThreshold: Double = 1.0,
    /**
     * Ships derived prose that **no judge has adjudicated**.
     *
     * Off by default, and it has to be, because the failure is invisible from the outside.
     * A derived chunk renders as attributed prose with well-formed citation chips: the
     * identical trust claim a generated answer makes, and one the app cannot re-check,
     * since `chunk_derivation` guarantees the cited chunks *exist* and nothing more. The
     * `unchecked` build note went to the builder's operator; nothing at runtime reads
     * `build_report`, so the person holding the pack was never told.
     *
     * So with no judge the summaries are dropped and the drop is recorded — the book still
     * ships, minus the enrichment nobody vouched for. This flag exists for the tests that
     * need an unadjudicated pack on purpose, and for an operator who has decided to ship
     * one with their eyes open.
     */
    private val shipUnadjudicatedDerived: Boolean = false,
) {

    private val notes = mutableListOf<BuildNote>()

    /** chunk id, assigned in document order so a pack diffs stably across rebuilds. */
    private var nextChunkId = 1L

    /**
     * Stable key to chunk id — and a record of any key used by more than one source.
     *
     * `stable_key` is unique **within a source**, not globally, so two books may legally
     * reuse one. Indexing by the bare string lets the later chunk replace the earlier
     * entry, after which a derived citation, table, capability, entity, or constraint
     * naming that key attaches to the wrong book — in a pack that still activates, because
     * every reference it holds resolves to a real chunk.
     */
    private val chunkIdByStableKey = mutableMapOf<String, Long>()
    private val ambiguousStableKeys = mutableSetOf<String>()
    private val chunkTexts = mutableMapOf<Long, String>()
    private val chunkKinds = mutableMapOf<Long, Pair<String, String>>()
    private val chunkSpans = mutableMapOf<Long, Anchors.Span>()

    /** Child chunk id to parent, for the expansion redaction. */
    private val chunkParents = mutableMapOf<Long, Long>()

    /**
     * Builds into a sibling temporary file and moves it over [path] once it commits.
     *
     * Writing in place meant deleting the last known-good pack *first*, so any later
     * failure — an anchor that no longer matches, an embedding that throws, a claim the
     * judge refuses — destroyed a working artifact and left a half-written SQLite file
     * wearing its name. A rebuild that fails should leave you exactly where you were.
     *
     * Per-build state is reset here rather than in the constructor so a second `buildTo`
     * on one instance is a second *build*: carrying `nextChunkId` and the stable-key map
     * over would number the second pack's chunks differently and mark every repeated key
     * ambiguous, failing on the first derived citation that resolved one.
     */
    fun buildTo(path: Path): BuildOutcome {
        reset()
        val target = path.toAbsolutePath()
        Files.createDirectories(target.parent)
        val staging = Files.createTempFile(target.parent, ".${target.fileName}", ".building")
        // SQLite wants to create the file itself; the temp file reserved the name.
        Files.deleteIfExists(staging)
        try {
            DriverManager.getConnection("jdbc:sqlite:$staging").use { c ->
                c.autoCommit = false
                createSchema(c)
                writeMeta(c)
                writeSources(c)
                writeDerived(c)
                writeFts(c)
                writeVectors(c)
                writeEntities(c)
                val tables = writeTables(c)
                writeCapabilities(c, tables)
                writeConstraints(c)
                writeSupersessions(c)
                writeReport(c)
                c.commit()
            }
            Files.move(staging, target, StandardCopyOption.REPLACE_EXISTING)
        } catch (failure: Throwable) {
            Files.deleteIfExists(staging)
            throw failure
        }
        return BuildOutcome(path, notes.toList())
    }

    private fun reset() {
        nextChunkId = 1L
        notes.clear()
        chunkIdByStableKey.clear()
        ambiguousStableKeys.clear()
        chunkTexts.clear()
        chunkKinds.clear()
        chunkSpans.clear()
        chunkParents.clear()
    }

    // ------------------------------------------------------------------ schema and meta

    private fun createSchema(c: Connection) {
        c.createStatement().use { statement ->
            PackSchema.DDL.split(";").map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { statement.execute(it) }
        }
    }

    private fun writeMeta(c: Connection) {
        c.prepare(
            """
            INSERT INTO pack_meta (id, schema_version, pack_uid, pack_version, title,
                                   ruleset_id, embedder_id, embedder_dim, probe_vector,
                                   license_id, license_text, attribution, built_at,
                                   builder_version, signature)
            VALUES (1, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, ?, ?, ?, NULL)
            """.trimIndent(),
        ) {
            it.setInt(1, PackSchema.SCHEMA_VERSION)
            it.setString(2, spec.packUid)
            it.setString(3, spec.packVersion)
            it.setString(4, spec.title)
            it.setNullableString(5, spec.rulesetId)
            it.setString(6, embedder.contract.id)
            it.setInt(7, embedder.contract.dim)
            // Not a checksum: a fixed constant the app re-encodes and compares. It catches
            // byte-order, width, and padding errors that a length check cannot see,
            // because a wrong-endian vector is exactly as long as a right-endian one.
            it.setBytes(8, ProbeVector.canonicalBytes())
            it.setString(9, spec.licenseId)
            it.setNullableString(10, spec.attribution)
            it.setString(11, spec.builtAt)
            it.setString(12, spec.builderVersion)
        }
    }

    // ------------------------------------------------------------------ sources and chunks

    private fun writeSources(c: Connection) {
        spec.sources.forEachIndexed { index, source ->
            val sourceId = (index + 1).toLong()
            val text = spec.textOf(source)

            c.prepare(
                """
                INSERT INTO sources (source_id, source_uid, title, edition, publisher,
                                     text_sha256, locator_scheme)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ) {
                it.setLong(1, sourceId)
                it.setString(2, source.sourceUid)
                it.setString(3, source.title)
                it.setNullableString(4, source.edition)
                it.setNullableString(5, source.publisher)
                // Pins the exact bytes the spans index into. A source edited after a build
                // produces a pack whose every offset is subtly wrong and whose every
                // quotation is subtly not what the book says.
                it.setString(6, sha256(text))
                it.setString(7, source.locatorScheme)
            }

            for (label in source.pageLabels) {
                c.prepare(
                    """
                    INSERT INTO source_page_labels (source_id, seq, phys_start, phys_end,
                                                    scheme, start_value, prefix)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ) {
                    it.setLong(1, sourceId)
                    it.setInt(2, label.seq)
                    it.setInt(3, label.physStart)
                    it.setInt(4, label.physEnd)
                    it.setString(5, label.scheme)
                    if (label.startValue == null) it.setNull(6, Types.INTEGER)
                    else it.setInt(6, label.startValue)
                    it.setNullableString(7, label.prefix)
                }
            }

            for ((index2, gap) in source.gaps.withIndex()) {
                val span = Anchors.resolve(text, gap.from, gap.to)
                c.prepare(
                    """
                    INSERT INTO source_gaps (gap_id, source_id, span_start, span_end, reason, note)
                    VALUES (?, ?, ?, ?, ?, NULL)
                    """.trimIndent(),
                ) {
                    it.setLong(1, sourceId * 1000 + index2)
                    it.setLong(2, sourceId)
                    it.setInt(3, span.start)
                    it.setInt(4, span.end)
                    it.setString(5, gap.reason)
                }
            }

            val covered = mutableListOf<Anchors.Span>()
            source.gaps.forEach { covered += Anchors.resolve(text, it.from, it.to) }
            for (chunk in source.chunks) {
                covered += writeChunk(c, sourceId, text, chunk, parent = null)
            }
            requireTiled(source, text, covered)
        }
    }

    /**
     * Every top-level chunk and declared gap must tile the source exactly.
     *
     * A corpus edit that leaves a paragraph outside every declared span builds cleanly and
     * ships a pack with that content absent from the index, the vectors, and every
     * quotation. **Activation cannot discover it** — the source bytes are not in the pack,
     * so nothing downstream can tell a passage that was deliberately skipped from one that
     * was lost. The builder is the only place this is knowable.
     */
    private fun requireTiled(
        source: CorpusSpec.SourceSpec,
        document: String,
        covered: List<Anchors.Span>,
    ) {
        val total = document.utf8Length()
        val sorted = covered.sortedBy { it.start }
        var cursor = 0
        val holes = mutableListOf<Anchors.Span>()
        for (span in sorted) {
            if (span.start > cursor) holes += Anchors.Span(cursor, span.start)
            if (span.start < cursor) {
                error(
                    "${source.sourceUid}: spans overlap at byte ${span.start}; a byte in two " +
                        "chunks is a byte quoted twice under two citations",
                )
            }
            cursor = maxOf(cursor, span.end)
        }
        if (cursor < total) holes += Anchors.Span(cursor, total)

        // Whitespace between spans is the ordinary case and is not content.
        val bytes = document.toByteArray(Charsets.UTF_8)
        val real = holes.filter { hole ->
            String(bytes, hole.start, hole.length, Charsets.UTF_8).isNotBlank()
        }
        if (real.isNotEmpty()) {
            val sample = real.first().let {
                String(bytes, it.start, minOf(it.length, 60), Charsets.UTF_8).trim()
            }
            error(
                "${source.sourceUid}: ${real.size} region(s) of the source are in no chunk " +
                    "and no declared gap, so they would ship missing and unnoticeable -- " +
                    "first at byte ${real.first().start}: \"$sample...\"",
            )
        }
    }

    private fun writeChunk(
        c: Connection,
        sourceId: Long,
        document: String,
        chunk: CorpusSpec.ChunkSpec,
        parent: Pair<Long, Anchors.Span>?,
    ): Anchors.Span {
        val span = Anchors.resolve(document, chunk.from, chunk.to, parent?.second)
        val bytes = document.toByteArray(Charsets.UTF_8)
        val text = String(bytes, span.start, span.length, Charsets.UTF_8)

        val id = nextChunkId++
        c.prepare(
            """
            INSERT INTO chunks (chunk_id, kind, origin, text, source_id, heading_path,
                                page_label_start, page_label_end, span_start, span_end,
                                stable_key, parent_chunk_id)
            VALUES (?, ?, 'source', ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) {
            it.setLong(1, id)
            it.setString(2, chunk.kind)
            it.setString(3, text)
            it.setLong(4, sourceId)
            it.setNullableString(5, chunk.headingPath)
            it.setNullableString(6, chunk.pageLabelStart)
            it.setNullableString(7, chunk.pageLabelEnd)
            it.setInt(8, span.start)
            it.setInt(9, span.end)
            it.setString(10, chunk.stableKey)
            if (parent == null) it.setNull(11, Types.INTEGER) else it.setLong(11, parent.first)
        }

        if (chunkIdByStableKey.put(chunk.stableKey, id) != null) {
            ambiguousStableKeys += chunk.stableKey
        }
        chunkTexts[id] = text
        chunkKinds[id] = chunk.kind to "source"
        chunkSpans[id] = span
        if (parent != null) chunkParents[id] = parent.first

        for (child in chunk.children) writeChunk(c, sourceId, document, child, id to span)
        return span
    }

    /**
     * The chunk a corpus entry names, or a build failure.
     *
     * Refuses an ambiguous key rather than picking one: "whichever source was read last"
     * is how enrichment silently attaches to the wrong book.
     */
    private fun chunkFor(stableKey: String, what: String): Long {
        if (stableKey in ambiguousStableKeys) {
            error(
                "$what names stable key '$stableKey', which more than one source uses. " +
                    "Stable keys are unique within a source, not across the pack.",
            )
        }
        return chunkIdByStableKey[stableKey]
            ?: error("$what names '$stableKey', which is not in the pack")
    }

    // ------------------------------------------------------------------ derived prose

    private fun writeDerived(c: Connection) {
        var derivationId = 1L
        for (derived in spec.derived) {
            if (!supported(derived)) continue
            val id = nextChunkId++
            c.prepare(
                "INSERT INTO chunks (chunk_id, kind, origin, text) VALUES (?, ?, 'derived', ?)",
            ) {
                it.setLong(1, id)
                it.setString(2, derived.kind)
                it.setString(3, derived.text)
            }
            chunkTexts[id] = derived.text
            chunkKinds[id] = derived.kind to "derived"

            for (citation in derived.cites) {
                val target = chunkFor(citation.stableKey, "a derived chunk's citation")
                // A claim span anchors an inline chip to one sentence; its absence puts the
                // citation in the footer instead. Both shapes are legal and the app renders
                // them differently, so which one a builder emits is a real decision.
                val claim = citation.claim?.let { Anchors.within(derived.text, it) }
                c.prepare(
                    """
                    INSERT INTO chunk_derivation (derivation_id, derived_chunk_id,
                                                  source_chunk_id, claim_span_start,
                                                  claim_span_end)
                    VALUES (?, ?, ?, ?, ?)
                    """.trimIndent(),
                ) {
                    it.setLong(1, derivationId++)
                    it.setLong(2, id)
                    it.setLong(3, target)
                    if (claim == null) {
                        it.setNull(4, Types.INTEGER)
                        it.setNull(5, Types.INTEGER)
                    } else {
                        it.setInt(4, claim.start)
                        it.setInt(5, claim.end)
                    }
                }
            }
        }
    }

    /**
     * Whether a derived summary is entailed by the chunks it cites.
     *
     * A summary that fails is **dropped and recorded**, not shipped. Dropping it costs the
     * user a convenience; shipping it puts a fabricated detail on screen wearing a
     * citation, which is the one thing the app must never do.
     */
    private fun supported(derived: CorpusSpec.DerivedSpec): Boolean {
        if (judge == null) {
            if (shipUnadjudicatedDerived) {
                notes += BuildNote(
                    "unchecked", "chunk", null, "claim-support",
                    "no judge was supplied and unadjudicated derived prose was explicitly " +
                        "permitted, so this summary ships unchecked",
                )
                return true
            }
            // Dropped rather than shipped. The note reaches the operator; nothing at
            // runtime reads `build_report`, so a summary shipped unchecked reaches the
            // person holding the pack with no warning at all -- as attributed prose with
            // well-formed citation chips, which the app has no way to re-check.
            notes += BuildNote(
                "dropped", "chunk", null, "claim-support",
                "no judge was supplied, so this summary was dropped rather than shipped " +
                    "unadjudicated; pass one, or set shipUnadjudicatedDerived",
            )
            return false
        }

        // Each region is judged against the chunks *that region* cites -- not against the
        // union of every citation on the summary. Pooling them lets a sentence whose chip
        // points at source A pass because unrelated source B happens to support it, after
        // which the card renders that sentence with an authoritative and wrong chip.
        val wholeChunk = derived.cites.filter { it.claim == null }
            .map { chunkTexts.getValue(chunkFor(it.stableKey, "a derived chunk's citation")) }

        val regions = mutableListOf<Pair<String, List<String>>>()
        for (citation in derived.cites) {
            val claim = citation.claim ?: continue
            regions += claim to listOf(
                chunkTexts.getValue(chunkFor(citation.stableKey, "a derived chunk's citation")),
            )
        }
        // Whatever no claim span covers falls back to the whole-chunk citations, which is
        // the same rule generated answers follow: weaker, and defined.
        //
        // Built from the spans the chips are actually placed at, not by `replace`.
        // `writeDerived` anchors each claim with `Anchors.within(text, claim)` -- the
        // *first* occurrence -- while `replace` removed every one, so a summary that says
        // the same sentence twice lost its later copies from the remainder: never judged
        // against the fallback evidence, and rendered with neither a chip nor a footer.
        val remainder = buildString {
            val text = derived.text
            val blanked = BooleanArray(text.length)
            for ((claim, _) in regions) {
                val at = text.indexOf(claim)
                if (at < 0) continue
                for (index in at until at + claim.length) blanked[index] = true
            }
            for ((index, character) in text.withIndex()) {
                append(if (blanked[index]) ' ' else character)
            }
        }
        for (sentence in sentences(remainder)) regions += sentence to wholeChunk

        val unsupported = mutableListOf<String>()
        var claims = 0
        for ((region, evidence) in regions) {
            for (sentence in sentences(region)) {
                claims++
                if (evidence.isEmpty() || !judge.judge(sentence, evidence).entailed) {
                    unsupported += sentence
                }
            }
        }

        val rate = if (claims == 0) 1.0 else (claims - unsupported.size).toDouble() / claims
        if (rate < claimThreshold) {
            notes += BuildNote(
                "dropped", "chunk", null, "claim-support",
                "summary dropped: ${unsupported.size} of $claims claims are not supported " +
                    "by the chunks that cite them -- ${unsupported.firstOrNull()}",
            )
            return false
        }
        return true
    }

    private fun sentences(text: String): List<String> =
        text.split(Regex("(?<=[.!?])\\s+")).map { it.trim() }.filter { it.length > 1 }

    // ------------------------------------------------------------------ index and vectors

    private fun writeFts(c: Connection) {
        c.exec(
            "INSERT INTO chunks_fts (rowid, text, heading_path) " +
                "SELECT chunk_id, text, COALESCE(heading_path, '') FROM chunks",
        )
    }

    /**
     * Tiles each chunk with overlapping content windows, and adds expansions.
     *
     * Windows overlap so a passage straddling a boundary is wholly inside at least one of
     * them; without the overlap the sentence that answers the question is the one split in
     * half. Every byte of every chunk falls inside some window, which is what lets the
     * validator check coverage rather than take the builder's word for it.
     */
    private fun writeVectors(c: Connection) {
        c.prepareStatement(
            """
            INSERT INTO vectors (chunk_id, role, subchunk_index, embedding,
                                 window_start, window_end)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ).use { statement ->
            for ((chunkId, text) in chunkTexts.toSortedMap()) {
                val bytes = text.toByteArray(Charsets.UTF_8)
                for ((index, window) in windowsOf(bytes).withIndex()) {
                    val slice = String(
                        bytes, window.start, window.length, Charsets.UTF_8,
                    )
                    statement.setLong(1, chunkId)
                    statement.setString(2, "content")
                    statement.setInt(3, index)
                    statement.setBytes(4, Float16.encodeVector(embedder.embed(slice)))
                    statement.setInt(5, window.start)
                    statement.setInt(6, window.end)
                    statement.addBatch()
                }

                // Expansions close the query-side phrasing gap: a book says "seizing" and
                // a player types "how do I grapple". They carry no window because they
                // embed a generated question rather than a span of the chunk.
                //
                // **Every chunk gets them, and every one is generated from redacted
                // prose.** Retrieval's nesting test treats an `expansion` hit on a
                // route-3 parent as independent evidence *by construction* -- on the
                // stated ground that an expansion is generated from text with the nested
                // verbatim children already excised. Emitting them only for
                // verbatim-class chunks left that branch dead for the one kind of chunk
                // it exists to judge, so a `setting` parent could never present the
                // strongest evidence available to it; emitting them from raw text would
                // have made the branch live and its premise false, letting a question
                // generated from the nested table certify the parent as independent of
                // it. Redacting first is what makes the sentence in `Nesting` true.
                val prose = expansionProse(text, verbatimChildSpans(chunkId))
                if (prose.isNotBlank()) {
                    for ((index, question) in expansionsOf(prose).withIndex()) {
                        statement.setLong(1, chunkId)
                        statement.setString(2, "expansion")
                        statement.setInt(3, index)
                        statement.setBytes(4, Float16.encodeVector(embedder.embed(question)))
                        statement.setNull(5, Types.INTEGER)
                        statement.setNull(6, Types.INTEGER)
                        statement.addBatch()
                    }
                }
            }
            statement.executeBatch()
        }
    }

    private fun windowsOf(bytes: ByteArray): List<Anchors.Span> {
        if (bytes.size <= WINDOW_BYTES) return listOf(Anchors.Span(0, bytes.size))

        val spans = mutableListOf<Anchors.Span>()
        var start = 0
        while (start < bytes.size) {
            val end = snap(bytes, minOf(bytes.size, start + WINDOW_BYTES))
            spans += Anchors.Span(start, end)
            if (end >= bytes.size) break
            start = snap(bytes, maxOf(0, end - WINDOW_OVERLAP_BYTES))
        }
        return spans
    }

    /** Advances to the next UTF-8 sequence boundary, so no window splits a character. */
    private fun snap(bytes: ByteArray, offset: Int): Int {
        var snapped = offset
        while (snapped < bytes.size && !Utf8.isBoundary(bytes, snapped)) snapped++
        return snapped
    }

    /**
     * Stand-in expansions: the chunk's opening sentence, phrased as a question.
     *
     * A real builder generates these with a frontier model. Deriving them mechanically
     * keeps the *shape* honest — an expansion is question-phrased text with no window —
     * without pretending this project has generated them.
     */
    /** The local, parent-relative spans of this chunk's verbatim-class children. */
    private fun verbatimChildSpans(chunkId: Long): List<Pair<Int, Int>> {
        val base = chunkSpans[chunkId] ?: return emptyList()
        return chunkParents.filterValues { it == chunkId }.keys
            .filter { child ->
                val (kind, origin) = chunkKinds.getValue(child)
                origin == "source" && kind in PackSchema.VERBATIM_ELIGIBLE_KINDS
            }
            .mapNotNull { chunkSpans[it] }
            .map { it.start - base.start to it.end - base.start }
            .sortedBy { it.first }
    }

    // ------------------------------------------------------------------ the rest

    private fun writeEntities(c: Connection) {
        spec.entities.forEachIndexed { index, entity ->
            // Stored in the form a query is tokenized into, because matching is an indexed
            // lookup rather than a fold-on-the-fly comparison. An alias holding capitals
            // could never match anything, silently, for the life of the pack.
            // Stored as the rewriter will look it up: tokenized and joined by single
            // spaces. Folding alone leaves `fast-cast` and `D&D` intact, while the
            // rewriter compares against n-grams joined from tokens -- so those aliases
            // could never match anything, on a pack that passed alias-normalization
            // validation because folding is idempotent on them.
            val folded = Tokenizer.indexForm(entity.alias)
            if (folded.isEmpty()) {
                notes += BuildNote(
                    "dropped", "entity", entity.alias, "alias-form",
                    "tokenizes to nothing, so it could never match a query",
                )
                return@forEachIndexed
            }
            if (folded != entity.alias) {
                notes += BuildNote(
                    "normalized", "entity", entity.alias, "alias-form",
                    "stored as '$folded'; the index cannot match any other form",
                )
            }
            c.prepare(
                "INSERT INTO entities (entity_id, canonical, alias, kind, chunk_id) " +
                    "VALUES (?, ?, ?, ?, ?)",
            ) {
                it.setLong(1, (index + 1).toLong())
                it.setString(2, entity.canonical)
                it.setString(3, folded)
                it.setString(4, entity.kind)
                val chunk = entity.chunk?.let { key -> chunkFor(key, "an entity") }
                if (chunk == null) it.setNull(5, Types.INTEGER) else it.setLong(5, chunk)
            }
        }
    }

    /**
     * Structured table rows, for the tables whose enrichment holds up.
     *
     * A random table is **enrichment**: the source text is quotable with or without it,
     * and the rows and roll control are an extra the builder derives. So an unparseable
     * dice expression, a range that leaves a result uncovered, or an outcome whose text is
     * not where it claims to be drops *that table's* rows and its roll control and records
     * a note — it does not fail the book. Emitting it anyway meant the activation gate
     * refused the whole pack, so one malformed optional table could stop a 300-page
     * rulebook shipping, and the passage itself would have rendered perfectly.
     *
     * @return the ids of tables that were written, so capabilities naming a dropped one go
     * with it rather than pointing at a table that is not there.
     */
    private fun writeTables(c: Connection): Set<Long> {
        val written = mutableSetOf<Long>()
        spec.tables.forEachIndexed { index, table ->
            val tableId = (index + 1).toLong()
            val chunkId = chunkFor(table.chunk, "a table")
            val text = chunkTexts.getValue(chunkId)

            val problem = enrichmentFault(table, text)
            if (problem != null) {
                notes += BuildNote(
                    "dropped", "table", table.chunk, "table-enrichment",
                    "$problem; the passage is still quotable, but it carries no rows and no " +
                        "roll control",
                )
                return@forEachIndexed
            }

            c.prepare("INSERT INTO tables (table_id, chunk_id, dice_expr) VALUES (?, ?, ?)") {
                it.setLong(1, tableId)
                it.setLong(2, chunkId)
                it.setString(3, table.diceExpr)
            }

            val base = chunkSpans.getValue(chunkId).start
            var cursor = 0
            table.rows.forEachIndexed { seq, row ->
                // Located in order rather than by uniqueness: two outcomes reading
                // "Nothing further goes wrong" is ordinary in a real table, and refusing
                // it would be the fixture format dictating what a book may say.
                val relative = Anchors.within(text, row.text, cursor)
                cursor = text.indexOf(row.text, cursor) + row.text.length
                c.prepare(
                    """
                    INSERT INTO table_rows (table_id, seq, lo, hi, span_start, span_end, text)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                ) {
                    it.setLong(1, tableId)
                    it.setInt(2, seq)
                    it.setLong(3, row.lo)
                    it.setLong(4, row.hi)
                    it.setInt(5, base + relative.start)
                    it.setInt(6, base + relative.end)
                    it.setString(7, row.text)
                }
            }
            written += tableId
        }
        return written
    }

    /**
     * Why this table's enrichment is unusable, or null.
     *
     * The same three properties the activation gate checks, checked here so the answer is
     * a note rather than a refused book.
     */
    private fun enrichmentFault(table: CorpusSpec.TableSpec, text: String): String? {
        val expression = DiceExpression.parse(table.diceExpr)
            ?: return "'${table.diceExpr}' is not a dice expression this grammar version knows"
        if (table.rows.isEmpty()) return "it declares no outcomes"

        // Every result the expression can produce lands on exactly one row. Not "the rows
        // look contiguous": a gap is a roll with no outcome and an overlap is a roll with
        // two, and the roller fails closed on both -- so the control would appear and then
        // refuse, which is worse than never appearing.
        // **Compared as intervals, never enumerated.** A malformed row reading
        // `-2000000000..2000000000` is one row and two billion iterations, so the loop that
        // was going to report it out of range hung the builder first -- and a table this
        // method exists to *drop* took the whole build with it.
        val sorted = table.rows.sortedBy { it.lo }
        for (row in sorted) {
            if (row.lo > row.hi) return "row ${row.lo}-${row.hi} is inverted"
            if (row.lo < expression.min || row.hi > expression.max) {
                return "row ${row.lo}-${row.hi} lies outside what '${table.diceExpr}' can roll " +
                    "(${expression.min}-${expression.max})"
            }
        }
        // Contiguous from the first result to the last, with no overlap: each row must
        // start exactly where the previous one ended.
        var expected = expression.min
        for (row in sorted) {
            if (row.lo > expected) {
                return "'${table.diceExpr}' can roll $expected, which no row covers"
            }
            if (row.lo < expected) {
                return "$expected is covered by more than one row"
            }
            expected = row.hi + 1
        }
        if (expected != expression.max + 1) {
            return "'${table.diceExpr}' can roll $expected, which no row covers"
        }

        // And each outcome is where it says it is. A row whose text is not in the chunk is
        // a row the app would render as a quotation of something the book does not say.
        var cursor = 0
        for (row in table.rows) {
            val at = text.indexOf(row.text, cursor)
            if (at < 0) return "the outcome '${row.text.take(40)}' is not in the passage, in order"
            cursor = at + row.text.length
        }
        return null
    }

    private fun writeCapabilities(c: Connection, writtenTables: Set<Long>) {
        val tableIdByChunk = spec.tables.withIndex().associate { (index, table) ->
            table.chunk to (index + 1).toLong()
        }
        spec.capabilities.forEachIndexed { index, capability ->
            val chunkId = chunkFor(capability.chunk, "a capability")
            // A roll-table manifest must target its own chunk, or superseding the table
            // leaves a capability rooted elsewhere still offering to roll on it.
            val tableId = tableIdByChunk[capability.chunk]
                ?: error("roll-table capability on '${capability.chunk}', which has no table")
            // A capability naming a table whose enrichment was dropped goes with it. The
            // alternative is a manifest pointing at a table that is not in the pack, which
            // fails the reference check and refuses the book -- exactly the outcome
            // dropping the table was meant to avoid.
            if (tableId !in writtenTables) {
                notes += BuildNote(
                    "dropped", "capability", capability.chunk, "table-enrichment",
                    "its table was dropped, so there is nothing to roll on",
                )
                return@forEachIndexed
            }
            c.prepare(
                "INSERT INTO capabilities (capability_id, kind, chunk_id, manifest) " +
                    "VALUES (?, ?, ?, ?)",
            ) {
                it.setLong(1, (index + 1).toLong())
                it.setString(2, capability.kind)
                it.setLong(3, chunkId)
                it.setString(
                    4,
                    kotlinx.serialization.json.buildJsonObject {
                        put("table_id", kotlinx.serialization.json.JsonPrimitive(tableId))
                        put("label", kotlinx.serialization.json.JsonPrimitive(capability.label))
                    }.toString(),
                )
            }
        }
    }

    private fun writeConstraints(c: Connection) {
        spec.constraints.forEachIndexed { index, constraint ->
            val chunkId = chunkFor(constraint.chunk, "a constraint")
            c.prepare(
                "INSERT INTO constraints (constraint_id, form, args, chunk_id) VALUES (?, ?, ?, ?)",
            ) {
                it.setLong(1, (index + 1).toLong())
                it.setString(2, constraint.form)
                it.setString(3, constraint.args.toString())
                it.setLong(4, chunkId)
            }
        }
    }

    private fun writeSupersessions(c: Connection) {
        spec.supersessions.forEachIndexed { index, supersession ->
            c.prepare(
                """
                INSERT INTO supersessions (supersession_id, target_source_uid,
                                           target_stable_key, superseding_chunk_id,
                                           target_title, target_edition, target_heading_path,
                                           target_page_label_start, target_page_label_end)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ) {
                it.setLong(1, (index + 1).toLong())
                it.setString(2, supersession.targetSourceUid)
                it.setString(3, supersession.targetStableKey)
                // Only an *absent* field is NULL. A misspelled one would otherwise write a
                // withdrawal-only supersession: activating that pack removes the original
                // passage everywhere and supplies nothing in its place, with neither
                // activation nor the build report mentioning the typo.
                val chunk = supersession.supersedingChunk?.let { key ->
                    chunkFor(key, "a supersession's superseding chunk")
                }
                if (chunk == null) it.setNull(4, Types.INTEGER) else it.setLong(4, chunk)
                it.setString(5, supersession.targetTitle)
                it.setNullableString(6, supersession.targetEdition)
                it.setNullableString(7, supersession.targetHeadingPath)
                it.setNullableString(8, supersession.targetPageLabelStart)
                it.setNullableString(9, supersession.targetPageLabelEnd)
            }
        }
    }

    private fun writeReport(c: Connection) {
        notes.forEachIndexed { index, note ->
            c.prepare(
                """
                INSERT INTO build_report (report_id, severity, subject_kind, subject_id,
                                          validation, detail)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
            ) {
                it.setLong(1, (index + 1).toLong())
                it.setString(2, note.severity)
                it.setString(3, note.subjectKind)
                it.setNullableString(4, note.subjectId)
                it.setString(5, note.validation)
                it.setString(6, note.detail)
            }
        }
    }

    private fun sha256(text: String): String =
        MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private companion object {
        /**
         * Window size and overlap, in UTF-8 bytes.
         *
         * Large enough that a rule fits in one window and small enough that a long chunk
         * does not average away the paragraph that answers the question. The overlap is
         * roughly a sentence, so a passage straddling a boundary is wholly inside one
         * neighbour.
         */
        const val WINDOW_BYTES = 600
        const val WINDOW_OVERLAP_BYTES = 150
    }
}

// ---------------------------------------------------------------------- JDBC helpers

internal fun Connection.exec(sql: String) {
    createStatement().use { it.execute(sql) }
}

internal fun Connection.prepare(sql: String, bind: (PreparedStatement) -> Unit) {
    prepareStatement(sql).use { statement ->
        bind(statement)
        statement.executeUpdate()
    }
}

internal fun PreparedStatement.setNullableString(index: Int, value: String?) {
    if (value == null) setNull(index, Types.VARCHAR) else setString(index, value)
}

/**
 * A chunk's own prose: its text with every verbatim-class child excised.
 *
 * No marker is inserted, unlike the redaction that feeds generation. This string is never
 * shown and never sent to a model — it is the input a question is derived from — and
 * *"how does [table omitted] work"* is a worse expansion than one built from the prose
 * around it.
 *
 * @param childSpans parent-relative UTF-8 byte spans, ascending. They are inside the
 * parent by construction: a child's anchors are resolved *within* the parent's span, so
 * this needs no fail-closed branch the way retrieval's redaction of a third party's pack
 * does.
 */
internal fun expansionProse(text: String, childSpans: List<Pair<Int, Int>>): String {
    if (childSpans.isEmpty()) return text
    val bytes = text.toByteArray(Charsets.UTF_8)
    val kept = StringBuilder()
    var cursor = 0
    for ((start, end) in childSpans) {
        if (start > cursor) kept.append(String(bytes, cursor, start - cursor, Charsets.UTF_8))
        cursor = maxOf(cursor, end)
    }
    if (cursor < bytes.size) {
        kept.append(String(bytes, cursor, bytes.size - cursor, Charsets.UTF_8))
    }
    return kept.toString()
}

/** How many of a chunk's paragraphs get a question. */
private const val EXPANSION_BLOCKS = 3

/**
 * Stand-in expansions: a chunk's paragraph openings, phrased as questions.
 *
 * A real builder generates these with a frontier model. Deriving them mechanically keeps
 * the *shape* honest — an expansion is question-phrased text with no window — without
 * pretending this project has generated them.
 *
 * Blank blocks are skipped rather than counted: excising a nested child leaves the prose
 * with a hole in it, and taking that literally would embed *"how does  work"* once per
 * pack and call it a phrasing bridge.
 */
internal fun expansionsOf(text: String): List<String> =
    text.split(Regex("\\n\\s*\\n"))
        .mapNotNull { block -> block.lineSequence().firstOrNull { it.isNotBlank() }?.trim() }
        .filter { it.isNotEmpty() }
        .take(EXPANSION_BLOCKS)
        .flatMap { opening ->
            val head = opening.take(160)
            listOf("what are the rules for $head", "how does $head work")
        }
