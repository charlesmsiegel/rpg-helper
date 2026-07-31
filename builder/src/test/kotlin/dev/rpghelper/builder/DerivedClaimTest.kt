package dev.rpghelper.builder

import dev.rpghelper.model.Judge
import dev.rpghelper.model.Verdict
import dev.rpghelper.pack.JdbcDb
import dev.rpghelper.pack.Packs
import dev.rpghelper.pack.map
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.jsonObject
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Builder-side claim support: the check on the model nobody thinks to distrust.
 *
 * Membership catches plumbing bugs. On-device support catches the small model answering
 * live. This catches the large model that wrote the pack — whose output looks like data by
 * the time the app sees it, and whose invented detail would render as attributed text
 * beneath a well-formed citation, passing every validation the format has.
 */
class DerivedClaimTest {

    private val directory: Path = Files.createTempDirectory("derived-claims")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    /**
     * A deterministic stand-in for the frontier judge, and **not an entailment model**.
     *
     * It measures word overlap and calls the result support, which is a much weaker thing:
     * a paraphrase using none of the source's vocabulary would fail it, and a sentence
     * assembled from the source's words in a meaning-reversing order would pass. It is
     * here so the harness's plumbing — decomposition, evidence resolution, the drop, the
     * build note — is exercised without a network call and without a bill.
     *
     * The real judge is a pinned frontier model, checked against hand-annotated verdicts
     * before its opinion on anything else is counted. See `ClaimSupport.run`.
     */
    private val overlapJudge = Judge { claim, evidence ->
        val words = claim.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 4 }
        val haystack = evidence.joinToString(" ").lowercase()
        val missing = words.filterNot { it in haystack }
        val covered = if (words.isEmpty()) 1.0 else 1.0 - missing.size.toDouble() / words.size
        Verdict(covered >= 0.6, "only %.0f%% of the wording is in the cited text: missing %s"
            .format(covered * 100, missing))
    }

    private fun build(judge: Judge?): BuildOutcome =
        PackBuilder(CorpusPack.spec, CorpusPack.EMBEDDER, judge)
            .buildTo(directory.resolve("out-${judge.hashCode()}.rpgpack"))

    @Test
    fun `the corpus summary is entailed by the chunks it cites`() {
        val outcome = build(overlapJudge)
        assertTrue(
            outcome.notes.none { it.validation == "claim-support" && it.severity == "dropped" },
            "the fixture's own summary must survive its own check: ${outcome.notes}",
        )
        JdbcDb.openReadOnly(outcome.path).use { db ->
            val derived = db.map("SELECT count(*) FROM chunks WHERE origin = 'derived'") {
                it.long(0)
            }.single()
            assertEquals(1L, derived)
        }
    }

    @Test
    fun `a summary that invents a detail is dropped and recorded, not shipped`() {
        // The trust claim is identical to a generated answer's, and the citation is
        // well-formed either way. Dropping it costs a convenience; shipping it puts a
        // fabricated detail on screen wearing an authoritative citation.
        val skeptic = Judge { _, _ -> Verdict(false, "invented") }
        val outcome = build(skeptic)

        val dropped = outcome.notes.single { it.validation == "claim-support" }
        assertEquals("dropped", dropped.severity)
        assertTrue(dropped.detail.contains("claims are not"), dropped.detail)

        JdbcDb.openReadOnly(outcome.path).use { db ->
            assertEquals(
                0L,
                db.map("SELECT count(*) FROM chunks WHERE origin = 'derived'") { it.long(0) }
                    .single(),
                "the unsupported summary is not in the pack",
            )
        }
    }

    @Test
    fun `a claim the summary repeats is judged every time it appears`() {
        // The chip goes on the *first* occurrence -- `Anchors.within` resolves there --
        // so the remainder must lose only that one. Removing every occurrence dropped the
        // later copies out of the check entirely: never judged against the fallback
        // evidence, and rendered with neither a chip nor a footer citation.
        val claim = CorpusPack.spec.derived.single().cites.first().claim!!
        val repeated = withDerivedText(CorpusPack.spec, "$claim $claim")

        val asked = mutableListOf<String>()
        val recording = Judge { text, evidence ->
            asked += text
            overlapJudge.judge(text, evidence)
        }
        PackBuilder(repeated, CorpusPack.EMBEDDER, recording)
            .buildTo(directory.resolve("repeated.rpgpack"))

        assertEquals(
            2,
            asked.count { it == claim },
            "both occurrences reach the judge, not just the attributed one: $asked",
        )
    }

    @Test
    fun `a pack with a dropped summary still activates`() {
        // Dropping enrichment is not rejecting the book. A 300-page pack refused over one
        // bad summary is the "builder nobody can use" failure applied to activation.
        val outcome = build(Judge { _, _ -> Verdict(false, "invented") })
        val report = Packs.validateFile(outcome.path, setOf(CorpusPack.EMBEDDER.contract))
        assertTrue(report.isValid, "$report")
    }

    @Test
    fun `building with no judge records that nothing was adjudicated`() {
        // Honest rather than silent. A build that shipped unchecked summaries and said
        // nothing would be indistinguishable from one that checked them and found them
        // sound, which is the distinction the whole harness exists to make.
        val outcome = build(null)
        val note = outcome.notes.single { it.validation == "claim-support" }
        assertEquals("unchecked", note.severity)
        assertTrue(note.detail.contains("no judge"))
    }
}

/** The corpus with its derived summary's text replaced. */
private fun withDerivedText(corpus: CorpusSpec, text: String): CorpusSpec {
    val json = kotlinx.serialization.json.Json.parseToJsonElement(
        java.nio.file.Files.readString(corpus.root.resolve("pack.json")),
    ).jsonObject
    val derived = json.getValue("derived") as kotlinx.serialization.json.JsonArray
    val patched = kotlinx.serialization.json.JsonObject(
        derived.single().jsonObject.toMutableMap().apply {
            put("text", kotlinx.serialization.json.JsonPrimitive(text))
        },
    )
    return CorpusSpec(
        corpus.root,
        kotlinx.serialization.json.JsonObject(
            json.toMutableMap().apply {
                put("derived", kotlinx.serialization.json.JsonArray(listOf(patched)))
            },
        ),
    )
}
