package dev.rpghelper.pack

import dev.rpghelper.pack.ViolationCode.CHUNK_KIND_INVALID
import dev.rpghelper.pack.ViolationCode.CHUNK_ORIGIN_INVALID
import dev.rpghelper.pack.ViolationCode.CLAIM_SPAN_INVERTED
import dev.rpghelper.pack.ViolationCode.CLAIM_SPAN_NOT_UTF8_BOUNDARY
import dev.rpghelper.pack.ViolationCode.CLAIM_SPAN_OUT_OF_RANGE
import dev.rpghelper.pack.ViolationCode.CLAIM_SPAN_PARTIAL
import dev.rpghelper.pack.ViolationCode.DANGLING_CHUNK_REFERENCE
import dev.rpghelper.pack.ViolationCode.DERIVATION_TARGET_MISSING
import dev.rpghelper.pack.ViolationCode.DERIVATION_TARGET_NOT_SOURCE
import dev.rpghelper.pack.ViolationCode.DERIVED_CHUNK_HAS_SOURCE_COLUMNS
import dev.rpghelper.pack.ViolationCode.DERIVED_CHUNK_NESTED
import dev.rpghelper.pack.ViolationCode.DERIVED_CHUNK_NO_DERIVATION
import dev.rpghelper.pack.ViolationCode.DUPLICATE_STABLE_KEY
import dev.rpghelper.pack.ViolationCode.NESTED_TEXT_MISMATCH
import dev.rpghelper.pack.ViolationCode.NESTING_CROSS_SOURCE
import dev.rpghelper.pack.ViolationCode.NONVERBATIM_CHILD_OF_VERBATIM_PARENT
import dev.rpghelper.pack.ViolationCode.NESTING_NOT_CONTAINED
import dev.rpghelper.pack.ViolationCode.NESTING_TOO_DEEP
import dev.rpghelper.pack.ViolationCode.PARENT_CHUNK_MISSING
import dev.rpghelper.pack.ViolationCode.SOURCE_CHUNK_HAS_DERIVATION
import dev.rpghelper.pack.ViolationCode.SOURCE_CHUNK_MISSING_CITATION
import dev.rpghelper.pack.ViolationCode.SOURCE_CHUNK_MISSING_SPAN
import dev.rpghelper.pack.ViolationCode.SPAN_INVERTED
import dev.rpghelper.pack.ViolationCode.SPAN_LENGTH_MISMATCH

/**
 * Checks over `chunks` and `chunk_derivation`: shape, nesting, spans, and provenance.
 *
 * Everything here is decidable from the pack alone. Boundary validation and slice
 * equality are absent because they need the normalized source bytes, which the pack
 * does not ship -- see `docs/00-pack-schema.md` §7.
 */
internal fun checkChunkShape(chunks: Map<Long, ChunkRow>, out: MutableList<Violation>) {
    for (chunk in chunks.values) {
        if (chunk.kind !in PackSchema.KINDS) {
            out += Violation(CHUNK_KIND_INVALID, "chunk ${chunk.id} has kind '${chunk.kind}'")
        }
        if (chunk.origin !in PackSchema.ORIGINS) {
            out += Violation(CHUNK_ORIGIN_INVALID, "chunk ${chunk.id} has origin '${chunk.origin}'")
            continue // the remaining shape rules are stated per origin
        }

        if (chunk.isSource) {
            val absent = buildList {
                if (chunk.sourceId == null) add("source_id")
                if (chunk.headingPath == null) add("heading_path")
                if (chunk.pageLabelStart == null) add("page_label_start")
                if (chunk.pageLabelEnd == null) add("page_label_end")
                // Supersession targets (source_uid, stable_key), so a source chunk
                // without one cannot be amended by an errata pack.
                if (chunk.stableKey == null) add("stable_key")
            }
            if (absent.isNotEmpty()) {
                out += Violation(
                    SOURCE_CHUNK_MISSING_CITATION,
                    "chunk ${chunk.id} is origin='source' but has no ${absent.joinToString()}",
                )
            }
            if (chunk.spanStart == null || chunk.spanEnd == null) {
                out += Violation(
                    SOURCE_CHUNK_MISSING_SPAN,
                    "chunk ${chunk.id} is origin='source' but has no span",
                )
            } else if (chunk.spanStart >= chunk.spanEnd) {
                out += Violation(
                    SPAN_INVERTED,
                    "chunk ${chunk.id} span [${chunk.spanStart}, ${chunk.spanEnd}) is empty or inverted",
                )
            }
        } else {
            // Derived text has no location in any source document. Offsets here could
            // only be fabricated, and a fabricated offset is worse than none because it
            // looks checkable.
            val present = buildList {
                if (chunk.sourceId != null) add("source_id")
                if (chunk.headingPath != null) add("heading_path")
                if (chunk.pageLabelStart != null) add("page_label_start")
                if (chunk.pageLabelEnd != null) add("page_label_end")
                if (chunk.spanStart != null) add("span_start")
                if (chunk.spanEnd != null) add("span_end")
                if (chunk.stableKey != null) add("stable_key")
            }
            if (present.isNotEmpty()) {
                out += Violation(
                    DERIVED_CHUNK_HAS_SOURCE_COLUMNS,
                    "chunk ${chunk.id} is origin='derived' but sets ${present.joinToString()}",
                )
            }
            if (chunk.parentId != null) {
                // Nesting is validated by span containment, and a derived chunk has no
                // span to contain. Two derived chunks linked as parent and child pass
                // every nesting check vacuously.
                out += Violation(
                    DERIVED_CHUNK_NESTED,
                    "chunk ${chunk.id} is origin='derived' but nests under ${chunk.parentId}",
                )
            }
        }
    }
}

