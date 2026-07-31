package dev.rpghelper.pack

import dev.rpghelper.pack.ViolationCode.CONSTRAINTS_WITHOUT_RULESET
import dev.rpghelper.pack.ViolationCode.EMBEDDER_DIM_INVALID
import dev.rpghelper.pack.ViolationCode.EMBEDDER_DIM_MISMATCH
import dev.rpghelper.pack.ViolationCode.FTS_INDEX_UNUSABLE
import dev.rpghelper.pack.ViolationCode.MALFORMED_SCHEMA
import dev.rpghelper.pack.ViolationCode.MISSING_TABLE
import dev.rpghelper.pack.ViolationCode.PACK_META_NOT_SINGLETON
import dev.rpghelper.pack.ViolationCode.PROBE_VECTOR_MALFORMED
import dev.rpghelper.pack.ViolationCode.PROBE_VECTOR_MISMATCH
import dev.rpghelper.pack.ViolationCode.UNKNOWN_EMBEDDER
import dev.rpghelper.pack.ViolationCode.UNSUPPORTED_SCHEMA_VERSION

/** Identity and contract fields from `pack_meta`. */
data class PackMeta(
    val schemaVersion: Int,
    val packUid: String,
    val packVersion: String,
    val title: String,
    val rulesetId: String?,
    val embedderId: String,
    val embedderDim: Int,
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
        val versions = db.map("SELECT schema_version FROM pack_meta") { it.int(0) }
        if (versions.size != 1) {
            violations += Violation(
                PACK_META_NOT_SINGLETON,
                "pack_meta must hold exactly one row, found ${versions.size}",
            )
            return ValidationReport(violations)
        }
        if (versions[0] != PackSchema.SCHEMA_VERSION) {
            violations += Violation(
                UNSUPPORTED_SCHEMA_VERSION,
                "pack declares schema_version ${versions[0]}, " +
                    "this build understands ${PackSchema.SCHEMA_VERSION}",
            )
            return ValidationReport(violations)
        }

        val meta = readMeta(db)
        checkEmbedderContract(db, meta, violations)
        checkRulesetBinding(db, meta, violations)

        val chunks = loadChunks(db)
        val text = readTextPass(db)

        checkLexicalIndex(db, text.canary, violations)
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
        checkTableRows(db, chunks, violations)
        checkClosedVocabularies(db, violations)

        return ValidationReport(violations)
    }

    private fun readMeta(db: Db): PackMeta =
        db.map(
            """
            SELECT schema_version, pack_uid, pack_version, title, ruleset_id,
                   embedder_id, embedder_dim
            FROM pack_meta
            """.trimIndent(),
        ) {
            PackMeta(
                schemaVersion = it.int(0),
                packUid = it.string(1),
                packVersion = it.string(2),
                title = it.string(3),
                rulesetId = it.stringOrNull(4),
                embedderId = it.string(5),
                embedderDim = it.int(6),
            )
        }.single()

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
        } else if (contract.dim != meta.embedderDim) {
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
     * The canary is a term taken from a chunk's own text, so a positive result proves
     * the index exists, is queryable, and is populated with the content it indexes.
     */
    private fun checkLexicalIndex(db: Db, canary: Canary?, out: MutableList<Violation>) {
        val declaration = db.declarationOf("chunks_fts")
        val declared = declaration?.replace(Regex("\\s+"), " ")?.lowercase()
        if (declared == null || !declared.contains("virtual table") || !declared.contains("fts5")) {
            out += Violation(
                FTS_INDEX_UNUSABLE,
                "chunks_fts is not declared as an FTS5 virtual table",
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
