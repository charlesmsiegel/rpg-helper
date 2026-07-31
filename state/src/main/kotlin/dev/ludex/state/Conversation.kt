package dev.ludex.state

/** One exchange in the feed: what was asked, and the cards the app answered with. */
data class ConversationTurn(
    val turnId: Long,
    val askedAt: String,
    val query: String,
    /** The rendered cards, serialized. History, not live content — see [Conversation]. */
    val cards: String,
)

/**
 * The answer feed, which is also the conversational window.
 *
 * **There is no second, hidden context.** The window matters because follow-up
 * normalization uses it — *"what about at level 5?"* only resolves against the last few
 * turns — and an invisible memory that silently changes what a question means is at odds
 * with an app whose entire pitch is that you can tell where its answers came from. When
 * pronoun resolution goes wrong, and it does, the user has to be able to see the thing
 * that caused it; the cheapest way to guarantee that is for the thing to be the screen
 * they are already looking at.
 *
 * So **New topic** clears the feed and therefore clears the context. One control, one
 * meaning, nothing hidden. The alternative — an internal window with its own lifetime —
 * would need its own clear control, its own explanation, and would still leave a user
 * unable to answer *"why did it think I meant that?"*
 *
 * **Persisted cards are history.** A quote card already on screen stays there after its
 * chunk is superseded or its pack uninstalled: the feed records what the app said at the
 * time it said it. That does not weaken supersession, which is about what retrieval can
 * *reach* — no new answer will contain the withdrawn text, and scrolling back to an old
 * one shows what was true when it was asked.
 */
class Conversation(
    private val db: StateDb,
    private val clock: () -> String = { java.time.Instant.now().toString() },
) {

    /**
     * How many turns normalization may see.
     *
     * Bounded so a four-hour session at a table does not grow an unbounded prompt, and
     * bounded at three because that is what `04-retrieval-spec.md` resolves follow-ups
     * against. The feed itself is not truncated — the user can scroll — only what the
     * model is shown.
     */
    val windowSize: Int = 3

    /** Appends a turn and returns it. The feed is in `turn_id` order, which is ask order. */
    fun record(query: String, renderedCards: String): ConversationTurn = db.transaction {
        db.execute(
            "INSERT INTO conversation_turns (asked_at, query, cards) VALUES (?, ?, ?)",
            clock(), query, renderedCards,
        )
        val id = db.lastInsertId()
        db.query(
            "SELECT turn_id, asked_at, query, cards FROM conversation_turns WHERE turn_id = ?", id,
        ) { it.toTurn() }.single()
    }

    /** The whole feed, oldest first. What the user scrolls. */
    fun feed(): List<ConversationTurn> = db.query(
        "SELECT turn_id, asked_at, query, cards FROM conversation_turns ORDER BY turn_id",
    ) { it.toTurn() }

    /**
     * The last [windowSize] turns, oldest first — what normalization is allowed to see.
     *
     * Returned in ask order rather than in the order the `LIMIT` produced them, because a
     * window handed to a model backwards resolves *"what about at level 5?"* against the
     * wrong question, which is a wrong answer with no symptom.
     */
    fun window(): List<ConversationTurn> = db.query(
        "SELECT turn_id, asked_at, query, cards FROM conversation_turns " +
            "ORDER BY turn_id DESC LIMIT $windowSize",
    ) { it.toTurn() }.reversed()

    /**
     * **New topic**: empties the feed, and with it the context.
     *
     * One statement rather than two, because a clear that emptied the window and left the
     * feed would be the hidden context this design exists to not have — from the other
     * direction.
     */
    fun newTopic() {
        db.execute("DELETE FROM conversation_turns")
    }

    private fun StateDb.Row.toTurn() =
        ConversationTurn(long(0), string(1), string(2), string(3))
}