/**
 * `stable_key` must identify at most one passage within a source.
 *
 * A duplicate does not make the pack unreadable, it makes it un-amendable: an erratum
 * targeting `(source_uid, stable_key)` matches both chunks and filters an unrelated
 * passage alongside the one it meant to correct. The canonical DDL does not carry a
 * UNIQUE constraint for this, and could not be trusted if it did -- the pack ships its
 * own DDL -- so it is enforced here.
 */
internal fun checkStableKeys(
    chunks: Map<Long, ChunkRow>,
    sourceUids: Map<Long, String>,
    out: MutableList<Violation>,
) {
    chunks.values
        .filter { it.isSource && it.stableKey != null }
        // Grouped by source_uid, not source_id: supersession targets
        // (source_uid, stable_key), so two `sources` rows sharing a uid would make an
        // erratum ambiguous across them in exactly the way this check prevents within one.
        .groupBy { sourceUids[it.sourceId] to it.stableKey }
        .forEach { (key, sharing) ->
            if (sharing.size > 1) {
                out += Violation(
                    DUPLICATE_STABLE_KEY,
                    "chunks ${sharing.map { it.id }.sorted().joinToString()} in source " +
                        "'${key.first}' all claim stable_key '${key.second}'",
                )
            }
        }
}

internal fun checkNesting(chunks: Map<Long, ChunkRow>, out: MutableList<Violation>) {
    for (chunk in chunks.values) {
        val parentId = chunk.parentId ?: continue
        val parent = chunks[parentId]
        if (parent == null) {
            out += Violation(
                PARENT_CHUNK_MISSING,
                "chunk ${chunk.id} names parent $parentId, which is not in this pack",
            )
            continue
        }
        // The builder requires a child of a verbatim-class parent to be verbatim-eligible.
        // Unenforced it is a hole in the central guarantee: a `setting` child carved out
        // of a `rules` parent is an exact slice of rule text, and routing sends it to
        // generation because a child candidate has no ancestor span redacted from it.
        val parentVerbatim = parent.isSource && parent.kind in PackSchema.VERBATIM_ELIGIBLE_KINDS
        if (parentVerbatim && chunk.kind !in PackSchema.VERBATIM_ELIGIBLE_KINDS) {
            out += Violation(
                NONVERBATIM_CHILD_OF_VERBATIM_PARENT,
                "chunk ${chunk.id} is '${chunk.kind}' but nests inside verbatim-class " +
                    "chunk $parentId; its text would reach generation unredacted",
            )
        }
        if (parent.parentId != null) {
            // Deeper structures signal the parent was chunked too coarsely.
            out += Violation(
                NESTING_TOO_DEEP,
                "chunk ${chunk.id} nests under $parentId, which is itself nested",
            )
        }
        if (chunk.sourceId != parent.sourceId) {
            // Spans are only comparable within one source, so a cross-source parent makes
            // containment meaningless rather than merely wrong.
            out += Violation(
                NESTING_CROSS_SOURCE,
                "chunk ${chunk.id} (source ${chunk.sourceId}) nests under $parentId " +
                    "(source ${parent.sourceId})",
            )
            continue
        }

        val childStart = chunk.spanStart
        val childEnd = chunk.spanEnd
        val parentStart = parent.spanStart
        val parentEnd = parent.spanEnd
        if (childStart == null || childEnd == null || parentStart == null || parentEnd == null) {
            continue // already reported as a missing span or a nested derived chunk
        }
        if (childStart < parentStart || childEnd > parentEnd) {
            out += Violation(
                NESTING_NOT_CONTAINED,
                "chunk ${chunk.id} span [$childStart, $childEnd) is not inside " +
                    "parent $parentId span [$parentStart, $parentEnd)",
            )
        }
    }
}

