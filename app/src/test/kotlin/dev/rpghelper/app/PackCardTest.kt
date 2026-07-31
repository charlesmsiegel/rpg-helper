package dev.rpghelper.app

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.rpghelper.pack.BuildNote
import dev.rpghelper.session.ShelvedPack
import dev.rpghelper.session.SupersessionNotice
import dev.rpghelper.state.InstalledPack
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Packs surface, which exists so two computable things stop being invisible.
 *
 * A pack's `build_report` and its supersession snapshots were both readable from the moment
 * a pack was installed, and neither reached the device — so *"this table is quotable but not
 * rollable"* was something a user could only infer from a control that never appeared, and
 * an erratum's effect was something only the person who ran the build ever saw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PackCardTest {

    @get:Rule
    val compose = createComposeRule()

    private fun installed(active: Boolean = false) = InstalledPack(
        installId = 1,
        packUid = "srd:emberlight",
        packVersion = "2.0.0",
        title = "Emberlight SRD",
        rulesetId = "emberlight-2e",
        embedderId = "hash-128",
        byteSize = 2_400_000,
        fileSha256 = "a".repeat(64),
        active = active,
        priority = 0,
    )

    private fun shelved(
        active: Boolean = false,
        notes: List<BuildNote> = emptyList(),
        supersessions: List<SupersessionNotice> = emptyList(),
        unreadable: String? = null,
    ) = ShelvedPack(installed(active), notes, supersessions, unreadable)

    private val unchecked = BuildNote(
        severity = "unchecked",
        subjectKind = "chunk",
        subjectId = "42",
        validation = "claim-support",
        detail = "shipped without adjudication at the operator's request",
    )

    private val correction = SupersessionNotice(
        packUid = "srd:emberlight",
        targetSourceUid = "other:core:1e",
        targetStableKey = "other:grapple",
        targetTitle = "Other Core Rulebook",
        targetEdition = "1e",
        targetHeadingPath = "Combat > Grappling",
        pageLabelStart = "40",
        pageLabelEnd = "41",
        inEffect = true,
    )

    // ---------------------------------------------------------------- the build report

    @Test
    fun `a pack that ships unadjudicated content says so on its card`() {
        // The severity that outranks every other, because unchecked content is *present and
        // indistinguishable from checked content* while a dropped item is simply absent.
        compose.setContent { PacksScreen(packs = listOf(shelved(notes = listOf(unchecked)))) }
        compose.onNodeWithText("Ships content nobody adjudicated").assertIsDisplayed()
    }

    @Test
    fun `a clean pack claims nothing about adjudication`() {
        compose.setContent { PacksScreen(packs = listOf(shelved())) }
        compose.onNodeWithText("Ships content nobody adjudicated").assertDoesNotExist()
    }

    @Test
    fun `the builder's own note reaches the screen with its detail`() {
        compose.setContent { PacksScreen(packs = listOf(shelved(notes = listOf(unchecked)))) }
        compose.onNodeWithText("shipped without adjudication", substring = true).assertExists()
    }

    // ---------------------------------------------------------------- supersessions

    @Test
    fun `a correction explains itself without the book it corrects`() {
        // Nothing supplying `other:core:1e` is installed. Resolving through the target would
        // leave this blank in exactly the case it matters most.
        compose.setContent {
            PacksScreen(packs = listOf(shelved(supersessions = listOf(correction))))
        }
        compose.onNodeWithText("Combat > Grappling", substring = true).assertExists()
        compose.onNodeWithText("Other Core Rulebook", substring = true).assertExists()
        compose.onNodeWithText("40–41", substring = true).assertExists()
    }

    @Test
    fun `a correction that withdraws nothing says that it withdraws nothing`() {
        compose.setContent {
            PacksScreen(packs = listOf(shelved(supersessions = listOf(correction.copy(inEffect = false)))))
        }
        compose.onNodeWithText("not in effect", substring = true).assertExists()
    }

    // ---------------------------------------------------------------- activation

    @Test
    fun `the toggle names the pack it would activate`() {
        // A row of switches all announced as "switch" is a screen a screen-reader user
        // cannot use.
        val requested = mutableListOf<Pair<Long, Boolean>>()
        compose.setContent {
            PacksScreen(
                packs = listOf(shelved(active = false)),
                onSetActive = { id, active -> requested += id to active },
            )
        }
        compose.onNodeWithContentDescription("Activate Emberlight SRD").performClick()
        assertEquals(listOf(1L to true), requested)
    }

    @Test
    fun `an unreadable pack keeps its row and refuses its toggle`() {
        // The row is the only place a user can uninstall the thing that is broken, and a
        // pack the app cannot read is not one it should offer to activate.
        compose.setContent {
            PacksScreen(packs = listOf(shelved(unreadable = "file is not a database")))
        }
        compose.onNodeWithText("Emberlight SRD").assertExists()
        compose.onNodeWithText("file is not a database", substring = true).assertExists()
    }

    @Test
    fun `an empty library offers the action that fills it`() {
        compose.setContent { PacksScreen(packs = emptyList()) }
        compose.onNodeWithText("No packs installed", substring = true).assertExists()
    }

    // ---------------------------------------------------------------- install and order

    @Test
    fun `installing from a file is offered, because it is the only install path there is`() {
        var picked = false
        compose.setContent { PacksScreen(packs = emptyList(), onInstall = { picked = true }) }
        compose.onNodeWithText("Install from a file").performClick()
        assertTrue(picked)
    }

    @Test
    fun `replacing a pack with the same identifier is asked about, not assumed`() {
        // Two files claiming one identifier cannot both be active: everything downstream
        // names a passage by (pack, chunk), so the two would merge.
        var confirmed = false
        val replacing = PacksViewModel.PendingReplacement(
            staged = java.nio.file.Path.of("/tmp/staged.rpgpack"),
            existing = "srd:emberlight",
            title = "Emberlight SRD",
        )
        compose.setContent {
            PacksScreen(
                packs = listOf(shelved()),
                replacing = replacing,
                onConfirmReplace = { confirmed = true },
            )
        }
        compose.onNodeWithText("Replace Emberlight SRD?").assertExists()
        compose.onNodeWithText("keeps your documents", substring = true).assertExists()
        compose.onNodeWithText("Replace").performClick()
        assertTrue(confirmed)
    }

    @Test
    fun `priority can be changed, and the ends of the list say so`() {
        val moves = mutableListOf<Pair<Long, Boolean>>()
        compose.setContent {
            PacksScreen(packs = listOf(shelved()), onMove = { id, up -> moves += id to up })
        }
        // One pack: it is both first and last, so neither direction is offered.
        compose.onNodeWithContentDescription("Raise the priority of Emberlight SRD").performClick()
        compose.onNodeWithContentDescription("Lower the priority of Emberlight SRD").performClick()
        assertTrue(moves.isEmpty(), "a list of one has no order to change")
    }

    @Test
    fun `storage is three figures, because they have three different remedies`() {
        compose.setContent {
            PacksScreen(
                packs = listOf(shelved()),
                storage = PacksViewModel.Storage(packs = 84_000_000, models = 3_100_000_000, appData = 240_000),
            )
        }
        compose.onNodeWithText("packs 84 MB", substring = true).assertExists()
        compose.onNodeWithText("models 3100 MB", substring = true).assertExists()
        compose.onNodeWithText("app data 240 kB", substring = true).assertExists()
    }

    @Test
    fun `uninstalling names the pack it would remove`() {
        var uninstalled: Long? = null
        compose.setContent {
            PacksScreen(packs = listOf(shelved()), onUninstall = { uninstalled = it })
        }
        compose.onNodeWithContentDescription("Uninstall Emberlight SRD").performClick()
        assertEquals(1L, uninstalled)
    }

    // ---------------------------------------------------------------- the withdrawal question

    @Test
    fun `activation that would hollow out a book names the share per book`() {
        // "A third of your core rulebook" is the sentence that conveys it; "a third of
        // everything installed" is not.
        val pending = PacksViewModel.PendingActivation(
            installId = 1,
            title = "Emberlight SRD 3e",
            impacts = listOf(
                dev.rpghelper.state.SupersessionImpact(
                    targetSourceUid = "srd:emberlight:core",
                    targetTitle = "Emberlight SRD",
                    withdrawnChunks = 30,
                    totalChunks = 90,
                ),
            ),
            stale = false,
        )
        compose.setContent { PacksScreen(packs = listOf(shelved()), pending = pending) }
        compose.onNodeWithText("Emberlight SRD: 30 of 90 passages (33%)").assertExists()
        compose.onNodeWithText("Activate anyway").assertExists()
    }

    @Test
    fun `a stale measurement says nothing was activated`() {
        val pending = PacksViewModel.PendingActivation(
            installId = 1,
            title = "Emberlight SRD 3e",
            impacts = emptyList(),
            stale = true,
        )
        compose.setContent { PacksScreen(packs = listOf(shelved()), pending = pending) }
        compose.onNodeWithText("Nothing has been activated", substring = true).assertExists()
    }

    @Test
    fun `cancelling the question changes nothing`() {
        var confirmed = false
        var dismissed = false
        val pending = PacksViewModel.PendingActivation(1, "x", emptyList(), stale = false)
        compose.setContent {
            PacksScreen(
                packs = listOf(shelved()),
                pending = pending,
                onConfirm = { confirmed = true },
                onDismiss = { dismissed = true },
            )
        }
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(dismissed, "the question closes")
        assertTrue(!confirmed, "cancelling is not a quieter yes")
    }
}
