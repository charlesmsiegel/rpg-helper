package dev.ludex.app

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ludex.session.Library
import dev.ludex.session.Passage
import dev.ludex.session.RuleCheck
import dev.ludex.session.Rules
import dev.ludex.session.SheetState
import dev.ludex.state.Document
import dev.ludex.state.DocumentStore
import dev.ludex.state.DocumentTransfer
import dev.ludex.state.InvalidTrackerKeyException
import dev.ludex.state.Tracker
import dev.ludex.state.TrackerValue
import dev.ludex.state.Violation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One document, open: its trackers and what the books say about them. */
data class OpenSheet(
    val document: Document,
    val trackers: List<Tracker>,
    val check: RuleCheck,
)

/**
 * The Documents surfaces' state.
 *
 * **Every write re-checks**, on the active set as it is now. A validator the user has to
 * ask is a validator nobody asks, and the re-check is a local evaluation over a handful of
 * constraint rows — the expensive part is opening the packs, which is why it is done once
 * per operation and closed again rather than held.
 */
class DocumentsViewModel(application: Application) : AndroidViewModel(application) {

    private val store = Storage.of(application)
    private val documents = DocumentStore(store.db)

    var sheets by mutableStateOf<List<SheetState>>(emptyList())
        private set

    /** Non-null while a document is open. */
    var open by mutableStateOf<OpenSheet?>(null)
        private set

    /** Non-null while a violation's passage is being read. */
    var passage by mutableStateOf<Passage?>(null)
        private set

    var busy by mutableStateOf(false)
        private set

