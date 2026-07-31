package dev.rpghelper.session

import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.pack.PackSchema
import dev.rpghelper.pack.map
import dev.rpghelper.routing.Citation
import dev.rpghelper.routing.PackCitations
import dev.rpghelper.state.Constraint
import dev.rpghelper.state.ConstraintEngine
import dev.rpghelper.state.ConstraintParser
import dev.rpghelper.state.ConstraintSet
import dev.rpghelper.state.DocumentStore
import dev.rpghelper.state.DroppedConstraint
import dev.rpghelper.state.Violation

/**
 * One violation as a surface shows it: the flag, where it came from, and whether the user
 * has already said they meant it.
 *
 * The citation is resolved here rather than left to the caller because a violation with no
 * passage behind it is an assertion the app cannot back up — `07-documents-and-constraints-spec.md`
 * §4.7 requires tapping one to open the rule it came from. A null citation means the
 * constraint's chunk could not be resolved in the pack that supplied it, which is a
 * damaged pack rather than a rule without a source; the violation is still shown, because
 * suppressing a real rule to hide a broken locator is the worse trade.
 */
data class Flagged(
    val violation: Violation,
    val citation: Citation?,
    /** True when the user has accepted this exact rule on this document. */
    val accepted: Boolean,
    val note: String?,
)

/** What evaluating one document produced. */
data class RuleCheck(
    /** Violations the user has not accepted. These are the ones a surface shows as flags. */
    val flagged: List<Flagged>,
    /** Accepted violations, listed separately as deliberate deviations. */
    val accepted: List<Flagged>,
    /** Constraint rows that could not be loaded, and why. Shown on the Packs surface. */
    val dropped: List<DroppedConstraint>,
    /**
     * True when the document names a ruleset **no active pack supplies**.
     *
     * The difference between "checked, and clean" and "not checked" is the whole value of
     * the check: a sheet bound to a game whose book is deactivated has no constraints to
     * evaluate, and reporting that as zero violations would tell the user their character
     * is legal under rules the app cannot see. Same vacuity as a recall run with no
     * positive queries — a result over nothing is not a passing result.
     */
    val unchecked: Boolean,
) {
    /**
     * The document was checked against real rules and matches the book as written.
     *
     * **An accepted violation is not clean.** Acceptance moves a deviation out of the flags
     * — it is a house rule, not a mistake — but the sheet still departs from the book, and
     * that is precisely what a house rule is. A `clean` that ignored acceptances would let
     * a surface say "matches the book" about a character the user deliberately built
     * outside it.
     */
    val clean: Boolean get() = !unchecked && flagged.isEmpty() && accepted.isEmpty()
}

/**
 * How much checking a document has actually had — **four states, because four remedies**.
 *
 * `06-ui-spec.md` §2.3. [PARTLY_VALIDATED] is the one that has to exist: a malformed
 * constraint row is dropped rather than rejecting the whole pack, so without it a document
 * would read as *validated* while a rule it should have been checked against never ran.
 * Unlike a missing capability, which shows as a control that never appears, a missing
 * constraint has no natural symptom at all.
 */
enum class Validation {
    /** Bound to a ruleset an active pack supplies, and every constraint loaded. */
    VALIDATED,

    /** As above, but *N* constraints could not be loaded. The Packs surface names them. */
    PARTLY_VALIDATED,

    /** Bound, but the pack is absent or inactive. Install or activate it. */
    UNVALIDATED,

    /** No ruleset. Trackers work and nothing is checked. */
    UNBOUND,
}

/** One document as a list shows it. */
data class SheetState(
    val documentId: Long,
    val title: String,
    val campaign: String?,
    val draft: Boolean,
    val validation: Validation,
    /** Unaccepted violations. The number a list badge shows. */
    val violations: Int,
    /** Deliberate deviations, kept out of the count and out of the flags. */
    val accepted: Int,
    /** Constraint rows the pack could not load. Non-zero is what makes a sheet *partly*. */
    val dropped: Int,
)

/**
 * A passage, quoted, for tap-through from a violation.
 *
 * The feature that makes constraints worth having: a flag saying *aptitude.might is 7, this
 * game allows 1–5* is useful, and one that also opens the paragraph on page 2 saying so is
 * what settles the argument. Byte-exact and cited, like every other quotation this app
 * shows — a rule rendered as a paraphrase would be the app arguing on its own authority.
 */
