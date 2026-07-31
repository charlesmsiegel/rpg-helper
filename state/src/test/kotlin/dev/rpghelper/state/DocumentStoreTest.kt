package dev.rpghelper.state

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentStoreTest {

    private val root: Path = Files.createTempDirectory("rpgdocs")
    private val db = StateDb.open(root.resolve("state.db"))
    private val documents = DocumentStore(db)

    @AfterTest
    fun cleanUp() {
        db.close()
        root.toFile().deleteRecursively()
    }

    // ---------------------------------------------------------------- keys

    @Test
    fun `normalizes case at every entry point`() {
        val doc = documents.create("Ysabeau")
        documents.putTracker(doc.documentId, "Attribute.Strength", TrackerValue.Number(3.0))
        assertEquals("attribute.strength", documents.trackers(doc.documentId).single().key)
    }

    @Test
    fun `trims and folds keys to a single form`() {
        assertEquals("hp.current", DocumentStore.normalizeKey("  HP.Current  "))
        assertEquals("virtue.keen-sight", DocumentStore.normalizeKey("Virtue.Keen-Sight"))
    }

    @Test
    fun `rejects malformed keys rather than storing them as written`() {
        // Storing one as written recreates the silence normalization exists to remove:
        // a constraint selecting it would match nothing and report nothing.
        for (bad in listOf("", "  ", "attribute..strength", "attribute.", "a b", "attr!bute")) {
            assertFailsWith<InvalidTrackerKeyException>("'$bad' should be refused") {
                DocumentStore.normalizeKey(bad)
            }
        }
    }

    @Test
    fun `setting the same key twice updates in place and keeps its position`() {
        val doc = documents.create("Ysabeau")
        documents.putTracker(doc.documentId, "a.one", TrackerValue.Number(1.0))
        documents.putTracker(doc.documentId, "b.two", TrackerValue.Number(2.0))
        documents.putTracker(doc.documentId, "a.one", TrackerValue.Number(9.0))

        val trackers = documents.trackers(doc.documentId)
        assertEquals(listOf("a.one", "b.two"), trackers.map { it.key })
        assertEquals(TrackerValue.Number(9.0), trackers.first().value)
    }

    // ---------------------------------------------------------------- presence

    @Test
    fun `zero and blank are absent, but a zero is still a stored value`() {
        assertFalse(TrackerValue.Number(0.0).isPresent)
        assertFalse(TrackerValue.Flag(false).isPresent)
        assertFalse(TrackerValue.Text("  ").isPresent)
        assertTrue(TrackerValue.Number(-1.0).isPresent)
        assertTrue(TrackerValue.Text("Reluctant archivist").isPresent)

        // The distinction range depends on: a Strength of 0 exists and violates 1-5,
        // while an absent tracker does not fire at all.
        val doc = documents.create("Ysabeau")
        documents.putTracker(doc.documentId, "attribute.strength", TrackerValue.Number(0.0))
        assertEquals(1, documents.trackers(doc.documentId).size)
    }

    // ---------------------------------------------------------------- types

    @Test
    fun `round-trips every tracker type`() {
        val doc = documents.create("Ysabeau")
        documents.putTracker(doc.documentId, "n", TrackerValue.Number(2.5))
        documents.putTracker(doc.documentId, "f", TrackerValue.Flag(true))
        documents.putTracker(doc.documentId, "t", TrackerValue.Text("concept"))

        val byKey = documents.trackers(doc.documentId).associate { it.key to it.value }
        assertEquals(TrackerValue.Number(2.5), byKey["n"])
        assertEquals(TrackerValue.Flag(true), byKey["f"])
        assertEquals(TrackerValue.Text("concept"), byKey["t"])
    }

    @Test
    fun `the schema refuses a tracker whose value column disagrees with its type`() {
        // Unlike a pack's DDL, this schema is created by the app, so its CHECK clauses are
        // worth relying on -- but only once written, which they were not at first.
        val doc = documents.create("Ysabeau")
        assertFailsWith<Exception> {
            db.execute(
                "INSERT INTO trackers (document_id, key, type, text_value, ordinal) " +
                    "VALUES (?, 'x', 'number', 'not a number', 0)",
                doc.documentId,
            )
        }
        assertFailsWith<Exception> {
            db.execute(
                "INSERT INTO trackers (document_id, key, type, number_value, flag_value, " +
                    "ordinal) VALUES (?, 'y', 'number', 1.0, 1, 0)",
                doc.documentId,
            )
        }
        assertFailsWith<Exception> {
            db.execute(
                "INSERT INTO trackers (document_id, key, type, text_value, ordinal) " +
                    "VALUES (?, 'z', 'colour', 'mauve', 0)",
                doc.documentId,
            )
        }
    }

    // ---------------------------------------------------------------- cascade

    @Test
    fun `deleting a document removes its trackers and acceptances`() {
        // Only true because PRAGMA foreign_keys is set on every connection. SQLite
        // disables enforcement by default, which would leave these orphaned forever with
        // nothing reporting it.
        val doc = documents.create("Ysabeau", rulesetId = "vtm-20")
        documents.putTracker(doc.documentId, "hp.current", TrackerValue.Number(7.0))
        documents.accept(doc.documentId, "9f2c", "vtm-20", "Storyteller allowed it")

        documents.delete(doc.documentId)

        assertNull(documents.get(doc.documentId))
        assertEquals(0, db.query("SELECT count(*) FROM trackers") { it.int(0) }.single())
        assertEquals(0, db.query("SELECT count(*) FROM accepted_violations") { it.int(0) }.single())
    }

    // ---------------------------------------------------------------- binding

    @Test
    fun `rebinding keeps acceptances, which carry the ruleset they were made under`() {
        val doc = documents.create("Ysabeau", rulesetId = "vtm-20")
        documents.accept(doc.documentId, "9f2c", "vtm-20", "seventh dot")

        documents.rebind(doc.documentId, "cyberpunk-1e")

        val kept = documents.acceptances(doc.documentId).single()
        assertEquals("vtm-20", kept.rulesetId, "it goes dormant rather than following along")
        assertEquals("seventh dot", kept.note)
        assertEquals("cyberpunk-1e", documents.get(doc.documentId)?.rulesetId)
    }

    @Test
    fun `unbinding is legitimate and keeps the document intact`() {
        val doc = documents.create("Ysabeau", rulesetId = "vtm-20")
        documents.putTracker(doc.documentId, "hp.current", TrackerValue.Number(7.0))
        documents.rebind(doc.documentId, null)

        assertNull(documents.get(doc.documentId)?.rulesetId)
        assertEquals(1, documents.trackers(doc.documentId).size, "trackers work unbound")
    }

    @Test
    fun `documents are created as drafts`() {
        assertTrue(documents.create("Ysabeau").draft)
        documents.create("Ysabeau").let {
            documents.setDraft(it.documentId, false)
            assertFalse(documents.get(it.documentId)!!.draft)
        }
    }
}
