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
    SPAN_INVERTED,

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
