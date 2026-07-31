package dev.rpghelper.retrieval

import dev.rpghelper.pack.ChunkRef

import dev.rpghelper.pack.Utf8

/**
 * A candidate chunk, with everything §9 needs to decide whether it survives.
 *
 * `spanStart`/`spanEnd` are UTF-8 byte offsets into the normalized source document, so a
 * child's span is directly comparable with its parent's and with a dense window's — the
 * whole reason the format counts bytes rather than characters.
 */
data class NestingCandidate(
    val ref: ChunkRef,
    val kind: String,
    val origin: String,
    val parent: ChunkRef?,
    val text: String,
    val spanStart: Int?,
    val spanEnd: Int?,
    /** The window of the chunk's best-scoring dense vector, if dense found it. */
    val denseWindow: IntRange?,
    /** The role of that vector. An `expansion` is independent by construction. */
    val denseRole: String?,
)

/** A surviving candidate, and what routing must excise from it before generation. */
data class Survivor(
    val candidate: NestingCandidate,
    /**
     * Spans of [NestingCandidate.text] to excise, in UTF-8 bytes and ascending order — or
     * **null** when redaction cannot be performed safely and the chunk must be dropped
     * from the generation context entirely.
     *
     * Null rather than an empty list, because "nothing to redact" and "redaction is not
     * possible" must not be the same value: a partial redaction that leaves half a table
     * in the prompt is the exact failure this step exists to prevent.
     */
    val redact: List<IntRange>?,
)

/**
 * Resolves parent/child overlap among matching candidates.
 *
 * Nesting is legal and one level deep: a complete rule may contain a rollable table, and
 * the two spans necessarily overlap. When both match, which survives depends on the
 * **parent's class**, not on the nesting alone.
 *
 * | case | survivor |
 * |---|---|
 * | verbatim-class parent, matching child | parent — the user sees the complete rule, not a table torn out of it |
 * | non-verbatim parent matching *only* through its child | child — otherwise a quotable table is delivered as generated prose |
 * | non-verbatim parent that *also* matches independently | both — the lore is explained and the table is quoted, from one query |
 *
 * The third case exists because the unconditional version threw the setting evidence
 * away: a query hitting both the lore of a region and a rumour table inside it dropped
 * the parent, leaving routing with nothing to generate from and the user with a bare
 * table where they asked about a place.
 */
object Nesting {

    /**
     * Bytes of a dense window that must fall outside the child's span.
     *
     * Overlap alone is not evidence. A window containing the whole nested table plus one
     * adjacent byte overlaps text outside the child while having scored entirely on the
     * child's content, and routing would then keep a parent whose relevant material
     * redaction is about to remove.
     *
     * Tunable, and measured by the dedup rows of the regression suite. A starting point,
     * not a finding.
     */
    const val MIN_OUTSIDE_BYTES = 64

    /** …and that much of the window's own length, so a long window cannot pass on a sliver. */
    const val MIN_OUTSIDE_FRACTION = 0.25

    /**
     * @param nestedChildren every chunk in the same pack whose `parent_chunk_id` is the
     * given chunk. **A lookup on the pack, not a filter on the candidates** — a lore query
     * that never retrieved the child at all still has to redact it, or the rule reaches
     * generation through the front door, past a rule written specifically to keep it out.
     * @param verbatimEligible whether a chunk renders as a quotation: `origin='source'`
     * and a verbatim-eligible kind, together.
     */
    fun deduplicate(
        candidates: List<NestingCandidate>,
        queryTerms: List<String>,
        verbatimEligible: (NestingCandidate) -> Boolean,
        nestedChildren: (ChunkRef) -> List<NestingCandidate> = { emptyList() },
    ): List<Survivor> {
        val byRef = candidates.associateBy { it.ref }
        val redactions = candidates.associate { it.ref to redaction(it, nestedChildren, verbatimEligible) }

        val dropped = mutableSetOf<ChunkRef>()
        for (candidate in candidates) {
            val parentRef = candidate.parent ?: continue
            // The parent did not match: there is no overlap to resolve.
            val parent = byRef[parentRef] ?: continue

            if (verbatimEligible(parent)) {
                // A quote of the whole rule already contains the table, at the same
                // citation. Keeping both would put the same text on screen twice.
                dropped += candidate.ref
            } else if (!matchesIndependently(parent, redactions[parentRef], queryTerms)) {
                dropped += parent.ref
            }
        }

        return candidates
            .filter { it.ref !in dropped }
            .map { Survivor(it, redactions.getValue(it.ref)?.spans) }
    }

    /** The parent's own prose, and the spans excised to get it. */
    private class Redaction(val text: String, val spans: List<IntRange>)

