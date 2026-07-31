package dev.ludex.model

import dev.ludex.pack.ChunkRef

/** One assertion an answer makes, and what the answer said supports it. */
data class Claim(
    val text: String,
    /** The chunks to check against: one for a chip, the whole context for a footer. */
    val cited: List<ChunkRef>,
    /** True when the region carried a chip rather than a footer citation. */
    val chipped: Boolean,
)

/** A judge's ruling on one claim. */
data class Verdict(val entailed: Boolean, val reason: String)

/**
 * Adjudicates entailment. **Runs off-device**, where a frontier model can be the judge.
 *
 * An interface rather than a call, for two reasons that both matter. It keeps the phone
 * out of it — this is a CI gate, not something the app does — and it makes the judge
 * substitutable, which is what lets the harness be tested against a judge whose answers
 * are known.
 */
fun interface Judge {
    fun judge(claim: String, evidence: List<String>): Verdict
}

/** A claim whose correct verdict a human wrote down. */
data class GoldClaim(val claim: String, val evidence: List<String>, val entailed: Boolean)

/** What a run produced. */
data class ClaimReport(
    val verdicts: List<Pair<Claim, Verdict>>,
    val threshold: Double,
    val judgeAgreement: Double,
    val judgeAgreementFloor: Double,
    /** How many hand-written verdicts the judge was checked against. Zero is a failure. */
    val goldSize: Int = 0,
) {
    val supported: Int get() = verdicts.count { it.second.entailed }

    /**
     * Share of claims the judge upheld — and **zero claims is a failure, not a pass**.
     *
     * An answer that decomposes to nothing is not an answer nobody could fault; it is an
     * answer with nothing in it to check. A single `.` survives the blank check in
     * `Attributions.validate`, decomposes to no claim, and scored a clean 1.0 — so output
     * containing no assertion at all cleared the grounding gate and still rendered as a
     * generated card. The empty case is the one a rate cannot express, so it is answered
     * separately.
     */
    val supportRate: Double
        get() = if (verdicts.isEmpty()) 0.0 else supported.toDouble() / verdicts.size

    /** True when the answer held nothing the harness could check. */
    val vacuous: Boolean get() = verdicts.isEmpty()

    /**
     * The judge failed its own check, so its opinion on everything else is not counted.
     *
     * **An empty gold subset is a failure, not a pass.** It used to score 1.0 — a judge
     * that had been checked against nothing was treated as having agreed with everyone,
     * which is the same vacuity bug [supportRate] had two fields up: the case a ratio
     * cannot express, answered by pretending it can. A judge nobody calibrated is exactly
     * the *one model grading another* this field exists to prevent.
     */
    val judgeRejected: Boolean get() = uncalibrated || judgeAgreement < judgeAgreementFloor

    /** No human wrote down what the right answers were, so nothing checked the judge. */
    val uncalibrated: Boolean get() = goldSize == 0

    val passed: Boolean get() = !judgeRejected && !vacuous && supportRate >= threshold

    override fun toString(): String = buildString {
        if (uncalibrated) {
            appendLine(
                "JUDGE REJECTED: no gold subset, so nothing checked the judge. Its verdicts " +
                    "are one model's opinion of another's and are not counted.",
            )
        } else if (judgeRejected) {
            appendLine(
                "JUDGE REJECTED: agreed with the humans on %.1f%% of the gold subset, floor %.1f%%"
                    .format(judgeAgreement * 100, judgeAgreementFloor * 100),
            )
            appendLine("No verdict below is counted.")
        }
        if (vacuous) {
            appendLine("NO CLAIMS: the answer decomposed to nothing the judge could be asked about.")
        }
        appendLine("support %.3f (threshold %.3f)".format(supportRate, threshold))
        for ((claim, verdict) in verdicts.filterNot { it.second.entailed }) {
            appendLine("!! ${claim.text}")
            appendLine("   cited ${claim.cited} (${if (claim.chipped) "chip" else "footer"})")
            appendLine("   ${verdict.reason}")
        }
    }
}

/**
 * Checks that each claim is supported by **the chunk it cites**.
 *
 * Asserting that every citation resolves to a chunk that was in context is a real check
 * and catches real bugs, but it is a **membership** test, and membership is not support.
 * A model that invents a detail and attaches the citation of whatever chunk was nearest
 * passes it cleanly: the answer is fabricated, the citation is well-formed, and the test
 * is green — which is worse than no test, because the green is load-bearing in review.
 *
 * **The same check covers builder-written derived text.** A derived chunk is model-written
 * prose the app renders with citations: the identical trust claim as a generated answer,
 * made by a different model at a different time. Its `chunk_derivation` rows guarantee the
 * cited chunks exist and nothing else, so a summary that invents a detail carries a
 * well-formed citation, renders as attributed text, and passes every validation the format
 * has. Checking only live generation misses it entirely — and it is the output nobody
 * thinks to distrust, because it looks like data by the time the app sees it.
 */
object ClaimSupport {

