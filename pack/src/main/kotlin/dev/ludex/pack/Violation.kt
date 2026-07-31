package dev.ludex.pack

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
    /**
     * The pack holds more rows, or a larger single value, than this build will read.
     *
     * Not a statement that the pack is malformed -- a legitimate 300-page book is far below
     * every one of these -- but that reading it far enough to find out would cost more
     * memory than refusing it. Raised before any content is materialized, which is the only
     * point at which the refusal is cheaper than the failure.
     */
    PACK_EXCEEDS_LIMITS,

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
     * A nested child's text is not what its parent's text holds at the child's offset.
     *
     * Both strings ship in the pack, so this is decidable here even though slice
     * equality against the *source* is builder-only. Without it, redaction excises the
     * wrong region of the parent: the marker replaces innocuous prose and the child's
     * actual rule text passes into the generation context -- the precise failure
     * redaction exists to prevent, on a pack that satisfied every other check.
     */
    NESTED_TEXT_MISMATCH,

    /**
     * A non-verbatim child nested inside a verbatim-class parent.
     *
     * The builder forbids it, and unenforced it is a hole in the central guarantee: a
     * `setting` child carved out of a `rules` parent is an exact slice of rule text that
     * routing sends to generation, because a child candidate has no ancestor span
     * redacted from it.
     */
    NONVERBATIM_CHILD_OF_VERBATIM_PARENT,

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

    /**
     * Two `chunks` rows share a `chunk_id`.
     *
     * The PRIMARY KEY declaration is the builder's word, like everything else in a pack's
     * DDL. A duplicate makes every runtime lookup and citation join free to return either
     * row -- pairing retrieved text with the wrong source -- and it silently shrinks the
     * validator's own view of the pack, so the checks that would have caught the rest of
     * the damage never see the missing rows.
     */
    DUPLICATE_CHUNK_ID,

    /** Two `sources` rows share a `source_uid`, making every erratum targeting it ambiguous. */
    DUPLICATE_SOURCE_UID,

    /**
     * Two `sources` rows share a `source_id`.
     *
     * The pack's own PRIMARY KEY declaration is not evidence. A duplicate leaves every
     * citation join able to return either book, attributing authoritative text to the
     * wrong source.
     */
    DUPLICATE_SOURCE_ID,

    /** A row is NULL in a reference column the format requires. */
    MISSING_CHUNK_REFERENCE,

    /**
     * A `supersessions` row whose superseding chunk is one the same row withdraws.
     *
     * The correction is the one thing that must survive its own application. A row
     * naming a chunk carrying the targeted `(source_uid, stable_key)` deactivates the
     * replacement text along with the text it replaces, so the user is left with the
     * rule silently gone and no notice of why — strictly worse than shipping no errata.
     */
    SUPERSESSION_WITHDRAWS_ITSELF,

    // --- Structured tables -------------------------------------------------------
    /** A `table_rows` row names a `table_id` with no matching `tables` row. */
    DANGLING_TABLE_REFERENCE,

    /**
     * Two `tables` rows share a `table_id`.
     *
     * Validation would check every row against whichever definition the query returned
     * last, while the roller resolves the same id to either — rolling on one table's
     * outcomes and rendering them beneath the other's citation.
     */
    DUPLICATE_TABLE_ID,

    /**
     * A table row's stored text is not the slice of its table chunk that its span names.
     *
     * The roller renders outcome text under the quotation rule. Unchecked, that launders
     * arbitrary `table_rows.text` into quotation-styled output carrying the table's own
     * citation -- fabricated text wearing the app's most authoritative rendering.
     */
    TABLE_ROW_TEXT_MISMATCH,

    /** A table row's span falls outside the span of the table chunk it belongs to. */
    TABLE_ROW_SPAN_OUTSIDE_CHUNK,

    /** Two rows of one table claim overlapping outcome ranges, or one is inverted. */
    TABLE_ROW_RANGE_OVERLAP,

    /** A `tables.dice_expr` does not parse under the pinned grammar. */
    DICE_EXPR_UNPARSEABLE,

    /**
     * A table's rows do not cover its expression's outcome range exactly.
     *
     * A gap means a roll can find no row; an out-of-range row can never be rolled. Both
     * were previously left to the builder, whose word is the thing under inspection.
     */
    TABLE_ROWS_INCOMPLETE,

    // --- Closed vocabularies -----------------------------------------------------
    /** `pack_uid` is blank, or longer than the format allows. */
    PACK_UID_INVALID,

    /**
     * An `entities.alias` is not stored in the form the query is tokenized into.
     *
     * Matching is an indexed lookup, so an alias holding capitals or diacritics can never
     * fire -- silently, and for the life of the pack.
     */
    ALIAS_NOT_NORMALIZED,

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
