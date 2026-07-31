package dev.rpghelper.routing

import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.model.SupportedRegion

/** Everything needed to name a passage, resolved once and rendered by every card kind. */
data class Citation(
    val packUid: String,
    val chunkId: Long,
    val sourceTitle: String,
    val edition: String?,
    val headingPath: String?,
    val pageLabelStart: String?,
    val pageLabelEnd: String?,
    /**
     * `sources.locator_scheme`. A source that is not paginated reads in its own
     * vocabulary — *GM screen, panel 3*; *Hazard deck, card 14* — because that is how
     * people at a table refer to it. A closed vocabulary validated at activation, so
     * there is always a defined rendering.
     */
    val locatorScheme: String,
)

/** The five cards, and nothing else. */
sealed interface Card {

    /**
     * The stored string, rendered as an authoritative quotation.
     *
     * [body] is `chunks.text` byte-exact and **must never be passed through a markdown or
     * HTML renderer**. Game text is full of asterisks, underscores, brackets and pipes; a
     * renderer would interpret them and the string on screen would stop being the string
     * in the pack. Byte-exactness that survives the database and dies in the view layer is
     * not byte-exactness.
     */
    data class Verbatim(
        val ref: ChunkRef,
        val kind: String,
        val body: String,
        val citation: Citation,
        /**
         * Chunks this card offers a roll on — its own, **and any it absorbed**.
         *
         * A list rather than a flag, for two reasons that are really one. A pack may attach
         * more than one roll table to a chunk; nothing in the schema makes `tables.chunk_id`
         * unique, so a boolean silently picked whichever the database happened to return
         * last. And when a nested rollable table is deduplicated into its parent quote, the
         * table's text is on screen inside the parent while the capability stays keyed to
         * the child — so the card contained a table and offered no way to roll on it.
         */
        val rollableRefs: List<ChunkRef> = emptyList(),
        /**
         * Each rollable chunk's **own** citation, when it differs from the card's.
         *
         * A nested table deduplicated into its parent quote keeps its own heading path and
         * page labels, and the parent's range is typically wider. Rendering the rolled
         * outcome under the card's citation therefore put the book's own words beneath a
         * locator that does not point at them — the one thing a citation exists to get
         * right. Empty for a card whose only rollable chunk is itself.
         */
        val rollableCitations: Map<ChunkRef, Citation> = emptyMap(),
    ) : Card {
        val rollable: Boolean get() = rollableRefs.isNotEmpty()

        /** The citation to render a roll on [ref] under: its own if it has one. */
        fun citationFor(ref: ChunkRef): Citation = rollableCitations[ref] ?: citation

        /** Copy takes the citation with it: a quote in a group chat without its source is
         *  precisely the artifact this app exists to prevent. */
        fun copyText(): String = "$body\n\n— ${render(citation)}"
    }

    /** Prose the on-device model produced from route-3 chunks. */
    data class Generated(
        val body: String,
        /** Regions carrying a chip, in order, each resolving to a chunk that was in context. */
        val chips: List<Chip>,
        /** Sources for regions no chip covers, plus the whole-context fallback. */
        val footer: List<Citation>,
    ) : Card

    /**
     * Builder-written text rendered as stored.
     *
     * **Styled as generated, not as a quote**, because that is what it is. Its chips come
     * from `chunk_derivation`: a row with a claim span anchors a chip to that run of text,
     * a row with a NULL span contributes a footer citation. Each resolves through the
     * *cited* chunk's own source and page labels — a derived chunk has no citation columns
     * of its own, so its citation cannot drift from the chunks it was built from.
     */
    data class Derived(
        val ref: ChunkRef,
        val body: String,
        val chips: List<Chip>,
        val footer: List<Citation>,
    ) : Card

    /**
     * Retrieval succeeded, route 3 has chunks, and the model is not available.
     *
     * **Distinct from [Empty] on purpose.** The app found the answer and cannot currently
     * phrase it, which is a different statement and a different remedy. Answering this
     * with *not found in your active packs* would be a lie about the one thing the refusal
     * card exists to tell the truth about.
     */
    data class ModelUnavailable(
        val statement: String,
        /**
         * The chunks that *would* have been used, as citations only.
         *
         * **Never their content.** These are `('setting','source')` chunks, which §1
         * declares ineligible for verbatim rendering, and this card is reached precisely
         * because redaction emptied them or refused to touch them. Quoting them here would
         * put the original unredacted passage on screen in quotation styling, through the
         * one door the fail-closed rule left open.
         */
        val wouldHaveUsed: List<Citation>,
        /** Non-null only when a download is what would fix it. */
        val downloadBytes: Long?,
        /**
         * Which state produced this card.
         *
         * Carried so the surface can offer the right remedy: a download, a progress
         * indicator, or a retry. Collapsing them into one sentence tells a user whose
         * download already finished that it never started.
         */
        val availability: dev.rpghelper.model.Availability,
    ) : Card

    /**
     * *Not found in your active packs.*
     *
     * Load-bearing, not a courtesy: the visible half of the refuse-rather-than-hallucinate
     * rule, and the reason a user learns that silence means silence.
     */
    data class Empty(
        val statement: String = "Not found in your active packs.",
        val activePacks: List<String>,
        /** Offered, never performed automatically — activation changes every future answer. */
        val canSearchInactive: Boolean,
    ) : Card
}

/** An inline citation chip over a byte range of a card's body. */
data class Chip(val start: Int, val end: Int, val citation: Citation)

/** One line naming a passage, in the source's own locator vocabulary. */
fun render(citation: Citation): String = buildString {
    append(citation.sourceTitle)
    citation.edition?.let { append(" ($it)") }
    citation.headingPath?.let { append(", $it") }
    val locator = locatorOf(citation)
    if (locator != null) append(", $locator")
}

private fun locatorOf(citation: Citation): String? {
    val start = citation.pageLabelStart ?: return null
    val end = citation.pageLabelEnd
    // The scheme's own word, because "p. 14" is wrong for a GM screen and a user reading
    // it would go looking for a page that does not exist.
    val noun = when (citation.locatorScheme) {
        "page" -> "p."
        "panel" -> "panel"
        "card" -> "card"
        "sheet" -> "sheet"
        "section" -> "§"
        "position" -> "at"
        else -> citation.locatorScheme
    }
    return if (end == null || end == start) "$noun $start" else "$noun $start–$end"
}

/** What an answer turned into, plus why, for the diagnostics view. */
data class Answer(
    val cards: List<Card>,
    val diagnostics: List<String>,
) {
    val refused: Boolean get() = cards.size == 1 && cards.single() is Card.Empty
}

/** A region of a generated answer paired with the citation it resolved to. */
internal fun chipOf(region: SupportedRegion.Cited, citation: Citation) =
    Chip(region.start, region.end, citation)
