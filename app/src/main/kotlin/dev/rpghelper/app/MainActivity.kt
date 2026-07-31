package dev.rpghelper.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import dev.rpghelper.routing.Answer
import dev.rpghelper.routing.Card

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
                    AskScreen()
                }
            }
        }
    }
}

/**
 * One exchange in the feed.
 *
 * @param answer the live cards, or **null for a turn restored from storage**. A card is
 * built from a pack that is active *now*; a turn from a previous session was built from
 * whatever was active then, and rebuilding it against today's active set would silently
 * re-answer a question the user already read. History is shown as the text that was stored,
 * marked as history, rather than as cards that look current and are not.
 */
data class Turn(val query: String, val answer: Answer?, val rendered: String)

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
                items(model.turns) { turn ->
                    Column {
                        Text(
                            text = turn.query,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        val answer = turn.answer
                        if (answer != null) {
                            answer.cards.forEach { card ->
                                AnswerCard(
                                    card = card,
                                    onRoll = model::roll,
                                    rolled = (card as? Card.Verbatim)?.let { model.rolls[it.ref] },
                                )
                            }
                        } else {
                            HistoryCard(turn.rendered)
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
