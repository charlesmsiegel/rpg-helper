package dev.rpghelper.pack

import dev.rpghelper.pack.ViolationCode.CONSTRAINTS_WITHOUT_RULESET
import dev.rpghelper.pack.ViolationCode.DUPLICATE_CHUNK_ID
import dev.rpghelper.pack.ViolationCode.EMBEDDER_DIM_INVALID
import dev.rpghelper.pack.ViolationCode.EMBEDDER_DIM_MISMATCH
import dev.rpghelper.pack.ViolationCode.FTS_INDEX_UNUSABLE
import dev.rpghelper.pack.ViolationCode.MALFORMED_SCHEMA
import dev.rpghelper.pack.ViolationCode.MISSING_TABLE
import dev.rpghelper.pack.ViolationCode.PACK_META_NOT_SINGLETON
import dev.rpghelper.pack.ViolationCode.PACK_UID_INVALID
import dev.rpghelper.pack.ViolationCode.PROBE_VECTOR_MALFORMED
import dev.rpghelper.pack.ViolationCode.PROBE_VECTOR_MISMATCH
import dev.rpghelper.pack.ViolationCode.UNKNOWN_EMBEDDER
import dev.rpghelper.pack.ViolationCode.UNSUPPORTED_SCHEMA_VERSION

/** Identity and contract fields from `pack_meta`. */
data class PackMeta(
    /**
     * Read as [Long], never narrowed.
     *
     * A pack chooses these numbers and `Int` truncation is silent: `4294967297` narrows to
     * `1` and `4294967424` narrows to `128`, so a damaged or hostile file could declare
     * exactly the gross metadata corruption these fields exist to catch and be accepted.
     * The open-time check already read them at full width, and the full validator did not —
     * so a pack could pass `install` and `verify`, be reported as activating, and then be
     * deactivated as unreadable on its first borrow. Two checks of one field disagreeing is
     * worse than either alone.
     */
    val schemaVersion: Long,
    val packUid: String,
    val packVersion: String,
    val title: String,
    val rulesetId: String?,
    val embedderId: String,
    val embedderDim: Long,
)

/**
 * Decides whether a pack may be activated.
 *
 * Scope is deliberately "what a pack can prove about itself". Span *boundary*
 * validation and slice equality need the normalized source bytes, which the pack does
 * not ship -- only their hash -- so those stay in the builder, and what crosses to the
 * device is the guarantee that they ran.
 *
 * That line sits further along than `android-app-design.md` originally drew it.
 * Containment, sibling overlap, and claim-span validity were listed as builder-only,
 * but containment and overlap are comparisons between integers already stored in the
 * pack, and a claim span indexes into the derived chunk's own `text`, which ships. All
 * three are checked here, at no cost, because the alternative is trusting a builder
 * nobody in this codebase wrote.
 *
 * Every failure rejects the pack. There is no partial activation: by the time a
 * violation is visible the builder's guarantees have demonstrably not held, and a pack
 * that is wrong in one place has given no reason to be trusted in another.
 *
 * @param supportedEmbedders the contracts whose weights this build actually bundles.
 */
class PackValidator(private val supportedEmbedders: Set<EmbedderContract>) {

    /**
     * Never throws on a malformed pack.
     *
     * A pack is untrusted input, so a query failing against it is a fact about the file
     * rather than an error in this program. Letting a driver exception escape would put
     * a corrupt pack *past* the activation gate -- as a crash rather than a refusal,
     * which is the one outcome the gate exists to prevent.
     */
    fun validate(db: Db): ValidationReport =
        try {
            collect(db)
        } catch (e: PackReadException) {
            ValidationReport(
                listOf(
                    Violation(
                        MALFORMED_SCHEMA,
                        "pack does not match the schema it declares: ${e.message}",
                    ),
                ),
            )
        }

