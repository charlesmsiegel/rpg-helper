package dev.rpghelper.session

import dev.rpghelper.pack.ChunkRef
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
