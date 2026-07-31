package dev.ludex.routing

import dev.ludex.pack.ChunkRef

/**
 * One piece of a card, **named by its provenance rather than by its appearance**.
 *
 * This is the decision every surface was making independently. A terminal has a left
 * margin, a phone has type and colour, and a stored string has neither — but *which run of
 * text is the book's own words* is not a property of any of those, and three renderers each
 * deciding it separately is three chances to decide it differently. They did: the CLI and
 * the app placed chip markers with two copies of the same byte-span walk and the stored
 * rendering placed none at all; the roll control existed on one surface; the refusal card's
 * remedy on another. Each was a small bug, and all of them were the same bug.
 *
 * So a card lays itself out once, in these blocks, and a surface decides only how to paint
 * them. What it must never do is paint two of them the same way.
 */
sealed interface Block {

    /** What produced everything after it. Always first, because provenance comes first. */
    data class Label(val text: String) : Block

    /**
     * The book's own bytes.
     *
     * Never re-flowed, never passed through a markup renderer, always visually set apart —
     * whatever "set apart" means on the surface in hand. **This is the only block that may
     * be rendered as a quotation**, and the only one that ever carries text a pack wrote.
     */
    data class Quotation(val text: String) : Block

    /** Machine-written prose, with any chip markers already placed in it. */
    data class Prose(val text: String) : Block

    /** A sentence the app says in its own voice. Neither quoted nor attributed. */
    data class Statement(val text: String) : Block

    /** A citation. [marker] ties it to the run of [Prose] that carries the same number. */
    data class Cited(val citation: Citation, val marker: String? = null) : Block

    /**
     * Sources the card drew on **as a whole**.
     *
     * Kept apart from [Cited] with a marker, because that one says *this sentence came from
     * here* and this one only says *something on this card did*. Rendering them alike makes
     * the weaker claim look like the stronger one.
     */
    data class Footer(val citations: List<Citation>) : Block

    /**
     * Something the user can do, which a surface may or may not be able to offer.
     *
     * A surface with no way to perform it must not render it — an offer that cannot be
     * taken is worse than no offer, because the user concludes the app is broken rather
     * than that the feature is absent.
     */
    data class Action(val label: String, val roll: ChunkRef? = null, val searchInactive: Boolean = false) : Block
}

/**
 * The card, as blocks.
 *
 * Labels are here rather than in each surface because the label is the sentence that makes
 * the provenance legible to someone who does not know the app's conventions — the whole
 * mechanism by which "generated" and "quoted" stay distinguishable. Three surfaces wording
 * it three ways is three different promises.
 */
fun Card.layout(): List<Block> = when (this) {
    is Card.Verbatim -> buildList {
        add(Block.Label("Quotation — ${kind.uppercase()}"))
        add(Block.Quotation(body))
        add(Block.Cited(citation))
        rollableRefs.forEach { add(Block.Action("Roll on this table", roll = it)) }
    }

    is Card.Derived -> attributed(
        "Summary — written by this pack's builder, not quoted", body, chips, footer,
    )

    is Card.Generated -> attributed(
        "Generated on this device from setting text", body, chips, footer,
    )

    is Card.ModelUnavailable -> buildList {
        add(Block.Label("Passages found"))
        add(Block.Statement(statement))
        // Citations only, never their content: these are `('setting','source')` chunks,
        // ineligible for verbatim rendering, and this card is reached precisely because
        // redaction emptied them. A `Quotation` block here would put the unredacted passage
        // on screen through the one door the fail-closed rule left open.
        if (wouldHaveUsed.isNotEmpty()) add(Block.Footer(wouldHaveUsed))
    }

    is Card.Empty -> buildList {
        add(Block.Label("Not found"))
        add(Block.Statement(statement))
        if (activePacks.isNotEmpty()) {
            add(Block.Statement("Searched: ${activePacks.joinToString(", ")}"))
        }
        if (canSearchInactive) {
            add(Block.Statement("Installed packs that are not active were not searched."))
            add(Block.Action("Search inactive packs too", searchInactive = true))
        }
    }
}

private fun attributed(
    label: String,
    body: String,
    chips: List<Chip>,
    footer: List<Citation>,
): List<Block> = buildList {
    add(Block.Label(label))
    add(Block.Prose(markChips(body, chips)))
    chips.sortedBy { it.start }.forEachIndexed { index, chip ->
        add(Block.Cited(chip.citation, marker = "${index + 1}"))
    }
    if (footer.isNotEmpty()) add(Block.Footer(footer))
}
