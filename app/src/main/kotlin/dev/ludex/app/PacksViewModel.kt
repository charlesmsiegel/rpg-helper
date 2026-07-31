package dev.ludex.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ludex.session.ShelvedPack
import dev.ludex.session.Shelf
import dev.ludex.state.ActivationResult
import dev.ludex.state.SupersessionImpact
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Packs surface's state.
 *
 * The one thing on this screen that is not a list: activation is a decision, not a toggle.
 * `PackLibrary.setActive` may come back asking for an acknowledgement, and it wants **the
 * impact the user was shown** rather than a boolean — a yes cannot say what it is a yes to.
 * So [pending] holds the measured impact, the dialog renders exactly that, and confirming
 * hands the same object back.
 */
class PacksViewModel(application: Application) : AndroidViewModel(application) {

    private val store = Storage.of(application)

    /** What the surface lists. Empty until the first load lands. */
    var packs by mutableStateOf<List<ShelvedPack>>(emptyList())
        private set

    var busy by mutableStateOf(false)
        private set

    var failure by mutableStateOf<String?>(null)
        private set

    /** Non-null while an activation is waiting on the user to see what it would withdraw. */
    var pending by mutableStateOf<PendingActivation?>(null)
        private set

    /** Non-null while an install is waiting on the user to agree to replace a pack. */
    var replacing by mutableStateOf<PendingReplacement?>(null)
        private set

    /** Packs, models, and app data — the three figures `01-app-state-spec.md` §7 names. */
    var storage by mutableStateOf(StorageUse(0, 0, 0))
        private set

    /**
     * What the three storage figures are, separately, because their remedies differ.
     *
     * Named `StorageUse` rather than `Storage` because [Storage] is now the process's one
     * store. Two things called the same thing in one file is how a screen ends up measuring
     * a database handle.
     */
    data class StorageUse(val packs: Long, val models: Long, val appData: Long)

    /**
     * An install stopped because its uid is already installed.
     *
     * The staged file is kept, not re-copied: a user agreeing to replace should not wait for
     * a second copy of a 300 MB book, and re-reading the source on confirm would be reading
     * a file that may have changed since it was validated.
     */
    data class PendingReplacement(val staged: java.nio.file.Path, val existing: String, val title: String)

