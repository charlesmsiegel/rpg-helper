package dev.rpghelper.cli

import dev.rpghelper.model.ModelManifest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pinning a manifest to weights already on disk.
 *
 * `ModelManifest` refuses a placeholder digest at parse, which is only a tenable rule if
 * filling the field in is easy. This is what makes it easy, so it is held to the same
 * standard the parser is.
 */
class ManifestTest {

    private val directory = Files.createTempDirectory("weights").also {
        Files.writeString(it.resolve("model.gguf"), "pretend weights")
        Files.writeString(it.resolve("tokenizer.json"), "tok")
    }

    private fun json(baseUrl: String = "https://example.invalid/gemma") =
        manifestJson("gemma-3n-e2b", "Gemma 3n E2B", "gemma-terms", baseUrl, directory)

    @Test
    fun `every file is pinned by its own bytes`() {
        val manifest = ModelManifest.parse(json())
        assertEquals("gemma-3n-e2b", manifest.id)
        assertEquals(listOf("model.gguf", "tokenizer.json"), manifest.files.map { it.name })
        assertEquals(
            // sha256("pretend weights"), computed independently of the code under test.
            "b46e8daba03e1d333e7053378fa8301abf117b6702ac68580e67a0d4342a0cf3",
            manifest.files.first().sha256,
        )
        assertEquals(15L + 3L, manifest.totalBytes)
        assertEquals("https://example.invalid/gemma/model.gguf", manifest.files.first().url)
    }

    @Test
    fun `a base URL that is not HTTPS is refused rather than written out`() {
        // The generator is held to the parser's rules, because a generator that can emit
        // something its own parser rejects is a generator that will.
        val failure = runCatching { json("http://insecure.invalid/g") }.exceptionOrNull()
        assertTrue(failure != null)
        assertTrue(failure.message!!.contains("HTTPS"), failure.message!!)
    }

    @Test
    fun `a file name holding a quote is escaped, not concatenated`() {
        // A label carrying a quote or a backslash produces invalid JSON under hand-written
        // quoting, and the failure would land on whoever tried to use the manifest.
        val awkward = Files.createTempDirectory("awkward")
        Files.writeString(awkward.resolve("""a"b.gguf"""), "x")
        val manifest = ModelManifest.parse(
            manifestJson("id", """a "quoted" name""", "l", "https://x.invalid", awkward),
        )
        assertEquals("""a"b.gguf""", manifest.files.single().name)
        assertEquals("""a "quoted" name""", manifest.displayName)
    }

    @Test
    fun `a directory with nothing in it is an error, not an empty manifest`() {
        val empty = Files.createTempDirectory("empty")
        val failure = runCatching {
            manifestJson("id", "name", "l", "https://x.invalid", empty)
        }.exceptionOrNull()
        assertTrue(failure != null)
        assertTrue(failure.message!!.contains("no files to pin"), failure.message!!)
    }
}
