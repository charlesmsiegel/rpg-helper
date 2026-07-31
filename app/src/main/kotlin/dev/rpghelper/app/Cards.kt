package dev.rpghelper.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.rpghelper.routing.Card
import dev.rpghelper.routing.Citation
import dev.rpghelper.routing.render

/**
 * The five cards, rendered so a quote and a paraphrase cannot be confused.
 *
 * This is where the app's central guarantee stops being a property of the data and starts
 * being a property of the screen. Three things carry it, and none of them is decoration:
 *
 * - **A verbatim body is never passed through a markdown or rich-text renderer.** Game
 *   text is full of asterisks, underscores, brackets, and pipes; a renderer would
 *   interpret them, and the string on screen would no longer be the string in the pack.
 *   Byte-exactness that survives the database and dies in the view layer is not
 *   byte-exactness. Quotes render as monospaced, preformatted text with whitespace
 *   preserved, scrolling horizontally rather than re-wrapping — **re-flowing a quotation
 *   is editing it**.
 * - **Generated prose is italic, proportional, and labelled.** The label says what
 *   produced it, because "generated" and "quoted" have to be legible without knowing the
 *   app's conventions.
 * - **A rule is quoted or it is not shown.** There is no styling that means *probably
 *   accurate*, so nothing here has to decide how confident to look.
 */
@Composable
fun AnswerCard(card: Card, modifier: Modifier = Modifier) {
    when (card) {
        is Card.Verbatim -> VerbatimCard(card, modifier)
        is Card.Derived -> ProseCard(
            label = "Summary — written by this pack's builder, not quoted",
            body = card.body,
            citations = card.chips.map { it.citation } + card.footer,
            modifier = modifier,
        )
        is Card.Generated -> ProseCard(
            label = "Generated on this device from setting text",
            body = card.body,
            citations = card.chips.map { it.citation } + card.footer,
            modifier = modifier,
        )
        is Card.ModelUnavailable -> StatementCard(card.statement, card.wouldHaveUsed, modifier)
        is Card.Empty -> StatementCard(card.statement, emptyList(), modifier)
    }
}

@Composable
private fun VerbatimCard(card: Card.Verbatim, modifier: Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = card.kind.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Row {
                // The rule marker: a solid bar down the left of every quoted line. It is
                // the one signal that survives a screenshot, a low-vision zoom, and a
                // colour-blind palette, which is why the distinction does not rest on hue.
                Surface(
                    modifier = Modifier.width(3.dp).height(quoteHeight(card.body)),
                    color = MaterialTheme.colorScheme.primary,
                    content = {},
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    text = card.body,
                    // Monospaced and horizontally scrollable rather than wrapped. The
                    // stored string reaches the screen unchanged, including its line
                    // breaks and its runs of spaces.
                    fontFamily = FontFamily.Monospace,
                    softWrap = false,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.horizontalScroll(rememberScrollState()),
                )
            }
            Spacer(Modifier.height(8.dp))
            CitationLine(card.citation)
            if (card.rollable) {
                Text(
                    text = "Roll on this table",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** One line per quoted line, so the rule beside the quote is as tall as the quote. */
private fun quoteHeight(body: String) = (20 * (body.count { it == '\n' } + 1)).dp

@Composable
private fun ProseCard(
    label: String,
    body: String,
    citations: List<Citation>,
    modifier: Modifier,
) {
    Surface(modifier = modifier.fillMaxWidth(), color = Color.Transparent) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = body,
                // Italic, proportional, no gutter: everything the quote card is not.
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            citations.distinct().forEach { CitationLine(it) }
        }
    }
}

@Composable
private fun StatementCard(statement: String, listed: List<Citation>, modifier: Modifier) {
    Surface(modifier = modifier.fillMaxWidth(), color = Color.Transparent) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = statement,
                style = MaterialTheme.typography.bodyMedium,
                textAlign = TextAlign.Start,
            )
            Spacer(Modifier.height(8.dp))
            listed.forEach { CitationLine(it) }
        }
    }
}

@Composable
private fun CitationLine(citation: Citation) {
    Text(
        text = render(citation),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.background(Color.Transparent),
    )
}
