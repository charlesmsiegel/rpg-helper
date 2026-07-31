package dev.rpghelper.model

import dev.rpghelper.pack.ChunkRef

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
) {
    val supported: Int get() = verdicts.count { it.second.entailed }

    val supportRate: Double
        get() = if (verdicts.isEmpty()) 1.0 else supported.toDouble() / verdicts.size

    /** The judge failed its own check, so its opinion on everything else is not counted. */
    val judgeRejected: Boolean get() = judgeAgreement < judgeAgreementFloor

    val passed: Boolean get() = !judgeRejected && supportRate >= threshold

    override fun toString(): String = buildString {
        if (judgeRejected) {
            appendLine(
                "JUDGE REJECTED: agreed with the humans on %.1f%% of the gold subset, floor %.1f%%"
                    .format(judgeAgreement * 100, judgeAgreementFloor * 100),
            )
            appendLine("No verdict below is counted.")
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
        val agreement = if (gold.isEmpty()) 1.0 else {
            gold.count { judge.judge(it.claim, it.evidence).entailed == it.entailed }
                .toDouble() / gold.size
        }

        val verdicts = claims.map { claim ->
            val evidence = claim.cited.mapNotNull(evidenceFor)
            if (evidence.isEmpty()) {
                // A claim with nothing behind it is not a claim the judge should be asked
                // about; it is a plumbing failure, and reporting it as unsupported keeps
                // it visible instead of silently excluding it from the denominator.
                claim to Verdict(false, "cites ${claim.cited}, none of which resolved to text")
            } else {
                claim to judge.judge(claim.text, evidence)
            }
        }

        return ClaimReport(verdicts, threshold, agreement, judgeAgreementFloor)
    }
}
