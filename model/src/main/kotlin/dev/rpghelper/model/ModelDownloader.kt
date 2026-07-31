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

    /**
     * Where a download accumulates before it is verified.
     *
     * **Dot-prefixed, which is what keeps it out of the final names' namespace.** Plain
     * `<name>.partial` shares that namespace, and a manifest may legitimately hold both
     * `weights.partial` and `weights` — after which the first file's verified bytes
     * occupy the second file's partial path, so the second download appends to an
     * already-complete file and fails its digest, or, if the two are identical, reports
     * Complete with the first file gone. The names are the manifest's to choose and the
     * collision is not its fault.
     *
     * The prefix is safe *because [ModelManifest] refuses a name beginning with a dot*,
     * so no final name can ever be spelled like a partial. Two rules that hold each other
     * up, and the reason the refusal there is not merely tidiness.
     */
    private fun partialOf(name: String): Path = directory.resolve(".$name.partial")

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
        // Files *this call* finalized, as opposed to ones that were already here. Cancelling
        // must cost no storage, and for a multi-file manifest that has to include the
        // gigabytes finished before the cancel: deleting only the partial left a user who
        // cancelled during file two holding all of file one, with nothing on screen saying
        // so and no way to find it. What was on disk beforehand is not this call's to
        // remove -- it was not fetched here, and it may be another attempt's verified work.
        val finalizedHere = mutableListOf<Path>()

        for (file in manifest.files) {
            val target = fileOf(file.name)
            // A file that cannot be read is a file that cannot be trusted, and it fails
            // the same way a mismatch does. Letting the read throw would crash the model
            // lifecycle mid-check instead of moving it to an unavailable state.
            // **Digest and declared size.** A freshly downloaded partial is checked against
            // both; a file already on disk was checked against only the digest, so a
            // manifest whose `bytes` is wrong reported Complete for a file a clean install
            // would have refused -- and the registry recorded the wrong total.
            if (matches(target, file)) {
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
                    finalizedHere.forEach { discard(it) }
                    return DownloadResult.Cancelled
                }
                return DownloadResult.Failed(outcome)
            }

            val actual = digestOrNull(partial)
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

            // Finalization is a result, not an exception. Network failures and digest
            // mismatches are deliberately values so the model lifecycle can move to a
            // failed state and offer a retry; letting the *move* throw past all of that
            // crashed the caller after a multi-gigabyte fetch, for the ordinary reasons a
            // move fails -- storage gone read-only, the target replaced by a directory, a
            // filesystem error. The verified partial is kept: it hashed correctly, so a
            // retry has nothing left to download.
            try {
                Files.move(partial, target, StandardCopyOption.REPLACE_EXISTING)
            } catch (e: java.io.IOException) {
                return DownloadResult.Failed(
                    "'${file.name}' downloaded and verified but could not be put in place: " +
                        "${e.message}; the verified copy is kept at $partial for a retry",
                )
            }
            done[file.name] = target
            finalizedHere.add(target)
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
    fun verify(manifest: ModelManifest): Boolean = manifest.files.all { matches(fileOf(it.name), it) }

    /**
     * Whether [path] is the file [file] describes — **both** its digest and its length.
     *
     * The digest alone is what a hash comparison means; the length is what the manifest
     * *claims*, and the two disagreeing is a manifest that would fail a clean installation
     * while passing a reuse check. One predicate so the reuse path and the verification
     * pass cannot drift into checking different things.
     */
    private fun matches(path: Path, file: ModelFile): Boolean =
        Files.isRegularFile(path) &&
            runCatching { Files.size(path) }.getOrNull() == file.bytes &&
            digestOrNull(path) == file.sha256

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

        // **Before the request is opened, not only after.** The watcher below can only
        // close a stream that exists, so a cancel already set when this file started -- or
        // arriving while `HttpRangeFetcher` waits up to sixty seconds for response headers
        // -- still opened a connection and then left the caller waiting out the timeout.
        // Cancelling should cost a user nothing, least of all their data.
        if (cancel.get()) return CANCELLED

        // Cancellation has to reach a *blocked* read, not only the gap between two of
        // them. The connectivity failure where a user most wants to cancel -- a server
        // that accepted the connection and then stopped sending -- is exactly the one
        // where `input.read` never returns, and a flag polled before the read is never
        // reached again. So the stream is closed out from under it: the read throws,
        // and the catch below tells cancellation apart from failure.
        var watcher: Thread? = null
        return try {
            val (stream, servedFrom) = fetcher.open(file.url, have)
            watcher = Thread {
                try {
                    while (!cancel.get()) Thread.sleep(WATCH_INTERVAL_MS)
                    stream.close()
                } catch (_: InterruptedException) {
                    // The download finished first; nothing to interrupt.
                } catch (_: java.io.IOException) {
                    // Closing an already-closed stream is not a failure worth reporting.
                }
            }.apply { isDaemon = true; start() }
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
            // A read that threw *because the user cancelled* is a cancellation, not a
            // network failure, and reporting it as one would put a scary message on a
            // deliberate act.
            if (cancel.get()) {
                CANCELLED
            } else {
                // Kept on disk deliberately: a connection dropping mid-fetch is the
                // ordinary case this whole mechanism is for, and the next attempt
                // resumes from here.
                "'${file.name}' could not be fetched: ${e.message ?: e::class.simpleName}"
            }
        } finally {
            watcher?.interrupt()
        }
    }

    /** The digest, or null when the bytes cannot be read at all. */
    private fun digestOrNull(path: Path): String? = runCatching { sha256(path) }.getOrNull()

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

