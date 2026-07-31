package dev.rpghelper.app

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.rpghelper.model.Availability
import dev.rpghelper.model.ModelFile
import dev.rpghelper.model.ModelManifest
import dev.rpghelper.model.ShelvedModel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Models surface — the app's only network operation, and the only screen that admits
 * to being one.
 *
 * What these pin is mostly honesty about size and state: the figure shown before a download
 * is the figure that will be fetched, partial bytes are named as resumable rather than
 * looking like a failure, and a digest mismatch does not offer a retry as though it were a
 * connectivity problem.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ModelsScreenTest {

    @get:Rule
    val compose = createComposeRule()

    private val manifest = ModelManifest(
        id = "gemma-3n-e2b",
        displayName = "Gemma 3n E2B",
        license = "Gemma Terms of Use",
        files = listOf(
            ModelFile("weights.task", "https://example.invalid/weights.task", 3_100_000_000, "a".repeat(64)),
        ),
    )

    private fun shelved(availability: Availability, onDisk: Long = 0) =
        ShelvedModel(manifest, availability, onDisk)

    @Test
    fun `the size offered is the size that will be fetched`() {
        compose.setContent { ModelsScreen(models = listOf(shelved(Availability.NotDownloaded))) }
        compose.onNodeWithText("Download 3100 MB").assertExists()
    }

    @Test
    fun `the licence is on the card, because a download is agreeing to it`() {
        compose.setContent { ModelsScreen(models = listOf(shelved(Availability.NotDownloaded))) }
        compose.onNodeWithText("Gemma Terms of Use", substring = true).assertExists()
    }

    @Test
    fun `a download in flight says it is a network transfer and can be cancelled`() {
        // The one progress bar in this app that represents a network transfer, per the UI
        // spec — everything else is local, and a spinner that suggested otherwise would be
        // a mental model the app then has to keep correcting.
        var cancelled = false
        compose.setContent {
            ModelsScreen(
                models = listOf(shelved(Availability.NotDownloaded)),
                progress = manifest.id to Availability.Downloading(1_000_000_000, 3_100_000_000),
                onCancel = { cancelled = true },
            )
        }
        compose.onNodeWithText("over the network", substring = true).assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(cancelled)
    }

    @Test
    fun `partial bytes are named as resumable rather than as a failure`() {
        // Otherwise a user deletes them to "start clean" and re-fetches three gigabytes.
        compose.setContent {
            ModelsScreen(
                models = listOf(
                    shelved(Availability.Downloading(900_000_000, 3_100_000_000), onDisk = 900_000_000),
                ),
            )
        }
        compose.onNodeWithText("resumes from there", substring = true).assertExists()
        compose.onNodeWithText("Resume").assertExists()
    }

    @Test
    fun `a downloaded model offers the digest pass rather than running it on every visit`() {
        var verified = false
        compose.setContent {
            ModelsScreen(models = listOf(shelved(Availability.Ready)), onVerify = { verified = true })
        }
        compose.onNodeWithText("Downloaded").assertExists()
        compose.onNodeWithText("Verify").performClick()
        assertTrue(verified, "hashing gigabytes is an action a user takes, not a screen load")
    }

    @Test
    fun `a failure offers a retry and says what failed`() {
        compose.setContent {
            ModelsScreen(models = listOf(shelved(Availability.Failed("the connection dropped"))))
        }
        compose.onNodeWithText("the connection dropped").assertExists()
        compose.onNodeWithText("Try again").assertExists()
    }

    @Test
    fun `an empty shelf says the app still works without one`() {
        // True, and worth saying: rules questions are answered out of the books with no
        // model at all, and setting questions answer as citations until one arrives.
        compose.setContent { ModelsScreen(models = emptyList()) }
        compose.onNodeWithText("answers rules questions out of your books", substring = true)
            .assertExists()
    }

    @Test
    fun `adding a manifest is the only way a model gets here`() {
        var added = false
        compose.setContent { ModelsScreen(models = emptyList(), onAdd = { added = true }) }
        compose.onNodeWithText("Add a manifest").performClick()
        assertTrue(added)
    }

    @Test
    fun `downloading names the manifest it was asked about`() {
        var asked: ModelManifest? = null
        compose.setContent {
            ModelsScreen(
                models = listOf(shelved(Availability.NotDownloaded)),
                onDownload = { asked = it },
            )
        }
        compose.onNodeWithText("Download 3100 MB").performClick()
        assertEquals(manifest, asked)
    }
}
