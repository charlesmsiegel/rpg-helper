package dev.rpghelper.retrieval

import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.pack.Db
import dev.rpghelper.pack.map

/**
 * A pack the user has activated, opened read-only.
 *
 * Also owns **how supersession reaches SQL** for this pack, which is not a detail the
 * query builders should each solve. See [exclusion].
 */
class ActivePack(val packUid: String, val db: Db) {

    /** The withdrawn ids currently materialized in `temp.withdrawn`, or null for none. */
    private var installed: Set<Long>? = null

    /**
     * A `WHERE` fragment excluding this pack's withdrawn chunks, or `""` when none are.
     *
     * The withdrawn ids live in a **temp table**, not in the text of the query. Once a user
     * accepts a broad replacement the set is legitimately hundreds of thousands of chunks,
     * and pasting them into every `MATCH` — plus once more per term inside
     * `InformationGate.measure` — spends a multi-megabyte SQL parse on each question, which
     * is retrieval failing at exactly the moment a large errata pack made it matter. The
     * table is built when the set changes, which is when the active set changes, so an
     * ordinary question adds one indexed subquery and no string building at all.
     *
     * Kept here rather than in the query builders because it is per-pack state: both
     * callers were already narrowing the global set to this pack's ids on every call.
     */
    fun exclusion(superseded: SupersededSet, column: String = "rowid"): String {
        val ids = superseded.chunksIn(packUid)
        if (ids.isEmpty()) return ""
        // Checked against the *connection*, not only against the field. `temp` is
        // per-connection, so a second ActivePack over one Db -- or a connection reopened
        // beneath this object -- would leave this believing it had materialized a table
        // that is not there, and the subquery would then match nothing and withdraw
        // nothing. A filter that fails open returns corrected passages as answers with
        // nothing on screen saying so, which is the one direction this must not fail in.
        if (ids != installed || !materialized()) materialize(ids)
        return " AND $column NOT IN (SELECT chunk_id FROM temp.withdrawn)"
    }

    private fun materialized(): Boolean =
        db.map("SELECT count(*) FROM sqlite_temp_master WHERE name = 'withdrawn'") {
            it.long(0)
        }.single() == 1L

    private fun materialize(ids: Set<Long>) {
        db.execute("DROP TABLE IF EXISTS temp.withdrawn")
        db.execute("CREATE TEMP TABLE withdrawn (chunk_id INTEGER PRIMARY KEY)")
        // Batched so no single statement is itself the multi-megabyte string this exists
        // to avoid. The ids are longs read out of the pack's own supersession resolution,
        // never text.
        ids.chunked(500).forEach { batch ->
            db.execute(
                "INSERT INTO temp.withdrawn (chunk_id) VALUES " +
                    batch.joinToString(",") { "($it)" },
            )
        }
        installed = ids
    }
}

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

    /** The withdrawn chunk ids belonging to one pack. */
    fun chunksIn(packUid: String): Set<Long> =
        chunks.mapNotNullTo(mutableSetOf()) { if (it.packUid == packUid) it.chunkId else null }

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
        //
        // Run to a fixpoint, not once: a derived chunk can itself be cited by another
        // derived chunk, and a single pass would deactivate the summary while leaving the
        // summary-of-summaries standing on it. Bounded by the row count, since each round
        // that changes nothing ends the loop and each round that changes something adds at
        // least one chunk to a set that cannot exceed the pack's chunks.
        for (pack in packs) {
            val citations = pack.db.map(
                "SELECT derived_chunk_id, source_chunk_id FROM chunk_derivation",
            ) { it.long(0) to it.long(1) }

            while (true) {
                val victims = citations
                    .filter { (derived, cited) ->
                        ChunkRef(pack.packUid, cited) in superseded &&
                            ChunkRef(pack.packUid, derived) !in superseded
                    }
                    .map { (derived, _) -> ChunkRef(pack.packUid, derived) }
                if (victims.isEmpty()) break
                superseded += victims
            }
        }

        return SupersededSet(superseded)
    }
}