/**
 * Siblings must not overlap.
 *
 * The group key is `(source_id, parent_chunk_id)` with NULL as an ordinary group value.
 * Grouping by parent alone breaks both ways: every book's opening chunk collides with
 * every other book's, and an implementation using SQL `NULL = NULL` skips top-level
 * chunks entirely -- which looks like a passing build.
 *
 * Every overlapping pair is reported, not just adjacent ones after sorting. One long
 * sibling enclosing several short ones overlaps all of them, and stopping at the first
 * would make the promise to report every violation false in exactly the case where the
 * builder most needs the whole list.
 */
internal fun checkSiblingSpans(chunks: Map<Long, ChunkRow>, out: MutableList<Violation>) {
    chunks.values
        .filter { it.isSource && it.spanStart != null && it.spanEnd != null }
        .groupBy { it.sourceId to it.parentId }
        .forEach { (_, siblings) ->
            val open = mutableListOf<ChunkRow>()
            for (chunk in siblings.sortedWith(compareBy({ it.spanStart!! }, { it.spanEnd!! }))) {
                // Anything ending at or before this chunk starts can no longer overlap
                // anything that follows, since the sweep only moves forward.
                open.removeAll { it.spanEnd!! <= chunk.spanStart!! }
                for (earlier in open) {
                    out += Violation(
                        ViolationCode.SIBLING_SPAN_OVERLAP,
                        "chunks ${earlier.id} [${earlier.spanStart}, ${earlier.spanEnd}) and " +
                            "${chunk.id} [${chunk.spanStart}, ${chunk.spanEnd}) are siblings " +
                            "and overlap",
                    )
                }
                open += chunk
            }
        }
}

/**
 * `span_end - span_start` must equal the UTF-8 byte length of `text`.
 *
 * The app cannot re-slice the source -- it has only the hash -- but it can check that
 * the stored text is the size the span claims. That catches truncation and offset
 * drift, the corruptions most likely to survive a build and a transfer intact enough to
 * look valid.
 */
internal fun checkSpanLengths(
    chunks: Map<Long, ChunkRow>,
    textLengths: Map<Long, Int>,
    out: MutableList<Violation>,
) {
    for (chunk in chunks.values) {
        if (!chunk.isSource) continue
        val start = chunk.spanStart ?: continue
        val end = chunk.spanEnd ?: continue
        if (start >= end) continue // already reported
        val declared = end - start
        val actual = textLengths[chunk.id]?.toLong() ?: continue
        if (declared != actual) {
            out += Violation(
                SPAN_LENGTH_MISMATCH,
                "chunk ${chunk.id} span covers $declared bytes but its text is $actual",
            )
        }
    }
}

internal fun checkDerivation(
    db: Db,
    chunks: Map<Long, ChunkRow>,
    out: MutableList<Violation>,
) {
    val cited = mutableSetOf<Long>()
    db.forEachRow(
        "SELECT derivation_id, derived_chunk_id, source_chunk_id FROM chunk_derivation",
    ) { row ->
        val derivationId = row.long(0)
        val derivedId = row.long(1)
        val sourceId = row.long(2)
        cited += derivedId

        if (derivedId !in chunks) {
            out += Violation(
                DANGLING_CHUNK_REFERENCE,
                "chunk_derivation $derivationId names derived chunk $derivedId, " +
                    "which is not in this pack",
            )
        }
        val target = chunks[sourceId]
        if (target == null) {
            out += Violation(
                DERIVATION_TARGET_MISSING,
                "chunk_derivation $derivationId cites chunk $sourceId, which is not in this pack",
            )
        } else if (!target.isSource) {
            // One hop is all it takes, and it needs no source bytes -- just a join.
            out += Violation(
                DERIVATION_TARGET_NOT_SOURCE,
                "chunk_derivation $derivationId cites chunk $sourceId, which is itself " +
                    "derived; a citation chain must terminate at origin='source'",
            )
        }
    }

    for (chunk in chunks.values) {
        when {
            !chunk.isSource && chunk.id !in cited ->
                out += Violation(
                    DERIVED_CHUNK_NO_DERIVATION,
                    "chunk ${chunk.id} is origin='derived' but cites no source chunks",
                )
            chunk.isSource && chunk.id in cited ->
                out += Violation(
                    SOURCE_CHUNK_HAS_DERIVATION,
                    "chunk ${chunk.id} is origin='source' but has chunk_derivation rows",
                )
        }
    }
}

