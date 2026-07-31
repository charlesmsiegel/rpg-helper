package dev.rpghelper.routing

import dev.rpghelper.model.Attributions
import dev.rpghelper.model.Availability
import dev.rpghelper.model.CardSummary
import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.model.Generator
import dev.rpghelper.model.RedactedChunk
import dev.rpghelper.model.SupportedRegion
import dev.rpghelper.pack.PackSchema
import dev.rpghelper.retrieval.Candidate
import dev.rpghelper.retrieval.Retrieved

/** Resolves a chunk to its citation, or null when it cannot be resolved. */
fun interface CitationResolver {
    fun resolve(ref: ChunkRef): Citation?
}

/** Resolves a derived chunk's `chunk_derivation` rows: cited chunk, and its claim span if any. */
fun interface DerivationResolver {
    fun derivationsOf(ref: ChunkRef): List<Derivation>
}

/** One `chunk_derivation` row. A null span puts the citation in the footer. */
data class Derivation(val cited: ChunkRef, val claimStart: Int?, val claimEnd: Int?)

/** Which chunks carry a roll control. */
fun interface RollableChunks {
    operator fun contains(ref: ChunkRef): Boolean
}

/**
 * Turns retrieval's candidates into cards.
 *
 * **Routing is mechanical.** The app does not decide whether to quote — the pack already
 * decided. A chunk renders verbatim if and only if `origin = 'source'` and its `kind` is
 * verbatim-eligible; routing reads those two columns and partitions. Asking a model *"is
 * this a rules question?"* would reintroduce at query time exactly the failure the pack
 * contract spent its whole design budget eliminating at build time.
 */
