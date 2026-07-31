package dev.rpghelper.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.security.MessageDigest

/**
 * A selector over tracker keys.
 *
 * Two shapes and nothing else: an exact key, or a trailing `.*` prefix. No regex, no
 * alternation, no interior wildcards — a selector is meant to be read aloud in a
 * violation message.
 */
data class Selector(val text: String) {
    private val prefix: String? = if (text.endsWith(".*")) text.dropLast(1) else null

    init {
        // Validated on construction rather than checked at use. A malformed selector --
        // `attribute*`, an interior wildcard, an empty string -- is not a rule that
        // matches nothing; it is a rule that was never loaded, and a document showing as
        // validated against a rule that silently did not run is the failure this whole
        // subsystem exists to avoid. Refusing here routes it to the dropped-constraint
        // report, which is a thing someone can read.
        requireKey(if (prefix != null) text.dropLast(2) else text, "selector '$text'")
    }

    fun matches(key: String): Boolean =
        if (prefix != null) key.startsWith(prefix) else key == text

    override fun toString(): String = text

    companion object {
        /** The same production `DocumentStore.normalizeKey` writes keys into. */
        private val SEGMENT = Regex("[a-z0-9-]+")

        /**
         * Requires an exact tracker key — the form with no wildcard at all.
         *
         * Used for the selector's own key and for a bound's tracker reference. `*` is
         * not a legal segment, so `attribute.*` fails here without a separate test.
         */
        fun requireKey(key: String, what: String) {
            require(key.isNotEmpty()) { "$what names no tracker key" }
            for (segment in key.split('.')) {
                require(SEGMENT.matches(segment)) {
                    "$what has segment '$segment'; segments must match [a-z0-9-]+"
                }
            }
        }
    }
}

/** A bound: a literal, or the value of another tracker. */
sealed interface Bound {
    data class Literal(val value: Double) : Bound

    data class TrackerRef(val key: String) : Bound {
        // A reference resolves one tracker's value, so it must name one exactly. A
        // wildcard here would resolve to nothing and read as "unbounded".
        init {
            Selector.requireKey(key, "tracker reference '$key'")
        }
    }

    /**
     * Resolves against [trackers], or null when it cannot constrain.
     *
     * A reference to an absent tracker does not constrain: the sheet is incomplete, not
     * illegal, and inventing a default would be inventing a rule the book does not have.
     */
    fun resolve(trackers: Map<String, TrackerValue>): Double? = when (this) {
        is Literal -> value
        is TrackerRef -> (trackers[key] as? TrackerValue.Number)?.value
    }
}

/** One of the five forms, instantiated with its arguments. */
sealed interface Constraint {
    val chunkId: Long
    val rulesetId: String

    /**
     * `constraints.constraint_id`, and the priority of the pack it came from.
     *
     * Carried because the spec pins which passage a *shared* violation cites: where
     * several rows produce the same `excludes` pair, the citation is the one from the
     * highest-priority pack, ties falling to the lowest `constraint_id`. Without the two
     * keys the winner is whichever row the loader happened to reach first — so a rebuild
     * or a reordering of packs could change the passage shown beneath a rule the user
     * already read.
     */
    val constraintId: Long
    val packPriority: Int

    /**
     * Everything the rule *says*, for the fingerprint.
     *
     * The bounds belong here, not just the selector: accepting "at most 5" is not
     * accepting "at most 6", so a pack that tightens a rule must invalidate an acceptance
     * of the looser one.
     */
    fun canonicalArgs(): List<Pair<String, String>>

    data class Range(
        override val chunkId: Long,
        override val rulesetId: String,
        override val constraintId: Long = 0,
        override val packPriority: Int = 0,
        val selector: Selector,
        val min: Bound?,
        val max: Bound?,
    ) : Constraint {
        override fun canonicalArgs() = rangeArgs(selector, min, max)
    }

    data class SumRange(
        override val chunkId: Long,
        override val rulesetId: String,
        override val constraintId: Long = 0,
        override val packPriority: Int = 0,
        val selector: Selector,
        val min: Bound?,
        val max: Bound?,
    ) : Constraint {
        override fun canonicalArgs() = rangeArgs(selector, min, max)
    }

    data class CountRange(
        override val chunkId: Long,
        override val rulesetId: String,
        override val constraintId: Long = 0,
        override val packPriority: Int = 0,
        val selector: Selector,
        val min: Bound?,
        val max: Bound?,
    ) : Constraint {
        override fun canonicalArgs() = rangeArgs(selector, min, max)
    }

