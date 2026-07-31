package dev.rpghelper.builder

import dev.rpghelper.model.Embedder
import dev.rpghelper.pack.Float16
import dev.rpghelper.pack.PackSchema
import dev.rpghelper.pack.ProbeVector
import dev.rpghelper.pack.Utf8
import java.nio.file.Files
import java.nio.file.Path
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
) {

    private val notes = mutableListOf<BuildNote>()

    /** chunk id, assigned in document order so a pack diffs stably across rebuilds. */
    private var nextChunkId = 1L

    private val chunkIdByStableKey = mutableMapOf<String, Long>()
    private val chunkTexts = mutableMapOf<Long, String>()
    private val chunkKinds = mutableMapOf<Long, Pair<String, String>>()
    private val chunkSpans = mutableMapOf<Long, Anchors.Span>()

    fun buildTo(path: Path): BuildOutcome {
        Files.deleteIfExists(path)
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { c ->
            c.autoCommit = false
            createSchema(c)
            writeMeta(c)
            writeSources(c)
            writeDerived(c)
            writeFts(c)
            writeVectors(c)
            writeEntities(c)
            writeTables(c)
            writeCapabilities(c)
            writeConstraints(c)
            writeSupersessions(c)
            writeReport(c)
            c.commit()
        }
        return BuildOutcome(path, notes.toList())
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

            for (chunk in source.chunks) writeChunk(c, sourceId, text, chunk, parent = null)
        }
    }

    private fun writeChunk(
        c: Connection,
        sourceId: Long,
        document: String,
        chunk: CorpusSpec.ChunkSpec,
        parent: Pair<Long, Anchors.Span>?,
    ) {
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

        chunkIdByStableKey[chunk.stableKey] = id
        chunkTexts[id] = text
        chunkKinds[id] = chunk.kind to "source"
        chunkSpans[id] = span

        for (child in chunk.children) writeChunk(c, sourceId, document, child, id to span)
    }

    // ------------------------------------------------------------------ derived prose

    private fun writeDerived(c: Connection) {
        var derivationId = 1L
        for (derived in spec.derived) {
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
                val target = chunkIdByStableKey[citation.stableKey]
                    ?: error("derived chunk cites '${citation.stableKey}', which is not in the pack")
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
                val (kind, origin) = chunkKinds.getValue(chunkId)
                val verbatimClass = kind in PackSchema.VERBATIM_ELIGIBLE_KINDS && origin == "source"
                if (verbatimClass) {
                    for ((index, question) in expansionsOf(text).withIndex()) {
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
    private fun expansionsOf(text: String): List<String> {
        val opening = text.take(160).substringBefore('\n')
        return listOf("what are the rules for $opening", "how does $opening work")
    }

    // ------------------------------------------------------------------ the rest

    private fun writeEntities(c: Connection) {
        spec.entities.forEachIndexed { index, entity ->
            // Stored in the form a query is tokenized into, because matching is an indexed
            // lookup rather than a fold-on-the-fly comparison. An alias holding capitals
            // could never match anything, silently, for the life of the pack.
            val folded = Utf8.foldForIndex(entity.alias)
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
                val chunk = entity.chunk?.let { key -> chunkIdByStableKey[key] }
                if (chunk == null) it.setNull(5, Types.INTEGER) else it.setLong(5, chunk)
            }
        }
    }

    private fun writeTables(c: Connection) {
        spec.tables.forEachIndexed { index, table ->
            val tableId = (index + 1).toLong()
            val chunkId = chunkIdByStableKey[table.chunk]
                ?: error("table names chunk '${table.chunk}', which is not in the pack")
            c.prepare("INSERT INTO tables (table_id, chunk_id, dice_expr) VALUES (?, ?, ?)") {
                it.setLong(1, tableId)
                it.setLong(2, chunkId)
                it.setString(3, table.diceExpr)
            }

            val text = chunkTexts.getValue(chunkId)
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
                    it.setInt(3, row.lo)
                    it.setInt(4, row.hi)
                    it.setInt(5, base + relative.start)
                    it.setInt(6, base + relative.end)
                    it.setString(7, row.text)
                }
            }
        }
    }

    private fun writeCapabilities(c: Connection) {
        val tableIdByChunk = spec.tables.withIndex().associate { (index, table) ->
            table.chunk to (index + 1).toLong()
        }
        spec.capabilities.forEachIndexed { index, capability ->
            val chunkId = chunkIdByStableKey[capability.chunk]
                ?: error("capability names chunk '${capability.chunk}', which is not in the pack")
            // A roll-table manifest must target its own chunk, or superseding the table
            // leaves a capability rooted elsewhere still offering to roll on it.
            val tableId = tableIdByChunk[capability.chunk]
                ?: error("roll-table capability on '${capability.chunk}', which has no table")
            c.prepare(
                "INSERT INTO capabilities (capability_id, kind, chunk_id, manifest) " +
                    "VALUES (?, ?, ?, ?)",
            ) {
                it.setLong(1, (index + 1).toLong())
                it.setString(2, capability.kind)
                it.setLong(3, chunkId)
                it.setString(4, """{"table_id":$tableId,"label":${quote(capability.label)}}""")
            }
        }
    }

    private fun writeConstraints(c: Connection) {
        spec.constraints.forEachIndexed { index, constraint ->
            val chunkId = chunkIdByStableKey[constraint.chunk]
                ?: error("constraint names chunk '${constraint.chunk}', which is not in the pack")
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
                val chunk = supersession.supersedingChunk?.let { key -> chunkIdByStableKey[key] }
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

    private fun quote(text: String) = "\"" + text.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

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
