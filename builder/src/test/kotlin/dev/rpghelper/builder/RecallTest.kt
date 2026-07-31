package dev.rpghelper.builder

import dev.rpghelper.pack.JdbcDb
import dev.rpghelper.pack.Packs
import dev.rpghelper.pack.map
import dev.rpghelper.retrieval.ActivePack
import dev.rpghelper.retrieval.ActiveSet
import dev.rpghelper.retrieval.Gates
import dev.rpghelper.retrieval.Pipeline
import dev.rpghelper.retrieval.SupersededSet
import dev.rpghelper.retrieval.Supersession
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The whole read path, end to end, against the corpus and its labelled queries.
 *
 * Retrieval quality has no symptom until someone notices the app has been quietly failing
 * to find a rule for three releases, which is why this is a gate and not a report.
 *
 * This is the **structural tier**: real pack, real FTS5, real fusion and dedup, and a
 * stand-in embedder with no weights. It measures that the pipeline works, not that the
 * embeddings are good. The semantic tier swaps in a real contract and re-measures the
 * same set, and the split is explicit so a structural test cannot quietly come to depend
 * on embedding quality and start flaking for reasons unrelated to what it tests.
 */
class RecallTest {

    private val db = JdbcDb.openReadOnly(CorpusPack.path)
    private val packUid = Packs.readMeta(CorpusPack.path).packUid
    private val pack = ActivePack(packUid, db)

    @AfterTest
    fun cleanUp() = db.close()

    private val active = ActiveSet(
        packs = listOf(pack),
        priority = mapOf(packUid to 0),
        contracts = mapOf(packUid to CorpusPack.EMBEDDER.contract.id),
        superseded = Supersession.compute(listOf(pack)),
    )

    private val querySet = QuerySet.load(CorpusPack.directory)

    /**
     * The dense gate for the stand-in embedder, derived from this labelled set the way
     * every contract's is: **the highest value at which no negative query is admitted.**
     *
     * A surface-overlap embedder scores *what are the rules for starship combat* at 0.622
     * against a game with neither, because the query shares ordinary words with the text.
     * So its calibrated gate sits above that, and the contract contributes reinforcement
     * to candidates the lexical side already admitted rather than admitting any of its
     * own. That is a legitimate calibration outcome and exactly what per-contract
     * thresholds exist to express — a real embedder's is derived the same way and will
     * discriminate, and neither number is transferable to the other.
     */
    private val gates = Gates(dense = mapOf(CorpusPack.EMBEDDER.contract.id to 0.65))

    /** Maps a candidate back to the coordinates the labels are written in. */
    private fun run(query: String, gates: Gates = this.gates) = Pipeline.retrieve(
        query,
        active,
        queryVectors = mapOf(
            CorpusPack.EMBEDDER.contract.id to CorpusPack.EMBEDDER.embed(query),
        ),
        gates = gates,
    )

    private fun score(gates: Gates = this.gates): RecallReport {
        val outcomes = querySet.queries.map { labelled ->
            val retrieved = run(labelled.query, gates)
            val found = retrieved.candidates.take(querySet.recallAt).mapNotNull { candidate ->
                val source = candidate.sourceUid
                val key = candidate.stableKey
                if (source == null || key == null) null else Relevant(source, key)
            }
            QueryOutcome(labelled, found, retrieved.refused)
        }
        return RecallReport(outcomes, querySet.structuralThreshold)
    }

    // ---------------------------------------------------------------- the gate

    @Test
    fun `recall over the labelled set clears the structural threshold`() {
        // The structural tier's gate. It asserts the pipeline finds what lexical evidence
        // and the alias table can reach, and deliberately not more: the queries that need
        // genuine semantic bridging are marked as such in the set and are expected to miss
        // here. Asserting the semantic number against a weightless embedder would make the
        // per-PR gate a thing people learn to ignore.
        val report = score()
        assertTrue(report.passed, "structural recall gate failed:\n$report")
    }

    @Test
    fun `refusals cannot pay for missed answers`() {
        // A correctly refused negative scores 1.0. Counting those in the mean lets nine
        // held refusals and one wholly missed positive report 0.9 and clear a
        // retrieval-quality bar while positive recall is zero.
        val allMissed = querySet.positives.map { QueryOutcome(it, emptyList(), refused = true) }
        val allRefused = querySet.negatives.map { QueryOutcome(it, emptyList(), refused = true) }
        val report = RecallReport(allMissed + allRefused, threshold = 0.5)

        assertEquals(0.0, report.meanRecall, "positives only")
        assertTrue(report.negativesHeld.all { it.refused }, "the refusals did hold")
        assertTrue(!report.passed, "and the run still fails, because nothing was found")
    }

    @Test
    fun `the semantic threshold is stricter than the structural one`() {
        // Otherwise the split is decoration: the point is that bundling real weights has
        // to be measured against a bar the stand-in cannot clear.
        assertTrue(
            querySet.threshold > querySet.structuralThreshold,
            "semantic ${querySet.threshold} must exceed structural ${querySet.structuralThreshold}",
        )
    }

    @Test
    fun `every negative query produces the refusal`() {
        // The visible half of the refuse-rather-than-hallucinate rule. A retrieval change
        // that quietly starts answering these is exactly the regression that would
        // otherwise ship, because nothing about it looks like a failure.
        for (negative in querySet.negatives) {
            val retrieved = run(negative.query)
            assertTrue(
                retrieved.refused,
                "'${negative.query}' answered with ${retrieved.candidates.map { it.stableKey }}",
            )
        }
    }

