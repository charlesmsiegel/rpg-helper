package dev.rpghelper.pack

import java.nio.file.Path
import java.sql.Connection
import java.sql.DriverManager
import java.sql.PreparedStatement

/**
 * Builds a valid `.rpgpack`, then lets a test break exactly one thing about it.
 *
 * The alternative -- a constructor knob per defect -- grows a parameter for every new
 * rejection case and still cannot express corruptions nobody anticipated. Handing the
 * test an open connection instead means any defect expressible in SQL is testable, and
 * the forge itself stays a description of what a *correct* pack looks like.
 *
 * The baseline is deliberately not minimal. It carries two sources, a nested table
 * inside a rule, a derived chunk with both claim-scoped and whole-chunk citations, and
 * multi-byte characters in its text -- so a test that corrupts one row is corrupting
 * something the rest of the pack still depends on, the way a real defect would.
 */
object PackForge {

    /** The contract the forged pack declares. Deliberately tiny; the validator is dimension-agnostic. */
    val EMBEDDER = EmbedderContract(id = "test-embedder-8", dim = 8)

    /** The identity every forged pack claims, so a caller can name it without reading the file. */
    const val PACK_UID = "test:pack:core"

    /** The `source_uid`s the forged pack's two books carry. */
    const val CORE_SOURCE_UID = "test:core:1e"
    const val ADVENTURE_SOURCE_UID = "test:adv:1e"

    /** Distinguishes packs within one test's temporary directory. */
    private val counter = java.util.concurrent.atomic.AtomicInteger()

    /**
     * Writes a pack into [directory] and returns its path.
     *
     * [mutate] runs against the open connection after a valid pack has been built and
     * before it is closed. With no [mutate] the result is a pack that must validate
     * cleanly -- every test asserts that too, so a test cannot go green because the
     * baseline was broken for a reason it was not testing.
     */
    fun writePack(directory: Path, mutate: (Connection) -> Unit = {}): Path {
        val path = directory.resolve("forged-${counter.incrementAndGet()}.rpgpack")
        DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { connection ->
            connection.autoCommit = false
            createSchema(connection)
            populate(connection)
            mutate(connection)
            connection.commit()
        }
        return path
    }

    // ------------------------------------------------------------------ schema

    private fun createSchema(connection: Connection) {
        connection.createStatement().use { statement ->
            // The canonical DDL, split on statement boundaries. Sharing the constant with
            // the app is the point: a fixture built from its own private copy of the
            // schema would keep passing after the real one changed.
            PackSchema.DDL.split(";")
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .forEach { statement.execute(it) }
        }
    }

    // ------------------------------------------------------------------ content

    /**
     * Lays text out along a notional normalized source document, handing back UTF-8
     * byte spans. Offsets are accumulated rather than written by hand so the fixture
     * cannot drift into disagreeing with itself -- which is exactly the defect
     * `SPAN_LENGTH_MISMATCH` exists to catch, and a fixture that already had it would
     * make that test meaningless.
     */
    private class Layout {
        var cursor: Int = 0
            private set

        fun place(text: String): IntRange {
            val start = cursor
            cursor += Utf8Bytes.length(text)
            return start until cursor
        }

        fun skip(bytes: Int): IntRange {
            val start = cursor
            cursor += bytes
            return start until cursor
        }
    }

    private object Utf8Bytes {
        fun length(text: String): Int = text.toByteArray(Charsets.UTF_8).size

        /** UTF-8 byte span of [needle] within [haystack]. */
        fun spanOf(haystack: String, needle: String): IntRange {
            val index = haystack.indexOf(needle)
            require(index >= 0) { "'$needle' does not occur in the fixture text" }
            val start = length(haystack.substring(0, index))
            return start until (start + length(needle))
        }
    }