    /**
     * An activation the library refused to perform unattended, with the reason it refused.
     *
     * @param stale set when the active set changed while the question was on screen, so the
     * impact shown is no longer the impact that would happen. Re-measured rather than
     * re-confirmed: an acknowledgement of a stale measurement is an acknowledgement of
     * something else.
     */
    data class PendingActivation(
        val installId: Long,
        val title: String,
        val impacts: List<SupersessionImpact>,
        val stale: Boolean,
    )

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            busy = true
            // Every pack file opened, read, and closed -- off the main thread, because that
            // is a file read per installed pack and this runs on entering the screen.
            val loaded = withContext(Dispatchers.IO) {
                runCatching { Shelf.list(store.library) }
            }
            loaded
                .onSuccess { packs = it }
                .onFailure { failure = it.message ?: "could not read the library" }
            storage = withContext(Dispatchers.IO) { measureStorage() }
            busy = false
        }
    }

    /**
     * Installs a file the user picked.
     *
     * **The only install path there is.** The document is copied into the app's own cache
     * before anything looks at it, because a content URI is a handle to a file another app
     * owns and may rewrite: validating what the picker returned and then installing what the
     * picker returns *again* would be checking one file and quoting another. The library
     * then copies it a second time into its own directory, which is the same rule one layer
     * down and worth paying twice for.
     */
    fun install(uri: android.net.Uri) {
        if (busy) return
        viewModelScope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) { runCatching { stageAndInstall(uri, null) } }
            busy = false
            result
                .onSuccess { report(it.first, it.second) }
                .onFailure {
                    // **The staged copy goes with the failure.** A content provider that
                    // dies partway through the copy, or an install that throws rather than
                    // returning a result, used to leave a book-sized file in the cache that
                    // nothing would ever look at again -- and a user retrying a flaky import
                    // three times left three of them.
                    it.stagedPath()?.let { path -> runCatching { java.nio.file.Files.deleteIfExists(path) } }
                    failure = it.message ?: "could not install that file"
                }
        }
    }

    /** Confirms a replacement against the **staged bytes that were validated**, not the source. */
    fun confirmReplace(pending: PendingReplacement) {
        replacing = null
        viewModelScope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { store.library.install(pending.staged, pending.existing) to pending.staged }
            }
            busy = false
            result
                .onSuccess { report(it.first, it.second) }
                .onFailure {
                    // Same rule on the confirm path: the copy the user was asked about is
                    // the caller's to remove once the answer has been acted on.
                    runCatching { java.nio.file.Files.deleteIfExists(pending.staged) }
                    failure = it.message ?: "could not install that file"
                }
        }
    }

    fun cancelReplace() {
        val staged = replacing?.staged
        replacing = null
        // The temp copy goes with the decision. Keeping it would leave a book-sized file in
        // the cache that nothing will ever look at again.
        staged?.let { path -> viewModelScope.launch { withContext(Dispatchers.IO) { runCatching { java.nio.file.Files.deleteIfExists(path) } } } }
    }

    fun uninstall(installId: Long) {
        viewModelScope.launch {
            busy = true
            withContext(Dispatchers.IO) { runCatching { store.library.uninstall(installId) } }
                .onFailure { failure = it.message ?: "could not uninstall that pack" }
            busy = false
            refresh()
        }
    }

    /**
     * Moves a pack up or down the priority order.
     *
     * The order is total and visible: it decides which pack's chunk wins an exact tie in
     * retrieval and which passage a shared violation cites, so a user who cannot see or
     * change it cannot explain either outcome.
     */
    fun move(installId: Long, up: Boolean) {
        val order = packs.map { it.pack.installId }.toMutableList()
        val at = order.indexOf(installId)
        val to = if (up) at - 1 else at + 1
        if (at < 0 || to !in order.indices) return
        order[at] = order[to].also { order[to] = order[at] }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { store.library.reorder(order) } }
                .onFailure { failure = it.message ?: "could not reorder" }
            refresh()
        }
    }

    /**
     * A failure that knows which staged file it left behind, so the caller can remove it.
     *
     * The path is created before anything can fail, and everything that can fail happens
     * after — so "delete it on failure" needs the path to survive the throw. Carrying it on
     * the exception keeps the create and the delete in one function instead of leaking a
     * mutable `var` into the coroutine.
     */
    private class StagingFailure(val staged: java.nio.file.Path, cause: Throwable) :
        RuntimeException(cause.message ?: "could not install that file", cause)

    private fun Throwable.stagedPath(): java.nio.file.Path? = (this as? StagingFailure)?.staged

    private fun stageAndInstall(
        uri: android.net.Uri,
        confirmReplacing: String?,
    ): Pair<dev.ludex.state.InstallResult, java.nio.file.Path> {
        val application = getApplication<Application>()
        val staged = java.nio.file.Files.createTempFile(
            java.nio.file.Files.createDirectories(application.cacheDir.toPath().resolve("incoming")),
            "incoming", ".rpgpack",
        )
        return try {
            // **Bounded while it is written, not after.** `PackLibrary.install` enforces a
            // ceiling on the copy *it* makes, and that copy happens after this one -- so a
            // content provider handing back an oversized or endless stream filled the
            // device's storage before anything had the chance to refuse it. The same limit,
            // applied at the first place the bytes touch disk.
            val limit = dev.ludex.state.PackLibrary.PackLimits().maxFileBytes
            application.contentResolver.openInputStream(uri)?.use { input ->
                java.nio.file.Files.newOutputStream(staged).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > limit) error("that file is larger than $limit bytes")
                        output.write(buffer, 0, read)
                    }
                }
            } ?: error("could not read that file")
            store.library.install(staged, confirmReplacing) to staged
        } catch (failure: Throwable) {
            throw StagingFailure(staged, failure)
        }
    }

    private fun report(result: dev.ludex.state.InstallResult, staged: java.nio.file.Path) {
        // The staged copy is only kept when the user is being asked something about it.
        fun discard() = runCatching { java.nio.file.Files.deleteIfExists(staged) }
        when (result) {
            is dev.ludex.state.InstallResult.Installed -> {
                discard()
                failure = when {
                    result.deactivatedForReview.isNotEmpty() ->
                        "Installed inactive: it replaces an active pack and now withdraws a " +
                            "large part of another active book, which is a decision to make " +
                            "rather than one to inherit."
                    result.buildNotes.any { it.severity == "unchecked" } ->
                        "Installed. This pack ships content nobody adjudicated — see its card."
                    else -> null
                }
                refresh()
            }
            is dev.ludex.state.InstallResult.NeedsConfirmation -> {
                replacing = PendingReplacement(
                    staged = staged,
                    existing = result.existing.packUid,
                    title = result.existing.title,
                )
            }
            is dev.ludex.state.InstallResult.TooLarge -> {
                discard()
                failure = "That pack is larger than this app will install: ${result.limit}"
            }
            is dev.ludex.state.InstallResult.Rejected -> {
                discard()
                // The violations, not "invalid". A pack that will not activate is a pack
                // somebody built, and the person holding it can only fix what they are told.
                failure = "That pack would not activate:\n${result.report}"
            }
        }
    }

    private fun measureStorage(): StorageUse {
        val root = getApplication<Application>().filesDir.toPath()
        return StorageUse(
            packs = sizeOf(root.resolve("library/packs")),
            models = sizeOf(root.resolve("models")),
            // **The database and its sidecars.** `StateDb` runs in WAL mode, so recent
            // conversation and answer-cache pages live in `state.db-wal` until a checkpoint
            // -- and after a few large stored answers that is where most of the bytes are.
            // Measuring only `state.db` under-reported app data by whatever had not been
            // checkpointed, which is exactly the figure a user reads when they wonder why
            // the app is large.
            appData = listOf("library/state.db", "library/state.db-wal", "library/state.db-shm")
                .sumOf { sizeOf(root.resolve(it)) },
        )
    }

    private fun sizeOf(path: java.nio.file.Path): Long = runCatching {
        if (!java.nio.file.Files.exists(path)) return 0
        if (java.nio.file.Files.isRegularFile(path)) return java.nio.file.Files.size(path)
        java.nio.file.Files.walk(path).use { entries ->
            entries.filter { java.nio.file.Files.isRegularFile(it) }
                .mapToLong { runCatching { java.nio.file.Files.size(it) }.getOrDefault(0L) }
                .sum()
        }
    }.getOrDefault(0L)

    /**
     * Activates or deactivates, asking first only when the library says to.
     *
     * Deactivation never asks: switching a pack off can only ever restore reachability.
     */
    fun setActive(installId: Long, active: Boolean) {
        viewModelScope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { store.library.setActive(installId, active) }
            }
            busy = false
            handle(installId, result)
        }
    }

    /** Confirms an activation against **the impact that was on screen**, not against a yes. */
    fun confirm(pending: PendingActivation) {
        this.pending = null
        viewModelScope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { store.library.setActive(pending.installId, true, pending.impacts) }
            }
            busy = false
            handle(pending.installId, result)
        }
    }

    fun dismiss() {
        pending = null
    }

    private fun handle(installId: Long, result: Result<ActivationResult>) {
        result
            .onSuccess { activation ->
                when (activation) {
                    is ActivationResult.Changed -> refresh()
                    is ActivationResult.NotInstalled ->
                        failure = "that pack is no longer installed"
                    is ActivationResult.NeedsAcknowledgement -> pending = PendingActivation(
                        installId = installId,
                        title = packs.firstOrNull { it.pack.installId == installId }
                            ?.pack?.title ?: "this pack",
                        impacts = activation.impacts,
                        stale = activation.stale,
                    )
                }
            }
            .onFailure { failure = it.message ?: "could not change that pack" }
    }

    // No `onCleared` closing the store: it is the process's, shared with every other
    // surface, and a ViewModel closing a handle three others hold is the bug this
    // consolidation exists to remove.
}