    private fun collect(db: Db): ValidationReport {
        val violations = mutableListOf<Violation>()

        // Everything below reads from these relations. One clear refusal beats a cascade
        // of secondary failures caused by the first.
        val missing = PackSchema.REQUIRED_TABLES - db.tableNames()
        if (missing.isNotEmpty()) {
            missing.sorted().forEach {
                violations += Violation(MISSING_TABLE, "required table absent: $it")
            }
            return ValidationReport(violations)
        }

        // Read the version alone before anything else: on an unrecognised version the
        // remaining columns may not mean what this code thinks they mean, and a
        // best-effort read of an unknown layout produces confident nonsense.
        // Long, not Int. `Row.int` narrows, and a pack declaring 4294967297 would
        // truncate to exactly 1 -- admitting a file that explicitly says it is a schema
        // this build does not understand, which is the one claim the gate must read
        // literally before it reads anything else.
        val versions = db.map("SELECT schema_version FROM pack_meta") { it.long(0) }
        if (versions.size != 1) {
            violations += Violation(
                PACK_META_NOT_SINGLETON,
                "pack_meta must hold exactly one row, found ${versions.size}",
            )
            return ValidationReport(violations)
        }
        if (versions[0] != PackSchema.SCHEMA_VERSION.toLong()) {
            violations += Violation(
                UNSUPPORTED_SCHEMA_VERSION,
                "pack declares schema_version ${versions[0]}, " +
                    "this build understands ${PackSchema.SCHEMA_VERSION}",
            )
            return ValidationReport(violations)
        }

        val meta = readMeta(db)
        checkPackUid(meta, violations)
        checkEmbedderContract(db, meta, violations)
        checkRulesetBinding(db, meta, violations)

        val table = loadChunks(db)
        if (table.duplicateIds.isNotEmpty()) {
            // Refused here rather than reported alongside everything else: the map below
            // is what every remaining check reads, and a duplicate means that map is
            // missing rows. A cascade of secondary failures against an incomplete view is
            // worse than one clear refusal -- the same argument REQUIRED_TABLES makes.
            violations += Violation(
                DUPLICATE_CHUNK_ID,
                "chunks rows share chunk_id ${table.duplicateIds.sorted()}",
            )
            return ValidationReport(violations)
        }
        val chunks = table.byId
        val text = readTextPass(db)

        checkLexicalIndex(db, text.canary, chunks.size.toLong(), violations)
        val sourceUids = mutableMapOf<Long, String>()
        db.forEachRow("SELECT source_id, source_uid FROM sources") {
            sourceUids[it.long(0)] = it.string(1)
        }

        checkChunkShape(chunks, violations)
        checkStableKeys(chunks, sourceUids, violations)
        checkNesting(chunks, violations)
        checkSiblingSpans(chunks, violations)
        checkSpanLengths(chunks, text.lengths, violations)
        checkDerivation(db, chunks, violations)
        checkClaimSpans(db, violations)
        checkVectors(db, meta.embedderDim, chunks, text.lengths, violations)
        checkNestedText(db, violations)
        checkChunkReferences(db, chunks, violations)
        checkSourceReferences(db, chunks, violations)
        checkSourceUids(db, violations)
        checkSupersessions(db, violations)
        checkTableRows(db, chunks, violations)
        checkAliasNormalization(db, violations)
        checkClosedVocabularies(db, violations)
        checkBuildReport(db, violations)

        return ValidationReport(violations)
    }

    /**
     * `build_report` must be **readable**, because it is the one thing the app carries
     * forward on trust.
     *
     * Every other check here verifies a property of the pack. This one verifies that a
     * *claim the pack makes about itself* can be read at all — specifically the `unchecked`
     * severity, meaning model-written prose no judge adjudicated. The reader used to
     * swallow a read failure and return an empty report, which made a malformed table
     * indistinguishable from a clean build: a pack could suppress its own warning by
     * shipping columns the reader chokes on.
     *
     * `REQUIRED_TABLES` only established that the name exists. This reads one row's worth
     * of the declared shape, which is what turns "the table is there" into "the report can
     * be read".
     */
    private fun checkBuildReport(db: Db, out: MutableList<Violation>) {
        runCatching { BuildReport.of(db) }.onFailure {
            out += Violation(
                MALFORMED_SCHEMA,
                "build_report cannot be read as the format declares it: ${it.message}",
            )
        }
    }

    private fun readMeta(db: Db): PackMeta =
        db.map(
            "SELECT schema_version, pack_uid, pack_version, title, ruleset_id, " +
                "embedder_id, embedder_dim FROM pack_meta",
        ) {
            PackMeta(
                schemaVersion = it.long(0),
                packUid = it.string(1),
                packVersion = it.string(2),
                title = it.string(3),
                rulesetId = it.stringOrNull(4),
                embedderId = it.string(5),
                embedderDim = it.long(6),
            )
        }.single()

