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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
                    AskScreen()
                }
            }
        }
    }
}

/** One exchange in the feed: what was asked, and what the app answered. */
data class Turn(val query: String, val answer: Answer)

@Composable
fun AskScreen(turns: List<Turn> = emptyList()) {
    var question by remember { mutableStateOf("") }

    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            LazyColumn(
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(turns) { turn ->
                    Column {
                        Text(
                            text = turn.query,
                            style = MaterialTheme.typography.titleMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                        turn.answer.cards.forEach { AnswerCard(it) }
                    }
                }
            }
            TextField(
                value = question,
                onValueChange = { question = it },
                placeholder = { Text("Ask about your books") },
                // Width, not size. `fillMaxSize` on a non-weighted child is measured
                // first and takes the whole column, leaving the weighted feed above it
                // zero height -- so every answer was rendered behind a full-screen input.
                modifier = Modifier.fillMaxWidth().padding(8.dp),
            )
        }
    }
}