    @Test
    fun `the labels name passages the pack actually contains`() {
        // A label set pointing at a passage that no longer exists reports a recall number
        // for a question nobody can answer, and looks like a retrieval regression forever.
        val present = db.map(
            "SELECT s.source_uid, c.stable_key FROM chunks c " +
                "JOIN sources s ON s.source_id = c.source_id WHERE c.stable_key IS NOT NULL",
        ) { Relevant(it.string(0), it.string(1)) }.toSet()

        val labelled = querySet.positives.flatMap { it.relevant }.toSet()
        assertTrue(
            (labelled - present).isEmpty(),
            "labels name passages the corpus does not contain: ${labelled - present}",
        )
    }

    // ---------------------------------------------------------------- the alias gap

    @Test
    fun `an alias finds a rule the book never words that way`() {
        // The book says "seizing" and never "grapple". No surface-overlap embedder can
        // bridge that, which is exactly why the rewrite is a query rewrite against the
        // entities table rather than a ranking tweak.
        val retrieved = run("how do I grapple someone")
        assertTrue("grapple" in retrieved.terms, "the user's own wording survives")
        assertTrue("seizing" in retrieved.terms, "and the canonical is added")
        assertEquals(
            "srd:core:seizing",
            retrieved.candidates.first().stableKey,
            "found: ${retrieved.candidates.map { it.stableKey }}",
        )
    }

    @Test
    fun `an alias reinforces but never introduces a candidate`() {
        // If it could introduce one, a query where every gate closed could still answer
        // from a substring match alone, and "gating left no candidates" would stop being
        // the same statement as "the app refuses".
        val impossible = Gates(lexical = 1.1, denseDefault = 1.1)
        assertTrue(run("how do I grapple someone", impossible).refused)
    }

    // ---------------------------------------------------------------- routing inputs

    @Test
    fun `a rules query yields verbatim candidates and a lore query does not`() {
        // The partition routing acts on, mechanically rather than by judgment.
        val rules = run("how do I grapple someone").candidates.first()
        assertTrue(rules.verbatim, "${rules.stableKey} is ${rules.kind}/${rules.origin}")

        val lore = run("what lives in the cinder marches").candidates
            .first { it.stableKey == "srd:core:marches" }
        assertTrue(!lore.verbatim, "setting/source is never quoted")
    }

    @Test
    fun `a surviving lore parent carries the span routing must excise`() {
        // The rumour table is quotable and sits inside non-quotable lore. Sending the
        // parent to generation whole would put the table's authoritative text in the
        // prompt through the front door.
        val lore = run("what lives in the cinder marches").candidates
            .firstOrNull { it.stableKey == "srd:core:marches" }
        if (lore != null) {
            val redact = lore.redact
            assertTrue(redact != null, "redaction must be possible or the chunk must be dropped")
            assertEquals(1, redact.size, "the nested rumour table, and only it")
            val bytes = lore.text.toByteArray(Charsets.UTF_8)
            val excised = String(
                bytes, redact.single().first, redact.single().last + 1 - redact.single().first,
                Charsets.UTF_8,
            )
            assertTrue(excised.startsWith("d6 | Rumour"), "excised: ${excised.take(40)}")
        }
    }

    @Test
    fun `a quoted rule absorbs its nested table rather than shipping both`() {
        // The user sees the complete rule, not a table torn out of it, and the same text
        // does not appear twice under the same citation.
        val found = run("how do I grapple someone").candidates.map { it.stableKey }
        assertTrue("srd:core:seizing" in found)
        assertTrue("srd:core:seizing-mishaps" !in found, "found: $found")
    }

    // ---------------------------------------------------------------- supersession

    @Test
    fun `the errata row supersedes nothing, because its target is not installed`() {
        assertEquals(SupersededSet.EMPTY.size, active.superseded.size)
    }

    @Test
    fun `a superseded rule is unreachable by every route`() {
        // Simulated by filtering the seizing rule directly: a correction removes what it
        // corrects from search, quotation, and generation alike, not merely from ranking.
        val seizing = run("how do I grapple someone").candidates.first().ref
        val withCorrection = active.copy(superseded = SupersededSet(setOf(seizing)))

        val retrieved = Pipeline.retrieve(
            "how do I grapple someone",
            withCorrection,
            mapOf(CorpusPack.EMBEDDER.contract.id to CorpusPack.EMBEDDER.embed("how do I grapple someone")),
            gates,
        )
        assertTrue(
            retrieved.candidates.none { it.ref == seizing },
            "found: ${retrieved.candidates.map { it.stableKey }}",
        )
    }

    // ---------------------------------------------------------------- diagnostics

    @Test
    fun `retrieval reports what each signal contributed`() {
        val top = run("how do I grapple someone").candidates.first()
        assertTrue(top.contributions.isNotEmpty(), "a candidate records the lists that found it")
        assertTrue(top.entityHit, "the alias fired on this one")
    }

    @Test
    fun `retrieval hands over at most ten candidates`() {
        val retrieved = run("what does a warden do in the marches with ash and fire and dice")
        assertTrue(retrieved.candidates.size <= Pipeline.MAX_CANDIDATES)
    }
}