    /**
     * `pack_uid` is an identity the app keys installs and cache entries on.
     *
     * It is never used as a path component (`01-app-state-spec.md` §1), so this is not
     * what makes installation safe. It bounds a different problem: a blank uid would enter
     * the unique install namespace and make every other blank-uid pack look like a
     * replacement for it, silently inheriting an activation the user never granted.
     */
    private fun checkPackUid(meta: PackMeta, out: MutableList<Violation>) {
        if (meta.packUid.isBlank() || meta.packUid.length > PackSchema.MAX_PACK_UID_LENGTH) {
            out += Violation(
                PACK_UID_INVALID,
                "pack_uid must be non-blank and at most ${PackSchema.MAX_PACK_UID_LENGTH} " +
                    "characters; this one is ${meta.packUid.length}",
            )
        }
    }

    private fun checkEmbedderContract(db: Db, meta: PackMeta, out: MutableList<Violation>) {
        if (meta.embedderDim <= 0) {
            out += Violation(EMBEDDER_DIM_INVALID, "embedder_dim is ${meta.embedderDim}")
        }

        val contract = supportedEmbedders.firstOrNull { it.id == meta.embedderId }
        if (contract == null) {
            out += Violation(
                UNKNOWN_EMBEDDER,
                "pack needs embedder '${meta.embedderId}', which this build does not bundle",
            )
        } else if (contract.dim.toLong() != meta.embedderDim) {
            out += Violation(
                EMBEDDER_DIM_MISMATCH,
                "embedder '${meta.embedderId}' is ${contract.dim}-dimensional here, " +
                    "pack declares ${meta.embedderDim}",
            )
        }

        val probe = db.map("SELECT probe_vector FROM pack_meta") { it.bytes(0) }.single()
        if (probe.size != ProbeVector.BYTE_LENGTH) {
            out += Violation(
                PROBE_VECTOR_MALFORMED,
                "probe vector is ${probe.size} bytes, expected ${ProbeVector.BYTE_LENGTH}",
            )
        } else if (!ProbeVector.matches(probe)) {
            // Reached only when the bytes are the right length and still decode wrong:
            // byte order, float32, NaN-boxing, or padding.
            out += Violation(
                PROBE_VECTOR_MISMATCH,
                "probe vector does not decode to its pinned constant; the pack's vector " +
                    "encoding does not match the format (byte order or element width)",
            )
        }
    }

    /**
     * Constraints need a ruleset to be reachable.
     *
     * Documents name the ruleset they are played under, and constraints load only from
     * packs matching that binding -- with *unbound* meaning nothing is validated at all.
     * So a pack shipping constraints with no `ruleset_id` has declared rules that can
     * never load for any document. That is a silently disabled feature rather than a
     * wrong answer, which is exactly the case the format refuses to leave a user
     * guessing about.
     */
    private fun checkRulesetBinding(db: Db, meta: PackMeta, out: MutableList<Violation>) {
        if (meta.rulesetId != null) return
        val constraints = db.map("SELECT count(*) FROM constraints") { it.long(0) }.single()
        if (constraints > 0) {
            out += Violation(
                CONSTRAINTS_WITHOUT_RULESET,
                "pack ships $constraints constraint rows but declares no ruleset_id, so no " +
                    "document could ever load them",
            )
        }
    }

