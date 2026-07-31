package dev.rpghelper.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.rpghelper.session.AskService
import dev.rpghelper.session.Library
import dev.rpghelper.session.Store
import dev.rpghelper.state.AnswerCache
import dev.rpghelper.state.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The Ask surface's state, and the only thing in `:app` that knows how to answer.
 *
 * Thin on purpose: it opens the store, opens the active set for the length of one question,
 * and hands the question to [AskService] — which is where the order of operations that
 * matters lives (follow-up rewrite before retrieval, cache before routing, feed after the
 * answer exists). Reimplementing that sequence here would be a second copy of it, and the
 * two would drift on the day one of them was fixed.
 *
 * **The active set is opened per question, not held.** That is not a performance oversight;
 * it is the design: a query captures the active set once at its start and completes against
 * that snapshot, holding a lease on each file for exactly that long. Holding one open across
 * the app's lifetime would mean a pack the user activated in Settings does not take effect
 * until relaunch, and a pack they uninstalled stays readable.
 */
class AskViewModel(application: Application) : AndroidViewModel(application) {

    /**
     * `state.db` and `packs/` under the app's private files directory.
     *
     * Private storage rather than external: a pack is content the app is trusted to quote
     * byte-exactly, and a file any other app can rewrite is a file whose quotation is a
     * claim about somebody else's bytes.
     */
    private val store = Store(application.filesDir.toPath().resolve("library"))

    private val conversation = Conversation(store.db)

    private val service = AskService(
        cache = AnswerCache(store.db),
        conversation = conversation,
    )

    /** The feed, which is also the conversational window. There is no second context. */
    var turns by mutableStateOf<List<Turn>>(emptyList())
        private set

    var asking by mutableStateOf(false)
        private set

    /** Non-null when the last question could not be asked at all. */
    var failure by mutableStateOf<String?>(null)
        private set

    init {
        // The feed survives process death, because the answer a table was looking at half
        // an hour ago is the thing they scrolled back to. Restored as history: the stored
        // render, not live cards -- a card is only ever built from a pack that is active
        // now, and these were built from whatever was active then.
        turns = conversation.feed().map { Turn(it.query, answer = null, rendered = it.cards) }
    }

    fun ask(question: String) {
        if (question.isBlank() || asking) return
        asking = true
        failure = null
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    // Reconciled and reopened per question; closed before the next one.
                    Library.openActive(store.library).use { library ->
                        service.ask(
                            question = question,
                            library = library,
                            // No weights are bundled yet, so no generator is offered and
                            // route 3 never fires. That is the app's honest state before a
                            // model is downloaded, not a degraded mode: setting questions
                            // answer as citations rather than as prose, and the answer
                            // cache is never consulted because there is no generated card
                            // to keep.
                            generator = null,
                            hasInactivePacks = store.library.installed().any { !it.active },
                        )
                    }
                }
            }
            outcome
                .onSuccess { asked ->
                    turns = turns + Turn(asked.question, asked.answer, asked.rendered)
                }
                .onFailure { failure = it.message ?: "could not answer that" }
            asking = false
        }
    }

    /**
     * Clears the feed **and** the conversational window, which are the same thing.
     *
     * `01-app-state-spec.md` §3: what is on screen is exactly what a follow-up resolves
     * against, so a **New topic** that cleared only the display would leave the next
     * *"what about at level 5?"* resolving against passages the user believes are gone.
     */
    fun newTopic() {
        conversation.newTopic()
        turns = emptyList()
        failure = null
    }

    override fun onCleared() {
        store.close()
    }
}
