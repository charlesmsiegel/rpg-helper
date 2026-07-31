package dev.rpghelper.app

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.rpghelper.capabilities.RollResult
import dev.rpghelper.capabilities.RollableTable
import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.routing.Card
import dev.rpghelper.routing.Chip
import dev.rpghelper.routing.Block
import dev.rpghelper.routing.layout
import dev.rpghelper.routing.markChips
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
fun AnswerCard(
    card: Card,
    modifier: Modifier = Modifier,
    rolls: RollControls = RollControls.None,
    onSearchInactive: (() -> Unit)? = null,
) {
    when (card) {
        // Labels come from `Card.layout()`, not from here. The label is the sentence that
        // makes provenance legible to a reader who does not know this app's conventions --
        // the whole mechanism by which "generated" and "quoted" stay distinguishable -- and
        // three surfaces wording it three ways is three different promises.
        is Card.Verbatim -> VerbatimCard(card, modifier, rolls)
        is Card.Derived -> ProseCard(
            label = card.labelText(),
            body = card.body,
            chips = card.chips,
            footer = card.footer,
            modifier = modifier,
        )
        is Card.Generated -> ProseCard(
            label = card.labelText(),
            body = card.body,
            chips = card.chips,
            footer = card.footer,
            modifier = modifier,
        )
        is Card.ModelUnavailable -> StatementCard(card.statement, card.wouldHaveUsed, modifier)
        is Card.Empty -> EmptyCard(card, onSearchInactive, modifier)
    }
}

/** This card's label, as `Card.layout()` words it for every surface. */
private fun Card.labelText(): String =
    layout().filterIsInstance<Block.Label>().first().text

@Composable
private fun VerbatimCard(
    card: Card.Verbatim,
    modifier: Modifier,
    rolls: RollControls = RollControls.None,
) {
    // **Provenance is announced before content, and as one node.** Sighted readers get the
    // rule down the left edge and the monospaced face; a screen-reader user got a `RULES`
    // label, then the body, then a citation three nodes later -- so the fact that this was
    // the book's own words arrived after the words, if it arrived at all. Merging the
    // card's semantics and stating the quotation first is the same signal in the channel
    // TalkBack actually reads (`06-ui-spec.md` section 1.3).
    val spoken = "Quotation from ${card.citation.sourceTitle}" +
        (card.citation.headingPath?.let { ", $it" } ?: "") +
        ". ${card.kind}. ${card.body}. Cited as ${render(card.citation)}." +
        if (card.rollable) " A roll control is available." else ""

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) { contentDescription = spoken },
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
            // **The rule is as tall as the text, measured rather than guessed.** It was a
            // fixed 20dp per newline, which is right at font scale 1.0 and wrong at every
            // other -- and the scales it was wrong at are the accessibility text sizes
            // `06-ui-spec.md` requires these cards to survive, where the bar stopped
            // partway down the quotation and the provenance gutter simply ended. Sizing the
            // Row to its own minimum intrinsic height and filling it is the same signal
            // whatever the reader's text size is.
            Row(Modifier.height(IntrinsicSize.Min)) {
                // A solid bar down the left of every quoted line: the one signal that
                // survives a screenshot, a low-vision zoom, and a colour-blind palette,
                // which is why the distinction does not rest on hue.
                Surface(
                    modifier = Modifier.width(3.dp).fillMaxHeight(),
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
            // **An actionable control, not a label that looks like one** -- and one per
            // table, because a chunk may carry several and a nested table deduplicated into
            // this quote brought its own. Labelled by the table so two controls on one card
            // are distinguishable, which a repeated "Roll on this table" would not be.
            for (ref in card.rollableRefs) {
                for (table in rolls.tablesFor(ref)) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(onClick = { rolls.onRoll(ref, table) }) {
                        Text("Roll: ${table.label}")
                    }
                    rolls.resultFor(ref, table.tableId)?.let { RolledOutcome(it, card.citation) }
                }
            }
        }
    }
}

