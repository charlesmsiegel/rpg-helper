package dev.rpghelper.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.rpghelper.capabilities.RollResult
import dev.rpghelper.capabilities.RollableTable
import dev.rpghelper.capabilities.Roller
import dev.rpghelper.capabilities.SecureDiceSource
import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.routing.asPlainText
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

    /**
     * What each rollable chunk on screen has most recently rolled.
     *
     * Per chunk rather than per card, because the same table can appear in two answers and
     * a roll belongs to the table the user tapped.
     */
    var rolls by mutableStateOf<Map<Pair<ChunkRef, Long>, RollResult>>(emptyMap())
        private set

    /**
     * The loadable tables behind the cards currently on screen.
     *
     * Captured while the library is open and **kept after it closes**. The active set is
     * opened for the length of one question, so by the time a user taps a roll control the
     * connection that loaded the table is long gone — and `RollableTable` already holds its
     * rows, validated at activation, so nothing needs re-reading. Rolling from a live
     * connection would mean re-opening the pack on a tap, which is both slower and a
     * different pack from the one the card was built from.
     */
    private var tables: Map<ChunkRef, List<RollableTable>> = emptyMap()

    private val roller = Roller(SecureDiceSource())

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
                        // Grouped, not mapped. Nothing in the schema makes `tables.chunk_id`
                        // unique, so a pack may attach several roll tables to one chunk --
                        // and `toMap` silently kept whichever the database returned last,
                        // making one control unreachable and the surviving one a function of
                        // iteration order.
                        val loadable = library.rollables.flatMap { (uid, tables) ->
                            tables.map { ChunkRef(uid, it.chunkId) to it }
                        }.groupBy({ it.first }, { it.second })
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
                            // **Not the default.** `AskService`'s fallback render is
                            // `cards.joinToString`, which is Kotlin's data-class toString --
                            // so a feed restored after process death read
                            // `Verbatim(ref=ChunkRef(...), citation=Citation(...))`, and the
                            // same text would become the context a follow-up resolves
                            // against once a generator is wired. The plain-text rendering
                            // keeps the quote gutter, which is what makes a stored answer
                            // still say which lines were the book's own words.
                            render = { it.asPlainText() },
                            hasInactivePacks = store.library.installed().any { !it.active },
                        ) to loadable
                    }
                }
            }
            outcome
                .onSuccess { (asked, loadable) ->
                    tables = tables + loadable
                    turns = turns + Turn(asked.question, asked.answer, asked.rendered)
                }
                .onFailure { failure = it.message ?: "could not answer that" }
            asking = false
        }
    }

    /**
     * Rolls on the table behind a quoted chunk.
     *
     * Fails **closed and visibly**: a result matching no row is a defect in a pack that
     * passed activation, and showing nothing at all would read as a control that does not
     * work rather than as a pack that is wrong.
     */
    fun roll(ref: ChunkRef, table: RollableTable) {
        roller.roll(table)
            .onSuccess { rolls = rolls + ((ref to table.tableId) to it) }
            .onFailure { failure = "the roll produced nothing: ${it.message}" }
    }

    /** The tables a card may offer a control for, in the order the pack loaded them. */
    fun tablesFor(ref: ChunkRef): List<RollableTable> = tables[ref].orEmpty()

    fun rollOf(ref: ChunkRef, tableId: Long): RollResult? = rolls[ref to tableId]

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
        rolls = emptyMap()
        tables = emptyMap()
        failure = null
    }

    override fun onCleared() {
        store.close()
    }
}
