package dev.ludex.state

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelRegistryTest {

    private val root: Path = Files.createTempDirectory("models")
    private var db = StateDb.open(root.resolve("state.db"))
    private var tick = 0
    private var registry = ModelRegistry(db) { "2026-07-31T00:00:%02dZ".format(tick++) }

    @AfterTest
    fun cleanUp() {
        db.close()
        root.toFile().deleteRecursively()
    }

    private val gemma = "gemma-3n-e2b-q4"

    @Test
    fun `a download the user started survives a restart`() {
        // The downloader resumes from bytes on disk; what it cannot know from the disk is
        // what the user agreed to. A model half-fetched when the app was killed is a
        // download they expect to see continue.
        registry.beginDownload(gemma, ModelRole.GENERATIVE, 2_000_000_000)
        registry.progress(gemma, 750_000_000)

        db.close()
        db = StateDb.open(root.resolve("state.db"))
        registry = ModelRegistry(db)

        val status = registry.statusOf(gemma)!!
        assertEquals(ModelState.DOWNLOADING, status.state)
        assertEquals(750_000_000L, status.bytesFetched)
        assertEquals(2_000_000_000L, status.bytesTotal)
    }

    @Test
    fun `progress for a model nobody started is ignored`() {
        // Either a stale callback from a cancelled fetch or a bug. Inventing a row from it
        // makes the table say the user agreed to something they did not.
        registry.progress("never-requested", 5_000)
        assertNull(registry.statusOf("never-requested"))
    }

    @Test
    fun `progress does not resurrect a model that already failed`() {
        registry.beginDownload(gemma, ModelRole.GENERATIVE, 1_000)
        registry.failed(gemma, ModelRole.GENERATIVE, "the disk filled")
        registry.progress(gemma, 900)
        assertEquals(ModelState.FAILED, registry.statusOf(gemma)!!.state)
    }

    @Test
    fun `a failure keeps its reason, because the card shows it`() {
        // "Could not be loaded" with no cause tells a user only that they cannot fix it.
        registry.beginDownload(gemma, ModelRole.GENERATIVE, 1_000)
        registry.progress(gemma, 400)
        registry.failed(gemma, ModelRole.GENERATIVE, "checksum mismatch after resume")

        val status = registry.statusOf(gemma)!!
        assertEquals(ModelState.FAILED, status.state)
        assertEquals("checksum mismatch after resume", status.reason)
        assertEquals(400L, status.bytesFetched, "and what had arrived before it failed")
    }

    @Test
    fun `failed is distinct from absent, and stays distinct`() {
        // Someone who already chose to download must not be asked to choose again, and
        // someone who never chose must not be told something went wrong.
        assertNull(registry.statusOf("asr-small"))
        registry.failed("asr-small", ModelRole.ASR, "unpacking failed")
        assertEquals(ModelState.FAILED, registry.statusOf("asr-small")!!.state)
    }

    @Test
    fun `ready records the whole file as fetched`() {
        registry.beginDownload(gemma, ModelRole.GENERATIVE, 1_000)
        registry.ready(gemma, ModelRole.GENERATIVE, 1_000)
        val status = registry.statusOf(gemma)!!
        assertEquals(ModelState.READY, status.state)
        assertEquals(status.bytesTotal, status.bytesFetched)
        assertNull(status.reason)
    }

    @Test
    fun `all three roles coexist and are listed`() {
        registry.ready("bge-small", ModelRole.EMBEDDER, 100)
        registry.ready("whisper-tiny", ModelRole.ASR, 200)
        registry.ready(gemma, ModelRole.GENERATIVE, 300)
        assertEquals(
            listOf(ModelRole.EMBEDDER, ModelRole.GENERATIVE, ModelRole.ASR),
            registry.all().map { it.role },
            "ordered by model_id: bge-small, gemma, whisper",
        )
    }

    @Test
    fun `forgetting a model forgets the agreement with it`() {
        registry.ready(gemma, ModelRole.GENERATIVE, 1_000)
        registry.forget(gemma)
        assertNull(registry.statusOf(gemma))
    }

    @Test
    fun `a reason containing a colon survives the round trip`() {
        // The state column carries both, so the encoding has to hold for a message that
        // looks like the encoding.
        registry.failed(gemma, ModelRole.GENERATIVE, "HTTP 503: try again later")
        assertEquals("HTTP 503: try again later", registry.statusOf(gemma)!!.reason)
    }

    @Test
    fun `a download of no bytes is refused rather than recorded`() {
        val failure = runCatching {
            registry.beginDownload(gemma, ModelRole.GENERATIVE, 0)
        }.exceptionOrNull()
        assertTrue(failure != null)
    }

    @Test
    fun `the role column holds what the DDL's check constraint permits`() {
        // Written as the schema spells it, or SQLite refuses the row -- which would surface
        // as a download that cannot be recorded at all.
        for (role in ModelRole.entries) {
            registry.ready("model-${role.stored}", role, 1)
            assertEquals(role, registry.statusOf("model-${role.stored}")!!.role)
        }
    }
}