    data class Requires(
        override val chunkId: Long,
        override val rulesetId: String,
        val subject: Selector,
        override val constraintId: Long = 0,
        override val packPriority: Int = 0,
        val requirements: List<Requirement>,
    ) : Constraint {
        data class Requirement(val selector: Selector, val min: Double?)

        override fun canonicalArgs() = listOf(
            "subject" to subject.text,
            "requires" to requirements.sortedBy { it.selector.text }
                .joinToString(",") { "${it.selector}${it.min?.let { m -> ">=$m" } ?: ""}" },
        )
    }

    data class Excludes(
        override val chunkId: Long,
        override val rulesetId: String,
        val subject: Selector,
        val excluded: List<Selector>,
        override val constraintId: Long = 0,
        override val packPriority: Int = 0,
    ) : Constraint {
        // Never used for the violation fingerprint, which is over the unordered pair --
        // see ConstraintEngine.checkExcludes. Present so the interface stays total.
        override fun canonicalArgs() = listOf(
            "subject" to subject.text,
            "excludes" to excluded.map { it.text }.sorted().joinToString(","),
        )
    }
}

private fun rangeArgs(selector: Selector, min: Bound?, max: Bound?) = listOf(
    "selector" to selector.text,
    "min" to render(min),
    "max" to render(max),
)

private fun render(bound: Bound?): String = when (bound) {
    null -> ""
    is Bound.Literal -> bound.value.toString()
    is Bound.TrackerRef -> "@${bound.key}"
}

/** A rule a document departs from, with the values that made it fail. */
data class Violation(
    val fingerprint: String,
    val rulesetId: String,
    val chunkId: Long,
    val explanation: String,
)

/** A constraint row that could not be loaded, and why. */
data class DroppedConstraint(val constraintId: Long, val reason: String)

/** What loading a ruleset's constraints produced. */
data class ConstraintSet(
    val constraints: List<Constraint>,
    val dropped: List<DroppedConstraint>,
)

/**
 * Parses `constraints` rows into the closed vocabulary.
 *
 * A row that fails is **dropped and reported**, never silently ignored. Rejecting a
 * 300-page book over one malformed constraint is disproportionate, but a silently missing
 * constraint is worse than it looks: unlike a capability, whose absence shows as a control
 * that never appears, it leaves a document displaying as validated while a rule it should
 * have been checked against never loaded.
 */
object ConstraintParser {

    private val json = Json { ignoreUnknownKeys = true }

    fun parse(
        constraintId: Long,
        rulesetId: String,
        form: String,
        args: String,
        chunkId: Long,
        packPriority: Int = 0,
    ): Result<Constraint> = runCatching {
        val obj = json.parseToJsonElement(args).jsonObject
        requireKnownKeys(obj, ARGUMENT_KEYS[form] ?: error("unknown constraint form '$form'"))
        when (form) {
            "range" -> Constraint.Range(
                chunkId, rulesetId, constraintId, packPriority,
                selector(obj, "selector"), bound(obj, "min"), bound(obj, "max"),
            ).also { requireUsableBounds(it.selector, it.min, it.max) }

            "sum_range" -> Constraint.SumRange(
                chunkId, rulesetId, constraintId, packPriority,
                selector(obj, "selector"), bound(obj, "min"), bound(obj, "max"),
            ).also { requireUsableBounds(it.selector, it.min, it.max) }

            "count_range" -> Constraint.CountRange(
                chunkId, rulesetId, constraintId, packPriority,
                selector(obj, "selector"), bound(obj, "min"), bound(obj, "max"),
            ).also { requireUsableBounds(it.selector, it.min, it.max) }

            "requires" -> Constraint.Requires(
                chunkId, rulesetId, selector(obj, "subject"), constraintId, packPriority,
                obj.getValue("requires").jsonArray.map { element ->
                    val requirement = element.jsonObject
                    requireKnownKeys(requirement, REQUIREMENT_KEYS)
                    Constraint.Requires.Requirement(
                        selector(requirement, "selector"),
                        number(requirement, "min"),
                    )
                },
            )

            "excludes" -> Constraint.Excludes(
                chunkId, rulesetId, selector(obj, "subject"),
                obj.getValue("excludes").jsonArray.map { Selector(it.jsonPrimitive.content) },
                constraintId, packPriority,
            ).also(::requireDistinctExclusions)

            else -> error("unknown constraint form '$form'")
        }
    }