    // Multi-byte characters are in the fixture on purpose: an em-dash is three UTF-8
    // bytes and one UTF-16 char, so any span arithmetic that confuses the two produces
    // a wrong answer here rather than in production.
    private const val TABLE_TEXT =
        "d6 | Complication\n1 | The rope frays—2 rounds\n2 | A goblin cuts free\n" +
            "3 | Footing gives way\n4 | Nothing\n5 | Nothing\n6 | Advantage next round"

    private val RULES_TEXT =
        "Grappling. To grapple a creature, make a contested check. On a success the " +
            "target is restrained until it escapes.\n\n$TABLE_TEXT\n\nA restrained " +
            "creature’s speed becomes 0."

    private const val SETTING_TEXT =
        "The Underdark is a lightless expanse beneath the surface world, home to " +
            "drow enclaves, mind flayer colonies, and older things that predate both."

    private const val STATBLOCK_TEXT =
        "Ogre. Large giant, chaotic evil. AC 11, HP 59 (7d10+21), Speed 40 ft. " +
            "STR 19 DEX 8 CON 16 INT 5 WIS 7 CHA 7."

    private const val READALOUD_TEXT =
        "The cavern opens before you. Water drips from stalactites you cannot see, " +
            "and something far below answers each drop with a sound like breathing."

    private const val GLOSSARY_TEXT =
        "Restrained. A restrained creature’s speed is 0, and it has disadvantage " +
            "on Dexterity saving throws."

    private const val DERIVED_TEXT =
        "Grappling is resolved with a contested check—see the core rules. A " +
            "restrained target cannot move until it escapes."

    private fun populate(connection: Connection) {
        insertMeta(connection)
        insertSources(connection)

        val core = Layout()
        core.skip(512) // front matter, declared as a gap below
        val rulesSpan = core.place(RULES_TEXT)
        // The nested table sits inside the rule's own text, so its span is derived from
        // the parent's rather than invented alongside it.
        val tableWithin = Utf8Bytes.spanOf(RULES_TEXT, TABLE_TEXT)
        val tableSpan = (rulesSpan.first + tableWithin.first) until
            (rulesSpan.first + tableWithin.last + 1)
        val settingSpan = core.place(SETTING_TEXT)
        val statblockSpan = core.place(STATBLOCK_TEXT)
        val legalSpan = core.skip(256)

        val adventure = Layout()
        val readaloudSpan = adventure.place(READALOUD_TEXT)
        val glossarySpan = adventure.place(GLOSSARY_TEXT)

        insertSourceChunk(connection, 1, "rules", 1, RULES_TEXT, rulesSpan, "Combat > Grappling", "42", "43", "core:grapple", null)
        insertSourceChunk(connection, 2, "table", 1, TABLE_TEXT, tableSpan, "Combat > Grappling > Complications", "42", "42", "core:grapple-complications", 1)
        insertSourceChunk(connection, 3, "setting", 1, SETTING_TEXT, settingSpan, "The Underdark", "88", "88", "core:underdark", null)
        insertSourceChunk(connection, 4, "statblock", 1, STATBLOCK_TEXT, statblockSpan, "Bestiary > Ogre", "201", "201", "core:ogre", null)
        insertSourceChunk(connection, 5, "readaloud", 2, READALOUD_TEXT, readaloudSpan, "Chapter 1 > The Descent", "3", "3", "adv:descent-boxed", null)
        insertSourceChunk(connection, 6, "glossary", 2, GLOSSARY_TEXT, glossarySpan, "Appendix > Conditions", "22", "22", "adv:restrained", null)

        // A builder-written definition: verbatim-eligible kind, derived origin, so it
        // renders as attributed prose and never as a quotation.
        connection.prepare(
            "INSERT INTO chunks (chunk_id, kind, origin, text) VALUES (7, 'glossary', 'derived', ?)",
        ) { it.setString(1, DERIVED_TEXT) }

        insertFts(connection)
        insertGaps(connection, legalSpan)
        insertDerivation(connection)
        insertVectors(connection)
        insertEntities(connection)
        insertTables(connection, tableSpan)
        insertCapabilities(connection)
        insertConstraints(connection)
        insertSupersessions(connection)
        insertBuildReport(connection)
    }

