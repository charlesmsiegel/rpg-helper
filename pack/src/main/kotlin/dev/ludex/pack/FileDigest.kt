package dev.ludex.pack

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/**
 * SHA-256 of a file, lowercase hex.
 *
 * One implementation, because two would be a correctness problem rather than a tidiness
 * one. `installed_packs.file_sha256` is written at install and the answer cache is keyed on
 * `(pack_uid, file_sha256)` in priority order — that key is the *whole* of the cache's
 * invalidation story, so a second digest routine that ever disagreed with the first would
 * serve answers built from bytes that had since been corrected, and nothing on screen would
 * say so. There were two.
 *
 * Streamed rather than read whole: a pack is up to two gigabytes and this runs on a phone.
 */
object FileDigest {

    fun of(path: Path): String {
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
}
