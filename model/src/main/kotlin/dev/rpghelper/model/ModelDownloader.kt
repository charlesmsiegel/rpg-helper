package dev.rpghelper.model

import java.io.InputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/** How a download ended. */
sealed interface DownloadResult {
    data class Complete(val files: Map<String, Path>) : DownloadResult
    data class Failed(val reason: String) : DownloadResult
    object Cancelled : DownloadResult
}

/** Bytes fetched so far against the manifest's total, for the progress indicator. */
data class DownloadProgress(val fetched: Long, val total: Long)

/** Opens a byte range of a URL. Abstracted so the tests are not a network. */
fun interface RangeFetcher {
    /**
     * @param from first byte wanted, zero-based. A server that ignores it is a correctness
     * problem the caller must detect, not one it can paper over.
     * @return the stream, and the offset the server actually started at.
     */
    fun open(url: String, from: Long): Pair<InputStream, Long>
}

/**
 * Fetches the generative model, resumably, and refuses to hand over bytes it cannot vouch
 * for.
 *
 * This download is the app's **only network operation**, and the one moment where several
 * gigabytes of executable behaviour arrive from outside. Transport security establishes
 * who sent the bytes; the pinned hash establishes that the bytes are the ones this build
 * was tested against, and only the second survives a compromised mirror, a captive-portal
 * page saved under the model's name, or a truncation nobody noticed.
 */