    private fun insertMeta(connection: Connection) {
        connection.prepare(
            """
            INSERT INTO pack_meta (id, schema_version, pack_uid, pack_version, title,
                                   ruleset_id, embedder_id, embedder_dim, probe_vector,
                                   license_id, license_text, attribution, built_at,
                                   builder_version, signature)
            VALUES (1, ?, '$PACK_UID', '1.0.0', 'Forged Test Pack', 'test-srd-1e',
                    ?, ?, ?, 'CC-BY-4.0', 'Test licence text', 'Test attribution',
                    '2026-07-31T00:00:00Z', 'forge/1.0.0', NULL)
            """.trimIndent(),
        ) {
            it.setInt(1, PackSchema.SCHEMA_VERSION)
            it.setString(2, EMBEDDER.id)
            it.setInt(3, EMBEDDER.dim)
            it.setBytes(4, ProbeVector.canonicalBytes())
        }
    }

    private fun insertSources(connection: Connection) {
        connection.exec(
            """
            INSERT INTO sources (source_id, source_uid, title, edition, publisher,
                                 text_sha256, locator_scheme)
            VALUES (1, '$CORE_SOURCE_UID', 'Test Core Rulebook', '1e', 'Test Press',
                    '${"0".repeat(64)}', 'page'),
                   (2, '$ADVENTURE_SOURCE_UID', 'Test Adventure', '1e', 'Test Press',
                    '${"1".repeat(64)}', 'page')
            """.trimIndent(),
        )
        connection.exec(
            """
            INSERT INTO source_page_labels (source_id, seq, phys_start, phys_end, scheme,
                                            start_value, prefix)
            VALUES (1, 0, 1, 8, 'roman-lower', 1, NULL),
                   (1, 1, 9, 320, 'decimal', 1, NULL),
                   (2, 0, 1, 32, 'decimal', 1, NULL)
            """.trimIndent(),
        )
    }

