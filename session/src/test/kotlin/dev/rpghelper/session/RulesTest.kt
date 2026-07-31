package dev.rpghelper.session

import dev.rpghelper.builder.CorpusSpec
import dev.rpghelper.builder.PackBuilder
import dev.rpghelper.state.DocumentStore
import dev.rpghelper.state.StateDb
import dev.rpghelper.state.TrackerValue
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The path from an installed pack's `constraints` rows to a flag on a character sheet.
 *
 * `ConstraintParser` and `ConstraintEngine` were complete, tested, and reachable only from
 * their own tests: nothing in main source ever built a `ConstraintSet` out of a pack, so
 * the state layer could store a character and could not check one. Every test here runs
 * against the **real SRD pack**, built from `corpus/srd`, because a loader tested against
 * hand-written constraint objects would prove the engine works and leave the thing that
 * was actually missing — the wiring — untested again.
 */
class RulesTest {

    private val root: Path = Files.createTempDirectory("rules")
    private val db = StateDb.open(root.resolve("state.db"))
    private val documents = DocumentStore(db)

    @AfterTest
    fun cleanUp() {
        db.close()
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

    private fun <T> withLibrary(body: (Library) -> T): T = Library.open(listOf(pack)).use(body)

    private fun sheet(vararg trackers: Pair<String, Double>, ruleset: String? = RULESET): Long {
        val document = documents.create(title = "Ysabeau", rulesetId = ruleset)
        trackers.forEach { (key, value) ->
            documents.putTracker(document.documentId, key, TrackerValue.Number(value))
        }
        return document.documentId
    }

    // ------------------------------------------------------------------ the loader

    @Test
    fun `the SRD's own constraints load out of the active set`() {
        val set = withLibrary { Rules.constraintsFor(it, RULESET) }
        assertEquals(4, set.constraints.size, "the four the corpus declares:\n${set.dropped}")
        assertTrue(set.dropped.isEmpty(), "and none of them dropped: ${set.dropped}")
    }

    @Test
    fun `a ruleset no active pack declares loads nothing`() {
        // Not an error and not a silence: `check` turns this into `unchecked`, which is the
        // distinction the whole feature rests on.
        val set = withLibrary { Rules.constraintsFor(it, "some-other-game") }
        assertTrue(set.constraints.isEmpty())
    }

    // ------------------------------------------------------------------ evaluation

    @Test
    fun `a sheet inside the book's bounds is clean`() {
        val id = sheet("aptitude.might" to 3.0, "aptitude.wits" to 3.0)
        documents.setDraft(id, draft = false)
        val check = withLibrary { Rules.check(it, documents, id) }
        assertTrue(check.clean, "expected no violations, got ${check.flagged.map { f -> f.violation.explanation }}")
    }

    @Test
    fun `an aptitude above the book's maximum is flagged, and cited`() {
        val id = sheet("aptitude.might" to 7.0, "aptitude.wits" to 3.0)
        val check = withLibrary { Rules.check(it, documents, id) }

        val flagged = check.flagged.single { "might" in it.violation.explanation }
        assertTrue("7" in flagged.violation.explanation, flagged.violation.explanation)
        // A violation with no passage behind it is an assertion the app cannot back up.
        val citation = flagged.citation
        assertTrue(citation != null, "the rule must resolve to the passage it came from")
        assertEquals("srd:emberlight", citation.packUid)
        assertTrue(citation.sourceTitle.isNotBlank())
    }

    @Test
    fun `a minimum is not evaluated while the sheet is a draft`() {
        // A half-entered sheet violates every minimum it has not reached yet, and a user who
        // sees eleven flags on an empty document learns within one session that flags mean
        // nothing. The sum is 2, under the book's minimum of 4.
        val id = sheet("aptitude.might" to 1.0, "aptitude.wits" to 1.0)
        assertTrue(withLibrary { Rules.check(it, documents, id) }.clean, "drafts stay quiet")

        documents.setDraft(id, draft = false)
        val ready = withLibrary { Rules.check(it, documents, id) }
        assertFalse(ready.clean, "and leaving draft is what makes the sheet claim completeness")
        assertTrue(ready.flagged.any { "totals" in it.violation.explanation })
    }

    @Test
    fun `a requirement the sheet does not meet is flagged`() {
        val id = documents.create(title = "Ysabeau", rulesetId = RULESET).documentId
        documents.putTracker(id, "boon.iron-handed", TrackerValue.Flag(true))
        documents.putTracker(id, "aptitude.might", TrackerValue.Number(1.0))
        documents.putTracker(id, "aptitude.wits", TrackerValue.Number(3.0))

        val check = withLibrary { Rules.check(it, documents, id) }
        assertTrue(
            check.flagged.any { "iron-handed" in it.violation.explanation },
            "expected the requirement to fire, got ${check.flagged.map { f -> f.violation.explanation }}",
        )
    }

    // ------------------------------------------------------------------ acceptance

    @Test
    fun `an accepted violation moves out of the flags and stays on the record`() {
        // House rules are normal. What acceptance must not do is erase the fact that the
        // sheet departs from the book, which is exactly what a house rule is.
        val id = sheet("aptitude.might" to 7.0, "aptitude.wits" to 3.0)
        val violation = withLibrary { Rules.check(it, documents, id) }
            .flagged.single { "might" in it.violation.explanation }.violation
        documents.accept(id, violation.fingerprint, RULESET, "we play with a cap of 8")

        val after = withLibrary { Rules.check(it, documents, id) }
        assertTrue(after.flagged.none { "might" in it.violation.explanation }, "no longer a flag")
        val kept = after.accepted.single { "might" in it.violation.explanation }
        assertEquals("we play with a cap of 8", kept.note)
        assertFalse(after.clean, "an unflagged deviation is still a deviation")
    }

    @Test
    fun `an acceptance made under another ruleset does not follow the document`() {
        val id = sheet("aptitude.might" to 7.0, "aptitude.wits" to 3.0)
        val violation = withLibrary { Rules.check(it, documents, id) }
            .flagged.single { "might" in it.violation.explanation }.violation
        documents.accept(id, violation.fingerprint, "some-other-game", "from a different table")

        val after = withLibrary { Rules.check(it, documents, id) }
        assertTrue(
            after.flagged.any { "might" in it.violation.explanation },
            "an acceptance carries the ruleset it was made under, and goes dormant elsewhere",
        )
    }

    // ------------------------------------------------------------------ the vacuity

    @Test
    fun `an unbound document is reported as unchecked, never as clean`() {
        // The difference between "checked, and clean" and "not checked" is the whole value
        // of the check: telling a user their character is legal under rules the app cannot
        // see is worse than telling them nothing.
        val id = sheet("aptitude.might" to 99.0, ruleset = null)
        val check = withLibrary { Rules.check(it, documents, id) }
        assertTrue(check.unchecked)
        assertFalse(check.clean)
        assertTrue(check.flagged.isEmpty())
    }

    @Test
    fun `a document bound to a ruleset no active pack supplies is unchecked`() {
        val id = sheet("aptitude.might" to 99.0, ruleset = "some-other-game")
        val check = withLibrary { Rules.check(it, documents, id) }
        assertTrue(check.unchecked, "99 is outside every bound this pack declares, and it is not this pack's game")
        assertFalse(check.clean)
    }

    // ------------------------------------------------------------------ the app's path

    @Test
    fun `an installed and activated pack checks a sheet, and a deactivated one stops`() {
        // The path the app actually takes: `openActive`, not a file list -- priority as
        // `installed_packs` records it, under a lease, with the pack absent from the set
        // the moment the user switches it off. Deactivating must turn the check *off*
        // rather than turn every sheet clean, which is the failure `unchecked` exists for.
        val store = Store(root.resolve("library"))
        store.use {
            val result = it.library.install(pack)
            val installed = (result as dev.rpghelper.state.InstallResult.Installed).pack
            val id = sheet("aptitude.might" to 7.0, "aptitude.wits" to 3.0)

            assertTrue(
                Library.openActive(it.library).use { library -> Rules.check(library, documents, id) }
                    .unchecked,
                "installed but not active supplies no rules",
            )

            it.library.setActive(installed.installId, true)
            val live = Library.openActive(it.library).use { library ->
                Rules.check(library, documents, id)
            }
            assertFalse(live.unchecked, "an active pack is what makes the sheet checkable")
            assertTrue(live.flagged.any { flagged -> "might" in flagged.violation.explanation })

            it.library.setActive(installed.installId, false)
            val off = Library.openActive(it.library).use { library ->
                Rules.check(library, documents, id)
            }
            assertTrue(off.unchecked, "and switching it off is not the same as passing")
            assertFalse(off.clean)
        }
    }

    private companion object {
        const val RULESET = "emberlight-2e"
    }
}
