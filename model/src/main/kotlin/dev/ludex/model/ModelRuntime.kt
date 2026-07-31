package dev.ludex.model

import java.nio.file.Path

/**
 * A binding from downloaded weights to a running [Generator].
 *
 * This is the seam an inference engine plugs into. It is deliberately **not** implemented in
 * this repository: no runtime ships here, so `open` would have to be a stub, and a stub that
 * returns a `Generator` producing nothing is worse than no generator at all — routing would
 * take route 3, the model would "answer", and the answer would be empty prose under real
 * citation chips. That is the one failure mode this whole product is built to prevent, and a
 * placeholder is a very efficient way to produce it.
 *
 * @see ModelRuntimes for what the absence of an implementation means on screen.
 */
interface ModelRuntime {

    /** A name for the registry and for diagnostics. */
    val id: String

    /**
     * Whether this runtime can load [manifest]'s artifacts.
     *
     * Asked before the files are opened, so a device with a runtime for one format does not
     * claim a model in another. A manifest names its files; a runtime recognises them.
     */
    fun supports(manifest: ModelManifest): Boolean

    /**
     * Loads the weights and returns something that can answer, or null if it cannot.
     *
     * @param files the verified artifacts, by their manifest names. **Verified** is the
     * caller's obligation and it is not negotiable: these bytes are executable behaviour,
     * and the digest is the only thing standing between them and an arbitrary payload.
     */
    fun open(manifest: ModelManifest, files: Map<String, Path>): Generator?
}

/**
 * The runtimes this build has — **none, and that is a fact the surfaces state.**
 *
 * A model that has been downloaded and verified is, right now, a few gigabytes on disk that
 * nothing can execute. The alternative to saying so was letting the Models surface imply
 * that downloading enables phrased setting answers while `AskService` is still handed
 * `generator = null` on every question, which is a promise the app cannot keep and the user
 * cannot check.
 *
 * When a runtime is added — a GGUF loader, a MediaPipe task binding — it registers here and
 * every surface that asks [runtimeFor] starts getting a generator without any of them
 * changing. The registry is the whole coupling: `:app` never names an inference library, and
 * routing keeps its "is the model ready" logic exactly as it is.
 */
object ModelRuntimes {

    private val runtimes = mutableListOf<ModelRuntime>()

    /** Adds a runtime. Called by whatever module supplies one; nothing does yet. */
    @Synchronized
    fun register(runtime: ModelRuntime) {
        runtimes.removeAll { it.id == runtime.id }
        runtimes += runtime
    }

    @Synchronized
    fun runtimeFor(manifest: ModelManifest): ModelRuntime? = runtimes.firstOrNull {
        runCatching { it.supports(manifest) }.getOrDefault(false)
    }

    /** True when no runtime is bundled at all — the state every build has shipped so far. */
    @get:Synchronized
    val empty: Boolean get() = runtimes.isEmpty()

    /**
     * What a surface should tell a user about a model it cannot run.
     *
     * One sentence, in one place, because three surfaces would otherwise word it three ways
     * and the wording *is* the promise being made.
     */
    fun unsupported(manifest: ModelManifest): String = if (empty) {
        "This build has no inference runtime, so ${manifest.displayName} is stored and " +
            "verified but cannot answer yet. Rules questions are unaffected: they are " +
            "answered out of your books."
    } else {
        "No bundled runtime can load ${manifest.displayName}'s files."
    }
}
