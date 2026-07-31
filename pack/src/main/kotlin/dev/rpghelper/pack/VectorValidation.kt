package dev.rpghelper.pack

import dev.rpghelper.pack.ViolationCode.DANGLING_CHUNK_REFERENCE
import dev.rpghelper.pack.ViolationCode.VECTOR_LENGTH_MISMATCH
import dev.rpghelper.pack.ViolationCode.VECTOR_NON_FINITE
import dev.rpghelper.pack.ViolationCode.VECTOR_ROLE_INVALID
import dev.rpghelper.pack.ViolationCode.VECTOR_WINDOW_INVALID
import dev.rpghelper.pack.ViolationCode.VECTOR_ZERO_NORM
import kotlin.math.sqrt

/**
 * Layout and numeric usability, in one pass over data that has to be read anyway.
 *
 * Layout checks say the bytes were arranged correctly; they say nothing about whether
 * the numbers mean anything. A NaN propagates through the dot product and compares
 * false against everything, so the chunk either vanishes from retrieval or lands
 * wherever the comparator leaves it; an infinity outranks every real result for every
 * query; a zero norm makes cosine a division by zero. None of these is exotic -- a zero
 * vector is the ordinary result of an embedding call that failed and was not checked.
 */
internal fun checkVectors(
    db: Db,
    embedderDim: Long,
    chunks: Map<Long, ChunkRow>,
    textLengths: Map<Long, Int>,
    out: MutableList<Violation>,
) {
    // Long throughout: the product overflows Int for a dimension a pack is free to
    // declare, and an overflowed expectation matches blobs that are nothing like it.
    val expectedBytes = embedderDim * PackSchema.VECTOR_ELEMENT_BYTES

    db.forEachRow(
        """
        SELECT chunk_id, role, subchunk_index, embedding, window_start, window_end
        FROM vectors
        """.trimIndent(),
    ) { row ->
        val chunkId = row.long(0)
        val role = row.string(1)
        val index = row.long(2)
        val where = "vector (chunk $chunkId, $role, $index)"

        if (chunkId !in chunks) {
            out += Violation(
                DANGLING_CHUNK_REFERENCE,
                "$where references a chunk that is not in this pack",
            )
        }
        if (role !in PackSchema.VECTOR_ROLES) {
            out += Violation(VECTOR_ROLE_INVALID, "$where has an unrecognised role")
            return@forEachRow
        }

        val blob = row.bytes(3)
        if (blob.size.toLong() != expectedBytes) {
            // Decoding past this point would compare arbitrary numbers.
            out += Violation(
                VECTOR_LENGTH_MISMATCH,
                "$where is ${blob.size} bytes, expected $expectedBytes " +
                    "($embedderDim float16 elements)",
            )
            return@forEachRow
        }

        val values = Float16.decodeVector(blob)
        if (values.any { !it.isFinite() }) {
            out += Violation(VECTOR_NON_FINITE, "$where contains NaN or infinity")
        } else {
            var sumOfSquares = 0.0
            for (value in values) sumOfSquares += value.toDouble() * value.toDouble()
            if (sqrt(sumOfSquares) <= PackSchema.MIN_VECTOR_NORM) {
                out += Violation(
                    VECTOR_ZERO_NORM,
                    "$where has effectively zero norm; cosine similarity is undefined for it",
                )
            }
        }

        checkWindow(role, row.longOrNull(4), row.longOrNull(5), textLengths[chunkId], where, out)
    }
}

private fun checkWindow(
    role: String,
    start: Long?,
    end: Long?,
    textLength: Int?,
    where: String,
    out: MutableList<Violation>,
) {
    when (role) {
        "content" -> {
            val invalid = start == null || end == null || start >= end || start < 0 ||
                (textLength != null && end > textLength.toLong())
            if (invalid) {
                // Nesting deduplication asks whether a parent matched because of its
                // child or independently of it, and answers with these offsets. Without
                // usable ones the rule cannot be implemented.
                out += Violation(
                    VECTOR_WINDOW_INVALID,
                    "$where is role='content' but its window [$start, $end) is absent or " +
                        "outside the chunk's text",
                )
            }
        }
        "expansion" -> if (start != null || end != null) {
            // An expansion embeds a generated question, not a span of the chunk.
            out += Violation(
                VECTOR_WINDOW_INVALID,
                "$where is role='expansion' but declares a window",
            )
        }
    }
}
