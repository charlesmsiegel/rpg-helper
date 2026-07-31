package dev.rpghelper.cli

import dev.rpghelper.session.BUNDLED
import dev.rpghelper.session.EMBEDDER
import dev.rpghelper.session.GATES
import dev.rpghelper.session.Library
import dev.rpghelper.session.Store

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
    fun `a name with a space or a hash becomes a usable URL`() {
        // Concatenated raw, `model v2.gguf` throws in URI.create and `weights#1.bin`
        // becomes a fragment -- so the request goes to the wrong file and *succeeds*. The
        // manifest passes its own round-trip check either way, so the failure surfaces
        // only later, in fetch-model, on someone else's machine.
        val awkward = Files.createTempDirectory("awkward-names")
        Files.writeString(awkward.resolve("model v2.gguf"), "x")
        Files.writeString(awkward.resolve("weights#1.bin"), "y")
        val manifest = ModelManifest.parse(
            manifestJson("id", "name", "l", "https://x.invalid/m", awkward),
        )

        val urls = manifest.files.associate { it.name to it.url }
        assertEquals("https://x.invalid/m/model%20v2.gguf", urls.getValue("model v2.gguf"))
        assertEquals("https://x.invalid/m/weights%231.bin", urls.getValue("weights#1.bin"))

        // And every one of them is a URI that names the path it was meant to name.
        for (file in manifest.files) {
            val uri = java.net.URI.create(file.url)
            assertEquals(null, uri.fragment, "${file.url} must not carry a fragment")
            assertTrue(uri.path.endsWith(file.name), "${uri.path} should end with ${file.name}")
        }
    }

    @Test
    fun `a non-ASCII name is percent-encoded from its UTF-8 bytes`() {
        // Tested on the function rather than through a real file, because a filesystem
        // whose encoding is ASCII cannot hold the name at all -- and the encoding rule is
        // about bytes, which is exactly what that filesystem would take away.
        assertEquals("mod%C3%A8le.gguf", urlSegment("modèle.gguf"))
        assertEquals("mod%C3%A8le.gguf", java.net.URI.create("https://x.invalid/" + urlSegment("modèle.gguf")).rawPath.removePrefix("/"))
        assertEquals("/modèle.gguf", java.net.URI.create("https://x.invalid/" + urlSegment("modèle.gguf")).path)
    }

    @Test
    fun `unreserved characters are left alone, and a plus is not a space`() {
        // URLEncoder is not used: it encodes for query strings, where a space becomes `+`.
        // In a path a `+` is a literal plus sign, so the request would ask for a file that
        // is not the one the manifest pinned.
        assertEquals("a-b._c~d9", urlSegment("a-b._c~d9"))
        assertEquals("a%20b", urlSegment("a b"))
        assertEquals("a%2Bb", urlSegment("a+b"))
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
