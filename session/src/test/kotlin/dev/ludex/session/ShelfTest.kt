package dev.ludex.session

import dev.ludex.builder.CorpusSpec
import dev.ludex.builder.PackBuilder
import dev.ludex.state.InstallResult
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the Packs surface reads.
 *
 * Every fact this produces was computable before and appeared nowhere on the device: what a
 * pack corrects, and what its builder dropped or shipped unchecked. A pack that admits it
 * ships unadjudicated prose was telling only whoever ran the build.
 */
class ShelfTest {

    private val root: Path = Files.createTempDirectory("shelf")
    private val store = Store(root.resolve("library"))

    @AfterTest
    fun cleanUp() {
        store.close()
        root.toFile().deleteRecursively()
    }

    private val pack: Path by lazy {
        PackBuilder(CorpusSpec.load(corpusDirectory()), EMBEDDER)
            .buildTo(root.resolve("srd.rpgpack")).path
    }

    private fun corpusDirectory(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null) {
            val corpus = candidate.resolve("corpus/srd")
            if (Files.isDirectory(corpus)) return corpus
            candidate = candidate.parent
        }
        error("corpus/srd not found above ${Path.of("").toAbsolutePath()}")
    }

    private fun install(): Long =
        (store.library.install(pack) as InstallResult.Installed).pack.installId

    @Test
    fun `an installed pack is listed with its own build report`() {
        install()
        val shelved = Shelf.list(store.library).single()
        assertEquals("Emberlight SRD", shelved.pack.title)
        // The corpus ships a summary with no judge, which the builder drops and records.
        assertTrue(
            shelved.buildNotes.any { it.severity == "dropped" },
            "expected the builder's own account of what it dropped, got ${shelved.buildNotes}",
        )
    }

    @Test
    fun `the report is ordered worst first`() {
        install()
        val severities = Shelf.list(store.library).single().buildNotes.map { it.severity }
        val ranks = severities.map { dev.ludex.pack.BuildReport.SEVERITIES.indexOf(it) }
        assertEquals(ranks.sorted(), ranks, "unchecked outranks dropped outranks note: $severities")
    }

    @Test
    fun `an inactive pack is still listed, because that is where the toggle is`() {
        install()
        val shelved = Shelf.list(store.library).single()
        assertFalse(shelved.pack.active)
        assertTrue(shelved.buildNotes.isNotEmpty(), "and its report is readable before activation")
    }

    @Test
    fun `a pack whose file is gone is listed as unreadable rather than dropped`() {
        // Dropping it would remove the only row from which a user could uninstall the thing
        // that is broken.
        val installId = install()
        Files.delete(store.library.fileOf(installId))

        val shelved = Shelf.list(store.library).single()
        assertTrue(shelved.unreadable != null, "the row survives and says what is wrong")
        assertTrue(shelved.buildNotes.isEmpty())
    }

    @Test
    fun `a supersession renders from its own snapshot, not from the book it targets`() {
        // The whole reason the format carries a citation snapshot: this pack corrects a book
        // that is not installed, and the notice still has to say what and where.
        install()
        val notice = Shelf.list(store.library).single().supersessions.first()

        // Nothing supplying `targetSourceUid` is installed, so every field below could only
        // have come out of the supersession row itself.
        assertTrue(
            store.library.installed().none { it.packUid == notice.targetSourceUid },
            "the target must not be installed, or this test proves nothing",
        )
        assertTrue(notice.targetTitle.isNotBlank(), "the target's title travels with the row")
        val rendered = notice.render()
        assertTrue(rendered.startsWith("replaces "), rendered)
        assertTrue(notice.targetTitle in rendered, rendered)
        notice.pageLabelStart?.let { assertTrue(it in rendered, "and the locator: $rendered") }
    }

    @Test
    fun `the inactive-pack offer can actually be taken`() {
        // The refusal card says "you have installed packs that are not active; they were not
        // searched". An offer a surface prints and cannot honour is worse than one it never
        // makes -- and this is the path that honours it.
        val installId = install()
        assertTrue(
            Library.openActive(store.library).use { it.packs }.isEmpty(),
            "nothing is active, so the ordinary path has nothing to search",
        )
        Library.openAll(store.library).use { library ->
            assertEquals(
                listOf("srd:emberlight"),
                library.packs.map { it.packUid },
                "and the explicit path opens the pack the user was told about",
            )
        }
        // Not a fallback the app takes by itself: the pack is still inactive afterwards.
        assertFalse(store.library.installed().single { it.installId == installId }.active)
    }

    @Test
    fun `a correction over a book no active pack supplies is not reported as in effect`() {
        // An erratum active over a book the user never installed withdraws nothing, and
        // saying otherwise would tell them a rule was removed when it is the only text they
        // have. The SRD's own supersession targets `other:core:1e`, which is not installed.
        val installId = install()
        store.library.setActive(installId, true)

        val notices = Shelf.list(store.library).single().supersessions
        assertTrue(notices.isNotEmpty(), "the corpus declares at least one")
        assertTrue(
            notices.none { it.inEffect },
            "nothing here targets an installed book: ${notices.map { it.targetSourceUid }}",
        )
    }
}