    /**
     * Excises every nested verbatim child's span from [parent], or null if it cannot.
     *
     * **Fail closed.** If any child's offsets do not land within the parent's text or do
     * not fall on UTF-8 sequence boundaries, the answer is null and the parent is out —
     * a malformed span is not a reason to risk leaving half a table in the prompt, and
     * the child remains independently retrievable and quotable, which is how the user
     * should have been getting it anyway.
     */
    private fun redaction(
        parent: NestingCandidate,
        nestedChildren: (ChunkRef) -> List<NestingCandidate>,
        verbatimEligible: (NestingCandidate) -> Boolean,
    ): Redaction? {
        val bytes = parent.text.toByteArray(Charsets.UTF_8)
        val children = nestedChildren(parent.ref).filter(verbatimEligible)
        if (children.isEmpty()) return Redaction(parent.text, emptyList())

        val spans = mutableListOf<IntRange>()
        for (child in children) {
            val span = relativeSpan(parent, child, bytes.size) ?: return null
            spans += span
        }
        spans.sortBy { it.first }

        val kept = StringBuilder()
        var cursor = 0
        for (span in spans) {
            if (span.first > cursor) {
                kept.append(String(bytes, cursor, span.first - cursor, Charsets.UTF_8))
            }
            cursor = maxOf(cursor, span.last + 1)
        }
        if (cursor < bytes.size) {
            kept.append(String(bytes, cursor, bytes.size - cursor, Charsets.UTF_8))
        }
        return Redaction(kept.toString(), spans)
    }

    /**
     * The parent's `text` with every nested verbatim child's span excised.
     *
     * This is what the lexical independence test reads and what routing sends to
     * generation, so the two agree by construction rather than by two implementations
     * happening to excise the same bytes. Null when redaction fails closed.
     */
    fun redactedText(
        parent: NestingCandidate,
        nestedChildren: (ChunkRef) -> List<NestingCandidate>,
        verbatimEligible: (NestingCandidate) -> Boolean,
    ): String? = redaction(parent, nestedChildren, verbatimEligible)?.text

    /**
     * Whether [parent] matched for reasons of its own rather than through its children.
     *
     * Either signal suffices, but two conditions come before both. Redaction must be
     * possible at all — a parent that cannot be safely redacted cannot enter generation,
     * so it has no independent contribution to make. And **a parent whose redacted text is
     * empty or whitespace-only is never independent**: a `setting` chunk entirely covered
     * by its children has nothing of its own to contribute, and sending it to generation
     * would contribute an empty context, which is the failure the whole route-3 design
     * exists to prevent, arriving through a different door.
     */
    private fun matchesIndependently(
        parent: NestingCandidate,
        redaction: Redaction?,
        queryTerms: List<String>,
    ): Boolean {
        if (redaction == null) return false
        if (redaction.text.isBlank()) return false
        return denseEvidence(parent, redaction) || lexicalEvidence(parent, redaction, queryTerms)
    }

    /** An `expansion` hit is independent by construction: it is generated from redacted text. */
    private fun denseEvidence(parent: NestingCandidate, redaction: Redaction): Boolean {
        if (parent.denseRole == "expansion") return true
        val window = parent.denseWindow ?: return false

        val length = window.last + 1 - window.first
        if (length <= 0) return false
        val covered = redaction.spans.sumOf { span ->
            maxOf(0, minOf(window.last, span.last) - maxOf(window.first, span.first) + 1)
        }
        val outside = length - covered
        return outside >= MIN_OUTSIDE_BYTES && outside >= length * MIN_OUTSIDE_FRACTION
    }

    /**
     * Did the parent match *because of* its children?
     *
     * **At least one query term matched inside a redacted span, and every term that did
     * also matches outside.** Not "any term matches", which almost any English prose
     * satisfies through a word like *check* or *creature*; not "all terms match", which
     * nothing satisfies.
     *
     * The first clause is not redundant, and omitting it inverts the rule. A parent that
     * matched only semantically contains no query term inside the child either, so the set
     * to check is empty and the universal quantifier passes **vacuously** — declaring
     * independent a parent in whose redacted text no query term occurs at all, which is
     * exactly the irrelevant lore this test exists to keep out of generation.
     */
    private fun lexicalEvidence(
        parent: NestingCandidate,
        redaction: Redaction,
        queryTerms: List<String>,
    ): Boolean {
        val bytes = parent.text.toByteArray(Charsets.UTF_8)
        val inside = redaction.spans
            .flatMap { span ->
                val slice = bytes.copyOfRange(span.first, span.last + 1)
                Tokenizer.tokenize(String(slice, Charsets.UTF_8))
            }
            .toSet()
        val outside = Tokenizer.tokenize(redaction.text).toSet()

        val matchedInside = queryTerms.map { Tokenizer.fold(it) }.filter { it in inside }
        if (matchedInside.isEmpty()) return false
        return matchedInside.all { it in outside }
    }

    /**
     * [child]'s span as byte offsets into [parent]'s own text, or null if it does not land.
     *
     * Both endpoints must sit on UTF-8 sequence boundaries. An offset mid-character would
     * cut a character in half, and the half left in the prompt is not something the model
     * — or a reader of the diagnostics view — can interpret.
     */
    private fun relativeSpan(
        parent: NestingCandidate,
        child: NestingCandidate,
        parentBytes: Int,
    ): IntRange? {
        val parentStart = parent.spanStart ?: return null
        val childStart = child.spanStart ?: return null
        val childEnd = child.spanEnd ?: return null
        val start = childStart - parentStart
        val end = childEnd - parentStart
        if (start < 0 || end > parentBytes || start >= end) return null

        val bytes = parent.text.toByteArray(Charsets.UTF_8)
        if (!Utf8.isBoundary(bytes, start) || !Utf8.isBoundary(bytes, end)) return null
        return start until end
    }
}