    /**
     * A selector must not match its own bound tracker.
     *
     * `sum_range` over `freebie.*` bounded by `freebie.budget` sums the budget along with
     * everything spent, so the rule fails the moment a single point is spent. It is an
     * easy authoring mistake and a silent one, so it is a drop rather than a warning.
     */
    private fun requireUsableBounds(selector: Selector, min: Bound?, max: Bound?) {
        for (bound in listOfNotNull(min, max)) {
            if (bound is Bound.TrackerRef && selector.matches(bound.key)) {
                error("selector '$selector' matches its own bound tracker '${bound.key}'")
            }
        }
        // Two literals in the wrong order describe a range nothing can satisfy. Every
        // matching value then violates one side or the other, and the UI offers the user
        // an impossible correction -- "this game allows 5-1". A rule that flags every
        // document is a malformed rule, and it belongs in the dropped report rather than
        // on screen. Tracker-referenced bounds are left alone: those depend on values
        // this parser cannot see, and an inversion there is a fact about one document.
        val low = (min as? Bound.Literal)?.value
        val high = (max as? Bound.Literal)?.value
        if (low != null && high != null && low > high) {
            error("bounds are inverted: min $low is above max $high")
        }
    }

    /**
     * A selector must not exclude itself.
     *
     * `setOf` in the engine collapses the pair to one element, and the violation message
     * needs two halves. Rejecting here rather than handling the singleton is deliberate:
     * "X cannot be taken with X" is not a rule anyone meant to write, so a pack carrying
     * one has an authoring bug that should reach the dropped-constraint report.
     */
    private fun requireDistinctExclusions(c: Constraint.Excludes) {
        for (excluded in c.excluded) {
            if (excluded.text == c.subject.text) {
                error("'${c.subject}' excludes itself")
            }
        }
    }

    /** Every key each form understands, and nothing else is tolerated. */
    private val ARGUMENT_KEYS = mapOf(
        "range" to setOf("selector", "min", "max"),
        "sum_range" to setOf("selector", "min", "max"),
        "count_range" to setOf("selector", "min", "max"),
        "requires" to setOf("subject", "requires"),
        "excludes" to setOf("subject", "excludes"),
    )

    private val REQUIREMENT_KEYS = setOf("selector", "min")

    /**
     * A key this form does not understand is a dropped constraint, not a shrug.
     *
     * `{"selector": "attribute.*", "mx": 5}` parses, and every optional field it misspelled
     * reads as absent — so a 1-5 range silently becomes "at least 1", the document
     * validates, and nothing anywhere says a rule was weakened. That is the exact failure
     * mode the dropped-constraint report exists for: unlike a capability, whose absence
     * shows as a missing control, a constraint that half-loaded is invisible. Strictness
     * here costs an author one clear error message at build time.
     */
    private fun requireKnownKeys(obj: JsonObject, allowed: Set<String>) {
        val unknown = obj.keys - allowed
        if (unknown.isNotEmpty()) {
            error("unknown argument key(s) ${unknown.sorted()}; expected ${allowed.sorted()}")
        }
    }

    private fun selector(obj: JsonObject, field: String) =
        Selector(obj.getValue(field).jsonPrimitive.content)

    /**
     * A JSON number, or null when the field is absent.
     *
     * Present-but-not-a-number is an error rather than a null, because for `requires`
     * the two mean different rules: null is "you must have it at all", and a number is
     * "you must have it at this rating". Letting `"min": true` degrade to null silently
     * weakens the rule to presence and reports nothing.
     */
    private fun number(obj: JsonObject, field: String): Double? {
        val element = obj[field] ?: return null
        if (element is JsonNull) return null
        val primitive = element as? JsonPrimitive ?: error("'$field' must be a JSON number")
        return numberOf(field, primitive)
    }

    private fun numberOf(field: String, primitive: JsonPrimitive): Double {
        require(!primitive.isString) {
            "'$field' must be a JSON number, not the quoted string \"${primitive.content}\""
        }
        val value = primitive.doubleOrNull
            ?: error("'$field' is not a number: ${primitive.content}")
        // `1e309` is syntactically valid JSON and parses to infinity. An infinite maximum
        // turns a range into a rule nothing can violate; an infinite minimum turns it into
        // one every finite value violates. Both are silent -- the parser reports success,
        // and the dropped-constraint report, which exists precisely so a weakened rule is
        // never invisible, says nothing.
        if (!value.isFinite()) {
            error("'$field' is ${primitive.content}, which is not a finite number")
        }
        return value
    }

    private fun bound(obj: JsonObject, field: String): Bound? {
        val element = obj[field] ?: return null
        if (element is JsonNull) return null
        if (element is JsonPrimitive) return Bound.Literal(numberOf(field, element))
        val obj = element.jsonObject
        requireKnownKeys(obj, setOf("tracker"))
        return Bound.TrackerRef(obj.getValue("tracker").jsonPrimitive.content)
    }
}

