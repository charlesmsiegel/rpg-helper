package dev.rpghelper.session

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
import dev.rpghelper.state.CachedPack
import dev.rpghelper.state.PackLibrary
import dev.rpghelper.state.StateDb
import java.nio.file.Files
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
    /**
     * The active set as a cache key sees it: `(pack_uid, file_sha256)` in priority order.
     *
     * By **content**, not by declared version. Installing a pack whose uid already exists
     * replaces it, and nothing requires a corrected rebuild to declare a new
     * `pack_version` — so a cache keyed on the version would keep serving answers built
     * from the bytes that were corrected.
     */
    val fingerprint: List<CachedPack>,
    /**
     * Held for the life of this set, released on close.
     *
     * A query captures the active set once, at its start, and completes against that
     * snapshot; the lease is what makes the *file* half of that snapshot true too. Without
     * it an uninstall mid-query unlinks the file, and on the platforms where that succeeds
     * the in-flight read keeps going against bytes no longer in the directory and answers
     * citing a book the user just removed.
     */
    private val leases: List<AutoCloseable> = emptyList(),
) : AutoCloseable {

    /** Which chunks a roll control is offered for, as `:capabilities` decided it. */
    val rollableChunks: Set<ChunkRef> =
        rollables.flatMap { (uid, tables) -> tables.map { ChunkRef(uid, it.chunkId) } }.toSet()

    override fun close() {
        packs.forEach { it.db.close() }
        leases.forEach { it.close() }
    }

    companion object {

        /**
         * Opens and validates [paths], or throws with the first pack's violations.
         *
         * Ordered by the caller: `installed_packs.priority` is the app's, and on the
         * command line the argument order is the only statement of it there is.
         */
        fun open(paths: List<Path>): Library = openLeased(paths.map { it to null })

        /** SHA-256 of a pack file, for callers that have no install row to read one from. */
        private fun digestOf(path: Path): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            Files.newInputStream(path).use { input ->
                val buffer = ByteArray(1 shl 16)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }

        /**
         * The active set of an installed library, each pack held by a lease.
         *
         * This is the app's own path, and the reason it is worth having on a command line:
         * the priority order is the one `installed_packs` records rather than one an
         * argument list implies, and the deactivated packs are absent because the user
         * said so rather than because they were not typed.
         */
        fun openActive(library: PackLibrary): Library {
            val active = library.active()
            // The digests the library already recorded at install, rather than a fresh
            // hash of every pack on every query: the rows are the app's own and the
            // background verification pass is what keeps them true.
            val digests = active.associate { it.packUid to (it.fileSha256 ?: "") }
            val leased: List<Pair<Path, AutoCloseable?>> = active.mapNotNull { pack ->
                // A pack whose file has been condemned refuses its lease. It is dropped
                // from the set rather than read anyway, which is the same answer the app
                // gives and one query short of the answer it would otherwise give.
                library.borrow(pack.installId)?.let { lease -> lease.file to lease }
            }
            return openLeased(leased, digests)
        }

        private fun openLeased(
            sources: List<Pair<Path, AutoCloseable?>>,
            digests: Map<String, String> = emptyMap(),
        ): Library {
            val opened = mutableListOf<ActivePack>()
            val fingerprint = mutableListOf<CachedPack>()
            val priority = mutableMapOf<String, Int>()
            val contracts = mutableMapOf<String, String>()

            for ((index, source) in sources.withIndex()) {
                val (path, _) = source
                val report = Packs.validateFile(path, BUNDLED.bundled)
                if (!report.isValid) {
                    opened.forEach { it.db.close() }
                    sources.forEach { it.second?.close() }
                    error("$path would not activate:\n$report")
                }
                val meta = Packs.readMeta(path)
                // Two files claiming one uid cannot both be active. Everything downstream
                // identifies a chunk by `(pack_uid, chunk_id)` -- retrieval, routing, and
                // the citation resolver alike -- so two editions of one book merge on
                // equal chunk ids and a card can render one version's text beneath the
                // other version's citation. The library enforces this with a uid
                // uniqueness rule at install; on a command line the check has to be here.
                if (meta.packUid in priority) {
                    opened.forEach { it.db.close() }
                    sources.forEach { it.second?.close() }
                    error(
                        "two packs claim '${meta.packUid}'; they cannot be active together, " +
                            "because a chunk is identified by (pack_uid, chunk_id) everywhere " +
                            "downstream and the two would merge",
                    )
                }
                opened += ActivePack(meta.packUid, JdbcDb.openReadOnly(path))
                fingerprint += CachedPack(
                    meta.packUid,
                    digests[meta.packUid]?.takeIf { it.isNotEmpty() } ?: digestOf(path),
                )
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
                fingerprint = fingerprint,
                leases = sources.mapNotNull { it.second },
            )
        }
    }
}

/**
 * The app's own storage, opened where the user keeps it.
 *
 * `state.db` and `packs/` beside each other under one root, exactly as on the device.
 * Held open for the life of a command rather than a query: `StateDb` serializes its own
 * connection, and a tool that opened it per call would be a tool whose two commands could
 * see different databases.
 */
class Store(root: Path) : AutoCloseable {

    private val directory: Path = Files.createDirectories(root)

    val db: StateDb = StateDb.open(directory.resolve("state.db"))

    val library: PackLibrary = PackLibrary(db, directory, BUNDLED.bundled)

    init {
        // Journalled installs that died mid-flight are resolved on open, not left for
        // whoever notices. A row describing bytes that are not there is a pack the user
        // sees listed and cannot use.
        library.reconcile()
    }

    override fun close() = db.close()
}
