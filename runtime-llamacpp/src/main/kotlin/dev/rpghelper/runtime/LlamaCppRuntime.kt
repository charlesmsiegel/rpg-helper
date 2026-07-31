package dev.rpghelper.runtime

import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import dev.rpghelper.model.Availability
import dev.rpghelper.model.ContextBudget
import dev.rpghelper.model.Generator
import dev.rpghelper.model.ModelManifest
import dev.rpghelper.model.ModelRuntime
import dev.rpghelper.model.ModelRuntimes
import dev.rpghelper.model.PromptedGenerator
import dev.rpghelper.model.TextCompletion
import java.nio.file.Files
import java.nio.file.Path

/**
 * GGUF weights, run locally by llama.cpp.
 *
 * The first real [ModelRuntime]: until this existed, a downloaded model was a few gigabytes
 * on disk that nothing could execute, and every question was answered with `generator =
 * null`. Registering this is what turns "the seam exists" into "drop in a model".
 *
 * **Everything about *what to ask* stays in `:model`.** This class supplies a
 * [TextCompletion] and nothing else — the prompts, the JSON contract, the attribution
 * arithmetic and the degradation rules are shared with every other backend and tested
 * without weights. A runtime that wanted to answer differently would have to change the
 * file where the rules are written down.
 *
 * The context window is [ContextBudget]'s decision, not a constant: the design assumed 8 GB
 * because that was the phone nobody could test on, and a 16 GB device has room for four
 * times the window. What is not negotiable is the direction of the error — a window that
 * does not fit gets the process killed mid-answer, which reads to a user as a crash.
 */
class LlamaCppRuntime(
    /** Total physical memory of the device this will run on. */
    private val totalRamBytes: Long = detectRam(),
    /** A user's explicit context choice, clamped to what the device can hold. */
    private val requestedContext: Int? = null,
    /** Generation threads. Default leaves a core for the rest of the system. */
    private val threads: Int = (Runtime.getRuntime().availableProcessors() - 1).coerceAtLeast(1),
) : ModelRuntime {

    override val id: String = "llama.cpp"

    /**
     * Any manifest carrying a `.gguf` file.
     *
     * Extension rather than magic bytes, deliberately: the file has already been verified
     * against a digest the user supplied, so the question here is *which* runtime should
     * open it, not whether to trust it.
     */
    override fun supports(manifest: ModelManifest): Boolean =
        manifest.files.any { it.name.endsWith(".gguf", ignoreCase = true) }

    override fun open(manifest: ModelManifest, files: Map<String, Path>): Generator? {
        val weights = files.entries
            .firstOrNull { it.key.endsWith(".gguf", ignoreCase = true) }
            ?.value
            ?: return null
        if (!Files.isRegularFile(weights)) return null

        val weightsBytes = runCatching { Files.size(weights) }.getOrDefault(0L)
        val context = ContextBudget.forDevice(
            totalRamBytes = totalRamBytes,
            weightsBytes = weightsBytes,
            requested = requestedContext,
        ) ?: return null // Not enough memory for a usable window: decline rather than crash.

        val model = runCatching {
            LlamaModel(
                ModelParameters()
                    .setModel(weights.toAbsolutePath().toString())
                    .setCtxSize(context)
                    .setThreads(threads),
            )
        }.getOrElse { return null }

        return PromptedGenerator(LlamaCompletion(model), Availability.Ready)
    }

    /**
     * One completion, greedy.
     *
     * `temperature = 0`, because every job here is an extraction job: resolve a pronoun,
     * name the remaining question, restate what the passages say. Sampling would buy variety
     * in exactly the place variety is a defect — a rules app whose answer changes between
     * two identical questions is one nobody can check.
     */
    private class LlamaCompletion(private val model: LlamaModel) : TextCompletion {
        override fun complete(prompt: String, maxTokens: Int, stop: List<String>): String {
            val parameters = InferenceParameters(prompt)
                .setTemperature(0f)
                .setNPredict(maxTokens)
            if (stop.isNotEmpty()) parameters.setStopStrings(*stop.toTypedArray())
            return model.complete(parameters)
        }
    }

    companion object {

        /**
         * Registers this runtime, so `ModelLibrary.readyGenerator()` starts returning one.
         *
         * Called by a surface that wants inference — `:cli` at startup. Not by `:model`,
         * which must not depend on any particular backend, and not automatically, because
         * "the app loads a 12 MB native library because it is on the classpath" is not a
         * decision to make implicitly.
         */
        fun register(
            totalRamBytes: Long = detectRam(),
            requestedContext: Int? = null,
        ) {
            ModelRuntimes.register(LlamaCppRuntime(totalRamBytes, requestedContext))
        }

        /**
         * Physical memory, read from the OS.
         *
         * `Runtime.maxMemory` is the JVM's *heap* ceiling and says nothing about the KV
         * cache, which is native. `/proc/meminfo` is the honest figure on Linux and Android;
         * elsewhere the JMX bean answers, and where neither does the fallback is the design's
         * original 8 GB assumption — conservative, and the reason this is a fallback rather
         * than the rule.
         */
        fun detectRam(): Long {
            val meminfo = Path.of("/proc/meminfo")
            if (Files.isReadable(meminfo)) {
                runCatching {
                    Files.readAllLines(meminfo)
                        .firstOrNull { it.startsWith("MemTotal:") }
                        ?.filter { it.isDigit() }
                        ?.toLongOrNull()
                        ?.let { return it * 1024 }
                }
            }
            runCatching {
                val bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
                val method = bean.javaClass.getMethod("getTotalMemorySize")
                method.isAccessible = true
                return method.invoke(bean) as Long
            }
            return 8L * 1024 * 1024 * 1024
        }
    }
}
