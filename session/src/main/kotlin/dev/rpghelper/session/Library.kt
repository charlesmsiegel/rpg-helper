package dev.rpghelper.session

import dev.rpghelper.capabilities.Capabilities
import dev.rpghelper.capabilities.RollableTable
import dev.rpghelper.model.BundledEmbedders
import dev.rpghelper.model.HashingEmbedder
import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.pack.Db
import dev.rpghelper.pack.FileDigest
import dev.rpghelper.pack.Packs
import dev.rpghelper.pack.Sqlite
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
     * Each pack's declared `ruleset_id`, or null where it declares none.
     *
     * Kept here because the alternative is re-reading `pack_meta` per document evaluation,
     * on connections this object already owns. A pack with no ruleset ships no constraints
     * — the validator refuses that combination — so a null entry is a pack that simply has
     * no rules to contribute, not one whose rules were lost.
     */
    val rulesets: Map<String, String?>,
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
    /** Where [open] put its copies, removed on close. Null when nothing was staged. */
    private val staging: Path? = null,
) : AutoCloseable {

    /** Which chunks a roll control is offered for, as `:capabilities` decided it. */
    val rollableChunks: Set<ChunkRef> =
        rollables.flatMap { (uid, tables) -> tables.map { ChunkRef(uid, it.chunkId) } }.toSet()

    override fun close() {
        packs.forEach { it.db.close() }
        leases.forEach { it.close() }
        staging?.let { dir ->
            runCatching {
                Files.list(dir).use { entries -> entries.forEach { Files.deleteIfExists(it) } }
                Files.deleteIfExists(dir)
            }
        }
    }

    /**
     * How much checking a pack still needs at open.
     *
     * Not a performance switch — a statement about **where the file came from**. A path a
     * user typed has been checked by nobody, so it gets the whole gate. A file in the app's
     * own directory, opened under a lease, was gated at install, is re-hashed by the
     * background pass, and had the open-time check run by `borrow` moments ago; putting the
     * full gate in front of every question means scanning every chunk and decoding every
     * vector of a large rulebook before the app answers anything.
     */
    private enum class Gate { FULL, ALREADY_INSTALLED }

    companion object {

        /**
         * The last active set's resolved supersession, keyed by its content fingerprint.
         *
         * One entry, because the app has one active set at a time and a second entry would
         * only ever be the previous one. Synchronized because `openActive` may be called
         * from any thread; the computation itself is idempotent, so a rare double compute
         * on a race costs one scan and never a wrong answer.
         */
        private val supersessionCache = object {
            private var key: List<CachedPack>? = null
            private var value: dev.rpghelper.retrieval.SupersededSet? = null

            @Synchronized
            fun get(
                fingerprint: List<CachedPack>,
                compute: () -> dev.rpghelper.retrieval.SupersededSet,
            ): dev.rpghelper.retrieval.SupersededSet {
                val cached = value
                if (cached != null && key == fingerprint) return cached
                val computed = compute()
                key = fingerprint
                value = computed
                return computed
            }
        }

        /**
         * Opens and validates [paths], or throws with the first pack's violations.
         *
         * Ordered by the caller: `installed_packs.priority` is the app's, and on the
         * command line the argument order is the only statement of it there is.
         *
         * **Each pack is copied before it is looked at**, and every subsequent step — the
         * gate, the metadata, the digest, and the queries that answer questions — reads the
         * copy. These paths are wherever the user typed, writable by anything on the
         * machine, and validating a path and then reading that path checks one file and
         * quotes another that is only probably the same. A rename between the two is enough
         * to make retrieval quote bytes that never passed the gate; an in-place rewrite is
         * enough to make the runtime connection and the cache fingerprint disagree about
         * what the book says. This is the same rule `PackLibrary.install` already keeps —
         * every decision is derived from the staged copy, never from the source — and the
         * cost is one copy per invocation, paid by a command line rather than by the app.
         *
         * [openActive] does not stage: its files live in the app's own directory, under a
         * lease that prevents removal, with a digest recorded at install time.
         */
        fun open(paths: List<Path>): Library {
            val staging = Files.createTempDirectory("rpghelper-packs")
            return try {
                val staged = paths.mapIndexed { index, path ->
                    Files.copy(path, staging.resolve("$index.rpgpack"))
                }
                openLeased(staged.map { it to null }, emptyMap(), Gate.FULL, staging)
            } catch (e: Throwable) {
                runCatching {
                    Files.list(staging).use { it.forEach { entry -> Files.deleteIfExists(entry) } }
                    Files.deleteIfExists(staging)
                }
                throw e
            }
        }

        /**
         * SHA-256 of a pack file, for callers that have no install row to read one from.
         *
         * The *same* routine the library hashes with. This was a second copy, and the two
         * agreeing is what makes the answer cache's key mean anything -- a cache keyed on
         * `(pack_uid, file_sha256)` has no other invalidation story.
         */
        private fun digestOf(path: Path): String = FileDigest.of(path)

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
            // **The full gate does not run here.** `install` ran it over these exact
            // bytes, `verify` re-hashes them in the background, and `borrow` just ran the
            // open-time check on every one of them. Running it again means scanning every
            // chunk's text and decoding every vector in a large rulebook -- before each
            // question a user asks. That is the bargain `01-app-state-spec.md` strikes,
            // and re-validating here was not caution, it was the direct-file path's rule
            // applied to a case it was not written for: these files came from the app's own
            // private directory under a lease, not from wherever somebody typed.
            return openLeased(leased, digests, gate = Gate.ALREADY_INSTALLED)
        }

        /**
         * Opens each source **once** and does everything through that one handle.
         *
         * The gate, the metadata read, and every query that later answers a question are
         * the same connection to the same inode. Opening the path again per step made each
         * step a fresh chance to read a different file, and the one that mattered was the
         * last: retrieval could quote a pack that the gate never saw.
         */
        private fun openLeased(
            sources: List<Pair<Path, AutoCloseable?>>,
            digests: Map<String, String> = emptyMap(),
            gate: Gate = Gate.FULL,
            staging: Path? = null,
        ): Library {
            val opened = mutableListOf<ActivePack>()
            val fingerprint = mutableListOf<CachedPack>()
            val priority = mutableMapOf<String, Int>()
            val contracts = mutableMapOf<String, String>()

            // **Everything after the leases are held is inside this.** Only the two
            // anticipated validation branches used to clean up, so anything else that threw
            // -- `readMeta` on a damaged pack, a capability query, the supersession scan --
            // left every open database and every lease behind. The caller's `use` block has
            // nothing to close when the constructor never returns, so questions leaked
            // SQLite handles and an uninstall waited forever on reader counts that could
            // never fall to zero. The set of things that can throw here is not a set anyone
            // can enumerate, which is the argument for wrapping rather than for listing.
            return try {
                assemble(sources, digests, gate, staging, opened, fingerprint, priority, contracts)
            } catch (failure: Throwable) {
                opened.forEach { runCatching { it.db.close() } }
                sources.forEach { runCatching { it.second?.close() } }
                staging?.let { dir ->
                    runCatching {
                        Files.list(dir).use { e -> e.forEach { Files.deleteIfExists(it) } }
                        Files.deleteIfExists(dir)
                    }
                }
                throw failure
            }
        }

        private fun assemble(
            sources: List<Pair<Path, AutoCloseable?>>,
            digests: Map<String, String>,
            gate: Gate,
            staging: Path?,
            opened: MutableList<ActivePack>,
            fingerprint: MutableList<CachedPack>,
            priority: MutableMap<String, Int>,
            contracts: MutableMap<String, String>,
        ): Library {
            val rulesets = mutableMapOf<String, String?>()

            for ((index, source) in sources.withIndex()) {
                val (path, _) = source
                val db = Sqlite.openReadOnly(path)
                // Closes only the database this loop just opened -- the wrapper above owns
                // everything else, including the leases.
                fun bail(message: String): Nothing {
                    db.close()
                    error(message)
                }

                if (gate == Gate.FULL) {
                    val report = Packs.validate(db, BUNDLED.bundled)
                    if (!report.isValid) bail("$path would not activate:\n$report")
                }

                val meta = Packs.readMeta(db)
                // Two files claiming one uid cannot both be active. Everything downstream
                // identifies a chunk by `(pack_uid, chunk_id)` -- retrieval, routing, and
                // the citation resolver alike -- so two editions of one book merge on
                // equal chunk ids and a card can render one version's text beneath the
                // other version's citation. The library enforces this with a uid
                // uniqueness rule at install; on a command line the check has to be here.
                if (meta.packUid in priority) {
                    bail(
                        "two packs claim '${meta.packUid}'; they cannot be active together, " +
                            "because a chunk is identified by (pack_uid, chunk_id) everywhere " +
                            "downstream and the two would merge",
                    )
                }
                opened += ActivePack(meta.packUid, db)
                fingerprint += CachedPack(
                    meta.packUid,
                    digests[meta.packUid]?.takeIf { it.isNotEmpty() } ?: digestOf(path),
                )
                priority[meta.packUid] = index
                contracts[meta.packUid] = meta.embedderId
                rulesets[meta.packUid] = meta.rulesetId
            }

            // **Resolved once per active set, not once per question.** `Supersession` is
            // documented as work done when the active set changes -- it scans every source,
            // every supersession row, every stable-keyed chunk and every derivation row --
            // and that was true right up until the app started opening a fresh `Library`
            // for each question, at which point a full-pack scan ran before every single
            // retrieval. The fingerprint is `(pack_uid, file_sha256)` in priority order, so
            // it changes exactly when activation or the bytes change, which is exactly when
            // the answer would differ.
            val superseded = supersessionCache.get(fingerprint.toList()) { Supersession.compute(opened) }
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
                rulesets = rulesets,
                fingerprint = fingerprint,
                leases = sources.mapNotNull { it.second },
                staging = staging,
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
