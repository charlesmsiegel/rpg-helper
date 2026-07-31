package dev.rpghelper.routing

import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.pack.map
import dev.rpghelper.retrieval.ActivePack

/**
 * Citations, read from the packs the answer came out of.
 *
 * Every card the app renders carries one, and until this existed the resolver was an
 * interface with no implementation but a test double — which is a comfortable place for a
 * design to sit, because the awkward questions do not come up. They are all here:
 *
 * - **A chunk with no source has no citation.** `('glossary','derived')` prose is stored
 *   with `source_id` NULL, because a derived chunk's citation must come from the chunks it
 *   was built from and never from itself. Returning a citation here would let one drift,
 *   which is the failure `chunk_derivation` exists to prevent, so this returns null and
 *   [Router] resolves derived cards through their cited chunks instead.
 * - **A ref naming a pack that is not active resolves to nothing.** The active set can
 *   change between a query and the render of its answer; a citation that survived that
 *   would name a book the user has since deactivated.
 *
 * Cached because a pack is immutable while installed and a footer can name the same chunk
 * several times, but the cache is per-instance: it is built for one active set, and a new
 * active set gets a new resolver rather than an invalidation protocol nobody would test.
 */
class PackCitations(packs: List<ActivePack>) : CitationResolver {

    private val byUid = packs.associateBy { it.packUid }
    private val cache = mutableMapOf<ChunkRef, Citation?>()

    override fun resolve(ref: ChunkRef): Citation? = cache.getOrPut(ref) { read(ref) }

    private fun read(ref: ChunkRef): Citation? {
        val pack = byUid[ref.packUid] ?: return null
        // chunk_id is an integer this pack supplied and retrieval read back out of it, so
        // it is interpolated rather than bound -- `Db` takes no bind parameters by design,
        // and nothing here comes from the user.
        return pack.db.map(
            "SELECT s.title, s.edition, s.locator_scheme, c.heading_path, " +
                "c.page_label_start, c.page_label_end " +
                "FROM chunks c JOIN sources s ON s.source_id = c.source_id " +
                "WHERE c.chunk_id = ${ref.chunkId}",
        ) {
            Citation(
                packUid = ref.packUid,
                chunkId = ref.chunkId,
                sourceTitle = it.string(0),
                edition = it.stringOrNull(1),
                locatorScheme = it.string(2),
                headingPath = it.stringOrNull(3),
                pageLabelStart = it.stringOrNull(4),
                pageLabelEnd = it.stringOrNull(5),
            )
        }.singleOrNull()
    }
}

/**
 * A derived chunk's `chunk_derivation` rows, in a stable order.
 *
 * Ordered by `derivation_id` rather than left to SQLite's iteration, because the order
 * decides the footer's order on screen and a citation list that reshuffles between two
 * renders of the same answer reads as two different answers.
 */
class PackDerivations(packs: List<ActivePack>) : DerivationResolver {

    private val byUid = packs.associateBy { it.packUid }

    override fun derivationsOf(ref: ChunkRef): List<Derivation> {
        val pack = byUid[ref.packUid] ?: return emptyList()
        return pack.db.map(
            "SELECT source_chunk_id, claim_span_start, claim_span_end FROM chunk_derivation " +
                "WHERE derived_chunk_id = ${ref.chunkId} ORDER BY derivation_id",
        ) {
            Derivation(
                cited = ChunkRef(ref.packUid, it.long(0)),
                claimStart = it.longOrNull(1)?.toInt(),
                claimEnd = it.longOrNull(2)?.toInt(),
            )
        }
    }
}

/**
 * Which chunks a roll control is offered for.
 *
 * Deliberately a **set handed in**, not a query: the answer is `:capabilities`' to give,
 * and it is the one that knows which manifests were dropped and why. Reading the
 * `capabilities` table here instead would offer a control for a capability that module
 * refused — a roller over rows whose validation failed, or over a table an active
 * correction withdrew.
 */
class LoadedRollables(private val chunks: Set<ChunkRef>) : RollableChunks {
    override fun contains(ref: ChunkRef): Boolean = ref in chunks
}
