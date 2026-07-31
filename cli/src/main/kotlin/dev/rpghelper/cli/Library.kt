package dev.rpghelper.cli

import dev.rpghelper.capabilities.Capabilities
import dev.rpghelper.capabilities.RollableTable
import dev.rpghelper.model.BundledEmbedders
import dev.rpghelper.model.HashingEmbedder
import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.pack.JdbcDb
import dev.rpghelper.pack.Packs
import dev.rpghelper.retrieval.ActivePack
import dev.rpghelper.retrieval.ActiveSet
import dev.rpghelper.retrieval.Gates
import dev.rpghelper.retrieval.Supersession
import java.nio.file.Path

/**
 * What this build of the tool can serve.
 *
 * Exactly one embedder, and it has no weights. That is not a placeholder for a real
 * registry — it is the honest statement of what a JVM tool with no model files can do,
 * and it is the same shape the app's registry has: a pack naming a contract that is not
 * bundled is **refused at activation** rather than retrieved against incorrectly.
 */
val EMBEDDER = HashingEmbedder(dim = 128)

val BUNDLED = BundledEmbedders(listOf(EMBEDDER))

/**
 * The gates this build ships, per contract.
 *
 * A dense threshold is **not transferable between embedders** — a cosine of 0.65 means
 * different things in different spaces — so `Gates` refuses an uncalibrated contract by
 * defaulting to an unreachable number. Shipping the calibrated value here is what a
 * release does; leaving it out would silently turn every query lexical-only, which looks
 * like working retrieval right up until a question needs the other half of the pipeline.
 *
 * 0.65 is the value `RecallTest` measures the stand-in embedder's recall against on the
 * labelled query set. A real contract's is derived the same way.
 */
val GATES = Gates(dense = mapOf(EMBEDDER.contract.id to 0.65))

/**
 * A set of packs opened together, validated, and ready to answer.
 *
 * The gate runs on **every** pack before any of them is read for content. A tool that
 * skipped it would be a tool whose answers came from files the app itself would refuse,
 * which is precisely the disagreement between builder and app that having one validator
 * exists to prevent.
 */
class Library private constructor(
    val packs: List<ActivePack>,
    val active: ActiveSet,
    /** Loadable roll tables, by the pack that supplied them. */
    val rollables: Map<String, List<RollableTable>>,
    val dropped: List<String>,
) : AutoCloseable {

    /** Which chunks a roll control is offered for, as `:capabilities` decided it. */
    val rollableChunks: Set<ChunkRef> =
        rollables.flatMap { (uid, tables) -> tables.map { ChunkRef(uid, it.chunkId) } }.toSet()

    override fun close() = packs.forEach { it.db.close() }

    companion object {

        /**
         * Opens and validates [paths], or throws with the first pack's violations.
         *
         * Ordered by the caller: `installed_packs.priority` is the app's, and on the
         * command line the argument order is the only statement of it there is.
         */
        fun open(paths: List<Path>): Library {
            val opened = mutableListOf<ActivePack>()
            val priority = mutableMapOf<String, Int>()
            val contracts = mutableMapOf<String, String>()

            for ((index, path) in paths.withIndex()) {
                val report = Packs.validateFile(path, BUNDLED.bundled)
                if (!report.isValid) {
                    opened.forEach { it.db.close() }
                    error("$path would not activate:\n$report")
                }
                val meta = Packs.readMeta(path)
                opened += ActivePack(meta.packUid, JdbcDb.openReadOnly(path))
                priority[meta.packUid] = index
                contracts[meta.packUid] = meta.embedderId
            }

            val superseded = Supersession.compute(opened)
            val dropped = mutableListOf<String>()
            val rollables = mutableMapOf<String, List<RollableTable>>()
            for (pack in opened) {
                val withdrawn = superseded.asSet()
                    .filter { it.packUid == pack.packUid }
                    .map { it.chunkId }
                    .toSet()
                val loaded = Capabilities.load(pack.db, withdrawn)
                rollables[pack.packUid] = loaded.tables
                loaded.dropped.forEach {
                    dropped += "${pack.packUid}: capability ${it.capabilityId} dropped -- ${it.reason}"
                }
            }

            return Library(
                packs = opened,
                active = ActiveSet(opened, priority, contracts, superseded),
                rollables = rollables,
                dropped = dropped,
            )
        }
    }
}
