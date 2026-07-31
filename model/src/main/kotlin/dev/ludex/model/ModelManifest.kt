package dev.ludex.model

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** One file a model is made of. */
data class ModelFile(
    val name: String,
    val url: String,
    val bytes: Long,
    /** Lowercase hex SHA-256, pinned in the app binary. */
    val sha256: String,
)

/** A downloadable model, and everything needed to fetch and trust it. */
data class ModelManifest(
    val id: String,
    val displayName: String,
    val license: String,
    val files: List<ModelFile>,
) {
    /** What the Wi-Fi prompt shows, so the size figure is the one that will be fetched. */
    val totalBytes: Long get() = files.sumOf { it.bytes }

    companion object {

        /**
         * Length of a hex SHA-256. A digest of any other length is not a weak digest, it
         * is a field somebody has not filled in.
         */
        private const val DIGEST_LENGTH = 64

        private val HEX = Regex("[0-9a-f]{$DIGEST_LENGTH}")

        private val json = Json { ignoreUnknownKeys = true }

        /**
         * Parses a manifest, refusing one whose digests are not real.
         *
         * A manifest is written before the artifact it describes exists, so the digest
         * field spends part of its life as a placeholder. **That placeholder must fail
         * loudly**, because the failure mode of tolerating it is the one thing this whole
         * mechanism exists to prevent: a build that downloads several gigabytes of
         * executable behaviour and verifies nothing, in the app's only network operation.
         *
         * Refusing at parse rather than at verify means the failure lands in a test on the
         * shipped assets rather than on a user's phone on first run.
         */
        fun parse(text: String): ModelManifest {
            val root = json.parseToJsonElement(text).jsonObject
            val files = root.getValue("files").jsonArray.map { element ->
                val file = element.jsonObject
                ModelFile(
                    name = file.string("name"),
                    url = file.string("url"),
                    bytes = file.getValue("bytes").jsonPrimitive.content.toLong(),
                    sha256 = file.string("sha256"),
                )
            }
            val manifest = ModelManifest(
                id = root.string("id"),
                displayName = root.string("display_name"),
                license = root.string("license"),
                files = files,
            )
            manifest.requireUsable()
            return manifest
        }

        private fun JsonObject.string(field: String) = getValue(field).jsonPrimitive.content
    }

    private fun requireUsable() {
        require(files.isNotEmpty()) { "manifest '$id' lists no files" }
        for (file in files) {
            require(HEX.matches(file.sha256)) {
                "manifest '$id' file '${file.name}' has no usable SHA-256 " +
                    "(got '${file.sha256}'). Fill it in from the published artifact — " +
                    "an unverified multi-gigabyte download is the one thing this refuses."
            }
            require(file.bytes > 0) { "manifest '$id' file '${file.name}' declares no size" }
            require(file.url.startsWith("https://")) {
                "manifest '$id' file '${file.name}' is not fetched over HTTPS"
            }
        }
        val names = files.map { it.name }
        require(names.size == names.toSet().size) { "manifest '$id' repeats a file name" }
        for (name in names) {
            // A single safe leaf, because the name becomes a path under the model
            // directory. `../state.db` or an absolute path would let a *verified* download
            // land on unrelated application state -- the digest check confirms the bytes
            // are the ones this build expects and says nothing about where they go.
            require(name.isNotEmpty() && name !in setOf(".", "..")) {
                "manifest '$id' has a file with no usable name"
            }
            require(!name.contains('/') && !name.contains('\\')) {
                "manifest '$id' file '$name' is a path, not a name; it would escape the " +
                    "model directory"
            }
            require(!name.startsWith(".")) {
                "manifest '$id' file '$name' is hidden; names must be ordinary files"
            }
        }
    }
}