class Router(
    private val citations: CitationResolver,
    private val derivations: DerivationResolver,
    private val rollable: RollableChunks = RollableChunks { false },
) {

    /** At most this many route-3 chunks enter the generation context, in fused rank order. */
    private val maxContextChunks = 5

    /**
     * And at most this many bytes of redacted text across them.
     *
     * A count alone is not a budget. The pack contract caps a chunk's text at a size
     * chosen so a *valid* pack cannot be rejected for being verbose, not so five of them
     * fit in a phone-sized context window — five chunks at that ceiling is megabytes, and
     * the model would either truncate the prompt itself (silently losing the last chunks,
     * and any instruction after them) or fail to run at all. 16 KiB of prose is roughly
     * four thousand tokens, which leaves an E2B-class window room for the prompt scaffold,
     * the question, and the answer.
     */
    private val maxContextBytes = 16 * 1024

    fun route(
        retrieved: Retrieved,
        generator: Generator?,
        activePacks: List<String>,
        hasInactivePacks: Boolean = false,
        downloadBytes: Long? = null,
    ): Answer {
        val diagnostics = mutableListOf<String>()
        retrieved.gatedOut.forEach { diagnostics += "gated out: $it" }

        // Retrieval returns nothing exactly when gating left nothing, which is the refusal
        // and never a generated card.
        if (retrieved.candidates.isEmpty()) {
            return Answer(
                listOf(Card.Empty(activePacks = activePacks, canSearchInactive = hasInactivePacks)),
                diagnostics + "no candidate cleared its gate",
            )
        }

        val quotes = mutableListOf<Card>()
        val derived = mutableListOf<Card>()
        val settings = mutableListOf<Candidate>()

        // What the user will actually see answered. Built as cards are *emitted*, not from
        // the candidate list, because a card suppressed for an unresolvable citation covers
        // nothing on screen -- subtracting its intent from the residual would leave the one
        // part of the question nobody answered missing from both halves of the split.
        val covered = mutableListOf<CardSummary>()

        for (candidate in retrieved.candidates) {
            diagnostics += "${candidate.stableKey ?: candidate.ref}: " +
                "${candidate.kind}/${candidate.origin} via ${candidate.contributions}"

            when {
                candidate.origin == "source" &&
                    candidate.kind in PackSchema.VERBATIM_ELIGIBLE_KINDS -> {
                    quoteCard(candidate)?.let {
                        quotes += it
                        covered += CardSummary(candidate.kind, candidate.headingPath)
                    }
                        ?: diagnostics.plusAssign("${candidate.ref}: citation unresolved, card suppressed")
                }

                // Route 2, easy to miss and it matters. A ('glossary','derived') definition
                // or a builder-written statblock summary is not setting text and must not
                // be fed to the model. It is also not quotable. It is stored prose with
                // known provenance and it renders as exactly that -- regenerating it on the
                // phone would be slower, worse, and would put a second unverified
                // generation between the user and text a frontier model already produced
                // and the builder already claim-checked.
                candidate.origin == "derived" -> {
                    derivedCard(candidate)?.let {
                        derived += it
                        covered += CardSummary(candidate.kind, candidate.headingPath)
                    }
                        ?: diagnostics.plusAssign("${candidate.ref}: citation unresolved, card suppressed")
                }

                candidate.kind == "setting" && candidate.origin == "source" -> settings += candidate
            }
        }

        val generatedCard =
            generate(settings, covered, retrieved.normalizedQuery, generator, downloadBytes, diagnostics)

        // Quotes lead because a rules answer is what the user most likely came for, and
        // because quotes are free. The generated card is last because it is the
        // least-verified thing on screen, and position is one of the signals saying so.
        val cards = quotes + derived + listOfNotNull(generatedCard)
        if (cards.isEmpty()) {
            return Answer(
                listOf(Card.Empty(activePacks = activePacks, canSearchInactive = hasInactivePacks)),
                diagnostics + "every candidate's card was suppressed",
            )
        }
        return Answer(cards, diagnostics)
    }

    // ------------------------------------------------------------------ route 1

    private fun quoteCard(candidate: Candidate): Card.Verbatim? {
        // A citation that cannot be resolved is a bug, not a display case. No card renders
        // a partial or placeholder citation: an attributed card with a broken citation is
        // indistinguishable to a user from one with a working one.
        val citation = citations.resolve(candidate.ref) ?: return null
        return Card.Verbatim(
            ref = candidate.ref,
            kind = candidate.kind,
            body = candidate.text,
            citation = citation,
            rollable = candidate.ref in rollable,
        )
    }

    // ------------------------------------------------------------------ route 2

    private fun derivedCard(candidate: Candidate): Card.Derived? {
        val rows = derivations.derivationsOf(candidate.ref)
        if (rows.isEmpty()) return null

        val chips = mutableListOf<Chip>()
        val footer = mutableListOf<Citation>()
        for (row in rows) {
            // Resolved through the *cited* chunk, because a derived chunk has no citation
            // columns of its own -- which is what stops its citation drifting from the
            // chunks it was actually built from.
            val citation = citations.resolve(row.cited) ?: return null
            if (row.claimStart != null && row.claimEnd != null) {
                chips += Chip(row.claimStart, row.claimEnd, citation)
            } else {
                footer += citation
            }
        }
        return Card.Derived(candidate.ref, candidate.text, chips.sortedBy { it.start }, footer)
    }

    // ------------------------------------------------------------------ route 3

    private fun generate(
        settings: List<Candidate>,
        covered: List<CardSummary>,
        normalizedQuery: String,
        generator: Generator?,
        downloadBytes: Long?,
        diagnostics: MutableList<String>,
    ): Card? {
        if (settings.isEmpty()) return null

        val availability = generator?.availability ?: Availability.NotDownloaded
        if (availability !is Availability.Ready) {
            // Citations only. Mixed results degrade rather than fail: quote and derived
            // cards render exactly as they always do, because they never needed the model.
            val listed = resolveAll(settings.map { it.ref }, diagnostics) ?: return null
            // The three states are different statements with different remedies, and a
            // user who already chose to download must not be asked to choose again.
            val statement = when (availability) {
                is Availability.NotDownloaded ->
                    "These passages answer your question, but the model that would phrase " +
                        "them has not been downloaded."
                is Availability.Downloading ->
                    "These passages answer your question. The model that would phrase them " +
                        "is still downloading (${availability.fetched} of ${availability.total} bytes)."
                is Availability.Failed ->
                    "These passages answer your question, but the model that would phrase " +
                        "them could not be loaded: ${availability.reason}"
                is Availability.Ready -> error("unreachable")
            }
            return Card.ModelUnavailable(
                statement = statement,
                wouldHaveUsed = listed,
                downloadBytes = if (availability is Availability.NotDownloaded) downloadBytes else null,
                availability = availability,
            )
        }

        // Chunks are dropped whole, never truncated: a half-sent setting chunk is a passage
        // whose ending -- often the qualification that changes its meaning -- is missing,
        // and the model has no way to know it was cut. So the budget is spent in fused rank
        // order and stops at the first chunk that will not fit; everything below it goes
        // too, and the count is recorded rather than absorbed silently.
        val context = mutableListOf<RedactedChunk>()
        var spent = 0
        var droppedForBytes = 0
        var unredactable = 0
        var considered = 0
        for (candidate in settings) {
            // **The cap counts chunks that made it in**, not positions in the candidate
            // list. Counting positions let a candidate that could not be redacted safely
            // consume a slot on its way to being skipped, so five unredactable candidates
            // in a row ended generation before it reached usable evidence sitting at rank
            // six -- and reported it as "every chunk redacted to nothing", which was true
            // of the five it looked at and false of the answer.
            if (context.size >= maxContextChunks) break
            considered++
            val chunk = RedactedChunk.of(
                candidate.ref,
                candidate.headingPath,
                redactedTextOf(candidate),
            )
            if (chunk == null) {
                unredactable++
                continue
            }
            val cost = chunk.redactedText.toByteArray(Charsets.UTF_8).size
            if (spent + cost > maxContextBytes) {
                droppedForBytes = settings.size - considered + 1
                break
            }
            context += chunk
            spent += cost
        }
        if (unredactable > 0) {
            diagnostics += "$unredactable chunk(s) could not be redacted safely and were skipped"
        }
        if (settings.size > considered && droppedForBytes == 0) {
            diagnostics += "context capped at $maxContextChunks; " +
                "${settings.size - considered} lower-ranked chunks dropped"
        }
        if (droppedForBytes > 0) {
            diagnostics += "context capped at $maxContextBytes bytes ($spent used); " +
                "$droppedForBytes lower-ranked chunks dropped"
        }

        // Redaction can empty the context, and that case is **not a refusal**. Route 3
        // fired -- retrieval genuinely matched -- so no generated card is produced, because
        // a model asked to answer from an empty context answers from pretraining wearing
        // the generated card's styling.
        if (context.isEmpty()) {
            diagnostics += "route 3 fired but every chunk redacted to nothing or unsafely"
            val listed = resolveAll(settings.map { it.ref }, diagnostics) ?: return null
            return Card.ModelUnavailable(
                statement = "These passages matched, but their quotable content cannot be " +
                    "separated from the rest, so they are listed rather than summarised.",
                wouldHaveUsed = listed,
                downloadBytes = null,
                availability = Availability.Ready,
            )
        }

        // The question carries rules intent even when the rules chunks do not enter the
        // context, so generation receives only the residual.
        val residual = if (covered.isEmpty()) {
            normalizedQuery
        } else {
            // **Blank means the quotes answered everything**, and spec 05 section 2.3 says
            // so explicitly -- so a compliant generator returning "" was being handed
            // straight to `answer`, which then produced prose about whatever was left in
            // the context and appended it to an already-complete answer. Null and blank are
            // the same statement made two ways; both end route 3 here, and neither is a
            // failure.
            val split = runCatching { generator!!.residualIntent(normalizedQuery, covered) }
                .getOrElse {
                    diagnostics += "route 3 dropped: the model failed — ${it.message}"
                    return null
                }
            if (split.isNullOrBlank()) {
                diagnostics += "route 3 dropped: the quote cards answered the whole question"
                return null
            }
            split
        }

        // **A model that throws costs the generated card and nothing else.** Loading
        // several gigabytes of weights, or running inference under memory pressure, fails
        // in ways no caller can anticipate -- and letting that escape `route` discarded the
        // quote and derived cards the same question had already produced correctly, turning
        // "the optional prose is unavailable" into "this question failed". The generated
        // card is the one part of an answer the app is designed to do without; the quoted
        // rules are the part it exists for.
        val answer = runCatching { generator!!.answer(residual, context) }.getOrElse {
            diagnostics += "generated card suppressed: the model failed — ${it.message}"
            return null
        }
        val validated = Attributions.validate(answer, context).getOrElse {
            // An attribution naming a chunk that was not in the context is a generation
            // failure, and the card is not rendered -- the same rule as an unresolvable
            // citation.
            diagnostics += "generated card suppressed: ${it.message}"
            return null
        }
        validated.discarded.forEach { diagnostics += "attribution discarded: $it" }

        val chips = mutableListOf<Chip>()
        val footer = linkedSetOf<Citation>()
        for (region in validated.regions) {
            when (region) {
                is SupportedRegion.Cited -> {
                    val citation = citations.resolve(region.chunk) ?: return null
                    chips += chipOf(region, citation)
                }
                is SupportedRegion.Contextual ->
                    footer += resolveAll(region.context, diagnostics) ?: return null
            }
        }
        return Card.Generated(validated.text, chips, footer.toList())
    }

    /**
     * Every citation, or null if any one of them fails.
     *
     * Citation failure is all-or-nothing. Dropping the unresolvable ones renders a card
     * that lists *some* of the passages it found and says nothing about the rest — a
     * partial attributed card, which is indistinguishable to a user from a complete one
     * and is exactly what the no-placeholder-citations rule forbids.
     */
    private fun resolveAll(refs: List<ChunkRef>, diagnostics: MutableList<String>): List<Citation>? {
        val resolved = mutableListOf<Citation>()
        for (ref in refs) {
            val citation = citations.resolve(ref)
            if (citation == null) {
                diagnostics += "$ref: citation unresolved, card suppressed"
                return null
            }
            resolved += citation
        }
        return resolved
    }

    /**
     * The candidate's redacted text, as retrieval already computed it.
     *
     * Retrieval's `Nesting` produced this and the lexical independence test read the same
     * string, so the two uses agree **by construction** rather than by two
     * implementations happening to excise the same bytes. A second copy here was exactly
     * that second implementation, and it disagreed: it inserted a different marker.
     */
    private fun redactedTextOf(candidate: Candidate): String? = candidate.redactedText
}
