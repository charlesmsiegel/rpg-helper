package dev.rpghelper.pack

/**
 * A chunk, identified the only way that is unambiguous.
 *
 * `chunk_id` is **pack-local**. Nothing in the format makes it globally unique, and
 * everything inside a pack — `parent_chunk_id`, `chunk_derivation`, `tables`,
 * `constraints`, `entities` — treats it as local by construction. Two packs built by
 * different people both have a chunk 1.
 *
 * Using the bare id merges unrelated chunks from different books: one inherits the other's
 * score and the other vanishes. The symptom is a citation pointing at the wrong book,
 * which reads as a retrieval-quality problem rather than an identity bug and would be
 * debugged for a long time.
 *
 * It lives in `:pack` so there is exactly one of it. Two modules each defining their own
 * would need a conversion at every boundary, and a conversion is a place to get the
 * pairing wrong.
 */
data class ChunkRef(val packUid: String, val chunkId: Long)
