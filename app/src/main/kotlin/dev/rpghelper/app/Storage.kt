package dev.rpghelper.app

import android.content.Context
import dev.rpghelper.session.Store

/**
 * The app's **one** [Store], for the life of the process.
 *
 * Each surface used to construct its own, which was wrong in a way that only showed up
 * across surfaces: `Store` opens a `PackLibrary`, and `PackLibrary`'s initializer calls
 * `reconcile()` — the pass that resolves installs which died mid-flight by deleting their
 * journal row and staged file. A second instance cannot tell *died mid-flight* from
 * *happening right now*. So opening the Documents surface while a pack was installing
 * destroyed the install: the row went, the staged file went, and the surface that was doing
 * the work reported a failure it did not cause.
 *
 * One store also matches what the storage actually is. `StateDb` serializes its single
 * connection behind a lock precisely because `autoCommit` and the current transaction are
 * connection-wide state; two connections to one SQLite file are two writers, which is the
 * thing that lock exists to prevent within a process.
 *
 * Never closed. It is the process's, not a screen's — and a `ViewModel` closing a handle
 * three other view models are holding is the same class of bug one directory up. The
 * database is closed when the process ends, which for an Android app is the only moment
 * that is true for everything else too.
 */
object Storage {

    @Volatile
    private var store: Store? = null

    @Volatile
    private var runtimeRegistered = false

    fun of(context: Context): Store = store ?: synchronized(this) {
        store ?: Store(context.applicationContext.filesDir.toPath().resolve("library")).also {
            store = it
            registerRuntime()
        }
    }

    /**
     * Registers the inference runtime, once, for the process.
     *
     * **Registering is not loading.** No weights are read here and no native library is
     * touched: `ModelRuntimes` records that something *claims* to run GGUF, and the claim is
     * only tested when a model is actually opened — inside a `runCatching` that returns null.
     * So a device where the native library cannot load behaves exactly as a build with no
     * runtime at all: setting questions answer as citations, and the Models surface says why.
     *
     * That failure mode is why this is wired at all despite the APK cost. The alternative
     * was an app that can download three gigabytes of weights and has nothing that can
     * execute them, which is a promise it cannot keep.
     */
    private fun registerRuntime() {
        if (runtimeRegistered) return
        runtimeRegistered = true
        runCatching { dev.rpghelper.runtime.LlamaCppRuntime.register(totalRamBytes = totalRam()) }
    }

    /**
     * Physical memory of *this* device.
     *
     * `/proc/meminfo` on Android as on Linux — the phone with 16 GB gets a window sized for
     * 16 GB rather than the 8 GB the design assumed, which is the whole point of measuring
     * rather than assuming. `Runtime.maxMemory` would answer with the Java heap ceiling,
     * which has nothing to do with a native KV cache.
     */
    private fun totalRam(): Long = dev.rpghelper.runtime.LlamaCppRuntime.detectRam()
}
