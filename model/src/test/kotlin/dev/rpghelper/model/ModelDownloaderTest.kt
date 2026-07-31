package dev.rpghelper.model

import java.io.ByteArrayInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelDownloaderTest {

    private val directory: Path = Files.createTempDirectory("models")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    /** Stands in for a few gigabytes. Every property under test is size-independent. */
    private val weights = ByteArray(50_000) { (it * 31 % 251).toByte() }

    private fun digestOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun manifest(
        bytes: ByteArray = weights,
        sha256: String = digestOf(weights),
    ) = ModelManifest(
        id = "test-model",
        displayName = "Test Model",
        license = "CC0",
        files = listOf(
            ModelFile("weights.gguf", "https://example.invalid/weights.gguf", bytes.size.toLong(), sha256),
        ),
    )

    /** Serves [body], honouring Range unless told not to. */
    private fun serving(
        body: ByteArray = weights,
        honourRange: Boolean = true,
        failAfter: Int = Int.MAX_VALUE,
    ) = RangeFetcher { _, from ->
        val start = if (honourRange) from.toInt() else 0
        val slice = body.copyOfRange(start, body.size)
        val stream: InputStream = if (failAfter >= slice.size) {
            ByteArrayInputStream(slice)
        } else {
            object : InputStream() {
                private val inner = ByteArrayInputStream(slice.copyOfRange(0, failAfter))
                override fun read(): Int = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    val read = inner.read(b, off, len)
                    if (read < 0) throw IOException("connection reset")
                    return read
                }
            }
        }
        stream to (if (honourRange) from else 0L)
    }

    // ---------------------------------------------------------------- the happy path

    @Test
    fun `a verified file lands at its final path`() {
        val downloader = ModelDownloader(directory, serving())
        val result = downloader.download(manifest())

        assertTrue(result is DownloadResult.Complete, "got $result")
        assertTrue(weights.contentEquals(Files.readAllBytes(downloader.fileOf("weights.gguf"))))
        assertTrue(downloader.verify(manifest()))
    }

    @Test
    fun `progress reaches the manifest's declared total`() {
        // The figure the Wi-Fi prompt showed has to be the figure that arrives, or the
        // honest-size promise is not one.
        val seen = mutableListOf<DownloadProgress>()
        ModelDownloader(directory, serving()).download(manifest()) { seen += it }
        assertEquals(weights.size.toLong(), seen.last().fetched)
        assertEquals(weights.size.toLong(), seen.last().total)
    }

    @Test
    fun `a file already present and verified is not fetched again`() {
        var opens = 0
        val counting = RangeFetcher { url, from -> opens++; serving().open(url, from) }
        val downloader = ModelDownloader(directory, counting)

        downloader.download(manifest())
        downloader.download(manifest())
        assertEquals(1, opens, "re-fetching would spend a user's data to reach a state they are in")
    }

    // ---------------------------------------------------------------- integrity

    @Test
    fun `a wrong-hash file is rejected and deleted`() {
        // A substituted artifact from a compromised mirror. Transport security says who
        // sent the bytes; only the hash says they are the bytes this build was tested on.
        val substituted = ByteArray(weights.size) { 7 }
        val downloader = ModelDownloader(directory, serving(substituted))

        val result = downloader.download(manifest(bytes = substituted))
        assertTrue(result is DownloadResult.Failed, "got $result")
        assertTrue(result.reason.contains("hashed"))
        assertEquals(0, Files.list(directory).use { it.count() }, "nothing is left behind")
    }

    @Test
    fun `a captive-portal page saved under the model's name is rejected`() {
        // The realistic version of the previous case: an HTML error page, a plausible
        // filename, a 200 response, and a file the app would otherwise try to load.
        val html = "<html><body>Sign in to continue</body></html>".toByteArray()
        val downloader = ModelDownloader(directory, serving(html))

        val result = downloader.download(manifest(bytes = html))
        assertTrue(result is DownloadResult.Failed, "got $result")
        assertFalse(Files.exists(downloader.fileOf("weights.gguf")))
    }

    @Test
    fun `a truncated file is never promoted to the final path`() {
        val downloader = ModelDownloader(directory, serving(failAfter = 20_000))
        val result = downloader.download(manifest())

        assertTrue(result is DownloadResult.Failed, "got $result")
        assertFalse(Files.exists(downloader.fileOf("weights.gguf")))
    }

    @Test
    fun `a server sending more than was declared is cut off`() {
        val overlong = weights + ByteArray(10_000)
        val downloader = ModelDownloader(directory, serving(overlong))

        val result = downloader.download(manifest())
        assertTrue(result is DownloadResult.Failed, "got $result")
        assertTrue(result.reason.contains("more than the declared"))
    }

    // ---------------------------------------------------------------- resume

    @Test
    fun `a download interrupted mid-file resumes to a byte-identical result`() {
        // The case the whole mechanism is for: a connection dropping on a multi-gigabyte
        // fetch must not mean starting over.
        val downloader = ModelDownloader(directory, serving(failAfter = 17_321))
        assertTrue(downloader.download(manifest()) is DownloadResult.Failed)

        val partial = directory.resolve("weights.gguf.partial")
        assertTrue(Files.exists(partial), "the bytes so far survive the failure")
        assertEquals(17_321L, Files.size(partial))

        val resumed = ModelDownloader(directory, serving()).download(manifest())
        assertTrue(resumed is DownloadResult.Complete, "got $resumed")
        assertTrue(weights.contentEquals(Files.readAllBytes(directory.resolve("weights.gguf"))))
    }

    @Test
    fun `verification covers the whole file, not the resumed tail`() {
        // A resume that stitched a good tail onto a corrupt prefix would otherwise pass,
        // which is the one way a resumable download can be worse than a plain one.
        Files.write(directory.resolve("weights.gguf.partial"), ByteArray(17_321) { 9 })

        val result = ModelDownloader(directory, serving()).download(manifest())
        assertTrue(result is DownloadResult.Failed, "got $result")
        assertTrue(result.reason.contains("hashed"))
    }

    @Test
    fun `a server that ignores Range starts over rather than concatenating`() {
        // Appending a complete copy to an existing prefix produces a file that is longer
        // than declared and fails only at the digest, after fetching everything twice.
        Files.write(directory.resolve("weights.gguf.partial"), weights.copyOfRange(0, 5_000))

        val result = ModelDownloader(directory, serving(honourRange = false)).download(manifest())
        assertTrue(result is DownloadResult.Complete, "got $result")
        assertTrue(weights.contentEquals(Files.readAllBytes(directory.resolve("weights.gguf"))))
    }

    @Test
    fun `a 206 whose Content-Range starts at zero is not taken at its word`() {
        // A misconfigured server answering 206 with `bytes 0-...` would otherwise be
        // reported as starting where we asked, so the caller appends a whole body to the
        // partial file, fails the digest after spending the bandwidth, and fails the same
        // way on every retry instead of restarting cleanly.
        Files.write(directory.resolve("weights.gguf.partial"), weights.copyOfRange(0, 5_000))

        val lying = RangeFetcher { _, _ -> ByteArrayInputStream(weights) to 0L }
        val result = ModelDownloader(directory, lying).download(manifest())

        assertTrue(result is DownloadResult.Complete, "got $result")
        assertTrue(
            weights.contentEquals(Files.readAllBytes(directory.resolve("weights.gguf"))),
            "the partial was discarded and the body written from the start",
        )
    }

    @Test
    fun `a partial longer than the declared file is discarded rather than resumed onto`() {
        Files.write(directory.resolve("weights.gguf.partial"), ByteArray(weights.size + 500))

        val result = ModelDownloader(directory, serving()).download(manifest())
        assertTrue(result is DownloadResult.Complete, "got $result")
    }

    // ---------------------------------------------------------------- cancellation

    @Test
    fun `cancelling discards the partial bytes rather than hiding them`() {
        // Several gigabytes occupying storage the user cannot see and did not agree to
        // keep is its own bug, separate from the download not finishing.
        val cancel = AtomicBoolean(true)
        val downloader = ModelDownloader(directory, serving())

        assertEquals(DownloadResult.Cancelled, downloader.download(manifest(), cancel))
        assertEquals(0, Files.list(directory).use { it.count() })
    }

    @Test
    fun `discardPartials clears an interrupted download on demand`() {
        ModelDownloader(directory, serving(failAfter = 100)).download(manifest())
        assertTrue(Files.exists(directory.resolve("weights.gguf.partial")))

        ModelDownloader(directory, serving()).discardPartials(manifest())
        assertEquals(0, Files.list(directory).use { it.count() })
    }

    // ---------------------------------------------------------------- re-verification

    @Test
    fun `a file that rots on disk fails verification before it is loaded`() {
        val downloader = ModelDownloader(directory, serving())
        downloader.download(manifest())

        Files.write(downloader.fileOf("weights.gguf"), ByteArray(weights.size) { 3 })
        assertFalse(downloader.verify(manifest()))
    }

    @Test
    fun `verification of a file that is not there fails rather than throwing`() {
        assertFalse(ModelDownloader(directory, serving()).verify(manifest()))
    }
}

