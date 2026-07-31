package dev.ludex.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.performClick
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import dev.ludex.capabilities.RollableTable
import dev.ludex.capabilities.TableRow
import dev.ludex.model.Availability
import dev.ludex.pack.ChunkRef
import dev.ludex.pack.DiceExpression
import dev.ludex.routing.Card
import dev.ludex.routing.Chip
import dev.ludex.routing.Citation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The first tests `:app` has ever had, and they are about the one thing this module owns.
 *
 * Every other module's guarantee is a property of data that a JVM test can read. The app's
 * is a property of a *screen*: that a quotation looks like a quotation and nothing else
 * does. That was checked by nobody, and it showed — every defect this project found in
 * `:app` was a rendering defect, and every one of them arrived as a review comment rather
 * than a test failure: the feed rendered behind a full-screen input, a roll control that
 * could not be tapped, no way to reach **New topic**, a quotation rule that stopped partway
 * down at accessibility text sizes.
 *
 * Robolectric runs the framework on the JVM, so these need no device. What they assert
 * against is the **semantics tree** — the same tree TalkBack reads — which is why an
 * assertion here is an assertion about what a screen-reader user is told, not merely about
 * pixels.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnswerCardTest {

    @get:Rule
    val compose = createComposeRule()

    private val ref = ChunkRef("srd:emberlight", 7)
    private val nested = ChunkRef("srd:emberlight", 8)

    private fun citation(id: Long, heading: String, page: String) = Citation(
        packUid = "srd:emberlight", chunkId = id, sourceTitle = "Emberlight", edition = "2e",
        headingPath = heading, pageLabelStart = page, pageLabelEnd = null,
        locatorScheme = "page",
    )

    private val quote = Card.Verbatim(
        ref = ref,
        kind = "rules",
        body = "A Held creature may not move away.",
        citation = citation(7, "How to Play > Seizing", "12"),
    )

    private val derived = Card.Derived(
        ref = ChunkRef("srd:emberlight", 9),
        body = "Seizing leaves the target Held.",
        chips = listOf(Chip(0, "Seizing leaves the target Held.".length, citation(7, "Seizing", "12"))),
        footer = emptyList(),
    )

    private val table = RollableTable(
        tableId = 1,
        chunkId = nested.chunkId,
        expression = DiceExpression.parse("d6")!!,
        rows = (1L..6L).map { TableRow(it, it, it, "Outcome $it") },
        label = "Complications",
    )

    /**
     * Matches a node whose accessibility description satisfies [predicate].
     *
     * The semantics tree rather than the pixels, because the description is what TalkBack
     * reads — so "does this card claim to be a quotation" is answerable, and answerable in
     * the channel where the claim is actually made.
     */
    private fun describedBy(predicate: (String) -> Boolean) =
        SemanticsMatcher("accessibility description matches") { node ->
            node.config.getOrNull(SemanticsProperties.ContentDescription)?.any(predicate) == true
        }

    // ---------------------------------------------------------------- the guarantee

    @Test
    fun `a quotation reaches the screen byte for byte`() {
        compose.setContent { AnswerCard(quote) }
        // Not "contains" -- the exact string. A view layer that re-flowed or trimmed it
        // would be editing the book, and byte-exactness that survives the database and dies
        // in the renderer is not byte-exactness.
        compose.onNodeWithText(quote.body, useUnmergedTree = true).assertIsDisplayed()
    }

    @Test
    fun `a quotation announces itself as one before it says anything else`() {
        compose.setContent { AnswerCard(quote) }
        // The card is one merged node and its description *opens* with the provenance.
        // `06-ui-spec.md` section 1.3: a screen-reader user learns this is the book's own
        // words before hearing them, rather than three nodes later or not at all.
        compose.onNode(describedBy { it.startsWith("Quotation from Emberlight") }).assertExists()
    }

    @Test
    fun `builder-written prose is never announced as a quotation`() {
        compose.setContent { AnswerCard(derived) }
        assertEquals(
            0,
            compose.onAllNodes(describedBy { "Quotation from" in it }).fetchSemanticsNodes(false).size,
            "a derived card must not describe itself as quoted",
        )
    }

    @Test
    fun `builder-written prose says what wrote it`() {
        compose.setContent { AnswerCard(derived) }
        compose.onNodeWithText(
            "Summary — written by this pack's builder, not quoted",
            useUnmergedTree = true,
        ).assertExists()
    }

    // ---------------------------------------------------------------- roll controls

    @Test
    fun `no roll control appears when the card has no table`() {
        compose.setContent { AnswerCard(quote, rolls = RollControls.None) }
        assertEquals(
            0,
            compose.onAllNodes(hasClickAction()).fetchSemanticsNodes(false).size,
            "a control that cannot do anything must not be offered",
        )
    }

    @Test
    fun `a roll control appears once per table and rolls the one it names`() {
        val rollable = quote.copy(rollableRefs = listOf(nested))
        var rolledTable: Long? = null
        compose.setContent {
            AnswerCard(
                rollable,
                rolls = RollControls(
                    tablesFor = { if (it == nested) listOf(table) else emptyList() },
                    resultFor = { _, _ -> null },
                    onRoll = { _, t -> rolledTable = t.tableId },
                ),
            )
        }
        compose.onNodeWithText("Roll: Complications").assertIsDisplayed().performClick()
        assertEquals(1L, rolledTable, "the control rolls the table it is labelled with")
    }

    @Test
    fun `a rolled outcome is quoted, and under the rolled chunk's own citation`() {
        // The nested table has its own heading and page. Rendering its outcome under the
        // parent's locator would put the book's own words beneath a pointer that does not
        // reach them.
        val own = citation(8, "How to Play > Seizing > Complications", "13")
        val rollable = quote.copy(
            rollableRefs = listOf(nested),
            rollableCitations = mapOf(nested to own),
        )
        val result = dev.ludex.capabilities.RollResult(
            expression = table.expression,
            dice = listOf(4),
            modifier = 0,
            total = 4,
            row = table.rows[3],
            chunkId = nested.chunkId,
        )
        compose.setContent {
            AnswerCard(
                rollable,
                rolls = RollControls(
                    tablesFor = { if (it == nested) listOf(table) else emptyList() },
                    resultFor = { _, _ -> result },
                    onRoll = { _, _ -> },
                ),
            )
        }
        compose.onNodeWithText("Outcome 4", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Complications", substring = true, useUnmergedTree = true)
            .onFirst().assertExists()
        compose.onNodeWithText("13", substring = true, useUnmergedTree = true).assertExists()
    }

    // ---------------------------------------------------------------- the refusal

    @Test
    fun `the refusal card names what was searched and offers the remedy`() {
        var searched = false
        compose.setContent {
            AnswerCard(
                Card.Empty(activePacks = listOf("srd:emberlight"), canSearchInactive = true),
                onSearchInactive = { searched = true },
            )
        }
        compose.onNodeWithText("Searched: srd:emberlight", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Search inactive packs too").performClick()
        assertTrue(searched, "the offer has to be takeable, or it is a claim the app cannot keep")
    }

    @Test
    fun `the refusal card offers nothing when there is nothing to offer`() {
        compose.setContent {
            AnswerCard(Card.Empty(activePacks = listOf("srd:emberlight"), canSearchInactive = false))
        }
        assertEquals(0, compose.onAllNodes(hasClickAction()).fetchSemanticsNodes(false).size)
    }

    // ---------------------------------------------------------------- stored turns

    @Test
    fun `a cache hit is not labelled as a previous session`() {
        // `AskService` returns a null answer for a cache hit by design. Rendering that the
        // same way as restored history put a *false* sentence on screen -- the cache key
        // pins the current pack bytes and contract.
        compose.setContent { StoredCard("  \" A Held creature may not move away.", Origin.CACHED) }
        compose.onNodeWithText("previous session", substring = true, useUnmergedTree = true)
            .assertDoesNotExist()
    }

    @Test
    fun `a restored turn says it is a restored turn`() {
        compose.setContent { StoredCard("  \" A Held creature may not move away.", Origin.RESTORED) }
        compose.onNodeWithText("previous session", substring = true, useUnmergedTree = true)
            .assertExists()
    }

    @Test
    fun `the model-unavailable card cites what it would have used and quotes none of it`() {
        val card = Card.ModelUnavailable(
            statement = "Found passages, but the model is not downloaded.",
            wouldHaveUsed = listOf(citation(11, "The Cinder Marches", "40")),
            downloadBytes = 1_200_000,
            availability = Availability.NotDownloaded,
        )
        compose.setContent { AnswerCard(card) }
        compose.onNodeWithText(card.statement, useUnmergedTree = true).assertExists()
        assertEquals(
            0,
            compose.onAllNodes(describedBy { "Quotation from" in it }).fetchSemanticsNodes(false).size,
            "these are redacted setting chunks; none of them may be quoted",
        )
    }
}