    var failure by mutableStateOf<String?>(null)
        private set

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                runCatching { Library.openActive(store.library).use { Rules.states(it, documents) } }
            }
                .onSuccess { sheets = it }
                .onFailure { failure = it.message ?: "could not read your documents" }
            busy = false
        }
    }

    fun create(title: String, campaign: String?, rulesetId: String?) {
        if (title.isBlank()) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                runCatching {
                    documents.create(
                        title = title.trim(),
                        campaign = campaign?.trim()?.ifBlank { null },
                        rulesetId = rulesetId?.trim()?.ifBlank { null },
                    )
                }
            }
                .onSuccess { refresh() }
                .onFailure { failure = it.message ?: "could not create that document" }
        }
    }

    fun openSheet(documentId: Long) {
        viewModelScope.launch {
            busy = true
            withContext(Dispatchers.IO) { runCatching { read(documentId) } }
                .onSuccess { open = it }
                .onFailure { failure = it.message ?: "could not open that document" }
            busy = false
        }
    }

    fun close() {
        open = null
        passage = null
        refresh()
    }

    /**
     * Sets a tracker and re-checks.
     *
     * A key the store will not normalize is **reported, not stored as typed**: a key that
     * misses the form constraints select on does not fail loudly, it silently matches
     * nothing, and the sheet then looks validated while nothing checked it.
     */
    fun putTracker(documentId: Long, key: String, raw: String) {
        if (key.isBlank()) return
        viewModelScope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                runCatching {
                    documents.putTracker(documentId, key, parse(raw))
                    read(documentId)
                }
            }
                .onSuccess { open = it; failure = null }
                .onFailure {
                    failure = when (it) {
                        is InvalidTrackerKeyException -> it.message
                        else -> it.message ?: "could not save that"
                    }
                }
            busy = false
        }
    }

    fun removeTracker(documentId: Long, key: String) {
        viewModelScope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                runCatching { documents.removeTracker(documentId, key); read(documentId) }
            }
                .onSuccess { open = it }
                .onFailure { failure = it.message ?: "could not remove that" }
            busy = false
        }
    }

    /**
     * Leaves draft, which is the moment minimum bounds start being evaluated.
     *
     * A half-entered sheet violates every minimum it has not reached yet, so those are
     * suppressed while `draft` is set. Clearing it is a deliberate action because it is the
     * sheet claiming to be complete.
     */
    fun setDraft(documentId: Long, draft: Boolean) {
        viewModelScope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                runCatching { documents.setDraft(documentId, draft); read(documentId) }
            }
                .onSuccess { open = it }
                .onFailure { failure = it.message ?: "could not change that" }
            busy = false
        }
    }

    /** Accepts a violation as a deliberate deviation — a house rule, kept on the record. */
    fun accept(documentId: Long, violation: Violation, note: String?) {
        viewModelScope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                runCatching {
                    documents.accept(documentId, violation.fingerprint, violation.rulesetId, note)
                    read(documentId)
                }
            }
                .onSuccess { open = it }
                .onFailure { failure = it.message ?: "could not accept that" }
            busy = false
        }
    }

    fun unaccept(documentId: Long, violation: Violation) {
        viewModelScope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                runCatching {
                    documents.unaccept(documentId, violation.fingerprint)
                    read(documentId)
                }
            }
                .onSuccess { open = it }
                .onFailure { failure = it.message ?: "could not undo that" }
            busy = false
        }
    }

    /** Opens the passage a violation came from. The feature that settles the argument. */
    fun showPassage(violation: Violation) {
        viewModelScope.launch {
            busy = true
            val found = withContext(Dispatchers.IO) {
                runCatching { Library.openActive(store.library).use { Rules.passage(it, violation) } }
            }
            busy = false
            found
                .onSuccess {
                    passage = it
                    if (it == null) failure = "that rule's passage is not in any active pack"
                }
                .onFailure { failure = it.message ?: "could not open that passage" }
        }
    }

    fun dismissPassage() {
        passage = null
    }

    /**
     * Writes one document to a file the user chose.
     *
     * A file is how a document moves between devices, because there is no sync — local is
     * the premise. What the app must not do is lose what it cannot read: if the target
     * already holds a `.rpgdoc`, its unknown top-level keys are carried into the new one,
     * so a document exported by a later build and re-exported by this one keeps the fields
     * this build does not understand.
     */
    fun export(documentId: Long, uri: Uri) {
        viewModelScope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                runCatching {
                    val resolver = getApplication<Application>().contentResolver
                    val previous = runCatching {
                        resolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                    }.getOrNull()
                    val text = DocumentTransfer.export(documents, documentId, previous)
                    // "wt" truncates. Without it a shorter export leaves the tail of the
                    // longer file it replaced, and the result is a document that parses and
                    // is not the one that was written.
                    resolver.openOutputStream(uri, "wt")?.use { it.write(text.encodeToByteArray()) }
                        ?: error("could not write that file")
                }
            }
                .onSuccess { failure = null }
                .onFailure { failure = it.message ?: "could not export that document" }
            busy = false
        }
    }

    /** Reads a `.rpgdoc`. A file it cannot read is refused whole, never imported partially. */
    fun import(uri: Uri) {
        viewModelScope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                runCatching {
                    val text = getApplication<Application>().contentResolver
                        .openInputStream(uri)
                        ?.use { it.readBytes().decodeToString() }
                        ?: error("could not read that file")
                    DocumentTransfer.import(documents, text)
                }
            }
                .onSuccess { imported ->
                    // Named, not swallowed. A ruling that quietly failed to arrive is found
                    // by seeing a flag the user thought they had settled.
                    failure = imported.droppedAcceptances.takeIf { it.isNotEmpty() }
                        ?.joinToString("; ", prefix = "Imported, but: ")
                    refresh()
                }
                .onFailure { failure = it.message ?: "could not import that file" }
            busy = false
        }
    }

    /**
     * Rebinds the document to another ruleset, or to none.
     *
     * Acceptances are deliberately retained: their fingerprints carry the ruleset they were
     * made under, so one from a previous binding goes dormant rather than following the
     * document into a new game — and becomes live again if the document comes back.
     */
    fun rebind(documentId: Long, rulesetId: String?) {
        viewModelScope.launch {
            busy = true
            withContext(Dispatchers.IO) {
                runCatching { documents.rebind(documentId, rulesetId); read(documentId) }
            }
                .onSuccess { open = it; failure = null }
                .onFailure { failure = it.message ?: "could not rebind that document" }
            busy = false
        }
    }

    fun delete(documentId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { runCatching { documents.delete(documentId) } }
                .onSuccess { open = null; refresh() }
                .onFailure { failure = it.message ?: "could not delete that document" }
        }
    }

    /**
     * One read of everything a document view shows, so the trackers and the check always
     * describe the same moment.
     */
    private fun read(documentId: Long): OpenSheet {
        val document = requireNotNull(documents.get(documentId)) { "that document is gone" }
        return Library.openActive(store.library).use { library ->
            OpenSheet(document, documents.trackers(documentId), Rules.check(library, documents, documentId))
        }
    }

    /**
     * Types a value by what was written.
     *
     * A `range` constraint reads numbers and skips everything else, so storing `3` as text
     * would leave the tracker on screen and invisible to every rule about it.
     */
    private fun parse(raw: String): TrackerValue {
        val trimmed = raw.trim()
        trimmed.toDoubleOrNull()?.let { return TrackerValue.Number(it) }
        return when (trimmed.lowercase()) {
            "true", "yes" -> TrackerValue.Flag(true)
            "false", "no" -> TrackerValue.Flag(false)
            else -> TrackerValue.Text(trimmed)
        }
    }

    // No `onCleared` closing the store: it is the process's, shared with every other
    // surface, and a ViewModel closing a handle three others hold is the bug this
    // consolidation exists to remove.
}