/** How often the cancellation watcher looks at the flag. */
private const val WATCH_INTERVAL_MS = 100L

/**
 * [RangeFetcher] over `HttpURLConnection` — **the one HTTP client both platforms have**.
 *
 * This was `java.net.http.HttpClient`, which is a cleaner API and does not exist on Android.
 * `ModelLibrary` constructs a fetcher eagerly, `AskViewModel` constructs a `ModelLibrary` on
 * the first screen, and the result was a `NoClassDefFoundError` during startup — before any
 * download had been requested, on a code path that has nothing to do with downloading. A
 * desktop-only class reached the phone the same way `java.sql` once did, and the lesson is
 * the same: `:model` ships to Android, so its defaults must be things Android has.
 *
 * `HttpURLConnection` is old and unlovely and is on every Android release and every JVM.
 */
class HttpRangeFetcher(
    private val connectTimeoutMillis: Int = 30_000,
    private val readTimeoutMillis: Int = 60_000,
) : RangeFetcher {

    override fun open(url: String, from: Long): Pair<InputStream, Long> {
        // Redirects are followed manually, because `HttpURLConnection` will not follow one
        // that crosses http/https -- and a mirror redirecting to a CDN on the other scheme
        // is ordinary. Bounded, because a redirect loop is otherwise a hang.
        var target = URI.create(url)
        repeat(MAX_REDIRECTS) {
            val connection = (target.toURL().openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                instanceFollowRedirects = false
                connectTimeout = connectTimeoutMillis
                // Bounds the wait for *bytes*, not for the whole body: a few gigabytes may
                // take as long as they take, but a server that says nothing must not block
                // forever with no way out.
                readTimeout = readTimeoutMillis
                if (from > 0) setRequestProperty("Range", "bytes=$from-")
            }
            when (val status = connection.responseCode) {
                // 206 says *a* range was served; `Content-Range` says which one. A
                // misconfigured server answering 206 with `bytes 0-…` would otherwise be
                // reported as starting where we asked, so the caller would append a whole
                // body to the partial file, fail the digest after spending the bandwidth,
                // and fail the same way on every retry instead of restarting cleanly.
                206 -> return try {
                    connection.inputStream to rangeStart(connection)
                } catch (e: Throwable) {
                    // Every failure branch closes the body; this one threw past it, so a
                    // misconfigured mirror leaked one stream per attempt.
                    runCatching { connection.inputStream.close() }
                    connection.disconnect()
                    throw e
                }
                // 200 to a ranged request means the range was ignored and the body starts
                // at zero -- reported as such rather than assumed.
                200 -> return connection.inputStream to 0L
                in 300..399 -> {
                    val location = connection.getHeaderField("Location")
                        ?: throw java.io.IOException("HTTP $status with no Location")
                    connection.disconnect()
                    target = target.resolve(location)
                }
                else -> {
                    connection.disconnect()
                    throw java.io.IOException("HTTP $status")
                }
            }
        }
        throw java.io.IOException("too many redirects")
    }

    /** First byte of `Content-Range: bytes <start>-<end>/<total>`. */
    private fun rangeStart(connection: java.net.HttpURLConnection): Long {
        // A 206 with no Content-Range is not something to guess about: assuming it starts
        // where we asked is the assumption this method exists to remove.
        val header = connection.getHeaderField("Content-Range")
            ?: throw java.io.IOException("206 with no Content-Range; cannot place the bytes")
        return CONTENT_RANGE.find(header)?.groupValues?.get(1)?.toLongOrNull()
            ?: throw java.io.IOException("unparseable Content-Range '$header'")
    }

    private companion object {
        val CONTENT_RANGE = Regex("bytes\\s+(\\d+)-")
        const val MAX_REDIRECTS = 5
    }
}
