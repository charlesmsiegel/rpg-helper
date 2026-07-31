package dev.rpghelper.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.routing.Citation
import dev.rpghelper.session.Flagged
import dev.rpghelper.session.Passage
import dev.rpghelper.session.RuleCheck
import dev.rpghelper.session.SheetState
import dev.rpghelper.session.Validation
import dev.rpghelper.state.Document
import dev.rpghelper.state.Tracker
import dev.rpghelper.state.TrackerValue
import dev.rpghelper.state.Violation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Documents surfaces.
 *
 * What these assert is mostly one property: **the screen never claims more checking than
 * happened**. A sheet bound to a deactivated book, a sheet whose pack dropped a constraint
 * row, and a sheet that genuinely matches the book all look different, because their
 * remedies are different — and the failure they guard against is the comfortable one, a
 * green label over an evaluation that never ran.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SheetScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val document = Document(
        documentId = 1,
        title = "Ysabeau",
        campaign = "The Cinder Marches",
        rulesetId = "emberlight-2e",
        draft = false,
        extensions = null,
    )

    private val citation = Citation(
        packUid = "srd:emberlight",
        chunkId = 3,
        sourceTitle = "Emberlight",
        edition = "2e",
        headingPath = "How to Play > Aptitudes",
        pageLabelStart = "1",
        pageLabelEnd = "2",
        locatorScheme = "page",
    )

    private val violation = Violation(
        fingerprint = "f".repeat(64),
        rulesetId = "emberlight-2e",
        chunkId = 3,
        explanation = "aptitude.might is 7. This game allows 1–5.",
    )

    private fun flagged(accepted: Boolean = false, note: String? = null) =
        Flagged(violation, citation, accepted, note)

    private fun sheet(
        trackers: List<Tracker> = listOf(Tracker("aptitude.might", TrackerValue.Number(7.0), 0)),
        check: RuleCheck,
        draft: Boolean = false,
    ) = OpenSheet(document.copy(draft = draft), trackers, check)

    private fun check(
        flagged: List<Flagged> = emptyList(),
        accepted: List<Flagged> = emptyList(),
        unchecked: Boolean = false,
    ) = RuleCheck(flagged, accepted, emptyList(), unchecked)

    // ---------------------------------------------------------------- the four states

    @Test
    fun `an unchecked sheet is never described as clean`() {
        // The whole value of the check is the difference between "checked, and clean" and
        // "not checked at all".
        compose.setContent { SheetScreen(sheet(check = check(unchecked = true))) }
        compose.onNodeWithText("Not checked", substring = true).assertExists()
        compose.onNodeWithText("Matches the book").assertDoesNotExist()
    }

    @Test
    fun `a sheet that matches the book says so`() {
        compose.setContent { SheetScreen(sheet(check = check())) }
        compose.onNodeWithText("Matches the book").assertExists()
    }

    @Test
    fun `a sheet with only accepted deviations does not claim to match the book`() {
        compose.setContent { SheetScreen(sheet(check = check(accepted = listOf(flagged(true))))) }
        compose.onNodeWithText("Matches the book").assertDoesNotExist()
        compose.onNodeWithText("beyond the ones you accepted", substring = true).assertExists()
    }

    @Test
    fun `partly validated is a distinct row state from validated`() {
        // A dropped constraint row has no natural symptom at all -- unlike a missing
        // capability, which shows as a control that never appears.
        val partly = SheetState(1, "Ysabeau", null, false, Validation.PARTLY_VALIDATED, 0, 0, 2)
        assertTrue("partly validated" in describe(partly), describe(partly))
        assertTrue("2 rules could not be loaded" in describe(partly), describe(partly))

        val whole = partly.copy(validation = Validation.VALIDATED, dropped = 0)
        assertEquals("validated", describe(whole))
    }

    @Test
    fun `a draft row says why it looks clean`() {
        val draft = SheetState(1, "Ysabeau", null, true, Validation.VALIDATED, 0, 0, 0)
        assertTrue("minimums are not checked yet" in describe(draft), describe(draft))
    }

    // ---------------------------------------------------------------- violations

    @Test
    fun `a violation renders against the tracker it names`() {
        compose.setContent { SheetScreen(sheet(check = check(flagged = listOf(flagged())))) }
        compose.onNodeWithText(violation.explanation).assertExists()
        compose.onNodeWithText("aptitude.might").assertExists()
    }

    @Test
    fun `a violation offers the passage that states the rule`() {
        var asked: Violation? = null
        compose.setContent {
            SheetScreen(
                sheet(check = check(flagged = listOf(flagged()))),
                onShowPassage = { asked = it },
            )
        }
        compose.onNodeWithText("Show the rule").performClick()
        assertEquals(violation, asked, "tap-through is what makes a flag settle an argument")
    }

    @Test
    fun `the passage is the book's own words, under its own citation`() {
        compose.setContent {
            SheetScreen(
                sheet(check = check(flagged = listOf(flagged()))),
                passage = Passage(
                    ChunkRef("srd:emberlight", 3),
                    "An aptitude ranges from 1 to 5.",
                    citation,
                ),
            )
        }
        compose.onNodeWithText("An aptitude ranges from 1 to 5.", useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Emberlight", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `a rule rooted in builder prose is cited and not quoted`() {
        // A pack may root a constraint at anything, including a summary its own builder
        // wrote. Rendering that under the quotation rule would launder derived prose into
        // the book's own words beneath a real citation.
        compose.setContent {
            SheetScreen(
                sheet(check = check(flagged = listOf(flagged()))),
                passage = Passage(ChunkRef("srd:emberlight", 9), text = null, citation = citation),
            )
        }
        compose.onNodeWithText("cannot be quoted", substring = true, useUnmergedTree = true).assertExists()
        compose.onNodeWithText("Emberlight", substring = true, useUnmergedTree = true).assertExists()
    }

    @Test
    fun `accepting a violation is offered and is not the same as dismissing it`() {
        var accepted: Violation? = null
        compose.setContent {
            SheetScreen(
                sheet(check = check(flagged = listOf(flagged()))),
                onAccept = { v, _ -> accepted = v },
            )
        }
        compose.onNodeWithText("We play it this way").performClick()
        assertEquals(violation, accepted)
    }

    @Test
    fun `an accepted deviation is listed with its note and can be flagged again`() {
        var unaccepted: Violation? = null
        compose.setContent {
            SheetScreen(
                sheet(check = check(accepted = listOf(flagged(true, "our table caps at 8")))),
                onUnaccept = { unaccepted = it },
            )
        }
        compose.onNodeWithText("Deliberate deviations").assertExists()
        compose.onNodeWithText("our table caps at 8").assertExists()
        compose.onNodeWithText("Flag it again").performClick()
        assertEquals(violation, unaccepted)
    }

    // ---------------------------------------------------------------- draft

    @Test
    fun `leaving draft is offered as what it is`() {
        var draft: Boolean? = null
        compose.setContent {
            SheetScreen(sheet(check = check(), draft = true), onSetDraft = { draft = it })
        }
        compose.onNodeWithText("starts checking minimums", substring = true).performClick()
        assertEquals(false, draft)
    }

    // ---------------------------------------------------------------- the list

    @Test
    fun `documents are grouped by campaign with Unfiled for the rest`() {
        compose.setContent {
            DocumentList(
                sheets = listOf(
                    SheetState(1, "Ysabeau", "The Cinder Marches", false, Validation.VALIDATED, 0, 0, 0),
                    SheetState(2, "Wren", null, false, Validation.UNBOUND, 0, 0, 0),
                ),
            )
        }
        compose.onNodeWithText("The Cinder Marches").assertExists()
        compose.onNodeWithText("Unfiled").assertExists()
    }

    @Test
    fun `a row with violations shows the count`() {
        compose.setContent {
            DocumentList(
                sheets = listOf(
                    SheetState(1, "Ysabeau", null, false, Validation.VALIDATED, 3, 1, 0),
                ),
            )
        }
        compose.onNodeWithText("3 violations").assertExists()
    }

    @Test
    fun `a document can be exported, because a file is how it moves`() {
        // There is no sync -- local is the premise -- so the file *is* the transfer, and a
        // surface that cannot write one is a surface that traps the user's character in it.
        var exported = false
        compose.setContent { SheetScreen(sheet(check = check()), onExport = { exported = true }) }
        compose.onNodeWithText("Export").performClick()
        assertTrue(exported)
    }

    @Test
    fun `the list offers import beside creation`() {
        var imported = false
        compose.setContent { DocumentList(sheets = emptyList(), onImport = { imported = true }) }
        compose.onNodeWithText("Import a file").performClick()
        assertTrue(imported)
    }

    @Test
    fun `an empty list explains what a document is for`() {
        compose.setContent { DocumentList(sheets = emptyList()) }
        compose.onNodeWithText("No documents yet", substring = true).assertExists()
    }
}
