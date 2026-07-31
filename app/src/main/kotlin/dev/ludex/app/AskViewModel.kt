package dev.ludex.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.ludex.capabilities.RollResult
import dev.ludex.capabilities.RollableTable
import dev.ludex.capabilities.Roller
import dev.ludex.capabilities.SecureDiceSource
import dev.ludex.pack.ChunkRef
import dev.ludex.routing.Card
import dev.ludex.routing.asPlainText
import dev.ludex.model.ModelLibrary
import dev.ludex.session.AskService
import dev.ludex.session.Library
import dev.ludex.state.AnswerCache
import dev.ludex.state.Conversation
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
    private val store = Storage.of(application)

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
     * What each rollable chunk on screen has most recently rolled, **scoped to its turn**.
     *
     * `ChunkRef` is `(pack_uid, chunk_id)` and a same-uid replacement reuses both. So a
     * map keyed on the ref alone let a new edition's tables overwrite an older turn's while
     * that turn was still on screen: tapping the control under the old card would roll the
     * *replacement's* table beneath the old card's text and citation, and a stored result
     * could surface under a card from a different edition. The turn index is what makes a
     * roll belong to the cards it was offered beside.
     */
    var rolls by mutableStateOf<Map<Roll, RollResult>>(emptyMap())
        private set

    /** One roll control's identity: which turn's card, which chunk, which of its tables. */
    data class Roll(val turn: Long, val ref: ChunkRef, val tableId: Long)

    /** Monotonic, so a turn's identity never depends on its position in the feed. */
    private var nextTurnId = 0L

    /** Set by [newTopic]; makes an in-flight history restore land nowhere. */
    private var discarded = false

    private val roller = Roller(SecureDiceSource())

    /**
     * The downloaded models, and whatever can run one.
     *
     * Read per question rather than held: a model downloaded on the Models surface should
     * take effect on the next question, not on the next launch — the same rule the active
     * pack set follows, for the same reason.
     */
    private val models = ModelLibrary(application.filesDir.toPath().resolve("models"))

    private val voiceInput = OnDeviceVoice(application)

    /**
     * Whether the microphone is offered, and why not when it is not.
     *
     * Starts as whatever the device can assert *before* any permission is granted, so a
     * device with no on-device recognizer never reaches the point of being asked for the
     * microphone at all — asking would be collecting a permission this app would then
     * refuse to use.
     */
    var voice by mutableStateOf(voiceState(voiceInput.onDeviceAvailable(), permissionGranted = false))
        private set

    private var listening: AutoCloseable? = null

    /** What was heard, waiting to be asked or edited. The user sees it before it is sent. */
    var heard by mutableStateOf<String?>(null)
        private set

    init {
        // The feed survives process death, because the answer a table was looking at half
        // an hour ago is the thing they scrolled back to. Restored as history: the stored
        // render, not live cards -- a card is only ever built from a pack that is active
        // now, and these were built from whatever was active then.
        //
        // Off the main thread. A ViewModel is constructed during `setContent`, and this
        // reads a SQLite file whose rows hold whole rendered answers -- so on a cold start
        // with a long window, on the storage a cheap device actually has, the first frame
        // waited on disk I/O. The feed arrives a frame or two later into an empty list,
        // which is the state the surface already has to render.
        viewModelScope.launch {
            val restored = withContext(Dispatchers.IO) { conversation.feed() }
            // **New topic** during the read wins: it already cleared the stored window, and
            // putting these back on screen would show turns the user was told are gone --
            // and, worse, turns a follow-up no longer resolves against.
            if (discarded) return@launch
            // Prepended, not assigned: `ask` is not blocked while this runs, so a question
            // asked before history arrives must not be dropped by the restore landing on
            // top of it. `nextTurnId` is only ever advanced on this thread.
            turns = restored.map {
                Turn(
                    id = nextTurnId++,
                    query = it.query,
                    answer = null,
                    rendered = it.cards,
                    origin = Origin.RESTORED,
                )
            } + turns
        }

        // The background digest pass, on the one schedule this app can honestly keep: once
        // per process, after the feed, off the main thread. `PackLibrary.verify` existed and
        // nothing outside `:cli` called it, so an installed pack's bytes were checked once
        // — at install — and quoted as the book's own for the rest of the pack's life.
        //
        // A pack that fails is already deactivated by the time this returns; it is surfaced
        // rather than swallowed, because a book vanishing from the active set with no
        // explanation is the failure this whole path exists to avoid.
        viewModelScope.launch {
            val failed = withContext(Dispatchers.IO) {
                runCatching { store.library.verifyStale(VERIFY_INTERVAL) }.getOrDefault(emptyList())
            }
            if (failed.isNotEmpty()) {
                failure = "deactivated ${failed.joinToString { it.title }}: the installed " +
                    "bytes no longer match what was installed, so they cannot be quoted"
            }
        }
    }

    /**
     * Asks over the active set — the ordinary path.
     *
     * @param includeInactive **only ever true because the user tapped the offer** on a
     * refusal card. A deactivated pack is deactivated because they said so, and an app that
     * quietly searched it after failing to find an answer would make the toggle a
     * suggestion. Wired because the card prints the offer, and an offer a surface makes and
     * cannot honour is worse than one it never makes.
     */
    fun ask(question: String, includeInactive: Boolean = false) {
        if (question.isBlank() || asking) return
        asking = true
        failure = null
        viewModelScope.launch {
            val outcome = withContext(Dispatchers.IO) {
                runCatching {
                    // Reconciled and reopened per question; closed before the next one.
                    val opened =
                        if (includeInactive) Library.openAll(store.library)
                        else Library.openActive(store.library)
                    opened.use { library ->
                        // Grouped, not mapped. Nothing in the schema makes `tables.chunk_id`
                        // unique, so a pack may attach several roll tables to one chunk --
                        // and `toMap` silently kept whichever the database returned last,
                        // making one control unreachable and the surviving one a function of
                        // iteration order.
                        val loadable = library.rollables.flatMap { (uid, tables) ->
                            tables.map { ChunkRef(uid, it.chunkId) to it }
                        }.groupBy({ it.first }, { it.second })
                        // **The downloaded model, if anything in this build can run it.**
                        // `null` here is not a placeholder: it is the honest state whenever
                        // no weights are present *or* no runtime is bundled, and routing
                        // reads it as "not downloaded" and answers setting questions as
                        // citations. The Models surface says the same thing in the same
                        // words rather than implying a download is enough.
                        val ready = models.readyGenerator()
                        val asked = service.ask(
                            question = question,
                            library = library,
                            generator = ready?.second,
                            // Weights *and* quantization, because the same weights at four
                            // bits answer differently from the same weights at eight -- and
                            // a cache keyed without it would serve one as the other.
                            modelId = ready?.first?.id ?: "none",
                            // **Not the default.** `AskService`'s fallback render is
                            // `cards.joinToString`, which is Kotlin's data-class toString --
                            // so a feed restored after process death read
                            // `Verbatim(ref=ChunkRef(...), citation=Citation(...))`, and the
                            // same text would become the context a follow-up resolves
                            // against once a generator is wired. The plain-text rendering
                            // keeps the quote gutter, which is what makes a stored answer
                            // still say which lines were the book's own words.
                            render = { it.asPlainText() },
                            // No second offer once every installed pack has been searched:
                            // a refusal that still says "and there are packs I did not
                            // search" would be false, and the button would search the same
                            // set again.
                            hasInactivePacks = !includeInactive &&
                                store.library.installed().any { !it.active },
                        )
                        // **Only the tables this answer's own cards can offer.** The turn
                        // keeps its tables for as long as it is on screen, and the feed is
                        // unbounded, so retaining the whole active set's rollables per turn
                        // meant every question re-retained every table in every active pack
                        // -- rows and outcome text included -- N times over for a feed of
                        // N. `Cards.kt` asks `tablesFor` only for refs a card carries, so
                        // everything outside this filter was retained to answer a lookup
                        // that cannot occur.
                        val addressed = asked.answer?.cards.orEmpty()
                            .filterIsInstance<Card.Verbatim>()
                            .flatMap { it.rollableRefs }
                            .toSet()
                        asked to loadable.filterKeys { it in addressed }
                    }
                }
            }
            outcome
                .onSuccess { (asked, loadable) ->
                    turns = turns + Turn(
                        id = nextTurnId++,
                        query = asked.question,
                        answer = asked.answer,
                        rendered = asked.rendered,
                        // A null answer from a *live* ask is a cache hit, not history. The
                        // stored render is the answer; what it is not is a previous session.
                        origin = if (asked.answer != null) Origin.LIVE else Origin.CACHED,
                        tables = loadable,
                    )
                }
                .onFailure { failure = it.message ?: "could not answer that" }
            asking = false
        }
    }

    /** The permission result. A refusal is not an error state — the control simply stays off. */
    fun onMicrophonePermission(granted: Boolean) {
        voice = voiceState(voiceInput.onDeviceAvailable(), granted)
    }

    /**
     * Listens once, on the device.
     *
     * The transcript lands in [heard] rather than being asked immediately: recognition is
     * imperfect, this app answers out of books where a mangled proper noun matters, and a
     * question the user never saw before it was sent is a question they cannot correct.
     */
    fun listen() {
        if (voice !is VoiceState.Idle) return
        voice = VoiceState.Listening
        listening = voiceInput.listen(
            onResult = {
                heard = it
                voice = VoiceState.Idle
                listening = null
            },
            onFailure = {
                voice = VoiceState.Failed(it)
                listening = null
            },
        )
    }

    fun stopListening() {
        listening?.close()
        listening = null
        if (voice is VoiceState.Listening) voice = VoiceState.Idle
    }

    /** Clears the transcript, whether it was asked or abandoned. */
    fun clearHeard() {
        heard = null
    }

    /**
     * Rolls on the table behind a quoted chunk.
     *
     * Fails **closed and visibly**: a result matching no row is a defect in a pack that
     * passed activation, and showing nothing at all would read as a control that does not
     * work rather than as a pack that is wrong.
     */
    fun roll(turn: Long, ref: ChunkRef, table: RollableTable) {
        roller.roll(table)
            .onSuccess { rolls = rolls + (Roll(turn, ref, table.tableId) to it) }
            .onFailure { failure = "the roll produced nothing: ${it.message}" }
    }

    fun rollOf(turn: Long, ref: ChunkRef, tableId: Long): RollResult? = rolls[Roll(turn, ref, tableId)]

    /**
     * Clears the feed **and** the conversational window, which are the same thing.
     *
     * `01-app-state-spec.md` §3: what is on screen is exactly what a follow-up resolves
     * against, so a **New topic** that cleared only the display would leave the next
     * *"what about at level 5?"* resolving against passages the user believes are gone.
     */
    fun newTopic() {
        discarded = true
        conversation.newTopic()
        turns = emptyList()
        rolls = emptyMap()
        failure = null
    }

    override fun onCleared() {
        // The recognizer holds a microphone. Leaving it open past the ViewModel would keep
        // it open past the screen.
        //
        // The store is *not* closed: it is the process's, shared with every other surface,
        // and a ViewModel closing a handle three others hold is the same bug one directory
        // up from the one that made every surface open its own.
        listening?.close()
    }

    private companion object {
        /**
         * How stale a pack's last digest may be before this process re-checks it.
         *
         * A day, because the cost is one full read of every active pack and the thing it
         * detects — bytes changing under a file the app never writes — is rare rather than
         * urgent. Shorter would make a cold start on a large library expensive for no
         * additional guarantee.
         */
        val VERIFY_INTERVAL: java.time.Duration = java.time.Duration.ofDays(1)
    }
}