/**
 * Evaluates a document against the constraints of the ruleset it is bound to.
 *
 * Constraints **advise**. A document that violates one is flagged, never rejected: house
 * rules are the norm, and an app that refuses to store a legal-at-this-table character is
 * an app that gets deleted.
 */
class ConstraintEngine(constraints: List<Constraint>) {

    /**
     * Evaluated in the order the spec's citation rule names: highest-priority pack first,
     * then lowest `constraint_id`.
     *
     * Sorting once here rather than reasoning about it at each use is what makes
     * "whichever fired first wins" *be* the specified winner. The `excludes` pair
     * deduplication depends on it, and a rule stated so two implementations pick the same
     * passage is not one the loader's iteration order gets to decide.
     */
    private val constraints: List<Constraint> =
        constraints.sortedWith(compareBy({ it.packPriority }, { it.constraintId }))

    /**
     * @param draft while set, **minimum bounds are not evaluated**. A half-entered sheet
     * violates every minimum it has not reached yet, and a user who sees eleven violations
     * on an empty document learns within one session that the flags mean nothing.
     */
    fun evaluate(trackers: List<Tracker>, draft: Boolean): List<Violation> {
        val byKey = trackers.associate { it.key to it.value }
        val violations = mutableListOf<Violation>()
        val seenPairs = mutableSetOf<Set<String>>()

        for (constraint in constraints) {
            when (constraint) {
                is Constraint.Range -> checkRange(constraint, byKey, draft, violations)
                is Constraint.SumRange -> checkSum(constraint, byKey, draft, violations)
                is Constraint.CountRange -> checkCount(constraint, byKey, draft, violations)
                is Constraint.Requires -> checkRequires(constraint, byKey, violations)
                is Constraint.Excludes -> checkExcludes(constraint, byKey, seenPairs, violations)
            }
        }
        return violations
    }

    private fun checkRange(
        c: Constraint.Range,
        trackers: Map<String, TrackerValue>,
        draft: Boolean,
        out: MutableList<Violation>,
    ) {
        // The one form that ignores presence. Presence treats zero as absence, which is
        // right for counting Virtues and wrong here: a Strength of 0 is a value, it
        // violates 1-5, and under presence semantics nothing could ever say so.
        for ((key, value) in trackers) {
            if (!c.selector.matches(key)) continue
            val number = (value as? TrackerValue.Number)?.value ?: continue
            val min = if (draft) null else c.min?.resolve(trackers)
            val max = c.max?.resolve(trackers)
            if (min != null && number < min || max != null && number > max) {
                out += violation(
                    c,
                    "$key is ${number.trim()}. This game allows ${describe(min, max)}.",
                    // The key distinguishes one match of a prefix selector from another,
                    // so accepting an over-cap Strength does not accept an over-cap Wits.
                    listOf("key" to key),
                )
            }
        }
    }

    private fun checkSum(
        c: Constraint.SumRange,
        trackers: Map<String, TrackerValue>,
        draft: Boolean,
        out: MutableList<Violation>,
    ) {
        val sum = trackers.entries
            .filter { c.selector.matches(it.key) }
            .sumOf { (it.value as? TrackerValue.Number)?.value ?: 0.0 }
        val min = if (draft) null else c.min?.resolve(trackers)
        val max = c.max?.resolve(trackers)
        if (min != null && sum < min || max != null && sum > max) {
            out += violation(
                c,
                "${c.selector} totals ${sum.trim()}. This game allows ${describe(min, max)}.",
                emptyList(),
            )
        }
    }

    private fun checkCount(
        c: Constraint.CountRange,
        trackers: Map<String, TrackerValue>,
        draft: Boolean,
        out: MutableList<Violation>,
    ) {
        val count = trackers.entries
            .count { c.selector.matches(it.key) && it.value.isPresent }
            .toDouble()
        val min = if (draft) null else c.min?.resolve(trackers)
        val max = c.max?.resolve(trackers)
        if (min != null && count < min || max != null && count > max) {
            out += violation(
                c,
                "You have ${count.trim()} of ${c.selector}. " +
                    "This game allows ${describe(min, max)}.",
                emptyList(),
            )
        }
    }

