package dev.ludex.pack

/**
 * An embedding space the app can actually produce query vectors in.
 *
 * Supporting an `embedder_id` means bundling its weights: there is no way to embed a
 * query in a space whose model you do not have. So the set of contracts the app can
 * serve is exactly the set shipped in its binary -- enumerated, versioned with the app,
 * and small. Adding one is an app release, not a pack decision.
 *
 * The set is passed in rather than read from a global so that this module stays honest
 * about owning no weights. It is the `:app` module, which will actually carry the
 * models, that supplies the real list; tests supply their own.
 */
data class EmbedderContract(val id: String, val dim: Int) {
    init {
        require(id.isNotBlank()) { "embedder id must not be blank" }
        require(dim > 0) { "embedder dim must be positive, was $dim" }
    }
}