data class Passage(
    val ref: ChunkRef,
    /**
     * The chunk's own bytes, or **null when the chunk may not be quoted**.
     *
     * A pack may root a constraint at any chunk it likes, including a builder-written
     * summary. Rendering that under the quotation rule would launder derived prose into the
     * book's own words beneath a real citation — past the routing partition that exists to
     * stop exactly that, and through a surface that never goes near the router. So the same
     * `origin == "source" && kind ∈ VERBATIM_ELIGIBLE_KINDS` test is made here, and a
     * chunk that fails it yields a citation the user can follow and no quotation.
     */
    val text: String?,
    val citation: Citation?,
)

/**
 * Loads a ruleset's constraints out of the active set and evaluates documents against them.
 *
 * **This is the production caller `ConstraintParser` and `ConstraintEngine` did not have.**
 * Both were complete and tested, and nothing outside their own tests ever built a
 * `ConstraintSet` from a pack — so the state layer could persist trackers and could not
 * validate them against a single installed rule. A validator with no loader is not a
 * partially-built feature; it is a feature that appears to exist.
 *
 * Everything here reads the connections [Library] already holds, under the lease that
 * makes them safe to read, and evaluates entirely locally: no model, no retrieval, no
 * network, per spec §4.6.
 */
object Rules {

    /**
     * Every constraint row from every active pack whose `ruleset_id` matches [rulesetId].
     *
     * Core book and supplement both contribute; that is how a supplement adds rules to a
     * game. **No conflict resolution is attempted** — if two packs in one ruleset declare
     * contradictory bounds, both evaluate, because the app cannot know which book wins and
     * guessing would silently suppress a rule the user owns. Supersession is the mechanism
     * for genuine corrections, and it is applied here: a constraint rooted at a withdrawn
     * chunk is dropped along with everything else that chunk carried, so an erratum removes
     * the rule it corrects rather than competing with it.
     *
     * A row that will not parse is **dropped and reported**, never silently skipped. Unlike
     * a capability, whose absence shows as a control that never appears, a missing
     * constraint leaves a document displaying as validated against a rule that never
     * loaded.
     */
    fun constraintsFor(library: Library, rulesetId: String): ConstraintSet {
        val constraints = mutableListOf<Constraint>()
        val dropped = mutableListOf<DroppedConstraint>()

        for (pack in library.packs) {
            if (library.rulesets[pack.packUid] != rulesetId) continue
            // Lower `installed_packs.priority` is higher precedence, and the engine sorts
            // ascending -- so this is passed through as the pack's own number rather than
            // inverted. The order decides which of two firing rules is cited first, and a
            // rule stated so two implementations pick the same passage is not one an
            // iteration order gets to decide.
            val priority = library.active.priority[pack.packUid] ?: 0
            val withdrawn = library.active.superseded.chunksIn(pack.packUid)

            pack.db.map(
                "SELECT constraint_id, form, args, chunk_id FROM constraints ORDER BY constraint_id",
            ) { listOf(it.long(0), it.string(1), it.string(2), it.long(3)) }.forEach { fields ->
                val constraintId = fields[0] as Long
                val form = fields[1] as String
                val args = fields[2] as String
                val chunkId = fields[3] as Long

                if (chunkId in withdrawn) {
                    dropped += DroppedConstraint(
                        constraintId,
                        "rooted at chunk $chunkId, which an active correction has withdrawn",
                    )
                    return@forEach
                }

                ConstraintParser.parse(constraintId, rulesetId, form, args, chunkId, priority)
                    .onSuccess { constraints += it }
                    .onFailure {
                        dropped += DroppedConstraint(
                            constraintId,
                            "${pack.packUid}: $form does not load -- ${it.message}",
                        )
                    }
            }
        }
        return ConstraintSet(constraints, dropped)
    }

    /**
     * Evaluates one document against the active set.
     *
     * An **unbound** document — one naming no ruleset — is not checked and does not claim
     * to be: constraints load only from packs declaring the ruleset the document names, so
     * there is nothing to evaluate and [RuleCheck.unchecked] says so rather than reporting
     * a clean sheet.
     *
     * Acceptances are matched by fingerprint, which contains the ruleset, so one made under
     * a previous binding goes dormant rather than following the document into a new game.
     */
    fun check(library: Library, documents: DocumentStore, documentId: Long): RuleCheck {
        val document = documents.get(documentId)
            ?: return RuleCheck(emptyList(), emptyList(), emptyList(), unchecked = true)
        val rulesetId = document.rulesetId
            ?: return RuleCheck(emptyList(), emptyList(), emptyList(), unchecked = true)

        val set = constraintsFor(library, rulesetId)
        if (set.constraints.isEmpty()) {
            // Bound to a game no active pack supplies. The dropped rows still travel: they
            // are the difference between "this ruleset has no rules" and "its rules would
            // not load", and a user staring at an unchecked sheet deserves the second one.
            return RuleCheck(emptyList(), emptyList(), set.dropped, unchecked = true)
        }

        val violations = ConstraintEngine(set.constraints)
            .evaluate(documents.trackers(documentId), document.draft)
        val accepted = documents.acceptances(documentId)
            .filter { it.rulesetId == rulesetId }
            .associate { it.fingerprint to it.note }

        val citations = PackCitations(library.packs)
        val flagged = violations.map { violation ->
            Flagged(
                violation = violation,
                citation = citations.resolve(chunkOf(library, violation)),
                accepted = violation.fingerprint in accepted,
                note = accepted[violation.fingerprint],
            )
        }
        return RuleCheck(
            flagged = flagged.filterNot { it.accepted },
            accepted = flagged.filter { it.accepted },
            dropped = set.dropped,
            unchecked = false,
        )
    }

