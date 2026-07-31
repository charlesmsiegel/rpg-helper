package dev.rpghelper.builder

import dev.rpghelper.model.HashingEmbedder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Builds the committed corpus into a pack, on demand, once per JVM.
 *
 * Assembling at test time rather than committing a `.rpgpack` is the whole point of
 * storing the corpus as text: a schema change updates the fixture automatically instead of
 * waiting for someone to remember to rebuild a binary.
 *
 * Cached because every consumer wants the same bytes and building costs an embedding per
 * window; the cost is small but paying it per test class would make the suite's runtime a
 * function of how many classes happen to read the corpus.
 */
object CorpusPack {

    /** The structural tier's embedder: deterministic, no weights, no model to load. */
    val EMBEDDER = HashingEmbedder(dim = 128)

    val directory: Path = locate()

    val spec: CorpusSpec by lazy { CorpusSpec.load(directory) }

    private val built: BuildOutcome by lazy {
        val target = Files.createTempDirectory("corpus-pack").resolve("srd.rpgpack")
        target.toFile().deleteOnExit()
        PackBuilder(spec, EMBEDDER).buildTo(target)
    }

    val path: Path get() = built.path

    val notes: List<BuildNote> get() = built.notes

    /**
     * Finds `corpus/srd` from wherever the test happens to be running.
     *
     * Gradle sets the working directory to the module, but a test run from an IDE or from
     * the repository root gets a different one, and a fixture that only loads under one
     * runner is a fixture people stop running.
     */
    private fun locate(): Path {
        var candidate: Path? = Path.of("").toAbsolutePath()
        while (candidate != null) {
            val corpus = candidate.resolve("corpus/srd")
            if (Files.isDirectory(corpus)) return corpus
            candidate = candidate.parent
        }
        error("corpus/srd not found above ${Path.of("").toAbsolutePath()}")
    }
}