/**
 * Claim spans index into the derived chunk's own `text`, which the pack ships -- so
 * unlike source spans, these can be fully validated here.
 *
 * Skipping them would leave the app anchoring an inline citation chip to offsets that
 * are out of range or land mid-character: a chip in the wrong place, or a crash
 * converting the offset for display, from a pack that passed every other check. Overlap
 * between claim spans is legal and deliberately unchecked -- two sources supporting one
 * sentence is corroboration.
 */
internal fun checkClaimSpans(db: Db, out: MutableList<Violation>) {
    db.forEachRow(
        """
        SELECT cd.derivation_id, cd.claim_span_start, cd.claim_span_end, c.text
        FROM chunk_derivation cd
        JOIN chunks c ON c.chunk_id = cd.derived_chunk_id
        WHERE cd.claim_span_start IS NOT NULL OR cd.claim_span_end IS NOT NULL
        """.trimIndent(),
    ) { row ->
        val derivationId = row.long(0)
        val start = row.longOrNull(1)
        val end = row.longOrNull(2)

        if (start == null || end == null) {
            out += Violation(
                CLAIM_SPAN_PARTIAL,
                "chunk_derivation $derivationId sets only one end of its claim span",
            )
            return@forEachRow
        }
        if (start >= end) {
            out += Violation(
                CLAIM_SPAN_INVERTED,
                "chunk_derivation $derivationId claim span [$start, $end) is empty or inverted",
            )
            return@forEachRow
        }

        val encoded = row.string(3).toByteArray(Charsets.UTF_8)
        if (start < 0 || end > encoded.size) {
            out += Violation(
                CLAIM_SPAN_OUT_OF_RANGE,
                "chunk_derivation $derivationId claim span [$start, $end) falls outside its " +
                    "derived text of ${encoded.size} bytes",
            )
            return@forEachRow
        }
        if (!Utf8.isBoundary(encoded, start.toInt()) || !Utf8.isBoundary(encoded, end.toInt())) {
            out += Violation(
                CLAIM_SPAN_NOT_UTF8_BOUNDARY,
                "chunk_derivation $derivationId claim span [$start, $end) splits a " +
                    "multi-byte character",
            )
        }
    }
}

/**
 * A nested child's text must be exactly what its parent's text holds at the child's
 * offset.
 *
 * Redaction excises `[C.span_start - P.span_start, C.span_end - P.span_start)` from the
 * parent before it becomes generation context. Containment and span-length checks both
 * pass for a pack whose child span points at the wrong region, and the consequence is
 * that redaction removes innocuous prose while the child's actual rule text travels into
 * the prompt.
 *
 * Slice equality against the normalized *source* is builder-only because the pack ships
 * only its hash. Slice equality against the *parent's own shipped text* needs nothing
 * the pack does not already carry, which is exactly the line §7 of the schema spec draws.
 */
internal fun checkNestedText(db: Db, out: MutableList<Violation>) {
    db.forEachRow(
        """
        SELECT c.chunk_id, c.text, c.span_start, c.span_end,
               p.chunk_id, p.text, p.span_start
        FROM chunks c
        JOIN chunks p ON p.chunk_id = c.parent_chunk_id
        WHERE c.origin = 'source' AND p.origin = 'source'
          AND c.span_start IS NOT NULL AND c.span_end IS NOT NULL
          AND p.span_start IS NOT NULL
        """.trimIndent(),
    ) { row ->
        val childId = row.long(0)
        val childText = row.string(1).toByteArray(Charsets.UTF_8)
        val childStart = row.long(2)
        val childEnd = row.long(3)
        val parentId = row.long(4)
        val parentText = row.string(5).toByteArray(Charsets.UTF_8)
        val parentStart = row.long(6)

        val from = childStart - parentStart
        val to = childEnd - parentStart
        // Containment and span-length failures are reported by their own checks; this
        // one only speaks about spans that are otherwise well-formed.
        if (from < 0 || to > parentText.size || from >= to) return@forEachRow

        val slice = parentText.copyOfRange(from.toInt(), to.toInt())
        if (!slice.contentEquals(childText)) {
            out += Violation(
                NESTED_TEXT_MISMATCH,
                "chunk $childId's text is not what parent $parentId holds at bytes " +
                    "[$from, $to) of its own text; redaction would excise the wrong region",
            )
        }
    }
}
