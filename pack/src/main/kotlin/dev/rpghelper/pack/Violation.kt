package dev.rpghelper.pack

/**
 * Why a pack may not be activated.
 *
 * An enum rather than a message string so tests can assert on the specific defect they
 * introduced. A test that matched on prose would keep passing after a copy edit and,
 * worse, would pass when the validator rejected the pack for an entirely different
 * reason than the one under test.
 */
enum class ViolationCode {
    // --- File and format ---------------------------------------------------------
    MISSING_TABLE,
    PACK_META_NOT_SINGLETON,
    UNSUPPORTED_SCHEMA_VERSION,

    /**
     * The file has every required relation name but cannot answer the queries the
     * format requires -- a missing column, or a relation that is not the kind of
     * relation it claims to be. Without this the validator would throw instead of
     * refusing, and a corrupt pack would escape the activation gate as an exception.
     */
    MALFORMED_SCHEMA,

    /**
     * `chunks_fts` is not a usable FTS5 index over the chunks.
     *
     * Checking the name alone admits an ordinary table wearing it, or an FTS5 index
     * that was never populated. Both leave BM25 retrieval either throwing at query
     * time or silently returning nothing, on a pack the user was told is active.
     */
    FTS_INDEX_UNUSABLE,

    /** A pack ships `constraints` rows but declares no `ruleset_id` to bind them to. */
    CONSTRAINTS_WITHOUT_RULESET,

    // --- Embedder contract -------------------------------------------------------
    UNKNOWN_EMBEDDER,
    EMBEDDER_DIM_INVALID,
    EMBEDDER_DIM_MISMATCH,
    PROBE_VECTOR_MALFORMED,
    PROBE_VECTOR_MISMATCH,

    // --- Chunk shape -------------------------------------------------------------
    CHUNK_KIND_INVALID,
    CHUNK_ORIGIN_INVALID,
    SOURCE_CHUNK_MISSING_CITATION,
    SOURCE_CHUNK_MISSING_SPAN,
    DERIVED_CHUNK_HAS_SOURCE_COLUMNS,

    /**
     * A derived chunk declares a parent. Nesting exists so a quotable child can be
     * lifted out of a quotable parent, and it is validated by span containment --
     * which derived chunks have no spans to support. Two derived chunks linked as
     * parent and child would pass every nesting check vacuously and still be treated
     * as a hierarchy by retrieval's deduplication.
     */
    DERIVED_CHUNK_NESTED,

    SPAN_INVERTED,

    /**
     * Two chunks in one source share a `stable_key`. Supersession targets
     * `(source_uid, stable_key)`, so a duplicate makes an erratum ambiguous: it would
     * filter an unrelated passage alongside the one it meant to correct.
     */
    DUPLICATE_STABLE_KEY,

    /**
     * `span_end - span_start` disagrees with the UTF-8 byte length of `text`. Cheap,
     * needs no source bytes, and catches truncation and offset drift -- the
     * corruptions most likely to survive a build and a transfer.
     */
    SPAN_LENGTH_MISMATCH,

    // --- Nesting -----------------------------------------------------------------
    PARENT_CHUNK_MISSING,
    NESTING_TOO_DEEP,
    NESTING_CROSS_SOURCE,
    NESTING_NOT_CONTAINED,
    SIBLING_SPAN_OVERLAP,

    // --- Derivation --------------------------------------------------------------
    DERIVED_CHUNK_NO_DERIVATION,
    SOURCE_CHUNK_HAS_DERIVATION,
    DERIVATION_TARGET_MISSING,

    /**
     * A derivation chain that terminates at another derived chunk. Checking only that
     * the row resolves is not enough: such a chain passes that test and then produces
     * a card whose citation resolves to NULL page fields -- prose rendered as
     * attributed with nothing behind it.
     */
    DERIVATION_TARGET_NOT_SOURCE,

    CLAIM_SPAN_PARTIAL,
    CLAIM_SPAN_INVERTED,
    CLAIM_SPAN_OUT_OF_RANGE,
    CLAIM_SPAN_NOT_UTF8_BOUNDARY,

    // --- Vectors -----------------------------------------------------------------
    VECTOR_ROLE_INVALID,
    VECTOR_LENGTH_MISMATCH,
    VECTOR_NON_FINITE,
    VECTOR_ZERO_NORM,
    VECTOR_WINDOW_INVALID,

    // --- Referential integrity ---------------------------------------------------
    DANGLING_CHUNK_REFERENCE,

    /**
     * A row names a `source_id` with no matching row in `sources`. SQLite's foreign-key
     * declarations do not validate data that was inserted without them enforced, so a
     * source chunk can arrive whose book title, edition, and UID cannot be resolved --
     * leaving its quotation uncitable, which is the one thing a quotation must not be.
     */
    DANGLING_SOURCE_REFERENCE,

    // --- Closed vocabularies -----------------------------------------------------
    LOCATOR_SCHEME_INVALID,
    PAGE_LABEL_SCHEME_INVALID,
    GAP_REASON_INVALID,
}

/** One defect, with enough detail to name the row it was found in. */
data class Violation(val code: ViolationCode, val detail: String) {
    override fun toString(): String = "$code: $detail"
}

/**
 * The outcome of validating one pack.
 *
 * Every violation is collected rather than stopping at the first, because the Packs
 * screen has to explain a refusal to someone who may be able to rebuild the pack, and
 * one defect at a time is a poor way to learn there are nine.
 */
data class ValidationReport(val violations: List<Violation>) {

    val isValid: Boolean get() = violations.isEmpty()

    val codes: Set<ViolationCode> get() = violations.mapTo(mutableSetOf()) { it.code }

    override fun toString(): String =
        if (isValid) "valid" else violations.joinToString("\n") { "  $it" }
}