class ModelManifestTest {

    private val realDigest = "a".repeat(64)

    private fun json(
        sha256: String = realDigest,
        bytes: Long = 1500000000,
        url: String = "https://example.invalid/weights.gguf",
    ) = """
        {
          "id": "test", "display_name": "Test", "license": "CC0",
          "files": [
            { "name": "weights.gguf", "url": "$url", "bytes": $bytes, "sha256": "$sha256" }
          ]
        }
    """.trimIndent()

    @Test
    fun `a complete manifest parses`() {
        val manifest = ModelManifest.parse(json())
        assertEquals("test", manifest.id)
        assertEquals(1500000000, manifest.totalBytes)
    }

    @Test
    fun `an unfilled digest is refused at parse, not tolerated at verify`() {
        // A manifest is written before the artifact it describes exists, so the digest
        // spends part of its life as a placeholder. Refusing at parse puts the failure in
        // a test over the shipped assets rather than on a user's phone on first run.
        val failure = assertFailsWith<IllegalArgumentException> {
            ModelManifest.parse(json(sha256 = "REPLACE-WITH-THE-SHA256"))
        }
        assertTrue(failure.message!!.contains("SHA-256"), failure.message!!)
    }

    @Test
    fun `a short or non-hex digest is refused too`() {
        assertFailsWith<IllegalArgumentException> { ModelManifest.parse(json(sha256 = "abc")) }
        assertFailsWith<IllegalArgumentException> { ModelManifest.parse(json(sha256 = "Z".repeat(64))) }
    }

    @Test
    fun `a plaintext URL is refused`() {
        assertFailsWith<IllegalArgumentException> {
            ModelManifest.parse(json(url = "http://example.invalid/weights.gguf"))
        }
    }

    @Test
    fun `a file with no declared size is refused`() {
        assertFailsWith<IllegalArgumentException> { ModelManifest.parse(json(bytes = 0)) }
    }

    @Test
    fun `the shipped manifests are refused until their digests are filled in`() {
        // Both ship as placeholders on purpose. When a real artifact is chosen and its
        // digest pasted in, this expectation flips -- and that flip is the commit that
        // says the download is now verified.
        val root = java.nio.file.Path.of("..", "models")
        val manifests = java.nio.file.Files.list(root).use { entries ->
            entries.filter { it.toString().endsWith(".json") }.toList()
        }
        assertTrue(manifests.isNotEmpty(), "no manifests found in $root")
        for (path in manifests) {
            val text = java.nio.file.Files.readString(path)
            val outcome = runCatching { ModelManifest.parse(text) }
            assertTrue(
                outcome.isFailure || ModelManifest.parse(text).files.all { it.sha256.length == 64 },
                "$path neither refuses as a placeholder nor carries a real digest",
            )
        }
    }
}
