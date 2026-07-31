package dev.ludex.session

import dev.ludex.model.Availability
import dev.ludex.model.Generator
import dev.ludex.model.Turn
import dev.ludex.retrieval.QueryNormalizer
import dev.ludex.retrieval.Pipeline
import dev.ludex.routing.Answer
import dev.ludex.routing.Card
import dev.ludex.routing.LoadedRollables
import dev.ludex.routing.PackCitations
import dev.ludex.routing.PackDerivations
import dev.ludex.routing.Router
import dev.ludex.state.AnswerCache
import dev.ludex.state.CacheKey
import dev.ludex.state.CachedPack
import dev.ludex.state.Conversation

/** One question, answered. */
data class Asked(
    val question: String,
    /** The query retrieval actually ran, after normalization and the alias rewrite. */
    val resolvedQuery: String,
    /** What goes on screen and into the feed. */
    val rendered: String,
    /**
     * The live cards, or **null on a cache hit**.
     *
     * A hit returns the render stored at the time, because that is the only way a hit and
     * a fresh generation can be indistinguishable on screen — re-assembling cards from a
     * cached body would be a second rendering under possibly different code, which is the
     * thing the contract version in the key exists to prevent.
     */
    val answer: Answer?,
    val fromCache: Boolean,
)

/**
 * A question in, cards out — the whole read path, in the one place that owns it.
 *
 * `:cli` and `:app` both need this sequence, and two copies of it would be two places for
 * the cache key, the feed, and the routing call to drift apart. The interesting decisions
 * are all about *when* the surrounding state is touched:
 *
 * - **The cache is consulted only for the generated card**, because that is the only card
 *   whose production is slow. A quote card is a database read, and caching it would be
 *   slower than not.
 * - **The feed is written after the answer exists**, so a query that throws does not leave
 *   a turn in the window with nothing under it — which would then be what the *next*
 *   follow-up resolves against.
 */
class AskService(
    private val cache: AnswerCache,
    private val conversation: Conversation,
    /**
     * Bumped whenever anything that shapes a rendered card changes.
     *
     * Lives here because this is the code the version describes: the retrieval thresholds
     * this call passes, the routing it performs, and the rendering the caller does with
     * what comes back. See [CacheKey.contractVersion].
     */
    private val contractVersion: Int = CONTRACT_VERSION,
) {

    /**
     * @param render turns cards into the stored string. Supplied by the caller because the
     * cache holds a **rendered** card — a cache hit and a fresh generation have to be
     * indistinguishable on screen, and the screen is the caller's.
     */
    fun ask(
        question: String,
        library: Library,
        generator: Generator? = null,
        /**
         * The generative model's identity **and quantization**, for the cache key. The
         * same weights at four bits answer differently from the same weights at eight.
         */
        modelId: String = "none",
        render: (Answer) -> String = { it.cards.joinToString("\n") },
        hasInactivePacks: Boolean = false,
    ): Asked {
        // **The follow-up is resolved before retrieval sees it.** `what about at level 5?`
        // is not a query; it is a query minus its subject, and the subject is in the feed.
        // Sending it unresolved searches a fragment, and -- worse -- stores that fragment
        // as the resolved query in the cache key, so the same wording asked after a
        // different conversation can be served the first conversation's card.
        //
        // Only when the model is *ready*: a rewrite is a generative call, and without one
        // the honest behaviour is to search what the user typed. Only when the feed is
        // non-empty, because with nothing to resolve against a rewrite is the model
        // inventing a subject. And only for a query that reads as dependent -- a
        // self-contained question never wakes the model, however it is phrased.
        val window = conversation.window()
        val resolved = if (
            generator != null &&
            generator.availability is Availability.Ready &&
            QueryNormalizer.needsRewrite(question, window.size)
        ) {
            val turns = window.map { Turn(it.query, it.cards) }
            generator.normalize(question, turns).takeIf { it.isNotBlank() } ?: question
        } else {
            question
        }

        val retrieved = Pipeline.retrieve(
            resolved,
            library.active,
            gates = GATES,
            embed = { rewritten, contracts -> BUNDLED.embedPerContract(rewritten, contracts) },
        )

        // **Looked up before routing, not after.** Routing is what invokes the model, so a
        // cache consulted afterwards has already paid the cost it exists to avoid. What
        // can be known this early is whether route 3 could fire at all -- a `setting`
        // chunk of source origin among the candidates -- which is the only route whose
        // production is slow enough to be worth keeping.
        val couldGenerate = generator != null &&
            retrieved.candidates.any { it.kind == "setting" && it.origin == "source" }
        val key = if (couldGenerate) {
            CacheKey(
                resolvedQuery = retrieved.normalizedQuery,
                activeSet = library.fingerprint,
                modelId = modelId,
                contractVersion = contractVersion,
            )
        } else {
            null
        }

        if (key != null) {
            val hit = cache.get(key)
            if (hit != null) {
                conversation.record(question, hit)
                return Asked(question, retrieved.normalizedQuery, hit, answer = null, fromCache = true)
            }
        }

        val answer = Router(
            PackCitations(library.packs),
            PackDerivations(library.packs),
            LoadedRollables(library.rollableChunks),
        ).route(
            retrieved,
            generator = generator,
            activePacks = library.packs.map { it.packUid },
            hasInactivePacks = hasInactivePacks,
        )
        val rendered = render(answer)

        // Stored only if a generated card actually came out. Route 3 firing is not the
        // same as it succeeding: redaction can empty the context, and an unresolvable
        // citation suppresses the card. Caching those would serve a degraded answer back
        // for as long as the key lives, long after whatever caused it was fixed.
        if (key != null && answer.cards.any { it is Card.Generated }) {
            cache.put(key, rendered)
        }

        conversation.record(question, rendered)
        return Asked(question, retrieved.normalizedQuery, rendered, answer, fromCache = false)
    }

    companion object {
        /**
         * The generation contract this build implements.
         *
         * Everything else in a cache key describes the *inputs*; without this, nothing
         * describes the *code*. Cached cards are stored already rendered and persist
         * across upgrades, so an app that changed its thresholds, its routing, or its card
         * layout without bumping this would keep serving answers built under rules it no
         * longer follows.
         */
        const val CONTRACT_VERSION = 1
    }
}