    /**
     * `chunks_fts` must be a real FTS5 index, and must actually contain the chunks.
     *
     * The required-table check matches on name, and `sqlite_master` lists a virtual
     * table as an ordinary `table` -- so a plain table, or a view, passes it wearing the
     * right name. Nothing downstream in this validator queries the index, so without
     * this the failure surfaces at the first search: either a thrown `MATCH` error or,
     * for an FTS5 table that was simply never populated, an empty lexical result on
     * every query, forever, on a pack the user was told is active.
     *
     * Two checks, because neither is sufficient alone. The row count catches an index
     * populated for some chunks and not others, which a single probe cannot see. The
     * canary -- a term taken from a chunk's own text -- proves the index answers `MATCH`
     * and returns the chunk that term came from.
     *
     * Together they establish that the index exists, is queryable, covers every chunk,
     * and returns the right rowid for at least one of them. They do not establish that
     * every chunk's *content* was indexed correctly; that would need a term per chunk,
     * and the row count is what makes the cheap version worth having.
     */
    private fun checkLexicalIndex(
        db: Db,
        canary: Canary?,
        chunkCount: Long,
        out: MutableList<Violation>,
    ) {
        val declaration = db.declarationOf("chunks_fts")
        val declared = declaration?.replace(Regex("\\s+"), " ")?.lowercase()
        if (declared == null || !declared.contains("virtual table") || !declared.contains("fts5")) {
            out += Violation(
                FTS_INDEX_UNUSABLE,
                "chunks_fts is not declared as an FTS5 virtual table",
            )
            return
        }

        // The *tokenizer* is part of the contract, not an implementation detail of the
        // builder. The app tokenizes queries as `unicode61 remove_diacritics 2`, and an
        // index built with anything else folds terms differently: with diacritic removal
        // off, the index holds `café` while every query carries `cafe`, and the content is
        // silently unretrievable for the life of the pack. No canary catches it, because a
        // canary is drawn from ASCII by construction.
        val tokenizer = Regex("tokenize\\s*=?\\s*['\"]([^'\"]*)['\"]").find(declared)
            ?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim()
        if (tokenizer != PackSchema.TOKENIZER) {
            out += Violation(
                FTS_INDEX_UNUSABLE,
                "chunks_fts declares tokenize='${tokenizer ?: "(absent)"}'; the format pins " +
                    "'${PackSchema.TOKENIZER}', and any other folds query terms differently",
            )
            return
        }
        // External content over `chunks`, keyed by `chunk_id`: the rowid correspondence
        // every other check in this validator and every query in retrieval assumes.
        if (!declared.contains("content='chunks'") ||
            !declared.contains("content_rowid='chunk_id'")
        ) {
            out += Violation(
                FTS_INDEX_UNUSABLE,
                "chunks_fts is not external-content over chunks(chunk_id); its rowids do " +
                    "not correspond to chunk ids",
            )
            return
        }

        // Row count first: it catches wholesale under-population, which one canary term
        // cannot. An index populated for chunk 1 and nothing else satisfies any single
        // probe while leaving every other chunk invisible to lexical search forever.
        //
        // `count(*)` on an external-content table reads the content table and would
        // always agree, so this counts the index's own per-document rows. The shadow
        // table is part of FTS5's documented on-disk structure, but if a future build
        // omits it the check is skipped rather than turned into a false rejection.
        // Compare the indexed *identifiers*, not how many there are. Equal counts do not
        // establish equal sets: an index missing chunk 5 while carrying a stray document
        // 999 has exactly the right cardinality, and a canary drawn from another chunk
        // still passes -- leaving chunk 5 permanently invisible to lexical search under
        // an activation that claimed complete coverage.
        val unindexed = try {
            db.map(
                "SELECT c.chunk_id FROM chunks c " +
                    "LEFT JOIN chunks_fts_docsize d ON d.id = c.chunk_id WHERE d.id IS NULL",
            ) { it.long(0) }
        } catch (e: PackReadException) {
            null // a build without the shadow table skips this rather than false-rejecting
        }
        // And the other direction. A pack carrying every legitimate id *plus* orphan index
        // documents activates on the check above alone, and a matching orphan consumes the
        // lexical depth budget before `loadRows` silently drops it -- so a query can refuse
        // with usable chunks sitting just below the limit.
        val orphans = try {
            db.map(
                "SELECT d.id FROM chunks_fts_docsize d " +
                    "LEFT JOIN chunks c ON c.chunk_id = d.id WHERE c.chunk_id IS NULL",
            ) { it.long(0) }
        } catch (e: PackReadException) {
            null
        }
        if (orphans != null && orphans.isNotEmpty()) {
            out += Violation(
                FTS_INDEX_UNUSABLE,
                "chunks_fts holds ${orphans.size} documents with no chunk " +
                    "(${orphans.take(5).joinToString()}); they can win a search and resolve to nothing",
            )
        }

        if (unindexed != null && unindexed.isNotEmpty()) {
            out += Violation(
                FTS_INDEX_UNUSABLE,
                "chunks_fts does not index ${unindexed.size} of $chunkCount chunks " +
                    "(${unindexed.take(5).joinToString()}); they are invisible to lexical search",
            )
            return
        }

        // An empty pack has nothing to look for; that is vacuous, not broken.
        if (canary == null) return

        val matches = try {
            // The term is ASCII letters by construction, so it cannot carry FTS5 syntax;
            // it is quoted as a string literal regardless, which is the discipline every
            // query in this app owes a string it did not author.
            db.map("SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH '\"${canary.term}\"'") {
                it.long(0)
            }
        } catch (e: PackReadException) {
            out += Violation(
                FTS_INDEX_UNUSABLE,
                "chunks_fts did not accept a MATCH query: ${e.message}",
            )
            return
        }

        if (canary.chunkId !in matches) {
            out += Violation(
                FTS_INDEX_UNUSABLE,
                "chunks_fts does not index its chunks: searching '${canary.term}', which " +
                    "occurs in chunk ${canary.chunkId}, did not return it",
            )
        }
    }
}
