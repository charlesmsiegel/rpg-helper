package dev.rpghelper.model

import java.io.ByteArrayInputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The shelf the app's Models surface reads.
 *
 * No manifest ships in the binary, and that is a consequence rather than an omission:
 * `ModelManifest.parse` refuses a placeholder digest, so a manifest cannot exist before the
 * artifact it pins does. The trust chain is one link long — a digest somebody computed from
 * the bytes that will actually be fetched — and these tests are about not weakening it.
 */
class ModelLibraryTest {

    private val root: Path = Files.createTempDirectory("modellib")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private val weights = ByteArray(4_096) { (it * 17 % 251).toByte() }

    private fun digestOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun manifestText(
        id: String = "gemma-test",
        name: String = "weights.gguf",
        bytes: Long = weights.size.toLong(),
        sha256: String = digestOf(weights),
    ) = """
        {
          "id": "$id",
          "display_name": "Gemma (test)",
          "license": "Gemma Terms of Use",
          "files": [
            {
              "name": "$name",
              "url": "https://example.invalid/$name",
              "bytes": $bytes,
              "sha256": "$sha256"
            }
          ]
        }
    """.trimIndent()

    /** Serves the weights, honouring Range. Standing in for a few gigabytes over the wire. */
    private val fetcher = RangeFetcher { _, from ->
        ByteArrayInputStream(weights.copyOfRange(from.toInt(), weights.size)) to from
    }

    private fun library() = ModelLibrary(root, fetcher)

    @Test
    fun `an added manifest is listed and survives a restart`() {
        library().add(manifestText())
        val shelved = library().list().single()
        assertEquals("gemma-test", shelved.manifest.id)
        assertEquals(Availability.NotDownloaded, shelved.availability)
    }

    @Test
    fun `a manifest with a placeholder digest is refused at the door`() {
        // The failure this whole mechanism exists to prevent: several gigabytes fetched and
        // nothing verified. It has to land here, not on a user's phone on first run.
        assertFailsWith<IllegalArgumentException> {
            library().add(manifestText(sha256 = "TODO"))
        }
        assertTrue(library().list().isEmpty(), "and nothing is stored")
    }

    @Test
    fun `an id that names a path cannot write outside the manifest directory`() {
        // `../../state.db` is a legal JSON string, and an id becomes a filename.
        val library = library()
        library.add(manifestText(id = "../../escape"))
        assertEquals(listOf("escape"), library.list().map { it.manifest.id.substringAfterLast('/') })
        assertTrue(
            Files.list(root.resolve("manifests")).use { it.count() } == 1L,
            "one file, and it is inside the directory",
        )
    }

    @Test
    fun `a downloaded model reads as ready without hashing it again`() {
        val library = library()
        val manifest = library.add(manifestText())
        assertTrue(library.download(manifest) is DownloadResult.Complete)

        assertEquals(Availability.Ready, library.list().single().availability)
        assertTrue(library.verify(manifest), "and the digest pass agrees when asked")
    }

    @Test
    fun `a file of the right length but the wrong bytes is ready to the list and fails verify`() {
        // The trade this class makes deliberately: presence and declared length on a list
        // screen, digests on demand and unconditionally before a download reports Complete.
        // Stating it here so a future change that "fixes" the list into hashing 4 GB per
        // visit has to argue with a test.
        val library = library()
        val manifest = library.add(manifestText())
        library.download(manifest)
        Files.write(library.fileOf(manifest, "weights.gguf"), ByteArray(weights.size) { 7 })

        assertEquals(Availability.Ready, library.list().single().availability)
        assertFalse(library.verify(manifest), "the digest pass is what catches this")
    }

    @Test
    fun `two manifests naming one file do not share it`() {
        // `weights.gguf` is what almost every quantized model calls its artifact. In a flat
        // directory the second download replaced the first's verified bytes, deleting either
        // deleted the other's, and where the declared lengths matched the list went on
        // reporting both as ready -- so the second model answered with the first's weights.
        val library = library()
        val first = library.add(manifestText(id = "model-a"))
        val second = library.add(manifestText(id = "model-b"))
        library.download(first)

        assertEquals(
            Availability.NotDownloaded,
            library.availability(second),
            "one model's artifact is not the other's",
        )
        assertTrue(library.fileOf(first, "weights.gguf") != library.fileOf(second, "weights.gguf"))

        library.removeFiles(second)
        assertTrue(library.verify(first), "and deleting one does not delete the other")
    }

    @Test
    fun `removing a manifest keeps the gigabytes, removing the files removes them`() {
        val library = library()
        val manifest = library.add(manifestText())
        library.download(manifest)

        library.remove(manifest.id)
        assertTrue(library.list().isEmpty())
        assertTrue(Files.exists(library.fileOf(manifest, "weights.gguf")), "forgetting is not deleting")

        library.add(manifestText())
        library.removeFiles(manifest)
        assertFalse(Files.exists(library.fileOf(manifest, "weights.gguf")))
        assertEquals(Availability.NotDownloaded, library.list().single().availability)
    }

    @Test
    fun `two ids that fold to the same filename stay distinct`() {
        // `vendor/a` and `vendor?a` both fold to `vendor_a`. Without a digest suffix the
        // second manifest overwrote the first and shared its artifact directory, so deleting
        // either model's files deleted the other's.
        val library = library()
        library.add(manifestText(id = "vendor/a"))
        library.add(manifestText(id = "vendor?a"))

        assertEquals(2, library.list().size, "two manifests, two entries")
        assertEquals(
            2,
            library.list().map { library.fileOf(it.manifest, "weights.gguf") }.toSet().size,
            "and two artifact directories",
        )
    }

    @Test
    fun `cancelling gives back every byte this call fetched, not only the last one`() {
        // A multi-file manifest cancelled during file two used to leave all of file one on
        // disk: the cancellation contract says it costs no storage, and gigabytes are
        // exactly the case where that promise matters.
        val second = ByteArray(2_048) { (it * 7 % 251).toByte() }
        val text = """
            {
              "id": "two-files", "display_name": "Two", "license": "CC0",
              "files": [
                {"name":"a.gguf","url":"https://example.invalid/a","bytes":${weights.size},
                 "sha256":"${digestOf(weights)}"},
                {"name":"b.gguf","url":"https://example.invalid/b","bytes":${second.size},
                 "sha256":"${digestOf(second)}"}
              ]
            }
        """.trimIndent()
        val cancel = java.util.concurrent.atomic.AtomicBoolean(false)
        val library = ModelLibrary(root, RangeFetcher { url, from ->
            val body = if (url.endsWith("/a")) weights else second
            // Cancel once the first file is done and the second is being asked for.
            if (url.endsWith("/b")) cancel.set(true)
            ByteArrayInputStream(body.copyOfRange(from.toInt(), body.size)) to from
        })
        val manifest = library.add(text)

        assertEquals(DownloadResult.Cancelled, library.download(manifest, cancel))
        assertFalse(
            Files.exists(library.fileOf(manifest, "a.gguf")),
            "the file finished before the cancel is this call's to remove",
        )
    }

    @Test
    fun `a manifest file that no longer parses drops out rather than taking the list down`() {
        val library = library()
        library.add(manifestText())
        Files.writeString(root.resolve("manifests/broken.json"), "{not json")

        assertEquals(1, library.list().size, "the good one still lists")
    }
}
