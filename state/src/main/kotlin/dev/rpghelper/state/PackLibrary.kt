package dev.rpghelper.state

import dev.rpghelper.pack.EmbedderContract
import dev.rpghelper.pack.PackMeta
import dev.rpghelper.pack.Packs
import dev.rpghelper.pack.ValidationReport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/** A pack the app has installed. */
data class InstalledPack(
    val installId: Long,
    val packUid: String,
    val packVersion: String,
    val title: String,
    val rulesetId: String?,
    val embedderId: String,
    val byteSize: Long,
    val fileSha256: String?,
    val active: Boolean,
    val priority: Int,
)

/** Why an install did not happen. */
sealed interface InstallResult {
    data class Installed(val pack: InstalledPack) : InstallResult
    data class Rejected(val report: ValidationReport) : InstallResult
    data class TooLarge(val limit: String) : InstallResult

    /**
     * The uid is already installed and the caller did not agree to replace *this* pack.
     *
     * Carries the pack itself so the caller can name it in the prompt, and so the
     * agreement it collects can be passed back as [InstalledPack.packUid].
     */
    data class NeedsConfirmation(val existing: InstalledPack) : InstallResult
}

/**
 * Installs, activates, and orders packs.
 *
 * Installation is journalled so that the file and the database row land together. A
 * rename is atomic for the file alone; a process killed between the move and the row
 * update would otherwise leave a row describing bytes that are not there, or bytes with
 * no row.
 */