    private fun insertSourceChunk(
        connection: Connection,
        id: Int,
        kind: String,
        sourceId: Int,
        text: String,
        span: IntRange,
        headingPath: String,
        pageStart: String,
        pageEnd: String,
        stableKey: String,
        parentId: Int?,
    ) {
        connection.prepare(
            """
            INSERT INTO chunks (chunk_id, kind, origin, text, source_id, heading_path,
                                page_label_start, page_label_end, span_start, span_end,
                                stable_key, parent_chunk_id)
            VALUES (?, ?, 'source', ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) {
            it.setInt(1, id)
            it.setString(2, kind)
            it.setString(3, text)
            it.setInt(4, sourceId)
            it.setString(5, headingPath)
            it.setString(6, pageStart)
            it.setString(7, pageEnd)
            it.setInt(8, span.first)
            it.setInt(9, span.last + 1)
            it.setString(10, stableKey)
            if (parentId == null) it.setNull(11, java.sql.Types.INTEGER) else it.setInt(11, parentId)
        }
    }

    private fun insertFts(connection: Connection) {
        connection.exec(
            """
            INSERT INTO chunks_fts (rowid, text, heading_path)
            SELECT chunk_id, text, COALESCE(heading_path, '') FROM chunks
            """.trimIndent(),
        )
    }

    private fun insertGaps(connection: Connection, legal: IntRange) {
        connection.exec(
            """
            INSERT INTO source_gaps (gap_id, source_id, span_start, span_end, reason, note)
            VALUES (1, 1, 0, 512, 'front-matter', NULL),
                   (2, 1, ${legal.first}, ${legal.last + 1}, 'legal', 'OGL text')
            """.trimIndent(),
        )
    }

    private fun insertDerivation(connection: Connection) {
        // One claim-scoped row anchoring an inline chip, one whole-chunk row that lands
        // in the footer. Both shapes are legal, and the app renders them differently.
        val claim = Utf8Bytes.spanOf(DERIVED_TEXT, "Grappling is resolved with a contested check")
        connection.prepare(
            """
            INSERT INTO chunk_derivation (derivation_id, derived_chunk_id, source_chunk_id,
                                          claim_span_start, claim_span_end)
            VALUES (1, 7, 1, ?, ?), (2, 7, 6, NULL, NULL)
            """.trimIndent(),
        ) {
            it.setInt(1, claim.first)
            it.setInt(2, claim.last + 1)
        }
    }

    /**
     * Content windows tile each chunk with overlap; expansions carry no window.
     *
     * Values are deterministic and derived from the row's identity, so a failure is
     * reproducible and no two vectors are accidentally identical. Nothing here is a
     * real embedding -- the validator checks layout and numeric usability, neither of
     * which cares what the numbers mean.
     */
    private fun insertVectors(connection: Connection) {
        data class Vector(val chunkId: Int, val role: String, val index: Int, val window: IntRange?)

        val texts = mutableMapOf<Int, String>()
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT chunk_id, text FROM chunks").use { results ->
                while (results.next()) texts[results.getInt(1)] = results.getString(2)
            }
        }

        val rows = mutableListOf<Vector>()
        for ((chunkId, text) in texts.toSortedMap()) {
            val encoded = text.toByteArray(Charsets.UTF_8)
            val length = encoded.size
            // Two overlapping windows where the text is long enough to warrant them,
            // one otherwise. Every byte of every chunk falls inside some window.
            if (length > 120) {
                val midpoint = snapToBoundary(encoded, length / 2)
                val overlapStart = snapToBoundary(encoded, maxOf(0, midpoint - 20))
                rows += Vector(chunkId, "content", 0, 0 until midpoint)
                rows += Vector(chunkId, "content", 1, overlapStart until length)
            } else {
                rows += Vector(chunkId, "content", 0, 0 until length)
            }
            // Expansions exist for verbatim-class chunks: the query-side phrasing gap is
            // what they close, and derived prose is already phrased like a question's answer.
            val verbatimClass = chunkId != 7
            if (verbatimClass) {
                rows += Vector(chunkId, "expansion", 0, null)
                rows += Vector(chunkId, "expansion", 1, null)
            }
        }

        connection.prepareBatch(
            """
            INSERT INTO vectors (chunk_id, role, subchunk_index, embedding,
                                 window_start, window_end)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            for (row in rows) {
                statement.setInt(1, row.chunkId)
                statement.setString(2, row.role)
                statement.setInt(3, row.index)
                statement.setBytes(4, syntheticEmbedding(row.chunkId, row.role, row.index))
                if (row.window == null) {
                    statement.setNull(5, java.sql.Types.INTEGER)
                    statement.setNull(6, java.sql.Types.INTEGER)
                } else {
                    statement.setInt(5, row.window.first)
                    statement.setInt(6, row.window.last + 1)
                }
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    /** Advances [offset] to the next UTF-8 sequence boundary, so no window splits a character. */
    private fun snapToBoundary(encoded: ByteArray, offset: Int): Int {
        var snapped = offset
        while (snapped < encoded.size && (encoded[snapped].toInt() and 0xC0) == 0x80) snapped++
        return snapped
    }

    private fun syntheticEmbedding(chunkId: Int, role: String, index: Int): ByteArray {
        val seed = chunkId * 31 + role.hashCode() * 7 + index
        val values = FloatArray(EMBEDDER.dim) { position ->
            // Bounded well inside binary16's range, never zero, and varying per element.
            val raw = ((seed + position * 17) % 97 + 1) / 100.0f
            if ((seed + position) % 2 == 0) raw else -raw
        }
        return Float16.encodeVector(values)
    }

    private fun insertEntities(connection: Connection) {
        connection.exec(
            """
            INSERT INTO entities (entity_id, canonical, alias, kind, chunk_id)
            VALUES (1, 'grapple', 'wrestle', 'rules-term', 1),
                   (2, 'grapple', 'wrestling', 'rules-term', 1),
                   (3, 'grapple', 'pin', 'rules-term', 1),
                   (4, 'Underdark', 'under dark', 'place', 3),
                   (5, 'ogre', 'ogres', 'creature', 4),
                   (6, 'restrained', 'held', 'rules-term', NULL)
            """.trimIndent(),
        )
    }

    private fun insertTables(connection: Connection, tableSpan: IntRange) {
        connection.exec("INSERT INTO tables (table_id, chunk_id, dice_expr) VALUES (1, 2, 'd6')")

        val outcomes = listOf(
            "The rope frays—2 rounds",
            "A goblin cuts free",
            "Footing gives way",
            "Nothing",
            "Nothing",
            "Advantage next round",
        )
        connection.prepareBatch(
            """
            INSERT INTO table_rows (table_id, seq, lo, hi, span_start, span_end, text)
            VALUES (1, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
        ) { statement ->
            // Rows cover 1..6 exactly: no gaps, no overlaps, nothing out of range. Each
            // outcome's text is a real span of the table chunk, not a transcription.
            var searchFrom = 0
            outcomes.forEachIndexed { index, outcome ->
                val relative = TABLE_TEXT.indexOf(outcome, searchFrom)
                require(relative >= 0) { "outcome '$outcome' not found in table text" }
                searchFrom = relative + outcome.length
                val start = tableSpan.first +
                    Utf8Bytes.length(TABLE_TEXT.substring(0, relative))
                statement.setInt(1, index)
                statement.setInt(2, index + 1)
                statement.setInt(3, index + 1)
                statement.setInt(4, start)
                statement.setInt(5, start + Utf8Bytes.length(outcome))
                statement.setString(6, outcome)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertCapabilities(connection: Connection) {
        connection.exec(
            """
            INSERT INTO capabilities (capability_id, kind, chunk_id, manifest)
            VALUES (1, 'roll-table', 2, '{"table_id":1,"label":"Grappling complications"}')
            """.trimIndent(),
        )
    }

    private fun insertConstraints(connection: Connection) {
        // One instance of the `range` form from the closed vocabulary pinned in
        // docs/07-documents-and-constraints-spec.md §4.
        connection.exec(
            """
            INSERT INTO constraints (constraint_id, form, args, chunk_id)
            VALUES (1, 'range', '{"selector":"hp.current","min":0}', 1)
            """.trimIndent(),
        )
    }

    private fun insertSupersessions(connection: Connection) {
        // Targets a book this pack does not contain: an errata pack is valid standalone,
        // and the citation snapshot is what makes its notice readable anyway.
        connection.exec(
            """
            INSERT INTO supersessions (supersession_id, target_source_uid, target_stable_key,
                                       superseding_chunk_id, target_title, target_edition,
                                       target_heading_path, target_page_label_start,
                                       target_page_label_end)
            VALUES (1, 'other:core:1e', 'other:grapple', 1, 'Other Core Rulebook', '1e',
                    'Combat > Grappling', '40', '41')
            """.trimIndent(),
        )
    }

    private fun insertBuildReport(connection: Connection) {
        connection.exec(
            """
            INSERT INTO build_report (report_id, severity, subject_kind, subject_id,
                                      validation, detail)
            VALUES (1, 'dropped', 'table', '2', 'round-trip',
                    'Re-rendered rows differed from the source beyond whitespace')
            """.trimIndent(),
        )
    }
}

// ---------------------------------------------------------------------- JDBC helpers

fun Connection.exec(sql: String) {
    createStatement().use { it.execute(sql) }
}

/** Binds one row and executes it. */
fun Connection.prepare(sql: String, bind: (PreparedStatement) -> Unit) {
    prepareStatement(sql).use { statement ->
        bind(statement)
        statement.executeUpdate()
    }
}

/** Hands the statement over so the caller can add and execute a batch. */
fun Connection.prepareBatch(sql: String, fill: (PreparedStatement) -> Unit) {
    prepareStatement(sql).use(fill)
}