class ModelDownloader(
    private val directory: Path,
    private val fetcher: RangeFetcher = HttpRangeFetcher(),
) {

    init {
        Files.createDirectories(directory)
    }

    /** The final path a verified file occupies. */
    fun fileOf(name: String): Path = directory.resolve(name)

    private fun partialOf(name: String): Path = directory.resolve("$name.partial")

    /**
     * Downloads every file in [manifest] that is not already present and verified.
     *
     * Resumable across app death, connectivity loss, and reboot: partial bytes live in a
     * `.partial` file beside the target and the next attempt asks for the range after
     * them. Verification always runs over the **whole** assembled file rather than over
     * the newly-fetched tail, because a resume that stitched onto a corrupt prefix would
     * otherwise pass.
     *
     * @param cancel checked between chunks. On cancellation partial bytes are discarded
     * rather than left occupying storage the user cannot see.
     */
    fun download(
        manifest: ModelManifest,
        cancel: AtomicBoolean = AtomicBoolean(false),
        onProgress: (DownloadProgress) -> Unit = {},
    ): DownloadResult {
        val total = manifest.totalBytes
        var fetchedBefore = 0L
        val done = mutableMapOf<String, Path>()

        for (file in manifest.files) {
            val target = fileOf(file.name)
            if (Files.isRegularFile(target) && sha256(target) == file.sha256) {
                // Already here and already trustworthy. Re-fetching it would be the app
                // spending a user's data to reach a state it is in.
                done[file.name] = target
                fetchedBefore += file.bytes
                onProgress(DownloadProgress(fetchedBefore, total))
                continue
            }

            val partial = partialOf(file.name)
            val outcome = fetchOne(file, partial, cancel) { bytesOfThisFile ->
                onProgress(DownloadProgress(fetchedBefore + bytesOfThisFile, total))
            }
            if (outcome != null) {
                if (outcome == CANCELLED) {
                    discard(partial)
                    return DownloadResult.Cancelled
                }
                return DownloadResult.Failed(outcome)
            }

            val actual = sha256(partial)
            if (actual != file.sha256) {
                // Deleted, not kept for inspection. A file that failed its digest is of
                // unknown provenance, and leaving several gigabytes of it on the device
                // invites a later code path to find it and use it.
                discard(partial)
                return DownloadResult.Failed(
                    "'${file.name}' hashed $actual, expected ${file.sha256}; the file was deleted",
                )
            }
            if (Files.size(partial) != file.bytes) {
                discard(partial)
                return DownloadResult.Failed(
                    "'${file.name}' is ${Files.size(partial)} bytes, expected ${file.bytes}",
                )
            }

            Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
            done[file.name] = target
            fetchedBefore += file.bytes
            onProgress(DownloadProgress(fetchedBefore, total))
        }

        return DownloadResult.Complete(done)
    }

    /** Removes any partially-fetched bytes for [manifest]. Cancelling must not cost storage. */
    fun discardPartials(manifest: ModelManifest) {
        manifest.files.forEach { discard(partialOf(it.name)) }
    }

    /** Re-verifies what is on disk, so a bit-rotted file is caught before it is loaded. */
    fun verify(manifest: ModelManifest): Boolean = manifest.files.all { file ->
        val path = fileOf(file.name)
        Files.isRegularFile(path) && sha256(path) == file.sha256
    }

    private fun discard(path: Path) {
        runCatching { Files.deleteIfExists(path) }
    }

    /** Null on success, [CANCELLED] on cancel, otherwise the failure. */
    private fun fetchOne(
        file: ModelFile,
        partial: Path,
        cancel: AtomicBoolean,
        onBytes: (Long) -> Unit,
    ): String? {
        var have = if (Files.exists(partial)) Files.size(partial) else 0L
        if (have > file.bytes) {
            // More bytes than the manifest says the file has. Whatever this is, it is not
            // a prefix of the file we want, and resuming onto it would produce something
            // that only fails at the digest after another multi-gigabyte fetch.
            discard(partial)
            have = 0
        }
        if (have == file.bytes) return null

        return try {
            val (stream, servedFrom) = fetcher.open(file.url, have)
            stream.use { input ->
                if (servedFrom != have) {
                    // A server that ignores Range answers with the whole file from zero.
                    // Appending that to what we have would silently concatenate a prefix
                    // with a complete copy; starting over is correct and honest.
                    if (servedFrom != 0L) {
                        return "'${file.name}' resumed at $servedFrom, not $have"
                    }
                    discard(partial)
                    have = 0
                }

                Files.newOutputStream(
                    partial,
                    StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.APPEND,
                ).use { output ->
                    val buffer = ByteArray(1 shl 16)
                    while (true) {
                        if (cancel.get()) return CANCELLED
                        val read = input.read(buffer)
                        if (read < 0) break
                        if (have + read > file.bytes) {
                            // The server is sending more than was declared. Stop rather
                            // than fill the disk finding out how much more.
                            return "'${file.name}' sent more than the declared ${file.bytes} bytes"
                        }
                        output.write(buffer, 0, read)
                        have += read
                        onBytes(have)
                    }
                }
            }
            if (have != file.bytes) {
                "'${file.name}' ended at $have of ${file.bytes} bytes"
            } else {
                null
            }
        } catch (e: Exception) {
            // Kept on disk deliberately: a connection dropping mid-fetch is the ordinary
            // case this whole mechanism is for, and the next attempt resumes from here.
            "'${file.name}' could not be fetched: ${e.message ?: e::class.simpleName}"
        }
    }

    private fun sha256(path: Path): String {
        val digest = MessageDigest.getInstance("SHA-256")
        Files.newInputStream(path).use { stream ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val CANCELLED = " cancelled"
    }
}

/** [RangeFetcher] over `java.net.http`, which speaks HTTP/2 and follows redirects. */
class HttpRangeFetcher(
    private val client: java.net.http.HttpClient = java.net.http.HttpClient.newBuilder()
        .followRedirects(java.net.http.HttpClient.Redirect.NORMAL)
        .build(),
) : RangeFetcher {

    override fun open(url: String, from: Long): Pair<InputStream, Long> {
        val builder = java.net.http.HttpRequest.newBuilder(URI.create(url)).GET()
        if (from > 0) builder.header("Range", "bytes=$from-")

        val response = client.send(
            builder.build(),
            java.net.http.HttpResponse.BodyHandlers.ofInputStream(),
        )
        return when (response.statusCode()) {
            // 206 means the range was honoured. 200 to a ranged request means it was not,
            // and the body starts at zero — reported as such rather than assumed.
            206 -> response.body() to from
            200 -> response.body() to 0L
            else -> {
                response.body().close()
                throw java.io.IOException("HTTP ${response.statusCode()}")
            }
        }
    }
}
