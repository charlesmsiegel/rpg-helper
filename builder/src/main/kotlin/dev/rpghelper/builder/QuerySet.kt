package dev.rpghelper.builder

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** A passage a query should find, named the way that survives a rebuild. */
data class Relevant(val sourceUid: String, val stableKey: String)

/** One labelled query. An empty [relevant] means the app must refuse. */
data class LabelledQuery(
    val query: String,
    val relevant: List<Relevant>,
    val notes: String?,
) {
    val isNegative: Boolean get() = relevant.isEmpty()
}

/**
 * The labelled query set for one pack.
 *
 * **Labels reference `(source_uid, stable_key)`, never `chunk_id`.** A pack-local id does
 * not survive a rebuild of the corpus, and a label set that silently re-points at
 * different passages after a fixture change is worse than no label set — it keeps
 * reporting a recall number for the wrong questions. Same reason supersession targets a
 * stable key.
 */
class QuerySet(
    val pack: String,
    val recallAt: Int,
    /** The semantic tier's gate: run when a real embedder contract is bundled. */
    val threshold: Double,
    /**
     * The structural tier's gate, met by the deterministic stand-in on every PR.
     *
     * Two numbers because there are two tiers. The structural one measures that the
     * pipeline works; the semantic one measures that retrieval is good. Collapsing them
     * would either make the per-PR gate untrue — asserting recall a weightless embedder
     * cannot reach — or make the semantic gate meaningless by setting it where a stand-in
     * can clear it.
     */
    val structuralThreshold: Double,
    val queries: List<LabelledQuery>,
) {

    val positives: List<LabelledQuery> get() = queries.filterNot { it.isNegative }
    val negatives: List<LabelledQuery> get() = queries.filter { it.isNegative }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(directory: Path, file: String = "queries.json"): QuerySet {
            val root = json.parseToJsonElement(Files.readString(directory.resolve(file))).jsonObject
            return QuerySet(
                pack = root.getValue("pack").jsonPrimitive.content,
                recallAt = root.getValue("recall_at").jsonPrimitive.content.toInt(),
                threshold = root.getValue("threshold").jsonPrimitive.content.toDouble(),
                structuralThreshold =
                    root.getValue("structural_threshold").jsonPrimitive.content.toDouble(),
                queries = root.getValue("queries").jsonArray.map { element ->
                    val query = element.jsonObject
                    LabelledQuery(
                        query = query.getValue("query").jsonPrimitive.content,
                        relevant = query.getValue("relevant").jsonArray.map {
                            val target = it.jsonObject
                            Relevant(
                                target.getValue("source_uid").jsonPrimitive.content,
                                target.getValue("stable_key").jsonPrimitive.content,
                            )
                        },
                        notes = query["notes"]?.jsonPrimitive?.content,
                    )
                },
            )
        }
    }
}

/** What one query's retrieval produced, scored against its labels. */
data class QueryOutcome(
    val query: LabelledQuery,
    val found: List<Relevant>,
    val refused: Boolean,
) {
    /** Fraction of the labelled passages that appeared in the top k. */
    val recall: Double
        get() = when {
            query.isNegative -> if (refused) 1.0 else 0.0
            query.relevant.isEmpty() -> 1.0
            else -> query.relevant.count { it in found }.toDouble() / query.relevant.size
        }
}

/** A run of the whole set. */
data class RecallReport(val outcomes: List<QueryOutcome>, val threshold: Double) {

    /** The positive queries — the ones that measure whether retrieval finds anything. */
    val positives: List<QueryOutcome> get() = outcomes.filterNot { it.query.isNegative }

    /**
     * True when the run scored no positive queries at all, and therefore measured nothing.
     *
     * The same vacuity the zero-claim and empty-gold-set checks guard against, arriving
     * here: a set that is all negatives (or empty) has a mean over nothing, and a mean
     * over nothing is not evidence that retrieval works. Reporting it as 1.0 would let a
     * fixture that lost its positives during an edit clear a recall gate by proving only
     * that unrelated questions were refused — the loudest possible pass for the emptiest
     * possible run.
     */
    val uncalibrated: Boolean get() = positives.isEmpty()

    /**
     * Mean over the **positive** queries only, or 0.0 when there are none.
     *
     * A correctly refused negative scores 1.0, and including those in the mean lets
     * refusals pay for missed answers: nine held refusals and one wholly missed positive
     * reports 0.9 and clears a retrieval-quality bar while positive recall is zero.
     * Negatives are enforced separately and absolutely by [negativesHeld], which is the
     * right instrument for them — a refusal is pass or fail, not a fraction.
     */
    val meanRecall: Double
        get() = positives.map { it.recall }.let { if (it.isEmpty()) 0.0 else it.average() }

    /** Negatives are reported separately: a refusal is pass/fail, not a fraction. */
    val negativesHeld: List<QueryOutcome> get() = outcomes.filter { it.query.isNegative }

    val passed: Boolean
        get() = !uncalibrated && meanRecall >= threshold && negativesHeld.all { it.refused }

    /** Every negative that answered when it should have refused. */
    val negativesBroken: List<QueryOutcome> get() = negativesHeld.filterNot { it.refused }

    override fun toString(): String = buildString {
        if (uncalibrated) {
            appendLine("no positive queries: this run measured nothing and cannot pass")
        }
        appendLine("mean recall %.3f (threshold %.3f)".format(meanRecall, threshold))
        for (outcome in outcomes.sortedBy { it.recall }) {
            val mark = if (outcome.recall >= 1.0) "  " else "!!"
            appendLine("$mark %.2f  ${outcome.query.query}".format(outcome.recall))
            if (outcome.recall < 1.0) {
                val missing = outcome.query.relevant - outcome.found.toSet()
                if (outcome.query.isNegative) {
                    appendLine("        answered when it should have refused")
                } else {
                    appendLine("        missed: ${missing.map { it.stableKey }}")
                    appendLine("        found:  ${outcome.found.map { it.stableKey }}")
                }
            }
        }
    }
}