    private fun checkRequires(
        c: Constraint.Requires,
        trackers: Map<String, TrackerValue>,
        out: MutableList<Violation>,
    ) {
        // Nothing fires when the subject is absent: a prerequisite is a statement about
        // what you took, not about what you did not.
        if (trackers.none { c.subject.matches(it.key) && it.value.isPresent }) return

        for (requirement in c.requirements) {
            val matches = trackers.entries.filter { requirement.selector.matches(it.key) }
            val satisfied = if (requirement.min == null) {
                matches.any { it.value.isPresent }
            } else {
                // Only a numeric tracker can satisfy a numeric minimum. Defaulting a text
                // or flag tracker to 0.0 satisfies any minimum at or below zero, so a
                // prerequisite silently passes on a value that is not a rating at all.
                matches.any { entry ->
                    val number = (entry.value as? TrackerValue.Number)?.value
                    number != null && number >= requirement.min
                }
            }
            if (!satisfied) {
                val need = requirement.min?.let { "${requirement.selector} ${it.trim()}" }
                    ?: requirement.selector.text
                out += violation(
                    c,
                    "${c.subject} requires $need.",
                    listOf("failed" to requirement.selector.text),
                )
            }
        }
    }

    private fun checkExcludes(
        c: Constraint.Excludes,
        trackers: Map<String, TrackerValue>,
        seenPairs: MutableSet<Set<String>>,
        out: MutableList<Violation>,
    ) {
        if (trackers.none { c.subject.matches(it.key) && it.value.isPresent }) return

        for (excluded in c.excluded) {
            // The parser refuses a self-exclusion; this guards a Constraint built in code.
            // Without it the unordered pair below collapses to one element and the
            // message has no second half — a crash while evaluating someone's document.
            if (excluded.text == c.subject.text) continue
            if (trackers.none { excluded.matches(it.key) && it.value.isPresent }) continue

            // Identified by the unordered pair. "A excludes B" and "B excludes A" are the
            // same rule, and letting whichever row loaded first decide the identity would
            // mean a rebuild could resurrect an acceptance the user had already made.
            val pair = setOf(c.subject.text, excluded.text)
            if (!seenPairs.add(pair)) continue

            val ordered = pair.sorted()
            out += Violation(
                fingerprint = fingerprint(
                    c.rulesetId,
                    "excludes",
                    listOf("pair" to ordered.joinToString("|")),
                ),
                rulesetId = c.rulesetId,
                chunkId = c.chunkId,
                explanation = "${ordered[0]} cannot be taken with ${ordered[1]}.",
            )
        }
    }

    /** @param discriminators what tells two violations of the *same* rule apart. */
    private fun violation(
        c: Constraint,
        explanation: String,
        discriminators: List<Pair<String, String>>,
    ) =
        Violation(
            fingerprint = fingerprint(c.rulesetId, formName(c), c.canonicalArgs() + discriminators),
            rulesetId = c.rulesetId,
            chunkId = c.chunkId,
            explanation = explanation,
        )

    private fun formName(c: Constraint) = when (c) {
        is Constraint.Range -> "range"
        is Constraint.SumRange -> "sum_range"
        is Constraint.CountRange -> "count_range"
        is Constraint.Requires -> "requires"
        is Constraint.Excludes -> "excludes"
    }

    private fun describe(min: Double?, max: Double?): String = when {
        min != null && max != null && min == max -> "exactly ${min.trim()}"
        min != null && max != null -> "${min.trim()}–${max.trim()}"
        min != null -> "at least ${min.trim()}"
        max != null -> "at most ${max.trim()}"
        else -> "any value"
    }

    private fun Double.trim(): String =
        if (this == toLong().toDouble()) toLong().toString() else toString()

    companion object {
        /**
         * Identity of a rule, for acceptances that must survive a pack rebuild.
         *
         * `constraint_id` is pack-local and does not survive one — the same reason
         * supersession does not use `chunk_id`. A constraint's identity genuinely is what
         * it says, so the fingerprint is over the canonical `(ruleset, form, args)`:
         * rebuild the pack and it is unchanged; edit the rule's arguments and it changes,
         * which correctly invalidates an acceptance of the old one.
         */
        fun fingerprint(rulesetId: String, form: String, args: List<Pair<String, String>>): String {
            val canonical = buildString {
                append("{\"ruleset_id\":\"").append(rulesetId)
                append("\",\"form\":\"").append(form).append("\",\"args\":{")
                args.sortedBy { it.first }.forEachIndexed { index, (key, value) ->
                    if (index > 0) append(',')
                    append('"').append(key).append("\":\"").append(value).append('"')
                }
                append("}}")
            }
            return MessageDigest.getInstance("SHA-256")
                .digest(canonical.toByteArray(Charsets.UTF_8))
                .joinToString("") { "%02x".format(it) }
        }
    }
}
