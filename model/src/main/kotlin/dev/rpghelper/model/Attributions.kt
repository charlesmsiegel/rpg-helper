package dev.rpghelper.model

import dev.rpghelper.pack.ChunkRef

import dev.rpghelper.pack.Utf8

/**
 * A region of the answer and what supports it.
 *
 * Two shapes, and the difference is what the card renders and what the claim-support
 * harness checks against.
 */
sealed interface SupportedRegion {
    val start: Int
    val end: Int

    /** Attributed to one chunk: renders an inline citation chip, checked against that chunk. */
    data class Cited(override val start: Int, override val end: Int, val chunk: ChunkRef) :
        SupportedRegion

    /**
     * Attributed to the context as a set: renders a footer citation, checked against all
     * of it. Weaker, and defined.
     */
    data class Contextual(
        override val start: Int,
        override val end: Int,
        val context: List<ChunkRef>,
    ) : SupportedRegion
}

/** A validated answer: every byte of it accounted for, or the whole card suppressed. */
data class ValidatedAnswer(
    val text: String,
    val regions: List<SupportedRegion>,
    /** Attributions thrown out, and why — for the diagnostics view. */
    val discarded: List<String>,
) {
    /** True when at least one region carries a chip rather than a footer citation. */
    val hasChips: Boolean get() = regions.any { it is SupportedRegion.Cited }
}

/** The card is not rendered at all. */
data class RejectedAnswer(val reason: String)

/**
 * Turns a model's raw attributions into regions the card can render and the harness can
 * check.
 *
 * A bare `String` answer would leave nothing mapping a claim to the chunk that supports
 * it, and two downstream requirements need exactly that mapping: the generated card
 * renders inline citation chips, and the claim-support harness checks each claim
 * **against the chunk it cites**. Neither can be built by matching text after the fact —
 * inferring which chunk a sentence came from is the same guessing the whole design
 * refuses to do.
 */
object Attributions {

    /**
     * Validates [answer] against the chunks that were actually in [context].
     *
     * Three rules, and the third is what makes the other two safe to apply:
     *
     * - **An attribution naming a chunk that was not in the context suppresses the card.**
     *   That is a generation failure, not a bad span — the model has cited something it
     *   was never shown, and no part of the answer can be trusted after that. Same rule as
     *   an unresolvable citation on a quote card.
     * - **A span that is empty, inverted, out of range, or off a UTF-8 sequence boundary
     *   is discarded**, and the region it covered falls back. These offsets come from a
     *   model rather than from a builder, so they get the treatment claim spans get in a
     *   pack: an in-range offset can still split a multi-byte character, and the result is
     *   a chip anchored to corrupted text or a crash converting it for display.
     * - **Every region not covered by a surviving attribution is attributed to the whole
     *   context.** Partial coverage is the common case — a model attributes two sentences
     *   of four — and if only *zero* attributions triggered the fallback, the remaining
     *   claims would render with no chip and reach the harness with no cited chunk to
     *   check them against. A model producing no usable attributions is simply the
     *   limiting case: every region is contextual, the card shows footer citations and no
     *   chips, and the harness evaluates the whole answer against the whole context.
     *   Nothing about the contract is special at zero, which is what stops the boundary
     *   between "some" and "none" from being a place where behaviour is undefined.
     */
    fun validate(
        answer: GeneratedAnswer,
        context: List<RedactedChunk>,
    ): Result<ValidatedAnswer> {
        // Whitespace-only is not an answer. It would otherwise produce one non-empty
        // contextual region, render as a card with nothing in it, and reach the claim
        // harness as zero claims -- which scores 100% support and passes the grounding
        // gate on an answer that says nothing.
        if (answer.text.isBlank()) {
            return Result.failure(AttributionException("the answer is empty"))
        }

        val available = context.map { it.ref }
        val availableSet = available.toSet()

        val fabricated = answer.attributions.map { it.chunk }.filterNot { it in availableSet }
        if (fabricated.isNotEmpty()) {
            return Result.failure(
                AttributionException(
                    "the answer cites ${fabricated.distinct()}, which was not in the context",
                ),
            )
        }

        val bytes = answer.text.toByteArray(Charsets.UTF_8)
        val discarded = mutableListOf<String>()
        val kept = mutableListOf<Attribution>()

        for (attribution in answer.attributions) {
            val problem = fault(attribution, bytes)
            if (problem == null) kept += attribution else discarded += problem
        }

        // Overlaps are resolved by keeping the earlier-starting, longer attribution: two
        // chips over the same bytes would render as one region with two citations, which
        // is precisely the "which chunk actually supports this" ambiguity chips exist to
        // remove.
        val ordered = kept.sortedWith(compareBy({ it.start }, { -(it.end - it.start) }))
        val regions = mutableListOf<SupportedRegion>()
        var cursor = 0
        for (attribution in ordered) {
            if (attribution.start < cursor) {
                discarded += "[${attribution.start}, ${attribution.end}) overlaps an earlier attribution"
                continue
            }
            if (attribution.start > cursor) {
                regions += SupportedRegion.Contextual(cursor, attribution.start, available)
            }
            regions += SupportedRegion.Cited(attribution.start, attribution.end, attribution.chunk)
            cursor = attribution.end
        }
        if (cursor < bytes.size) {
            regions += SupportedRegion.Contextual(cursor, bytes.size, available)
        }

        // An empty answer has no regions and nothing to render; it is not an attribution
        // failure, it is a model that produced nothing, and the card is suppressed.
        if (regions.isEmpty()) {
            return Result.failure(AttributionException("the answer is empty"))
        }
        return Result.success(ValidatedAnswer(answer.text, regions, discarded))
    }

    /** Why [attribution] is unusable, or null. */
    private fun fault(attribution: Attribution, bytes: ByteArray): String? {
        val where = "[${attribution.start}, ${attribution.end})"
        if (attribution.start >= attribution.end) {
            return "$where is empty or inverted"
        }
        if (attribution.start < 0 || attribution.end > bytes.size) {
            return "$where is outside the answer's ${bytes.size} bytes"
        }
        if (!Utf8.isBoundary(bytes, attribution.start) || !Utf8.isBoundary(bytes, attribution.end)) {
            return "$where does not fall on UTF-8 sequence boundaries"
        }
        return null
    }

    /** The bytes of [text] a region covers, decoded. */
    fun slice(text: String, region: SupportedRegion): String {
        val bytes = text.toByteArray(Charsets.UTF_8)
        return String(bytes, region.start, region.end - region.start, Charsets.UTF_8)
    }
}

class AttributionException(message: String) : Exception(message)
