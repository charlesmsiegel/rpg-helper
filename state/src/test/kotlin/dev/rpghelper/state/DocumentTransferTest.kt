package dev.rpghelper.state

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A character sheet outlives the software that checks it.
 *
 * Which is the whole argument for this file existing: losing a validator is an
 * inconvenience, and losing or silently rewriting a character is not recoverable. So the
 * tests here are mostly about what import **refuses** — a partial import is worse than a
 * refusal, because the part that did not arrive is invisible.
 */
class DocumentTransferTest {

    private val root: Path = Files.createTempDirectory("transfer")
    private val db = StateDb.open(root.resolve("state.db"))
    private val documents = DocumentStore(db)

    @AfterTest
    fun cleanUp() {
        db.close()
        root.toFile().deleteRecursively()
    }

    private fun ysabeau(): Long {
        val document = documents.create(
            title = "Ysabeau",
            campaign = "The Cinder Marches",
            rulesetId = "emberlight-2e",
            draft = false,
        )
        documents.putTracker(document.documentId, "aptitude.might", TrackerValue.Number(3.0))
        documents.putTracker(document.documentId, "condition.shaken", TrackerValue.Flag(false))
        documents.putTracker(document.documentId, "concept", TrackerValue.Text("Reluctant archivist"))
        documents.accept(document.documentId, "9".repeat(64), "emberlight-2e", "the Warden allowed it")
        return document.documentId
    }

    @Test
    fun `a document survives a round trip with its trackers, binding, and rulings`() {
        val exported = DocumentTransfer.export(documents, ysabeau())
        val imported = DocumentTransfer.import(documents, exported)

        val document = documents.get(imported.documentId)!!
        assertEquals("Ysabeau", document.title)
        assertEquals("The Cinder Marches", document.campaign)
        assertEquals("emberlight-2e", document.rulesetId)
        assertFalse(document.draft)

        assertEquals(
            listOf(
                "aptitude.might" to TrackerValue.Number(3.0),
                "condition.shaken" to TrackerValue.Flag(false),
                "concept" to TrackerValue.Text("Reluctant archivist"),
            ),
            documents.trackers(imported.documentId).map { it.key to it.value },
            "including the order the user put them in",
        )
        assertEquals(
            listOf(AcceptedViolation("9".repeat(64), "emberlight-2e", "the Warden allowed it")),
            documents.acceptances(imported.documentId),
            "a ruling is exactly the sort of thing that is lost and missed",
        )
    }

    @Test
    fun `import mints a new identity rather than claiming the file's`() {
        // A file is a copy. Two devices holding one file hold two documents, and there is no
        // sync to reconcile them -- so a shared identity would only produce a conflict
        // nothing can resolve.
        val original = ysabeau()
        val imported = DocumentTransfer.import(documents, DocumentTransfer.export(documents, original))
        assertTrue(imported.documentId != original)
        assertEquals(2, documents.all().size, "and the original is untouched")
        assertTrue("document_id" !in DocumentTransfer.export(documents, original))
    }

    @Test
    fun `an acceptance keeps its own ruleset rather than the document's current binding`() {
        // Acceptances survive rebinding, so a document bound to game B may carry dormant
        // acceptances from game A. Stamping every row with the current binding on import
        // would lose A's and wrongly activate them against B.
        val id = ysabeau()
        documents.accept(id, "a".repeat(64), "some-other-game", "from an older chronicle")
        documents.rebind(id, "some-third-game")

        val imported = DocumentTransfer.import(documents, DocumentTransfer.export(documents, id))
        assertEquals(
            setOf("emberlight-2e", "some-other-game"),
            documents.acceptances(imported.documentId).map { it.rulesetId }.toSet(),
        )
    }

    // ---------------------------------------------------------------- normalization

    @Test
    fun `keys are normalized on import, because import is an entry point`() {
        // The first way a key nobody typed can arrive. `Attribute.Strength` beside a
        // constraint selecting `attribute.strength` matches nothing and reports nothing.
        val file = """
            {"format":"rpgdoc/1","document":{"title":"Ysabeau","draft":true},
             "trackers":[{"key":"Aptitude.MIGHT","type":"number","value":3}],
             "accepted_violations":[]}
        """.trimIndent()
        val imported = DocumentTransfer.import(documents, file)
        assertEquals("aptitude.might", documents.trackers(imported.documentId).single().key)
    }

