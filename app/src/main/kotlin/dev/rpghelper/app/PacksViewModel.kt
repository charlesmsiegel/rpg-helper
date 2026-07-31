package dev.rpghelper.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.rpghelper.session.ShelvedPack
import dev.rpghelper.session.Shelf
import dev.rpghelper.session.Store
import dev.rpghelper.state.ActivationResult
import dev.rpghelper.state.SupersessionImpact
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

    private val store = Store(application.filesDir.toPath().resolve("library"))

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
            busy = false
        }
    }

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

    override fun onCleared() {
        store.close()
    }
}
