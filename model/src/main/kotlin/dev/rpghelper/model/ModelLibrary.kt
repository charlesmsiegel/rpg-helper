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
 *   manifests/<id>.json     what was added
 *   files/<name>            the verified artifacts, and `.name.partial` mid-download
 * ```
 */
class ModelLibrary(
    root: Path,
    private val fetcher: RangeFetcher = HttpRangeFetcher(),
) {

    private val manifestsDir: Path = Files.createDirectories(root.resolve("manifests"))
    private val filesDir: Path = Files.createDirectories(root.resolve("files"))

    /** Where the weights live, for a runtime binding that will load them. */
    fun fileOf(name: String): Path = filesDir.resolve(name)

    val downloader: ModelDownloader by lazy { ModelDownloader(filesDir, fetcher) }

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

    /** Deletes a model's artifacts and any partial bytes. */
    fun removeFiles(manifest: ModelManifest) {
        downloader.discardPartials(manifest)
        manifest.files.forEach { Files.deleteIfExists(fileOf(it.name)) }
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
        manifest.files.all { present(it) } -> Availability.Ready
        onDisk(manifest) > 0 -> Availability.Downloading(onDisk(manifest), manifest.totalBytes)
        else -> Availability.NotDownloaded
    }

    /** The digest pass, run when a user asks — or before a runtime is pointed at the files. */
    fun verify(manifest: ModelManifest): Boolean = downloader.verify(manifest)

    fun download(
        manifest: ModelManifest,
        cancel: AtomicBoolean = AtomicBoolean(false),
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadResult = downloader.download(manifest, cancel, onProgress)

    private fun present(file: ModelFile): Boolean {
        val path = fileOf(file.name)
        return Files.isRegularFile(path) &&
            runCatching { Files.size(path) }.getOrNull() == file.bytes
    }

    private fun onDisk(manifest: ModelManifest): Long = manifest.files.sumOf { file ->
        runCatching { Files.size(fileOf(file.name)) }.getOrDefault(0L)
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
