package dev.ludex.state

import java.security.MessageDigest

/**
 * One active pack, as the cache key sees it.
 *
 * **Fingerprinted by content, not by declared version.** Installing a pack whose
 * `pack_uid` already exists replaces it, and nothing requires the replacement to declare a
 * new `pack_version` — a corrected rebuild may keep it. `file_sha256` changes whenever the
 * bytes do, which is the property the cache actually needs.
 */
data class CachedPack(val packUid: String, val fileSha256: String)

/**
 * Everything that could change a generated answer.
 *
 * Each field earns its place by being something whose change must produce a different
 * key, and two of them were wrong in an earlier version of the design.
 */
data class CacheKey(
    /**
     * **The query retrieval actually ran** — after folding, the alias rewrite, and any
     * generative rewrite. Not the typed one.
     *
     * A follow-up is resolved against the conversation window before retrieval sees it, so
     * *"what about at level 5?"* means different things after a grappling question and
     * after a stealth question. Keying on the pre-rewrite text hashes both to one entry and
     * serves the second user the first conversation's card — a wrong answer with
     * well-formed citations, indistinguishable on screen from a fresh generation. Using
     * the query that was run also covers voice and camera input, which reach retrieval
     * only after a rewrite.
     */
    val resolvedQuery: String,
    /** Active packs in priority order. Order is part of the key because priority is. */
    val activeSet: List<CachedPack>,
    /** Model identity *and* quantization: the same weights at 4 bits answer differently. */
    val modelId: String,
    /**
     * Bumped whenever anything that shapes a rendered card changes — retrieval thresholds,
     * the routing partition, the generation prompt, attribution parsing, the card's
     * serialized form.
     *
     * Everything else in the key describes the *inputs*; without this, nothing describes
     * the *code*. Cached cards persist across upgrades and are stored already rendered, so
     * an upgraded app would otherwise keep serving answers built under rules it no longer
     * follows.
     */
    val contractVersion: Int,
) {
    /**
     * The key itself: SHA-256 over a canonical, unambiguously delimited encoding.
     *
     * Length-prefixed rather than joined by a separator, because a pack uid or a query
     * containing the separator would otherwise let two different keys encode identically —
     * a cache collision that serves one question's card for another, which is precisely
     * the failure the key exists to prevent.
     */
    fun hash(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        fun field(value: String) {
            val bytes = value.toByteArray(Charsets.UTF_8)
            digest.update(bytes.size.toString().toByteArray(Charsets.UTF_8))
            digest.update(':'.code.toByte())
            digest.update(bytes)
        }
        field(contractVersion.toString())
        field(modelId)
        field(resolvedQuery)
        field(activeSet.size.toString())
        for (pack in activeSet) {
            field(pack.packUid)
            field(pack.fileSha256)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}

/**
 * Generated cards, kept because generating them again is the slowest thing the app does.
 *
 * **There is no invalidation logic, and that is the design.** Activate a pack, deactivate
 * one, reorder priority, replace a book with a corrected rebuild, ask a differently
 * resolved follow-up, or change models, and the *key* changes: old entries stop being
 * reachable rather than needing to be found and deleted. Invalidation logic is where cache
 * bugs live, and the surest way to have none is to have nothing to invalidate.
 *
 * That covers the case which would otherwise be subtle. Installing an errata pack changes
 * the active set, so answers generated before the correction cannot be served after it —
 * supersession is a function of the active set, and the fingerprint already carries it.
 *
 * **Only generated cards.** A quote card is a database read, and caching it would be
 * slower than not.
 */
class AnswerCache(
    private val db: StateDb,
    private val limits: Limits = Limits(),
    private val clock: () -> String = { java.time.Instant.now().toString() },
) {

    /**
     * LRU bounds, in both dimensions that matter.
     *
     * A count alone lets a few enormous cards fill the disk; bytes alone lets thousands of
     * one-line answers accumulate rows nobody will read again. Neither is the interesting
     * number on its own, so both are enforced.
     */
    data class Limits(val entries: Int = 200, val bytes: Long = 4L * 1024 * 1024)

    /**
     * The card stored under [key], marking it used.
     *
     * The read and the touch are one transaction: an eviction running between them could
     * otherwise drop the entry this call is about to return as least-recently-used, and
     * the caller would be handed a card that no longer exists in the table it came from.
     */
    fun get(key: CacheKey): String? = db.transaction {
        val hash = key.hash()
        val card = db.query(
            "SELECT card FROM answer_cache WHERE cache_key = ?", hash,
        ) { it.string(0) }.singleOrNull() ?: return@transaction null
        db.execute("UPDATE answer_cache SET last_used_at = ? WHERE cache_key = ?", clock(), hash)
        card
    }

    /**
     * Stores a **rendered** card, citations and all.
     *
     * Rendered rather than re-assembled on read, so a cache hit and a fresh generation are
     * indistinguishable on screen — which is the whole point, and also why the contract
     * version is in the key: a stored render is only correct under the code that made it.
     */
    fun put(key: CacheKey, renderedCard: String) {
        db.transaction {
            val now = clock()
            db.execute(
                "INSERT INTO answer_cache (cache_key, card, created_at, last_used_at) " +
                    "VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(cache_key) DO UPDATE SET card = excluded.card, " +
                    "last_used_at = excluded.last_used_at",
                key.hash(), renderedCard, now, now,
            )
            evict()
        }
    }

    /** Clearable from settings, because a cache that cannot be cleared is a liability. */
    fun clear() {
        db.execute("DELETE FROM answer_cache")
    }

    fun size(): Int = db.query("SELECT count(*) FROM answer_cache") { it.int(0) }.single()

    /**
     * Total stored size, in the units the limit is stated in.
     *
     * `length()` on TEXT counts *characters*, so a cache full of CJK or emoji cards would
     * report a quarter of what it occupies and quietly run past a disk ceiling the user
     * was promised. Cast to BLOB and the answer is octets.
     */
    fun bytes(): Long = db.query(
        "SELECT COALESCE(sum(length(CAST(card AS BLOB))), 0) FROM answer_cache",
    ) { it.long(0) }.single()

    /**
     * Drops least-recently-used entries until both bounds hold.
     *
     * Ordered by `last_used_at` and then by `cache_key`, never by `last_used_at` alone:
     * two entries written in the same clock tick are an ordinary occurrence, and an
     * unordered tie-break makes which one survives depend on SQLite's row order — so the
     * same sequence of calls could evict different entries on two devices, and a test that
     * pinned the behaviour would be pinning an accident.
     */
    private fun evict() {
        while (size() > limits.entries || bytes() > limits.bytes) {
            val victim = db.query(
                "SELECT cache_key FROM answer_cache ORDER BY last_used_at, cache_key LIMIT 1",
            ) { it.string(0) }.singleOrNull() ?: return
            db.execute("DELETE FROM answer_cache WHERE cache_key = ?", victim)
        }
    }
}
