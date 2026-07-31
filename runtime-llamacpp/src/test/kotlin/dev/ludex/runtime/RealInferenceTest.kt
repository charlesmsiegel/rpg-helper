package dev.ludex.runtime

import dev.ludex.model.ModelFile
import dev.ludex.model.ModelManifest
import dev.ludex.pack.ChunkRef
import dev.ludex.model.RedactedChunk
import dev.ludex.model.Turn
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Real weights through the real seam — **gated on a model being present**.
 *
 * Set `RPG_TEST_GGUF` to a path and these run genuine llama.cpp inference; unset, they pass
 * vacuously and say so. Gated rather than downloading, because a test that fetches tens of
 * megabytes from a third party is a test of that third party's uptime — and gated rather
 * than absent, because "the adapter loads real weights and survives real output" is exactly
 * the claim no stand-in can make.
 *
 * What is asserted is deliberately weak. A tiny test model's *content* is garbage by
 * construction, and asserting quality against it would pin the test to noise. What cannot
 * be garbage is the contract: loading succeeds, completion returns, and whatever comes back
 * flows through `PromptedGenerator` without an exception — because on the question path an
 * exception is a crash, whatever the model said.
 */
class RealInferenceTest {

    private val weights: Path? = System.getenv("RPG_TEST_GGUF")
        ?.let(Path::of)
        ?.takeIf { Files.isRegularFile(it) }

    private fun manifest(path: Path): ModelManifest {
        val bytes = Files.readAllBytes(path)
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        return ModelManifest(
            id = "real-test",
            displayName = "Real Test",
            license = "test",
            files = listOf(ModelFile(path.fileName.toString(), "file://local", bytes.size.toLong(), digest)),
        )
    }

    @Test
    fun `real weights load, answer, and never throw on the question path`() {
        val path = weights ?: run {
            println("RPG_TEST_GGUF not set; real-inference smoke test skipped")
            return
        }
        val runtime = LlamaCppRuntime()
        val manifest = manifest(path)
        assertTrue(runtime.supports(manifest) || path.fileName.toString().endsWith(".gguf"))

        val generator = runtime.open(manifest, mapOf(path.fileName.toString() to path))
        assertNotNull(generator, "a real GGUF must load, or the adapter is decoration")

        // Every text job, against whatever the model produces. No assertion on content --
        // a tiny model's content is noise -- only that the contract absorbs it.
        val rewritten = generator.normalize(
            "what about there?",
            listOf(Turn("where do the hounds hunt", "In the Marches.")),
        )
        assertTrue(rewritten.isNotBlank(), "normalize degrades to the query, never to nothing")

        val context = listOf(
            RedactedChunk.of(
                ChunkRef("srd:test", 1),
                "The Marches",
                "Ash falls in the Marches for most of the year.",
            )!!,
        )
        val answer = generator.answer("what is the weather like", context)
        // The strong property: whatever the model emitted, every surviving attribution
        // names a chunk that was really in the context, on real byte boundaries.
        val bytes = answer.text.toByteArray(Charsets.UTF_8)
        answer.attributions.forEach { attribution ->
            assertTrue(attribution.chunk == ChunkRef("srd:test", 1))
            assertTrue(attribution.start in 0 until attribution.end && attribution.end <= bytes.size)
        }
    }
}