    /**
     * Splits an answer into claims at sentence boundaries.
     *
     * Deliberately mechanical. A model-driven decomposition would put a second model
     * between the answer and the verdict, and a disagreement there is indistinguishable
     * from a disagreement about entailment — which is the thing being measured.
     *
     * **The answer is split first, and regions are read off the sentences.** The other
     * order — regions, then sentences within each — lets the attributions decide what a
     * claim is, and the attributions come from the model being measured. A model emitting
     * one attribution per character fragments every sentence into single-character
     * regions, each too short to survive the length filter, and the harness reports zero
     * claims, 100% support, and a green run over an answer nobody checked. Sentence
     * boundaries are a property of the text, which the model cannot restate its way out of.
     */
    fun decompose(answer: ValidatedAnswer): List<Claim> {
        val bytes = answer.text.toByteArray(Charsets.UTF_8)
        val claims = mutableListOf<Claim>()
        for ((start, end) in sentenceSpans(answer.text)) {
            val sentence = String(bytes, start, end - start, Charsets.UTF_8).trim()
            if (sentence.length <= 1) continue

            // Every region the sentence touches. A sentence straddling two regions is
            // checked against the union of their evidence, because there is no honest way
            // to attribute half a sentence -- and dropping it, or checking it against one
            // side, would be a claim the harness does not measure.
            val touched = answer.regions.filter { it.start < end && it.end > start }
            val cited = touched.flatMap {
                when (it) {
                    is SupportedRegion.Cited -> listOf(it.chunk)
                    is SupportedRegion.Contextual -> it.context
                }
            }.distinct()
            claims += Claim(sentence, cited, touched.any { it is SupportedRegion.Cited })
        }
        return claims
    }

    /** Sentence spans of [text], as UTF-8 byte offsets on the scale regions use. */
    private fun sentenceSpans(text: String): List<Pair<Int, Int>> {
        val prefix = byteOffsets(text)
        val spans = mutableListOf<Pair<Int, Int>>()
        var start = 0
        for (gap in SENTENCE.findAll(text)) {
            spans += prefix[start] to prefix[gap.range.first]
            start = gap.range.last + 1
        }
        spans += prefix[start] to prefix[text.length]
        return spans
    }

    /** `prefix[i]` is the UTF-8 byte offset of char index `i`. */
    private fun byteOffsets(text: String): IntArray {
        val prefix = IntArray(text.length + 1)
        var i = 0
        var offset = 0
        while (i < text.length) {
            val codePoint = text.codePointAt(i)
            val chars = Character.charCount(codePoint)
            val width = when {
                codePoint < 0x80 -> 1
                codePoint < 0x800 -> 2
                codePoint < 0x10000 -> 3
                else -> 4
            }
            // A surrogate pair has no offset of its own between the halves; giving the low
            // surrogate the pair's start keeps the array total rather than inventing a
            // boundary inside a character.
            if (chars == 2) prefix[i + 1] = offset
            offset += width
            i += chars
            prefix[i] = offset
        }
        return prefix
    }

    private val SENTENCE = Regex("(?<=[.!?])\\s+")

    /**
     * Runs the gold subset first, then the claims.
     *
     * **The judge is itself checked**, against verdicts a human wrote down, and a judge
     * that disagrees with them fails the run *before* its opinion on anything else is
     * counted. Without that this is one model grading another, and the number it reports
     * is a number about the judge.
     *
     * Scored as a regression threshold rather than pass/fail on every claim. Entailment
     * judgment is noisy, and a suite that flakes gets disabled — which is the real failure
     * mode, and one that costs more than the imprecision it was trying to avoid.
     */
    fun run(
        claims: List<Claim>,
        evidenceFor: (ChunkRef) -> String?,
        judge: Judge,
        gold: List<GoldClaim>,
        threshold: Double,
        judgeAgreementFloor: Double = 0.9,
    ): ClaimReport {
        val agreement = agreement(judge, gold)

        val verdicts = claims.map { claim ->
            // **Every** cited chunk must resolve, not merely one of them. A sentence
            // straddling two regions cites both; dropping the one that failed and asking
            // the judge about the survivor lets a green verdict cover a membership failure
            // -- the plumbing check reporting success because the half that worked was
            // enough to support the sentence. Reporting it as unsupported keeps it visible
            // instead of excluding it from the denominator.
            val unresolved = claim.cited.filter { evidenceFor(it) == null }
            if (unresolved.isNotEmpty()) {
                claim to Verdict(
                    false,
                    if (unresolved.size == claim.cited.size) {
                        "cites ${claim.cited}, none of which resolved to text"
                    } else {
                        "cites ${claim.cited}; $unresolved did not resolve to text"
                    },
                )
            } else {
                claim to judge.judge(claim.text, claim.cited.mapNotNull(evidenceFor))
            }
        }

        return ClaimReport(verdicts, threshold, agreement, judgeAgreementFloor, gold.size)
    }

    /**
     * How often [judge] matches verdicts a human wrote down.
     *
     * Exposed because the builder adjudicates derived prose with its own decomposition —
     * it works from `chunk_derivation` rows rather than from attribution spans — but must
     * not therefore skip the calibration. The rule *a judge's opinions do not count until
     * it has agreed with humans* is the shared thing; the decomposition is not.
     *
     * Zero with an empty gold set, which is what makes an unchecked judge fail rather than
     * pass by default.
     */
    fun agreement(judge: Judge, gold: List<GoldClaim>): Double =
        if (gold.isEmpty()) 0.0
        else gold.count { judge.judge(it.claim, it.evidence).entailed == it.entailed }
            .toDouble() / gold.size
}