@Composable
private fun ProseCard(
    label: String,
    body: String,
    chips: List<Chip>,
    footer: List<Citation>,
    modifier: Modifier,
) {
    // A chip's whole content is *which run of text* a citation supports. Collapsing chips
    // into the footer throws the span away, and a card drawing two claims from two books
    // becomes two citations with no way to tell which supports which -- the one
    // distinction inline chips exist to carry. Each chipped run gets a superscript marker
    // and the markers appear again below.
    val marked = markChips(body, chips)

    Surface(modifier = modifier.fillMaxWidth(), color = Color.Transparent) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = marked,
                // Italic, proportional, no gutter: everything the quote card is not.
                fontStyle = FontStyle.Italic,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(8.dp))
            chips.sortedBy { it.start }.forEachIndexed { index, chip ->
                CitationLine(chip.citation, marker = "${index + 1}")
            }
            if (footer.isNotEmpty()) {
                Text(
                    text = "drawn as a whole from ${footer.size} source" +
                        if (footer.size == 1) "" else "s",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                footer.distinct().forEach { CitationLine(it) }
            }
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
private fun CitationLine(citation: Citation, marker: String? = null) {
    Text(
        text = if (marker == null) render(citation) else "[$marker] ${render(citation)}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.background(Color.Transparent),
    )
}

/**
 * A turn restored from a previous session, shown as **history and not as an answer**.
 *
 * The stored text is what a past active set produced. Rendering it through [AnswerCard]
 * would put a previous session's words inside this session's quotation styling — the exact
 * confusion between "what the book says" and "what was said about the book" that the rest
 * of this file exists to prevent, arriving through the back door of the scrollback.
 *
 * So it is deliberately plain: proportional, dimmed, labelled, and never in the monospaced
 * preformatted style a quotation gets. To see the quotation again, ask again; the packs
 * that would answer are the ones active now.
 */
@Composable
fun HistoryCard(rendered: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(
            Modifier.padding(12.dp).semantics(mergeDescendants = true) {
                contentDescription = "Earlier answer, from a previous session. $rendered"
            },
        ) {
            Text(
                text = "Earlier answer — from a previous session, not re-checked against " +
                    "the books active now",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = rendered,
                style = MaterialTheme.typography.bodyMedium,
                fontStyle = FontStyle.Italic,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * What a roll produced: the dice, then the outcome **as a quotation**.
 *
 * The outcome text is a validated span of a verbatim table chunk — the book's own words,
 * selected by a die rather than by a search. So it wears the same monospaced, preformatted,
 * cited styling every other quotation wears, and the dice line above it is visibly separate
 * from it. Paraphrasing the outcome into the roll line, or rendering it in the body style,
 * would make the one text on screen that came from a random number look exactly like text
 * the user asked for.
 *
 * The individual faces are shown, not just the total, so a user can confirm the app rolled
 * what it said it rolled.
 */
@Composable
private fun RolledOutcome(result: RollResult, citation: Citation, modifier: Modifier = Modifier) {
    val faces = result.dice.joinToString(" + ")
    val modifierPart = when {
        result.modifier > 0 -> " + ${result.modifier}"
        result.modifier < 0 -> " − ${-result.modifier}"
        else -> ""
    }
    val line = "${result.expression} → $faces$modifierPart = ${result.total}"

    Column(
        modifier.padding(top = 6.dp).semantics(mergeDescendants = true) {
            contentDescription = "Rolled $line. Quotation from ${citation.sourceTitle}. " +
                "${result.row.text}. Cited as ${render(citation)}."
        },
    ) {
        Text(
            text = line,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(4.dp))
        Row(Modifier.height(IntrinsicSize.Min)) {
            // The same rule marker every other quotation gets, at the same width, and
            // measured the same way.
            Surface(
                modifier = Modifier.width(3.dp).fillMaxHeight(),
                color = MaterialTheme.colorScheme.primary,
                content = {},
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = result.row.text,
                fontFamily = FontFamily.Monospace,
                softWrap = false,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.horizontalScroll(rememberScrollState()),
            )
        }
        Spacer(Modifier.height(4.dp))
        CitationLine(citation)
    }
}

/**
 * Everything a card needs to offer a roll, in one object.
 *
 * A struct rather than three parameters threaded through every composable, and a struct
 * rather than a `RollableTable` handed in directly: the tables are per chunk and a card can
 * carry several chunks' worth, so the card asks rather than being told.
 */
class RollControls(
    val tablesFor: (ChunkRef) -> List<RollableTable>,
    val resultFor: (ChunkRef, Long) -> RollResult?,
    val onRoll: (ChunkRef, RollableTable) -> Unit,
) {
    companion object {
        /** No packs loaded, or a surface with no roller. Offers nothing rather than a stub. */
        val None = RollControls({ emptyList() }, { _, _ -> null }, { _, _ -> })
    }
}

/**
 * *Not found in your active packs* — **with the remedy the card model already carries.**
 *
 * `Card.Empty` holds which packs were searched and whether inactive ones exist, and this
 * rendered the sentence and discarded both. A refusal is load-bearing here: it is how a
 * user learns that silence means silence, and a user who cannot see *what was searched*
 * has no way to tell a real absence from a book they forgot to activate. The inactive
 * search is offered and never performed, because activating changes every future answer.
 */
@Composable
private fun EmptyCard(
    card: Card.Empty,
    onSearchInactive: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(text = card.statement, style = MaterialTheme.typography.bodyMedium)
            if (card.activePacks.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "Searched: ${card.activePacks.joinToString(", ")}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (card.canSearchInactive) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "You have installed packs that are not active. They were not searched.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                onSearchInactive?.let {
                    TextButton(onClick = it) { Text("Search inactive packs too") }
                }
            }
        }
    }
}
