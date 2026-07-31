package dev.rpghelper.model

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ClaimSupportTest {

    private val marches = ChunkRef("srd:emberlight", 8)
    private val ashfall = ChunkRef("srd:emberlight", 9)

    private val evidence = mapOf(
        marches to "The Cinder Marches are the low country east of the Ember.",
        ashfall to "Ash falls on the Marches for roughly nine days in ten.",
    )

    private val context = evidence.map { (ref, text) -> RedactedChunk.of(ref, null, text)!! }

    /** A judge that entails a claim only when every content word of it is in the evidence. */
    private val literalJudge = Judge { claim, evidence ->
        val words = claim.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 3 }
        val haystack = evidence.joinToString(" ").lowercase()
        val missing = words.filterNot { it in haystack }
        Verdict(missing.isEmpty(), if (missing.isEmpty()) "supported" else "unsupported: $missing")
    }

    private fun validated(text: String, vararg attributions: Attribution) =
        Attributions.validate(GeneratedAnswer(text, attributions.toList()), context).getOrThrow()

    private fun span(text: String, needle: String, chunk: ChunkRef): Attribution {
        val index = text.indexOf(needle)
        val start = text.substring(0, index).toByteArray(Charsets.UTF_8).size
        return Attribution(start, start + needle.toByteArray(Charsets.UTF_8).size, chunk)
    }

    // ---------------------------------------------------------------- decomposition

    @Test
    fun `an answer becomes one claim per sentence, each carrying its citation`() {
        val text = "The Marches are low country. Ash falls nine days in ten."
        val answer = validated(
            text,
            span(text, "The Marches are low country.", marches),
            span(text, "Ash falls nine days in ten.", ashfall),
        )
        val claims = ClaimSupport.decompose(answer)

        assertEquals(2, claims.size)
        assertEquals(listOf(marches), claims[0].cited)
        assertEquals(listOf(ashfall), claims[1].cited)
        assertTrue(claims.all { it.chipped })
    }

    @Test
    fun `an unattributed sentence carries the whole context, not nothing`() {
        // Otherwise it reaches the harness with no cited chunk to check it against, which
        // is how a fabricated sentence gets tested by no one.
        val text = "The Marches are low country. Something the model made up."
        val answer = validated(text, span(text, "The Marches are low country.", marches))
        val claims = ClaimSupport.decompose(answer)

        val invented = claims.single { "made up" in it.text }
        assertFalse(invented.chipped)
        assertEquals(listOf(marches, ashfall), invented.cited)
    }

    // ---------------------------------------------------------------- support

    @Test
    fun `a fabricated detail with a well-formed citation fails`() {
        // The case membership testing passes cleanly: the citation resolves, the chunk was
        // in context, and the claim is invented. Green on a membership test is worse than
        // no test, because the green is load-bearing in review.
        val text = "The Marches are patrolled nightly by the Emberguard."
        val answer = validated(text, span(text, text, marches))

        val report = ClaimSupport.run(
            ClaimSupport.decompose(answer), evidence::get, literalJudge,
            gold = emptyList(), threshold = 0.9,
        )
        assertFalse(report.passed, "an invented patrol is not in the chunk it cites")
        assertEquals(0.0, report.supportRate)
    }

    @Test
    fun `a claim supported by the chunk it cites passes`() {
        val text = "The Marches are the low country east of the Ember."
        val answer = validated(text, span(text, text, marches))

        val report = ClaimSupport.run(
            ClaimSupport.decompose(answer), evidence::get, literalJudge,
            gold = emptyList(), threshold = 0.9,
        )
        assertTrue(report.passed, "$report")
    }

    @Test
    fun `a true claim citing the wrong chunk still fails`() {
        // Support, not truth. The ashfall sentence is correct and the Marches chunk does
        // not say it, and attaching the nearest citation is exactly what a model does when
        // it is guessing.
        val text = "Ash falls on the Marches for roughly nine days in ten."
        val answer = validated(text, span(text, text, marches))

        val report = ClaimSupport.run(
            ClaimSupport.decompose(answer), evidence::get, literalJudge,
            gold = emptyList(), threshold = 0.9,
        )
        assertFalse(report.passed, "a claim citing a chunk that does not support it fails")
    }

    @Test
    fun `a claim whose citation resolves to nothing is reported, not dropped`() {
        // Excluding it from the denominator would let a plumbing failure raise the score.
        val text = "The Marches are the low country east of the Ember."
        val answer = validated(text, span(text, text, marches))

        val report = ClaimSupport.run(
            ClaimSupport.decompose(answer), { null }, literalJudge,
            gold = emptyList(), threshold = 0.9,
        )
        assertEquals(0.0, report.supportRate)
        assertTrue(report.toString().contains("none of which resolved"))
    }

    // ---------------------------------------------------------------- the judge's own check

    @Test
    fun `a judge that disagrees with the humans fails before its opinions are counted`() {
        // Without this the harness is one model grading another, and the number it reports
        // is a number about the judge.
        val gold = listOf(
            GoldClaim("The Marches are low country.", listOf(evidence.getValue(marches)), true),
            GoldClaim("The Marches are underwater.", listOf(evidence.getValue(marches)), false),
        )
        val alwaysYes = Judge { _, _ -> Verdict(true, "sure") }

        val text = "The Marches are the low country east of the Ember."
        val answer = validated(text, span(text, text, marches))
        val report = ClaimSupport.run(
            ClaimSupport.decompose(answer), evidence::get, alwaysYes, gold, threshold = 0.9,
        )

        assertTrue(report.judgeRejected)
        assertFalse(report.passed, "even though every claim it judged came back supported")
        assertTrue(report.toString().startsWith("JUDGE REJECTED"))
    }

    @Test
    fun `a judge that agrees with the humans has its verdicts counted`() {
        val gold = listOf(
            GoldClaim("The Marches are low country.", listOf(evidence.getValue(marches)), true),
            GoldClaim("The Marches are underwater.", listOf(evidence.getValue(marches)), false),
        )
        val text = "The Marches are the low country east of the Ember."
        val answer = validated(text, span(text, text, marches))

        val report = ClaimSupport.run(
            ClaimSupport.decompose(answer), evidence::get, literalJudge, gold, threshold = 0.9,
        )
        assertFalse(report.judgeRejected, "$report")
        assertTrue(report.passed)
    }

    // ---------------------------------------------------------------- the threshold

    @Test
    fun `scoring is a threshold, not pass-fail on every claim`() {
        // Entailment judgment is noisy, and a suite that flakes gets disabled -- which is
        // the real failure mode, and it costs more than the imprecision it avoids.
        val text = "The Marches are the low country east of the Ember. " +
            "Ash falls on the Marches for roughly nine days in ten. " +
            "The Emberguard patrol it nightly."
        val answer = validated(
            text,
            span(text, "The Marches are the low country east of the Ember.", marches),
            span(text, "Ash falls on the Marches for roughly nine days in ten.", ashfall),
            span(text, "The Emberguard patrol it nightly.", marches),
        )
        val claims = ClaimSupport.decompose(answer)
        val report = { threshold: Double ->
            ClaimSupport.run(claims, evidence::get, literalJudge, emptyList(), threshold)
        }

        assertEquals(2.0 / 3, report(0.6).supportRate, 1e-9)
        assertTrue(report(0.6).passed, "two of three clears a 0.6 bar")
        assertFalse(report(0.9).passed, "and does not clear 0.9")
    }
}
