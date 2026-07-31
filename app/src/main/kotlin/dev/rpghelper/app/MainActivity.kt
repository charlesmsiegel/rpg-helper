package dev.rpghelper.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rpghelper.capabilities.RollableTable
import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.routing.Answer

/**
 * The Ask surface, which is the app.
 *
 * Deliberately thin. Everything that decides *what* is on screen — the routing partition,
 * redaction, citation resolution — lives in modules that know nothing about Android, and
 * this file's whole job is to put their output where a person can read it. That the six
 * modules below compose into an app without any of them importing an Android class is the
 * point of the layering rather than a happy accident.
 *
 * The feed is the conversational window (`01-app-state-spec.md` §3): what is on screen is
 * exactly what follow-up normalization sees, and **New topic** clears both. There is no
 * second, hidden context.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(Modifier.fillMaxSize()) {
                    RootScreen()
                }
            }
        }
    }
}

/** The surfaces this build has. Each one owns its own ViewModel and its own store handle. */
enum class Destination(val label: String) {
    ASK("Ask"),
    DOCUMENTS("Documents"),
    PACKS("Packs"),
}

/**
 * The whole app, which is two surfaces and a way between them.
 *
 * A `when` over an enum rather than a navigation library: there are two destinations and no
 * arguments to pass, and a dependency whose whole job is to hold one enum would be a
 * dependency to keep up to date for nothing. **The Packs surface being reachable is the
 * point** — everything it shows was computable and appeared nowhere, so a user could not see
 * what their packs corrected or what their builder kept back.
 *
 * Each surface's ViewModel opens its own `Store`. That is deliberate: `StateDb` serializes
 * its own connection, and the alternative — one shared handle — would make the surfaces
 * contend for it while a question is being answered.
 */
@Composable
fun RootScreen() {
    var destination by remember { mutableStateOf(Destination.ASK) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth()) {
            Destination.entries.forEach { candidate ->
                TextButton(
                    onClick = { destination = candidate },
                    enabled = candidate != destination,
                    modifier = Modifier.padding(horizontal = 4.dp),
                ) {
                    Text(candidate.label)
                }
            }
        }
        when (destination) {
            Destination.ASK -> AskScreen()
            Destination.DOCUMENTS -> DocumentsScreen()
            Destination.PACKS -> PacksScreen()
        }
    }
}

/**
 * One exchange in the feed.
 *
 * **Everything a turn needs travels with the turn.** An earlier version kept the loaded
 * tables in a second list indexed in lockstep with this one, which was wrong before it was
 * even wrong in an interesting way: the feed is restored from storage at startup with N
 * entries while the table list starts empty, so the first live answer landed at feed index
 * N and table index 0 and every roll control on it silently vanished. A parallel array with
 * an invariant nobody states is a bug waiting for its first off-by-N; putting the tables in
 * the turn deletes the invariant instead of maintaining it.
 *
 * @param answer the live cards. Null when there are none to show — see [origin].
 * @param tables the roll tables that turn's cards were built from, by chunk. Kept after the
 * library closes: the rows were validated at activation, and re-opening the pack on a tap
 * would read a different pack than the card was built from.
 */
data class Turn(
    val id: Long,
    val query: String,
    val answer: Answer?,
    val rendered: String,
    val origin: Origin,
    val tables: Map<ChunkRef, List<RollableTable>> = emptyMap(),
)

/**
 * Where a turn's content came from — **three states, not two**.
 *
 * These were collapsed into `answer == null`, which made a same-session cache hit
 * indistinguishable from a turn restored after process death. `AskService` returns a null
 * answer for a cache hit by design (the stored render *is* the answer), so a hit would have
 * rendered under the label *"Earlier answer — from a previous session, not re-checked"*,
 * which is simply false: the cache key pins the current active pack bytes and contract.
 */
enum class Origin {
    /** Answered just now, with live cards. */
    LIVE,

    /**
     * Answered just now, from the answer cache.
     *
     * The cache contract says a hit and a fresh generation must be indistinguishable on
     * screen. They are not yet: the cache stores a *rendering*, and this app stores the
     * plain-text one, so a hit shows the gutter but not the card chrome. Marked as its own
     * state rather than papered over — see `docs/THEORY.md` §4.1.
     */
    CACHED,

    /** Restored from storage. Built from whatever was active then, not now. */
    RESTORED,
}

// `TopAppBar` is still `ExperimentalMaterial3Api` in the pinned BOM. Opted in at the one
// composable that uses it rather than module-wide, so the next thing that reaches for an
// experimental API has to say so too.
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AskScreen(model: AskViewModel = viewModel()) {
    var question by remember { mutableStateOf("") }
    val listState = rememberLazyListState()

    // The newest turn is the one being read. Scrolling on answer rather than on every
    // recomposition leaves the user's own scroll position alone while they read back.
    LaunchedEffect(model.turns.size) {
        if (model.turns.isNotEmpty()) listState.animateScrollToItem(model.turns.lastIndex)
    }

    Scaffold(
        topBar = {
            // **New topic is reachable.** Every answer is appended and the whole feed is
            // restored at startup, so with no way to clear it the history only ever grows
            // -- and once follow-up generation is enabled, the feed *is* the context a
            // dependent question resolves against, so a user unable to clear it is a user
            // unable to change the subject.
            TopAppBar(
                title = { Text("Ask") },
                actions = {
                    TextButton(
                        onClick = model::newTopic,
                        enabled = model.turns.isNotEmpty() && !model.asking,
                    ) {
                        Text("New topic")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(model.turns, key = { it.id }) { turn ->
                    Column {
                        Text(
                            text = turn.query,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        val answer = turn.answer
                        if (answer != null) {
                            // Bound to *this* turn: a same-uid replacement reuses
                            // `(pack_uid, chunk_id)`, so a control keyed on the ref alone
                            // would roll the new edition's table under an older card.
                            val controls = RollControls(
                                tablesFor = { ref -> turn.tables[ref].orEmpty() },
                                resultFor = { ref, tableId -> model.rollOf(turn.id, ref, tableId) },
                                onRoll = { ref, table -> model.roll(turn.id, ref, table) },
                            )
                            answer.cards.forEach { card ->
                                AnswerCard(card = card, rolls = controls)
                            }
                        } else {
                            StoredCard(turn.rendered, turn.origin)
                        }
                    }
                }
            }

            model.failure?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                )
            }

            TextField(
                value = question,
                onValueChange = { question = it },
                placeholder = { Text("Ask about your books") },
                enabled = !model.asking,
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(
                    onSend = {
                        model.ask(question)
                        question = ""
                    },
                ),
                // Width, not size. `fillMaxSize` on a non-weighted child is measured
                // first and takes the whole column, leaving the weighted feed above it
                // zero height -- so every answer was rendered behind a full-screen input.
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
        }
    }
}
