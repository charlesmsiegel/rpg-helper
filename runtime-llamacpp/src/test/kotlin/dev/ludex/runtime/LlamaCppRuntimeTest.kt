package dev.ludex.runtime

import dev.ludex.model.ModelFile
import dev.ludex.model.ModelManifest
import dev.ludex.model.ModelRuntimes
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The runtime, tested without weights — because weights are gigabytes and the failures worth
 * catching are all before the first token.
 *
 * What is asserted here is that this thing **declines cleanly**. A runtime that throws when
 * handed a file it cannot open, or that loads a model into a window the device cannot hold,
 * fails in the two ways that look like the app crashing: an exception on the question path,
 * and a process killed mid-answer.
 */
class LlamaCppRuntimeTest {

    private val directory: Path = Files.createTempDirectory("llamacpp")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun manifest(name: String) = ModelManifest(
        id = "test",
        displayName = "Test",
        license = "CC0",
        files = listOf(ModelFile(name, "https://example.invalid/$name", 16, "a".repeat(64))),
    )

    @Test
    fun `it claims GGUF and nothing else`() {
        val runtime = LlamaCppRuntime()
        assertTrue(runtime.supports(manifest("weights.gguf")))
        assertTrue(runtime.supports(manifest("WEIGHTS.GGUF")), "case is not a format")
        assertFalse(runtime.supports(manifest("model.task")), "MediaPipe's format is not this one")
        assertFalse(runtime.supports(manifest("notes.txt")))
    }

    @Test
    fun `a missing file declines rather than throwing`() {
        // On the question path an exception is a crash. Declining is a null generator, which
        // routing already reads as "not downloaded".
        assertNull(LlamaCppRuntime().open(manifest("weights.gguf"), emptyMap()))
        assertNull(
            LlamaCppRuntime().open(
                manifest("weights.gguf"),
                mapOf("weights.gguf" to directory.resolve("absent.gguf")),
            ),
        )
    }

    @Test
    fun `a file that is not a model declines rather than throwing`() {
        // llama.cpp will refuse these bytes; what matters is that the refusal arrives as a
        // null and not as a native abort on the way to answering a question.
        val notAModel = directory.resolve("weights.gguf")
        Files.writeString(notAModel, "this is not a GGUF file")
        assertNull(LlamaCppRuntime().open(manifest("weights.gguf"), mapOf("weights.gguf" to notAModel)))
    }

    @Test
    fun `a device too small for a usable window is declined before the model is loaded`() {
        // The failure this ordering exists to prevent: opening a window that does not fit
        // gets the process killed mid-answer, which a user reads as the app crashing when
        // they asked a question.
        val weights = directory.resolve("weights.gguf")
        Files.writeString(weights, "x")
        val tiny = LlamaCppRuntime(totalRamBytes = 512L * 1024 * 1024)
        assertNull(tiny.open(manifest("weights.gguf"), mapOf("weights.gguf" to weights)))
    }

    @Test
    fun `the machine's real memory is what sizing is measured against`() {
        // Not `Runtime.maxMemory`, which is the JVM heap ceiling and says nothing about a
        // native KV cache.
        val ram = LlamaCppRuntime.detectRam()
        assertTrue(ram >= 1L * 1024 * 1024 * 1024, "implausible RAM reading: $ram")
        assertTrue(ram <= 4096L * 1024 * 1024 * 1024, "implausible RAM reading: $ram")
    }

    @Test
    fun `registering makes it the runtime for a GGUF manifest`() {
        LlamaCppRuntime.register()
        assertEquals("llama.cpp", ModelRuntimes.runtimeFor(manifest("weights.gguf"))?.id)
        assertNull(ModelRuntimes.runtimeFor(manifest("model.task")), "and claims nothing else")
    }
}
