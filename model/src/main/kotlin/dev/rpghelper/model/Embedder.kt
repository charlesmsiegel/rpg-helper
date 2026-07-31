package dev.rpghelper.model

import dev.rpghelper.pack.EmbedderContract

/**
 * Text to a vector, in a named space.
 *
 * There is no way to embed a query in a space whose model you do not have, so the set of
 * `embedder_id` values the app can serve is **exactly the set shipped in its binary**.
 * A pack naming a contract that is not bundled is refused at activation rather than
 * retrieved against incorrectly, which is the only honest option: the alternative is
 * comparing vectors from two different spaces and reporting the result as relevance.
 */
interface Embedder {
    val contract: EmbedderContract

    /** Length is always [EmbedderContract.dim]. */
    fun embed(text: String): FloatArray
}

/**
 * What this build can serve.
 *
 * `bundled` is what `PackValidator` receives as its supported set. That the validator
 * takes it as a parameter rather than reading a global is why `:pack` can stay honest
 * about owning no weights.
 */
interface EmbedderRegistry {
    val bundled: Set<EmbedderContract>
    fun forContract(id: String): Embedder?

    /**
     * Groups [packContracts] so the query is embedded **once per distinct contract**.
     *
     * A pack built two years ago and one built today can both be valid and name different
     * embedders, and the older one must keep working: packs are files people own, not
     * subscriptions. One query embedding cannot serve both — the vectors live in different
     * spaces, and where `embedder_dim` differs the cosine is not even computable.
     *
     * The cost is one inference per distinct contract per query, which is the real
     * constraint keeping the supported set small. Not tidiness.
     */
    fun embedPerContract(query: String, packContracts: Collection<String>): Map<String, FloatArray> =
        packContracts.distinct()
            .mapNotNull { id -> forContract(id)?.let { id to it.embed(query) } }
            .toMap()
}

/** A registry over a fixed set of embedders, which is what a release actually ships. */
class BundledEmbedders(private val embedders: List<Embedder>) : EmbedderRegistry {

    init {
        val ids = embedders.map { it.contract.id }
        require(ids.size == ids.toSet().size) {
            "two embedders claim the same contract id: ${ids.groupBy { it }.filterValues { it.size > 1 }.keys}"
        }
    }

    override val bundled: Set<EmbedderContract> = embedders.map { it.contract }.toSet()

    private val byId = embedders.associateBy { it.contract.id }

    override fun forContract(id: String): Embedder? = byId[id]
}