class PackLibrary(
    private val db: StateDb,
    private val root: Path,
    private val supportedEmbedders: Set<EmbedderContract>,
    private val limits: PackLimits = PackLimits(),
    private val clock: () -> String = { java.time.Instant.now().toString() },
) {

    private val packsDir: Path = root.resolve("packs")

    /** Open lease counts, and the files whose deletion is waiting on them. */
    private val leaseLock = ReentrantLock()
    private val readers = mutableMapOf<Long, Int>()
    private val pendingDeletion = mutableSetOf<Long>()

    init {
        Files.createDirectories(packsDir)
    }

    /**
     * Ceilings that bound what a malformed or hostile pack can do to responsiveness.
     *
     * A file-size limit alone is not one. A pack well under 2 GiB can hold millions of
     * chunks or aliases, or one text value of a gigabyte, and the validator scans and
     * materializes all of it *before* any check could reject it — so the refusal arrives
     * as an out-of-memory kill rather than as a refusal.
     */
    data class PackLimits(
        val maxFileBytes: Long = 2L * 1024 * 1024 * 1024,
        val maxChunks: Long = 500_000,
        val maxVectors: Long = 5_000_000,
        val maxEntities: Long = 500_000,
        val maxTableRows: Long = 1_000_000,
        /** One chunk's text. A book's longest chapter is far below this. */
        val maxTextBytes: Long = 4L * 1024 * 1024,
        /** One embedding. A 4096-dimension binary16 vector is 8 KiB. */
        val maxBlobBytes: Long = 64 * 1024,
    )

    /**
     * Validates [source] and installs it.
     *
     * A replacement is staged **beside** the pack it replaces, never over it: the new row
     * gets its own `install_id` and the previous `ready` row is not touched until both the
     * file and the metadata can be swapped in one transaction. A crash at any point before
     * that leaves the old pack installed, active, and intact — which is what matters when
     * the thing being replaced is the book someone is running a game from tonight.
     *
     * Every decision — the size limit, the uid, what is being replaced, the metadata
     * written to the row — is derived from the **staged copy**, never from [source].
     * The source is typically a `content://` URI backed by another app's file, which can
     * change between two reads of it; deciding replacement from one read and validating
     * another would let a pack inherit an activation and a priority the user granted to a
     * different pack entirely.
     *
     * @param confirmReplacing the `pack_uid` the caller has agreed to replace. Required,
     * and must match, when a pack with the staged file's uid is already installed. A
     * boolean would not survive the source changing underneath: it says *yes* without
     * saying yes to what.
     */
    fun install(source: Path, confirmReplacing: String? = null): InstallResult {
        // 1. Journal the intent, with its own id. Uniqueness of pack_uid applies only
        //    among ready rows, which is what makes staging a replacement possible at all.
        val installId = db.transaction {
            db.execute(
                "INSERT INTO installed_packs (pack_uid, pack_version, title, embedder_id, " +
                    "byte_size, state, installed_at, active, priority) " +
                    "VALUES (?, '', '', '', 0, ?, ?, 0, ?)",
                "", StateSchema.STATE_INSTALLING, clock(), nextPriority(),
            )
            db.lastInsertId()
        }

        val staged = packsDir.resolve("$installId.rpgpack")
        val existing: InstalledPack?
        val meta: PackMeta
        val size: Long
        val digest: String
        try {
            if (!copyBounded(source, staged, limits.maxFileBytes)) {
                abandon(installId, staged)
                return InstallResult.TooLarge("${limits.maxFileBytes} bytes")
            }
            size = Files.size(staged)

            // Counts and value sizes before the full passes, using SQL aggregates so
            // nothing large is loaded to discover it is too large.
            preflight(staged)?.let {
                abandon(installId, staged)
                return InstallResult.TooLarge(it)
            }

            val report = Packs.validateFile(staged, supportedEmbedders)
            if (!report.isValid) {
                abandon(installId, staged)
                return InstallResult.Rejected(report)
            }

            meta = Packs.readMeta(staged)
            existing = findReady(meta.packUid)
            if (existing != null && existing.packUid != confirmReplacing) {
                abandon(installId, staged)
                return InstallResult.NeedsConfirmation(existing)
            }
            digest = sha256(staged)

            // 2. Swap: the previous ready row and the new one change together.
            db.transaction {
                // Re-read inside the transaction. `existing` was read before the file was
                // hashed, and the user can uninstall or deactivate the old pack in that
                // window: acting on the stale snapshot would reinstate something they
                // explicitly removed, or hand the replacement an activation they had just
                // turned off.
                val current = findReady(meta.packUid)
                if (current?.installId != existing?.installId) {
                    abandon(installId, staged)
                    error(
                        "the pack being replaced changed while this one was being verified; " +
                            "nothing was installed",
                    )
                }
                db.execute(
                    "DELETE FROM installed_packs WHERE install_id = ?",
                    current?.installId ?: -1L,
                )
                db.execute(
                    "UPDATE installed_packs SET pack_uid = ?, pack_version = ?, title = ?, " +
                        "ruleset_id = ?, embedder_id = ?, byte_size = ?, file_sha256 = ?, " +
                        "state = ?, active = ?, priority = ? WHERE install_id = ?",
                    meta.packUid, meta.packVersion, meta.title, meta.rulesetId,
                    meta.embedderId, size, digest, StateSchema.STATE_READY,
                    // A replacement of an inactive pack stays inactive, and a new install
                    // is never active on arrival: a pack can claim any uid it likes, so
                    // inheriting activation would let an imported file become live content
                    // without the user ever deciding to activate it.
                    current?.active ?: false,
                    current?.priority ?: nextPriority(), installId,
                )
            }
        } catch (e: Exception) {
            abandon(installId, staged)
            throw e
        }

        // 3. Past the commit, the replacement *is* the installation: the new row is ready
        //    and the old row is gone. Removing the old file is housekeeping from here, so
        //    a failure — a reader still holding it on a locking platform — must not
        //    unwind a swap the database already records, which would leave neither pack
        //    installed. `reconcile` sweeps files with no row.
        if (existing != null) deleteWhenUnread(existing.installId)

        return InstallResult.Installed(requireNotNull(byId(installId)))
    }

    /**
     * Removes every trace of an interrupted install, in both directions.
     *
     * A crash can leave a journal row with no file or a file with no row, so both are
     * swept. Idempotent, and must run before any pack is opened.
     */
    fun reconcile() {
        val stale = db.query(
            "SELECT install_id FROM installed_packs WHERE state = ?",
            StateSchema.STATE_INSTALLING,
        ) { it.long(0) }
        for (id in stale) {
            Files.deleteIfExists(fileOf(id))
            db.execute("DELETE FROM installed_packs WHERE install_id = ?", id)
        }

        val known = db.query("SELECT install_id FROM installed_packs") { it.long(0) }.toSet()
        val held = leaseLock.withLock { readers.keys.toSet() }
        Files.list(packsDir).use { entries ->
            entries.filter { it.fileName.toString().endsWith(".rpgpack") }
                .forEach { path ->
                    val id = path.fileName.toString().removeSuffix(".rpgpack").toLongOrNull()
                    // Reconcile is specified to run before any pack is opened, so `held`
                    // is normally empty. Honouring it anyway costs nothing and keeps the
                    // one rule -- a file with a reader is not unlinked -- true from every
                    // direction rather than only from the one the caller came in through.
                    if (id !in known && id !in held) Files.deleteIfExists(path)
                }
        }
    }

    fun installed(): List<InstalledPack> = db.query(
        "SELECT install_id, pack_uid, pack_version, title, ruleset_id, embedder_id, " +
            "byte_size, file_sha256, active, priority FROM installed_packs " +
            "WHERE state = ? ORDER BY priority",
        StateSchema.STATE_READY,
    ) { it.toInstalledPack() }

    fun active(): List<InstalledPack> = installed().filter { it.active }

    fun setActive(installId: Long, active: Boolean) {
        db.execute(
            "UPDATE installed_packs SET active = ? WHERE install_id = ? AND state = ?",
            active, installId, StateSchema.STATE_READY,
        )
    }

    /**
     * Rewrites priority so the given order is total, in one transaction.
     *
     * Priorities are unique ordinals rather than scores: ties in a tie-breaker are not
     * useful. Rewriting via a temporary negative range avoids tripping the unique index
     * partway through, since SQLite checks it per statement.
     */
    fun reorder(installIdsInPriorityOrder: List<Long>) {
        db.transaction {
            installIdsInPriorityOrder.forEachIndexed { index, id ->
                db.execute(
                    "UPDATE installed_packs SET priority = ? WHERE install_id = ?",
                    -(index + 1), id,
                )
            }
            installIdsInPriorityOrder.forEachIndexed { index, id ->
                db.execute(
                    "UPDATE installed_packs SET priority = ? WHERE install_id = ?", index, id,
                )
            }
        }
    }

    /** Uninstalls a pack. Documents are never touched — only their validation stops. */
    /**
     * Forgets the installation, and deletes its file once nothing is reading it.
     *
     * Unlinking a SQLite file out from under an open connection is not a crash on every
     * platform, which is worse than if it were: on the platforms where it succeeds, the
     * in-flight query keeps reading a file that no longer exists in the directory and
     * returns an answer citing a book the user just removed. So the row goes now — the
     * pack is uninstalled the moment the user says so — and the bytes go when the last
     * reader closes, or at the next [reconcile] if the process dies first.
     */
    fun uninstall(installId: Long) {
        db.execute("DELETE FROM installed_packs WHERE install_id = ?", installId)
        deleteWhenUnread(installId)
    }

    /**
     * Borrows the pack's file for the life of a query.
     *
     * A query captures the active set once, at its start, and completes against that
     * snapshot. The lease is what makes the file half of that snapshot true as well.
     */
    fun borrow(installId: Long): PackLease? = leaseLock.withLock {
        // Refused once the file is condemned, even while it still exists. Deciding on
        // `Files.exists` alone would hand out a lease on a file whose deletion is already
        // committed, which is the query-snapshot guarantee failing in the one direction
        // it is supposed to hold.
        if (installId in pendingDeletion) return@withLock null
        val file = fileOf(installId)
        if (!Files.isRegularFile(file)) return@withLock null
        readers[installId] = (readers[installId] ?: 0) + 1
        PackLease(this, installId, file)
    }

    internal fun release(installId: Long) = leaseLock.withLock {
        val remaining = (readers[installId] ?: 0) - 1
        if (remaining > 0) {
            readers[installId] = remaining
            return@withLock
        }
        readers.remove(installId)
        if (pendingDeletion.remove(installId)) {
            runCatching { Files.deleteIfExists(fileOf(installId)) }
        }
    }

    /**
     * Deletes now if unread, otherwise leaves it to the last reader out.
     *
     * The condemnation and the unlink both happen **under the lock**. Releasing it in
     * between leaves a window where `borrow` sees a file that still exists and hands out a
     * lease on bytes that are about to vanish — a race that costs nothing to close and
     * whose symptom, a query reading a deleted file, is untraceable after the fact.
     */
    private fun deleteWhenUnread(installId: Long) = leaseLock.withLock {
        pendingDeletion += installId
        if ((readers[installId] ?: 0) > 0) return@withLock
        pendingDeletion -= installId
        runCatching { Files.deleteIfExists(fileOf(installId)) }
    }

    fun fileOf(installId: Long): Path = packsDir.resolve("$installId.rpgpack")

    /** Recomputes a pack's digest and deactivates it if the bytes have changed. */
    fun verify(installId: Long): Boolean {
        val row = byId(installId) ?: return false
        val expected = row.fileSha256 ?: return false
        // A file that cannot be read is a file that cannot be verified, and it fails the
        // same way a mismatch does. Letting the read throw past this point leaves the row
        // active for a pack every subsequent open will fail on -- external storage
        // cleanup and a concurrent removal both arrive here, not as a digest mismatch.
        val actual = runCatching { sha256(fileOf(installId)) }.getOrNull()
        if (actual != expected) {
            db.execute(
                "UPDATE installed_packs SET active = 0, verified_at = NULL WHERE install_id = ?",
                installId,
            )
            return false
        }
        db.execute(
            "UPDATE installed_packs SET verified_at = ? WHERE install_id = ?", clock(), installId,
        )
        return true
    }

    // ---------------------------------------------------------------- internals

    private fun abandon(installId: Long, staged: Path) {
        Files.deleteIfExists(staged)
        db.execute("DELETE FROM installed_packs WHERE install_id = ?", installId)
    }

    private fun nextPriority(): Int =
        (db.query("SELECT COALESCE(MAX(priority), -1) FROM installed_packs") { it.int(0) }
            .single()) + 1

    private fun findReady(packUid: String): InstalledPack? = db.query(
        "SELECT install_id, pack_uid, pack_version, title, ruleset_id, embedder_id, " +
            "byte_size, file_sha256, active, priority FROM installed_packs " +
            "WHERE pack_uid = ? AND state = ?",
        packUid, StateSchema.STATE_READY,
    ) { it.toInstalledPack() }.firstOrNull()

    private fun byId(installId: Long): InstalledPack? = db.query(
        "SELECT install_id, pack_uid, pack_version, title, ruleset_id, embedder_id, " +
            "byte_size, file_sha256, active, priority FROM installed_packs WHERE install_id = ?",
        installId,
    ) { it.toInstalledPack() }.firstOrNull()

    private fun StateDb.Row.toInstalledPack() = InstalledPack(
        installId = long(0),
        packUid = string(1),
        packVersion = string(2),
        title = string(3),
        rulesetId = stringOrNull(4),
        embedderId = string(5),
        byteSize = long(6),
        fileSha256 = stringOrNull(7),
        active = boolean(8),
        priority = int(9),
    )

    /**
     * Row counts and the largest single values, or null when everything is in bounds.
     *
     * Read with `count(*)` and `max(length(...))`, which SQLite answers without
     * materializing the values — the whole point, since materializing them is the failure
     * being prevented.
     */
    private fun preflight(path: Path): String? = runCatching {
        java.sql.DriverManager.getConnection("jdbc:sqlite:${path.toAbsolutePath()}").use { c ->
            fun scalar(sql: String): Long = c.createStatement().use { s ->
                s.executeQuery(sql).use { if (it.next()) it.getLong(1) else 0L }
            }
            val checks = listOf(
                Triple("chunks", "SELECT count(*) FROM chunks", limits.maxChunks),
                Triple("vectors", "SELECT count(*) FROM vectors", limits.maxVectors),
                Triple("entities", "SELECT count(*) FROM entities", limits.maxEntities),
                Triple("table rows", "SELECT count(*) FROM table_rows", limits.maxTableRows),
                Triple(
                    "a chunk's text",
                    "SELECT COALESCE(max(length(text)), 0) FROM chunks",
                    limits.maxTextBytes,
                ),
                Triple(
                    "an embedding",
                    "SELECT COALESCE(max(length(embedding)), 0) FROM vectors",
                    limits.maxBlobBytes,
                ),
            )
            for ((what, sql, ceiling) in checks) {
                val actual = scalar(sql)
                if (actual > ceiling) return@use "$what: $actual exceeds the limit of $ceiling"
            }
            null
        }
    }.getOrElse {
        // A file that cannot be queried at all is not *too large*, it is not a pack --
        // and the validator says so precisely, where this could only guess. Falling
        // through keeps "too large" meaning too large.
        null
    }

    /**
     * Copies [source] to [target], stopping if more than [limit] bytes arrive.
     *
     * `Files.size` is a *claim* made by whatever sits behind the path — on Android, another
     * app answering for a `content://` URI — and it is read before the bytes are. Enforcing
     * the ceiling on the bytes that actually arrive means a source understating its size
     * costs one extra buffer rather than the device's free space.
     */
    private fun copyBounded(source: Path, target: Path, limit: Long): Boolean =
        Files.newInputStream(source).use { input ->
            Files.newOutputStream(
                target,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE,
            ).use { output ->
                val buffer = ByteArray(1 shl 16)
                var written = 0L
                var withinLimit = true
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    written += read
                    if (written > limit) {
                        withinLimit = false
                        break
                    }
                    output.write(buffer, 0, read)
                }
                withinLimit
            }
        }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * A borrowed pack file, held for the life of a query.
 *
 * Closing it is what lets a deferred uninstall complete, so it belongs in a `use` block
 * rather than being closed on a happy path — a query that throws must still release.
 */
class PackLease internal constructor(
    private val library: PackLibrary,
    val installId: Long,
    val file: Path,
) : AutoCloseable {
    override fun close() = library.release(installId)
}
