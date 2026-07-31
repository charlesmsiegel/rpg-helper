package dev.ludex.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ludex.model.Availability
import dev.ludex.model.DownloadResult
import dev.ludex.model.ModelLibrary
import dev.ludex.model.ModelManifest
import dev.ludex.model.ShelvedModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The Models surface's state, and the app's **only network operation**.
 *
 * Which is why nothing here is implicit. A download starts because a user asked; it says how
 * many bytes before it starts; it can be cancelled, and cancelling costs no storage; and the
 * digest is checked before anything is reported as ready. `06-ui-spec.md` §3: this is the
 * one progress bar in the app that represents a network transfer, and it says so.
 */
class ModelsViewModel(application: Application) : AndroidViewModel(application) {

    private val library = ModelLibrary(application.filesDir.toPath().resolve("models"))

    /** For a runtime binding that has weights and wants to know where they landed. */
    fun fileOf(manifest: ModelManifest, name: String) = library.fileOf(manifest, name)

    var models by mutableStateOf<List<ShelvedModel>>(emptyList())
        private set

    /** Live progress for the one model being fetched, if any. */
    var progress by mutableStateOf<Pair<String, Availability.Downloading>?>(null)
        private set

    var failure by mutableStateOf<String?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    private var cancel = AtomicBoolean(false)

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { library.list() } }
                .onSuccess { models = it }
                .onFailure { failure = it.message ?: "could not read the model library" }
        }
    }

    /**
     * Adds a manifest the user picked.
     *
     * The manifest is the trust chain: its digests were computed from the bytes that will
     * actually be fetched, and [ModelManifest.parse] refuses a placeholder — which is why
     * a manifest is added rather than bundled. A file that is not one fails here, before
     * any network operation exists to be misdirected.
     */
    fun addManifest(uri: Uri) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver
                        .openInputStream(uri)
                        ?.use { it.readBytes().decodeToString() }
                        ?: error("could not read that file")
                    library.add(text)
                }
            }
                .onSuccess { failure = null; refresh() }
                .onFailure { failure = "that is not a model manifest: ${it.message}" }
        }
    }

    fun download(manifest: ModelManifest) {
        if (busy) return
        busy = true
        cancel = AtomicBoolean(false)
        val flag = cancel
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    library.download(manifest, flag) { fetched ->
                        // Straight onto the state a Compose surface reads. The progress
                        // callback arrives on the IO thread; `mutableStateOf` is safe to
                        // write from any thread and the recomposition is scheduled.
                        progress = manifest.id to
                            Availability.Downloading(fetched.fetched, fetched.total)
                    }
                }
            }
            progress = null
            busy = false
            result
                .onSuccess {
                    failure = when (it) {
                        is DownloadResult.Complete -> null
                        // Distinct messages, because the remedies differ: a digest mismatch
                        // is not a network problem and retrying will not fix it.
                        is DownloadResult.Cancelled -> null
                        is DownloadResult.Failed -> it.reason
                    }
                    refresh()
                }
                .onFailure { failure = it.message ?: "the download did not finish" }
        }
    }

    /** Cancels the download in flight. Partial bytes are discarded, not left invisible. */
    fun cancelDownload() {
        cancel.set(true)
    }

    /** The digest pass, on demand — what catches a file that rotted after it was fetched. */
    fun verify(manifest: ModelManifest) {
        viewModelScope.launch {
            busy = true
            val intact = withContext(Dispatchers.IO) { runCatching { library.verify(manifest) } }
            busy = false
            failure = when {
                intact.getOrNull() == true -> null
                intact.isFailure -> intact.exceptionOrNull()?.message ?: "could not verify it"
                else -> "${manifest.displayName}: the files on disk are not the ones the " +
                    "manifest pins. Download it again."
            }
            refresh()
        }
    }

    fun removeFiles(manifest: ModelManifest) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { library.removeFiles(manifest) } }
                .onFailure { failure = it.message ?: "could not delete those files" }
            refresh()
        }
    }

    fun forget(manifest: ModelManifest) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { library.remove(manifest.id) } }
                .onFailure { failure = it.message ?: "could not remove that manifest" }
            refresh()
        }
    }
}