    @Test
    fun `a file whose keys collide once normalized is refused rather than merged`() {
        val file = """
            {"format":"rpgdoc/1","document":{"title":"Ysabeau","draft":true},
             "trackers":[{"key":"aptitude.might","type":"number","value":3},
                         {"key":"Aptitude.Might","type":"number","value":5}],
             "accepted_violations":[]}
        """.trimIndent()
        val failure = assertFailsWith<DocumentTransferException> {
            DocumentTransfer.import(documents, file)
        }
        assertTrue("discard" in failure.message!!, failure.message!!)
        assertTrue(documents.all().isEmpty(), "and nothing was written")
    }

    @Test
    fun `a malformed key is a refusal, not a silent pass`() {
        val file = """
            {"format":"rpgdoc/1","document":{"title":"Ysabeau","draft":true},
             "trackers":[{"key":"aptitude..might","type":"number","value":3}],
             "accepted_violations":[]}
        """.trimIndent()
        assertFailsWith<DocumentTransferException> { DocumentTransfer.import(documents, file) }
        assertTrue(documents.all().isEmpty())
    }

    // ---------------------------------------------------------------- types

    @Test
    fun `a declared type that the value contradicts is refused rather than coerced`() {
        // `"3"` read as a number makes a text tracker satisfy a numeric rule; `false` read
        // as text becomes the string "false", which is present and non-blank and therefore
        // *true* under presence semantics.
        for (bad in listOf(
            """{"key":"aptitude.might","type":"number","value":"3"}""",
            """{"key":"condition.shaken","type":"flag","value":"false"}""",
            """{"key":"concept","type":"text","value":false}""",
            """{"key":"concept","type":"colour","value":"red"}""",
        )) {
            val file = """
                {"format":"rpgdoc/1","document":{"title":"x","draft":true},
                 "trackers":[$bad],"accepted_violations":[]}
            """.trimIndent()
            assertFailsWith<DocumentTransferException>(bad) {
                DocumentTransfer.import(documents, file)
            }
        }
        assertTrue(documents.all().isEmpty(), "and no half-imported documents are left behind")
    }

    @Test
    fun `a file from another format version is refused by name`() {
        val file = """{"format":"rpgdoc/2","document":{"title":"x"},"trackers":[]}"""
        val failure = assertFailsWith<DocumentTransferException> {
            DocumentTransfer.import(documents, file)
        }
        assertTrue("rpgdoc/2" in failure.message!! && "rpgdoc/1" in failure.message!!)
    }

    @Test
    fun `something that is not a document at all is refused`() {
        assertFailsWith<DocumentTransferException> { DocumentTransfer.import(documents, "{not json") }
        assertFailsWith<DocumentTransferException> { DocumentTransfer.import(documents, "{}") }
    }

    // ---------------------------------------------------------------- forward compatibility

    @Test
    fun `unknown top-level keys survive a round trip`() {
        // A document exported by a later version and re-imported by an earlier one must not
        // quietly lose data -- the user's copy is the only copy.
        val file = """
            {"format":"rpgdoc/1","portrait":"data:image/png;base64,AAAA",
             "document":{"title":"Ysabeau","draft":true},
             "trackers":[],"accepted_violations":[]}
        """.trimIndent()
        val imported = DocumentTransfer.import(documents, file)
        val again = DocumentTransfer.export(documents, imported.documentId, preservedFrom = file)
        assertTrue("portrait" in again, again)
        assertTrue("data:image/png;base64,AAAA" in again)
    }

    @Test
    fun `unknown keys survive an export made long after the file is gone`() {
        // The round-trip contract only meant something while the user still had the file
        // they imported. A document exported by a later build, imported here, and exported
        // again next week -- with nothing to pass as `preservedFrom` -- lost every field
        // this build cannot read, silently, from the only copy that existed.
        val file = """
            {"format":"rpgdoc/1","portrait":"data:image/png;base64,AAAA","tags":["archivist"],
             "document":{"title":"Ysabeau","draft":true},
             "trackers":[],"accepted_violations":[]}
        """.trimIndent()
        val imported = DocumentTransfer.import(documents, file)

        val later = DocumentTransfer.export(documents, imported.documentId)
        assertTrue("portrait" in later, later)
        assertTrue("data:image/png;base64,AAAA" in later, later)
        assertTrue("archivist" in later, "including arrays, not only scalars: $later")
    }

    @Test
    fun `a malformed acceptance is dropped by name rather than stored`() {
        val file = """
            {"format":"rpgdoc/1","document":{"title":"x","draft":true},"trackers":[],
             "accepted_violations":[{"fingerprint":"not-a-digest","ruleset_id":"e"},
                                    {"fingerprint":"${"b".repeat(64)}"}]}
        """.trimIndent()
        val imported = DocumentTransfer.import(documents, file)
        assertEquals(2, imported.droppedAcceptances.size, imported.droppedAcceptances.toString())
        assertTrue(documents.acceptances(imported.documentId).isEmpty())
    }
}
