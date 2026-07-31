package dev.rpghelper.state

import dev.rpghelper.pack.EmbedderContract
import dev.rpghelper.pack.Packs
import dev.rpghelper.pack.ValidationReport
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

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

    /** The uid is already installed, and [confirm] was not given. */
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

    init {
        Files.createDirectories(packsDir)
    }

    /** Ceilings that bound what a malformed or hostile pack can do to responsiveness. */
    data class PackLimits(val maxFileBytes: Long = 2L * 1024 * 1024 * 1024)

    /**
     * Validates [source] and installs it.
     *
     * A replacement is staged **beside** the pack it replaces, never over it: the new row
     * gets its own `install_id` and the previous `ready` row is not touched until both the
     * file and the metadata can be swapped in one transaction. A crash at any point before
     * that leaves the old pack installed, active, and intact — which is what matters when
     * the thing being replaced is the book someone is running a game from tonight.
     *
     * @param confirm required when a pack with the same `pack_uid` is already installed.
     */
    fun install(source: Path, confirm: Boolean = false): InstallResult {
        val size = Files.size(source)
        if (size > limits.maxFileBytes) {
            return InstallResult.TooLarge("${limits.maxFileBytes} bytes")
        }

        val existing = findReady(uidOf(source) ?: return InstallResult.Rejected(unreadable()))
        if (existing != null && !confirm) return InstallResult.NeedsConfirmation(existing)

        // 1. Journal the intent, with its own id. Uniqueness of pack_uid applies only
        //    among ready rows, which is what makes staging a replacement possible at all.
        val installId = db.transaction {
            db.execute(
                "INSERT INTO installed_packs (pack_uid, pack_version, title, embedder_id, " +
                    "byte_size, state, installed_at, active, priority) " +
                    "VALUES (?, '', '', '', ?, ?, ?, 0, ?)",
                "", size, StateSchema.STATE_INSTALLING, clock(), nextPriority(),
            )
            db.lastInsertId()
        }

        val staged = packsDir.resolve("$installId.rpgpack")
        try {
            Files.copy(source, staged, StandardCopyOption.REPLACE_EXISTING)
            val report = Packs.validateFile(staged, supportedEmbedders)
            if (!report.isValid) {
                abandon(installId, staged)
                return InstallResult.Rejected(report)
            }

            val meta = Packs.readMeta(staged)
            val digest = sha256(staged)

            // 2. Swap: the previous ready row and the new one change together.
            db.transaction {
                if (existing != null) {
                    db.execute(
                        "DELETE FROM installed_packs WHERE install_id = ?",
                        existing.installId,
                    )
                }
                db.execute(
                    "UPDATE installed_packs SET pack_uid = ?, pack_version = ?, title = ?, " +
                        "ruleset_id = ?, embedder_id = ?, file_sha256 = ?, state = ?, " +
                        "active = ?, priority = ? WHERE install_id = ?",
                    meta.packUid, meta.packVersion, meta.title, meta.rulesetId,
                    meta.embedderId, digest, StateSchema.STATE_READY,
                    // A replacement of an inactive pack stays inactive, and a new install
                    // is never active on arrival: a pack can claim any uid it likes, so
                    // inheriting activation would let an imported file become live content
                    // without the user ever deciding to activate it.
                    existing?.active ?: false,
                    existing?.priority ?: nextPriority(), installId,
                )
            }

            // 3. The old file now has no row, so reconciliation would remove it anyway.
            if (existing != null) Files.deleteIfExists(fileOf(existing.installId))

            return InstallResult.Installed(requireNotNull(byId(installId)))
        } catch (e: Exception) {
            abandon(installId, staged)
            throw e
        }
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
        Files.list(packsDir).use { entries ->
            entries.filter { it.fileName.toString().endsWith(".rpgpack") }
                .filter { it.fileName.toString().removeSuffix(".rpgpack").toLongOrNull() !in known }
                .forEach { Files.deleteIfExists(it) }
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
    fun uninstall(installId: Long) {
        db.execute("DELETE FROM installed_packs WHERE install_id = ?", installId)
        Files.deleteIfExists(fileOf(installId))
    }

    fun fileOf(installId: Long): Path = packsDir.resolve("$installId.rpgpack")

    /** Recomputes a pack's digest and deactivates it if the bytes have changed. */
    fun verify(installId: Long): Boolean {
        val row = byId(installId) ?: return false
        val expected = row.fileSha256 ?: return false
        val actual = sha256(fileOf(installId))
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

    private fun unreadable() = ValidationReport(
        listOf(
            dev.rpghelper.pack.Violation(
                dev.rpghelper.pack.ViolationCode.MALFORMED_SCHEMA,
                "the file could not be read as a pack",
            ),
        ),
    )

    private fun uidOf(path: Path): String? =
        runCatching { Packs.readMeta(path).packUid }.getOrNull()

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
