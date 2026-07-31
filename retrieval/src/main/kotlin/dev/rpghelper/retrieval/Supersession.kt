package dev.rpghelper.retrieval

import dev.rpghelper.pack.Db
import dev.rpghelper.pack.map

/** A pack the user has activated, opened read-only. */
class ActivePack(val packUid: String, val db: Db)

/** A chunk, identified the only way that is unambiguous across packs. */
data class ChunkRef(val packUid: String, val chunkId: Long)

/**
 * Chunks removed from the candidate set because an active pack corrects them.
 *
 * Membership is checked by every stage that can surface a chunk — search, quotation,
 * rolling, citation, and generation — because a correction removes what it corrects
 * *everywhere*, not merely from search results.
 */
class SupersededSet(private val chunks: Set<ChunkRef>) {

    operator fun contains(ref: ChunkRef): Boolean = ref in chunks

    fun contains(packUid: String, chunkId: Long): Boolean = ChunkRef(packUid, chunkId) in chunks

    val size: Int get() = chunks.size

    fun asSet(): Set<ChunkRef> = chunks

    companion object {
        val EMPTY = SupersededSet(emptySet())
    }
}

/**
 * Computes what the active set supersedes.
 *
 * Deliberately a **filter applied before scoring**, not a ranking preference. Priority
 * breaks genuine ties and cannot deliver *"our errata wins"*, because a corrected rule and
 * its original almost never tie — they score differently, and the superseded text can win
 * outright while priority is never consulted.
 *
 * Computed once per change to the active set rather than per query: it depends only on
 * which packs are active, and a per-query recomputation would read every pack's
 * supersession and derivation tables on every keystroke.
 */
object Supersession {

    fun compute(packs: List<ActivePack>): SupersededSet {
        if (packs.isEmpty()) return SupersededSet.EMPTY

        // A source is only a target if the pack containing it is itself active. An errata
        // pack whose target is not installed is valid and simply has nothing to amend.
        val sourcesByUid = mutableMapOf<String, MutableList<Pair<ActivePack, Long>>>()
        for (pack in packs) {
            pack.db.map("SELECT source_id, source_uid FROM sources") { it.long(0) to it.string(1) }
                .forEach { (sourceId, uid) ->
                    sourcesByUid.getOrPut(uid) { mutableListOf() } += pack to sourceId
                }
        }

        val targets = mutableSetOf<Pair<String, String>>()
        for (pack in packs) {
            pack.db.map(
                "SELECT target_source_uid, target_stable_key FROM supersessions",
            ) { it.string(0) to it.string(1) }
                .filter { (uid, _) -> uid in sourcesByUid }
                .forEach { targets += it }
        }
        if (targets.isEmpty()) return SupersededSet.EMPTY

        val superseded = mutableSetOf<ChunkRef>()
        for ((uid, sources) in sourcesByUid) {
            for ((pack, sourceId) in sources) {
                pack.db.map(
                    "SELECT chunk_id, stable_key FROM chunks " +
                        "WHERE source_id = $sourceId AND stable_key IS NOT NULL",
                ) { it.long(0) to it.string(1) }
                    .filter { (_, key) -> (uid to key) in targets }
                    .forEach { (chunkId, _) -> superseded += ChunkRef(pack.packUid, chunkId) }
            }
        }

        // The cascade follows chunk_derivation. A derived chunk's entire claim to authority
        // is the source chunks it cites, so a summary of a rule that has since been
        // corrected is stale prose carrying citations to text the app has agreed not to
        // show -- and unlike a superseded quote it is still retrievable and still renders.
        //
        // Deactivated when ANY cited chunk is superseded, not only when all are: a summary
        // of three rules, one of which was corrected, is wrong in exactly the place someone
        // would rely on it. Losing a summary is cheap; keeping a stale one costs the
        // guarantee that a correction actually corrects.
        for (pack in packs) {
            val victims = pack.db.map(
                "SELECT derived_chunk_id, source_chunk_id FROM chunk_derivation",
            ) { it.long(0) to it.long(1) }
                .filter { (_, cited) -> ChunkRef(pack.packUid, cited) in superseded }
                .map { (derived, _) -> ChunkRef(pack.packUid, derived) }
            superseded += victims
        }

        return SupersededSet(superseded)
    }
}