    /**
     * Every document, with how much checking it has had.
     *
     * Constraints are loaded **once per ruleset**, not once per document: a campaign is
     * typically a dozen sheets bound to the same game, and re-reading and re-parsing the
     * same rows for each of them would make opening the list a function of how many
     * characters the user has rather than of how many games.
     */
    fun states(library: Library, documents: DocumentStore): List<SheetState> {
        val byRuleset = mutableMapOf<String, ConstraintSet>()
        return documents.all().map { document ->
            val rulesetId = document.rulesetId
            if (rulesetId == null) {
                return@map SheetState(
                    documentId = document.documentId,
                    title = document.title,
                    campaign = document.campaign,
                    draft = document.draft,
                    validation = Validation.UNBOUND,
                    violations = 0,
                    accepted = 0,
                    dropped = 0,
                )
            }
            val set = byRuleset.getOrPut(rulesetId) { constraintsFor(library, rulesetId) }
            val trackers = documents.trackers(document.documentId)
            val accepted = documents.acceptances(document.documentId)
                .filter { it.rulesetId == rulesetId }
                .map { it.fingerprint }
                .toSet()
            val violations = if (set.constraints.isEmpty()) emptyList() else
                ConstraintEngine(set.constraints).evaluate(trackers, document.draft)

            SheetState(
                documentId = document.documentId,
                title = document.title,
                campaign = document.campaign,
                draft = document.draft,
                validation = when {
                    set.constraints.isEmpty() -> Validation.UNVALIDATED
                    set.dropped.isNotEmpty() -> Validation.PARTLY_VALIDATED
                    else -> Validation.VALIDATED
                },
                violations = violations.count { it.fingerprint !in accepted },
                accepted = violations.count { it.fingerprint in accepted },
                dropped = set.dropped.size,
            )
        }
    }

    /**
     * The passage a violation came from, quoted byte for byte.
     *
     * Returns null when the chunk cannot be resolved — a damaged pack, or a rule from a book
     * that has since been deactivated. The caller shows the violation either way: hiding a
     * real rule because its locator broke is the worse trade.
     */
    fun passage(library: Library, violation: Violation): Passage? {
        val ref = chunkOf(library, violation)
        val pack = library.packs.firstOrNull { it.packUid == ref.packUid } ?: return null
        val row = pack.db.map(
            "SELECT text, kind, origin FROM chunks WHERE chunk_id = ${ref.chunkId}",
        ) { Triple(it.string(0), it.string(1), it.string(2)) }.firstOrNull() ?: return null
        val quotable = row.third == "source" && row.second in PackSchema.VERBATIM_ELIGIBLE_KINDS
        return Passage(
            ref = ref,
            text = if (quotable) row.first else null,
            citation = PackCitations(library.packs).resolve(ref),
        )
    }

    /**
     * Which pack's chunk a violation points at.
     *
     * A `Violation` carries a bare `chunk_id`, and a chunk is identified everywhere
     * downstream by `(pack_uid, chunk_id)` — so resolving one without knowing its pack
     * would render a rule from the core book under a supplement's title the moment two
     * active packs in one ruleset happened to number a chunk alike. The constraint's
     * ruleset narrows the candidates; priority order picks among them the same way
     * retrieval does.
     */
    private fun chunkOf(library: Library, violation: Violation): ChunkRef {
        val candidates = library.packs
            .filter { library.rulesets[it.packUid] == violation.rulesetId }
            .sortedBy { library.active.priority[it.packUid] ?: Int.MAX_VALUE }
        val owner = candidates.firstOrNull { pack ->
            pack.db.map(
                "SELECT 1 FROM chunks WHERE chunk_id = ${violation.chunkId}",
            ) { it.long(0) }.isNotEmpty()
        }
        return ChunkRef(owner?.packUid ?: "", violation.chunkId)
    }
}
