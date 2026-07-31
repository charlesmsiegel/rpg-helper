package dev.rpghelper.model

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.streams.asSequence

/** A manifest the user has added, and what is on disk for it. */
data class ShelvedModel(
    val manifest: ModelManifest,
    val availability: Availability,
    /** Bytes present on disk for this model, complete or not. */
    val onDisk: Long,
)

/**
 * The models this device has, or could have.
 *
 * **No manifest ships in the binary.** [ModelManifest.parse] refuses a placeholder digest,
 * which means a manifest cannot exist before the artifact it pins does — so bundling one
 * would mean either shipping weights or shipping a lie. Instead a manifest is *added*: the
 * user (or `rpg-helper make-manifest`) supplies a file whose digests were computed from the
 * bytes that will actually be fetched. That is the whole trust chain, and it is short on
 * purpose: the app downloads several gigabytes of executable behaviour in its only network
 * operation, and the only thing standing between that and an arbitrary payload is a digest
 * somebody computed from a file they had.
 *
 * Storage layout, under one root:
 * ```
 *   manifests/<id>.json       what was added
 *   files/<id>/<name>         the verified artifacts, and `.name.partial` mid-download
 * ```
 *
 * **A manifest's files live in a directory of its own.** `weights.gguf` is what almost every
 * quantized model calls its artifact, so a flat directory made two manifests share one path:
 * downloading the second silently replaced the first's verified bytes, deleting either
 * deleted the other's, and where the declared lengths happened to match the list went on
 * reporting both as ready. Nothing about that is visible to a user — the second model just
 * answers with the first one's weights.
 */
class ModelLibrary(
    root: Path,
    private val fetcher: RangeFetcher = HttpRangeFetcher(),
) {

    private val manifestsDir: Path = Files.createDirectories(root.resolve("manifests"))
    private val filesDir: Path = Files.createDirectories(root.resolve("files"))

    /** One model's own directory, named by its sanitized id. */
    private fun dirOf(manifest: ModelManifest): Path =
        Files.createDirectories(filesDir.resolve(sanitize(manifest.id)))

    /** Where a model's weights live, for a runtime binding that will load them. */
    fun fileOf(manifest: ModelManifest, name: String): Path = dirOf(manifest).resolve(name)

    /**
     * The downloader for one model, writing into that model's directory.
     *
     * Per manifest rather than shared, because the directory is per manifest — and the
     * partial files are too, which is what keeps an interrupted download of one model from
     * being resumed into another's artifact.
     */
    fun downloaderFor(manifest: ModelManifest): ModelDownloader = ModelDownloader(dirOf(manifest), fetcher)

    /**
     * Every manifest that has been added, with what is on disk for it.
     *
     * A manifest file that no longer parses is **dropped from the list rather than thrown
     * over**: one bad file must not make the surface unusable, and the remedy — add it
     * again — is the same either way.
     */
    fun list(): List<ShelvedModel> = Files.list(manifestsDir).use { entries ->
        entries.asSequence()
            .filter { it.fileName.toString().endsWith(".json") }
            .mapNotNull { path -> runCatching { ModelManifest.parse(Files.readString(path)) }.getOrNull() }
            .sortedBy { it.displayName }
            .map { ShelvedModel(it, availability(it), onDisk(it)) }
            .toList()
    }

    /** Adds a manifest, or throws if it is not one. Replaces any manifest with the same id. */
    fun add(text: String): ModelManifest {
        val manifest = ModelManifest.parse(text)
        Files.writeString(manifestsDir.resolve("${sanitize(manifest.id)}.json"), text)
        return manifest
    }

    /** Forgets a manifest. The files stay: deleting gigabytes is [removeFiles]'s decision. */
    fun remove(id: String) {
        Files.deleteIfExists(manifestsDir.resolve("${sanitize(id)}.json"))
    }

    /** Deletes a model's artifacts and any partial bytes. Its directory, so only its own. */
    fun removeFiles(manifest: ModelManifest) {
        val downloader = downloaderFor(manifest)
        downloader.discardPartials(manifest)
        manifest.files.forEach { Files.deleteIfExists(fileOf(manifest, it.name)) }
        // The directory too, once it is empty. A model the user deleted should not leave a
        // named folder behind suggesting it is still half-installed.
        runCatching { Files.delete(dirOf(manifest)) }
    }

    /**
     * What the surface shows for one model, **without hashing gigabytes**.
     *
     * Presence and declared length, not the digest. A full verification of a 4 GB model
     * reads 4 GB, and doing that on every visit to a list screen would make the screen a
     * function of how many models the user has kept — so the digest pass is [verify], an
     * action a user takes, and the download path checks it unconditionally before it ever
     * reports Complete. This is the same trade the pack library makes between `borrow`'s
     * open-time check and the background digest pass.
     */
    fun availability(manifest: ModelManifest): Availability = when {
        manifest.files.all { present(manifest, it) } -> Availability.Ready
        onDisk(manifest) > 0 -> Availability.Downloading(onDisk(manifest), manifest.totalBytes)
        else -> Availability.NotDownloaded
    }

    /** The digest pass, run when a user asks — or before a runtime is pointed at the files. */
    fun verify(manifest: ModelManifest): Boolean = downloaderFor(manifest).verify(manifest)

    fun download(
        manifest: ModelManifest,
        cancel: AtomicBoolean = AtomicBoolean(false),
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadResult = downloaderFor(manifest).download(manifest, cancel, onProgress)

    private fun present(manifest: ModelManifest, file: ModelFile): Boolean {
        val path = fileOf(manifest, file.name)
        return Files.isRegularFile(path) &&
            runCatching { Files.size(path) }.getOrNull() == file.bytes
    }

    private fun onDisk(manifest: ModelManifest): Long = manifest.files.sumOf { file ->
        runCatching { Files.size(fileOf(manifest, file.name)) }.getOrDefault(0L)
    }

    /**
     * A generator for the first model that is ready **and** has a runtime, or null.
     *
     * Null is the ordinary answer in this build, because [ModelRuntimes] is empty — see the
     * note there for why a placeholder runtime would be worse than none. The digest pass runs
     * before any runtime is handed the files: they are executable behaviour, and presence
     * plus declared length is what a list screen may rely on, not what an interpreter may.
     */
    fun readyGenerator(): Pair<ModelManifest, Generator>? {
        for (shelved in list()) {
            if (shelved.availability !is Availability.Ready) continue
            val runtime = ModelRuntimes.runtimeFor(shelved.manifest) ?: continue
            if (!verify(shelved.manifest)) continue
            val files = shelved.manifest.files.associate { it.name to fileOf(shelved.manifest, it.name) }
            val generator = runCatching { runtime.open(shelved.manifest, files) }.getOrNull()
            if (generator != null) return shelved.manifest to generator
        }
        return null
    }

    /**
     * A manifest chooses its own id, and an id becomes a filename.
     *
     * `../../state.db` is a legal JSON string. Everything outside the allowed set becomes
     * `_`, so the id can name a file in this directory and cannot name one anywhere else —
     * the same reason `ModelManifest` refuses a file name that starts with a dot.
     */
    private fun sanitize(id: String): String = id.map {
        if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '_'
    }.joinToString("").trimStart('.').ifEmpty { "model" }
}
