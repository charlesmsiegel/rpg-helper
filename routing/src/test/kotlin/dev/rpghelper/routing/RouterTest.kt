package dev.rpghelper.routing

import dev.rpghelper.model.Attribution
import dev.rpghelper.model.Availability
import dev.rpghelper.model.CardSummary
import dev.rpghelper.model.GeneratedAnswer
import dev.rpghelper.model.Generator
import dev.rpghelper.model.ImageBuffer
import dev.rpghelper.model.RedactedChunk
import dev.rpghelper.model.Turn
import dev.rpghelper.pack.ChunkRef
import dev.rpghelper.retrieval.Candidate
import dev.rpghelper.retrieval.Retrieved
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RouterTest {

    private val pack = "srd:emberlight"

    private fun ref(id: Long) = ChunkRef(pack, id)

    private fun citation(id: Long) = Citation(
        packUid = pack, chunkId = id, sourceTitle = "Emberlight", edition = "2e",
        headingPath = "How to Play > Seizing", pageLabelStart = "2", pageLabelEnd = "3",
        locatorScheme = "page",
    )

    private val resolver = CitationResolver { citation(it.chunkId) }

    private fun candidate(
        id: Long,
        kind: String,
        origin: String = "source",
        text: String = "body of $id",
        redact: List<IntRange>? = emptyList(),
        headingPath: String? = "Somewhere",
        redactedText: String? = text,
    ) = Candidate(
        ref = ref(id), kind = kind, origin = origin, stableKey = "key:$id",
        sourceUid = "srd:emberlight:core", headingPath = headingPath, text = text,
        score = 1.0 / id, contributions = listOf("core"), entityHit = false,
        denseWindow = null, redact = redact, redactedText = redactedText,
    )

    private fun retrieved(vararg candidates: Candidate, gatedOut: List<String> = emptyList()) =
        Retrieved("a query", listOf("a", "query"), candidates.toList(), gatedOut)

    /** Counts calls, so "the model never runs for a rules question" is asserted, not assumed. */
    private class CountingGenerator(
        override val availability: Availability = Availability.Ready,
        val body: String = "The Marches are low country.",
        val attributions: (List<RedactedChunk>) -> List<Attribution> = { emptyList() },
    ) : Generator {
        var answers = 0
        var residuals = 0
        var normalizations = 0

        override fun normalize(query: String, history: List<Turn>): String {
            normalizations++
            return query
        }

        override fun residualIntent(query: String, covered: List<CardSummary>): String? {
            residuals++
            return query
        }

        override fun answer(residual: String, context: List<RedactedChunk>): GeneratedAnswer {
            answers++
            return GeneratedAnswer(body, attributions(context))
        }

        override fun describeImage(image: ImageBuffer): String = error("not under test")
    }

    private fun router(
        derivations: DerivationResolver = DerivationResolver { emptyList() },
        rollable: RollableChunks = RollableChunks { false },
        citations: CitationResolver = resolver,
    ) = Router(citations, derivations, rollable)

    // ---------------------------------------------------------------- the partition

    @Test
    fun `the pack decides what is quoted, not the app`() {
        // Asking a model "is this a rules question?" would reintroduce at query time
        // exactly the failure the pack contract eliminated at build time.
        val generator = CountingGenerator()
        val answer = router().route(
            retrieved(
                candidate(1, "rules"),
                candidate(2, "table"),
                candidate(3, "statblock"),
                candidate(4, "readaloud"),
                candidate(5, "glossary"),
            ),
            generator, listOf(pack),
        )
        assertTrue(answer.cards.all { it is Card.Verbatim })
        assertEquals(0, generator.answers, "no rules question wakes the model")
        assertEquals(0, generator.residuals)
    }

    @Test
    fun `a derived chunk is neither quoted nor sent to the model`() {
        // Route 2 is easy to miss and it matters. A ('glossary','derived') definition is
        // not setting text and must not be fed to the model; it is also not quotable.
        val generator = CountingGenerator()
        val answer = router(
            derivations = { listOf(Derivation(ref(1), null, null)) },
        ).route(retrieved(candidate(7, "glossary", origin = "derived")), generator, listOf(pack))

        val card = answer.cards.single()
        assertTrue(card is Card.Derived, "got $card")
        assertEquals(0, generator.answers, "already generated once, by a better model")
    }

    @Test
    fun `only a setting-source chunk reaches generation`() {
        val generator = CountingGenerator()
        router().route(
            retrieved(candidate(1, "rules"), candidate(8, "setting", text = "lore about ash")),
            generator, listOf(pack),
        )
        assertEquals(1, generator.answers)
    }

    @Test
    fun `card order is quotes, then derived, then generated`() {
        // Quotes lead because a rules answer is what the user came for and quotes are free.
        // The generated card is last because it is the least-verified thing on screen, and
        // position is one of the signals saying so.
        val answer = router(
            derivations = { listOf(Derivation(ref(1), null, null)) },
        ).route(
            retrieved(
                candidate(8, "setting", text = "lore"),
                candidate(7, "glossary", origin = "derived"),
                candidate(1, "rules"),
            ),
            CountingGenerator(), listOf(pack),
        )
        assertEquals(
            listOf(Card.Verbatim::class, Card.Derived::class, Card.Generated::class),
            answer.cards.map { it::class },
        )
    }

    // ---------------------------------------------------------------- the refusal

    @Test
    fun `nothing retrieved is the refusal, and never a generated card`() {
        val generator = CountingGenerator()
        val answer = router().route(retrieved(), generator, listOf(pack), hasInactivePacks = true)

        val card = answer.cards.single()
        assertTrue(card is Card.Empty, "got $card")
        assertEquals(listOf(pack), card.activePacks)
        assertTrue(card.canSearchInactive, "offered, never automatic")
        assertEquals(0, generator.answers)
        assertTrue(answer.refused)
    }

    @Test
    fun `the model-unavailable card is not the refusal`() {
        // The app found the answer and cannot currently phrase it. Answering that with
        // "not found in your active packs" would be a lie about the one thing the refusal
        // card exists to tell the truth about.
        val answer = router().route(
            retrieved(candidate(8, "setting", text = "lore about ash")),
            CountingGenerator(availability = Availability.NotDownloaded),
            listOf(pack), downloadBytes = 1_500_000_000,
        )
        val card = answer.cards.single()
        assertTrue(card is Card.ModelUnavailable, "got $card")
        assertEquals(1_500_000_000, card.downloadBytes)
        assertFalse(answer.refused)
    }

    @Test
    fun `the unavailable card lists citations and never the chunk's content`() {
        // These are ('setting','source') chunks, ineligible for verbatim rendering, and
        // this card is reached precisely because the model could not phrase them. Quoting
        // them here would put the passage on screen in quotation styling through the one
        // door the fail-closed rule left open.
        val secret = "The Ember hides a door that opens from the far side."
        val answer = router().route(
            retrieved(candidate(8, "setting", text = secret)),
            CountingGenerator(availability = Availability.Failed("out of memory")),
            listOf(pack),
        )
        val card = answer.cards.single() as Card.ModelUnavailable
        assertEquals(1, card.wouldHaveUsed.size)
        assertFalse(answer.cards.toString().contains(secret), "the passage itself never appears")
    }

    @Test
    fun `quotes still render when the model is absent`() {
        // Mixed results degrade rather than fail. A user who declines the download
        // permanently still has a complete, quoting rules reference.
        val answer = router().route(
            retrieved(candidate(1, "rules"), candidate(8, "setting", text = "lore")),
            CountingGenerator(availability = Availability.NotDownloaded), listOf(pack),
        )
        assertEquals(2, answer.cards.size)
        assertTrue(answer.cards[0] is Card.Verbatim)
        assertTrue(answer.cards[1] is Card.ModelUnavailable)
    }

    // ---------------------------------------------------------------- generation guards

    @Test
    fun `a chunk that cannot be redacted safely never reaches the model`() {
        // Nesting reports null when redaction is unsafe. Fail closed: no context, so no
        // generated card, and the citations are listed instead.
        val generator = CountingGenerator()
        val answer = router().route(
            retrieved(candidate(8, "setting", text = "lore", redact = null, redactedText = null)),
            generator, listOf(pack),
        )
        assertEquals(0, generator.answers, "the model is never handed an unredactable chunk")
        assertTrue(answer.cards.single() is Card.ModelUnavailable)
    }

    @Test
    fun `redaction removes the nested span before the model sees it`() {
        val text = "Vashenko waits. d6 | Rumour: the mine is haunted. Travellers pass through."
        val table = "d6 | Rumour: the mine is haunted."
        val start = text.indexOf(table)
        var seen: String? = null
        val generator = object : Generator by CountingGenerator() {
            override val availability = Availability.Ready
            override fun residualIntent(query: String, covered: List<CardSummary>) = query
            override fun answer(residual: String, context: List<RedactedChunk>): GeneratedAnswer {
                seen = context.single().redactedText
                return GeneratedAnswer("summary", emptyList())
            }
        }

        router().route(
            retrieved(
                candidate(
                    8, "setting", text = text,
                    redact = listOf(start until (start + table.length)),
                    redactedText = "Vashenko waits. [table omitted] Travellers pass through.",
                ),
            ),
            generator, listOf(pack),
        )
        assertTrue(seen != null)
        assertFalse(table in seen!!, "the quotable table never entered the prompt: $seen")
        assertTrue("Vashenko waits." in seen!!)
        assertTrue("Travellers pass through." in seen!!)
    }

    @Test
    fun `an attribution naming a chunk that was not in context suppresses the card`() {
        // A generation failure, not a bad span. The model has cited something it was never
        // shown, and nothing in the answer can be trusted after that.
        val generator = CountingGenerator(
            attributions = { listOf(Attribution(0, 5, ChunkRef("other:pack", 99))) },
        )
        val answer = router().route(
            retrieved(candidate(8, "setting", text = "lore about ash")),
            generator, listOf(pack),
        )
        assertTrue(answer.cards.none { it is Card.Generated }, "got ${answer.cards}")
        assertTrue(answer.diagnostics.any { "suppressed" in it })
    }

    @Test
    fun `an answer with no usable attributions renders with footer citations`() {
        val answer = router().route(
            retrieved(candidate(8, "setting", text = "lore about ash")),
            CountingGenerator(), listOf(pack),
        )
        val card = answer.cards.single { it is Card.Generated } as Card.Generated
        assertTrue(card.chips.isEmpty())
        assertEquals(1, card.footer.size, "the context, as a footer citation")
    }

    @Test
    fun `a well-formed attribution becomes an inline chip`() {
        val body = "The Marches are low country."
        val generator = CountingGenerator(
            body = body,
            attributions = { context -> listOf(Attribution(0, body.length, context.single().ref)) },
        )
        val answer = router().route(
            retrieved(candidate(8, "setting", text = "lore about ash")),
            generator, listOf(pack),
        )
        val card = answer.cards.single { it is Card.Generated } as Card.Generated
        assertEquals(1, card.chips.size)
        assertEquals(8L, card.chips.single().citation.chunkId)
    }

    // ---------------------------------------------------------------- citations

    @Test
    fun `a card whose citation cannot be resolved is not rendered`() {
        // An attributed card with a broken citation is indistinguishable to a user from
        // one with a working citation, so it is suppressed rather than shown partial.
        val answer = router(citations = { null }).route(
            retrieved(candidate(1, "rules")), CountingGenerator(), listOf(pack),
        )
        assertTrue(answer.cards.single() is Card.Empty)
        assertTrue(answer.diagnostics.any { "citation unresolved" in it })
    }

    @Test
    fun `a derived card resolves each chip through the chunk it cites`() {
        // A derived chunk has no citation columns of its own, so resolving through the
        // cited chunk is what stops its citation drifting from the chunks it was built on.
        val answer = router(
            derivations = {
                listOf(Derivation(ref(1), 0, 20), Derivation(ref(6), null, null))
            },
        ).route(
            retrieved(candidate(7, "glossary", origin = "derived", text = "a summary of seizing")),
            CountingGenerator(), listOf(pack),
        )
        val card = answer.cards.single() as Card.Derived
        assertEquals(listOf(1L), card.chips.map { it.citation.chunkId })
        assertEquals(listOf(6L), card.footer.map { it.chunkId })
    }

    @Test
    fun `copying a quote takes its citation with it`() {
        // A quote pasted into a group chat without its source is precisely the artifact
        // this app exists to prevent.
        val answer = router().route(
            retrieved(candidate(1, "rules", text = "To seize a creature, make a contested roll.")),
            CountingGenerator(), listOf(pack),
        )
        val copied = (answer.cards.single() as Card.Verbatim).copyText()
        assertTrue(copied.startsWith("To seize a creature"))
        assertTrue("Emberlight" in copied && "p. 2–3" in copied, copied)
    }

    @Test
    fun `a source that is not paginated reads in its own vocabulary`() {
        // "p. 3" is wrong for a GM screen, and a user reading it would go looking for a
        // page that does not exist.
        val screen = citation(1).copy(
            locatorScheme = "panel", pageLabelStart = "3", pageLabelEnd = null,
        )
        assertTrue(render(screen).endsWith("panel 3"), render(screen))
        assertTrue(render(citation(1)).endsWith("p. 2–3"), render(citation(1)))
    }

    // ---------------------------------------------------------------- diagnostics

    @Test
    fun `every answer can say why these results`() {
        // Retrieval quality degrades silently and continuously, and a user who can see
        // that their question matched on lexical score alone is a user who can rephrase.
        val answer = router().route(
            retrieved(candidate(1, "rules"), gatedOut = listOf("dense:e8")),
            CountingGenerator(), listOf(pack),
        )
        assertTrue(answer.diagnostics.any { "gated out: dense:e8" in it })
        assertTrue(answer.diagnostics.any { "key:1" in it && "rules/source" in it })
    }

    @Test
    fun `a rollable chunk carries its roll control`() {
        val answer = router(rollable = { it.chunkId == 2L }).route(
            retrieved(candidate(1, "rules"), candidate(2, "table")),
            CountingGenerator(), listOf(pack),
        )
        val cards = answer.cards.filterIsInstance<Card.Verbatim>()
        assertFalse(cards.single { it.ref.chunkId == 1L }.rollable)
        assertTrue(cards.single { it.ref.chunkId == 2L }.rollable)
    }
}
