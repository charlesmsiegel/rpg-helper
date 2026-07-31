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
        absorbed: List<ChunkRef> = emptyList(),
    ) = Candidate(
        ref = ref(id), kind = kind, origin = origin, stableKey = "key:$id",
        sourceUid = "srd:emberlight:core", headingPath = headingPath, text = text,
        score = 1.0 / id, contributions = listOf("core"), entityHit = false,
        denseWindow = null, redact = redact, redactedText = redactedText,
        absorbed = absorbed,
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
    fun `each unavailable state says what actually happened`() {
        // Three states, three remedies. Telling a user whose download already finished
        // that it never started is a false diagnosis with no way to act on it.
        fun statement(availability: Availability): String {
            val answer = router().route(
                retrieved(candidate(8, "setting", text = "lore")),
                CountingGenerator(availability = availability), listOf(pack),
                downloadBytes = 1_000,
            )
            return (answer.cards.single() as Card.ModelUnavailable).statement
        }
        assertTrue("has not been downloaded" in statement(Availability.NotDownloaded))
        assertTrue("still downloading" in statement(Availability.Downloading(30, 100)))
        assertTrue("out of memory" in statement(Availability.Failed("out of memory")))
    }

    @Test
    fun `only a not-downloaded state offers the download`() {
        fun card(availability: Availability) = router().route(
            retrieved(candidate(8, "setting", text = "lore")),
            CountingGenerator(availability = availability), listOf(pack), downloadBytes = 1_000,
        ).cards.single() as Card.ModelUnavailable

        assertEquals(1_000L, card(Availability.NotDownloaded).downloadBytes)
        assertEquals(null, card(Availability.Failed("boom")).downloadBytes, "retry, not download")
    }

    @Test
    fun `one unresolved citation suppresses the whole listing`() {
        // Dropping the unresolvable ones renders a card listing *some* of what was found
        // and saying nothing about the rest -- a partial attributed card, which a user
        // cannot tell from a complete one.
        val partial = CitationResolver { if (it.chunkId == 8L) citation(8) else null }
        val answer = Router(partial, { emptyList() }).route(
            retrieved(candidate(8, "setting", text = "a"), candidate(9, "setting", text = "b")),
            CountingGenerator(availability = Availability.NotDownloaded), listOf(pack),
        )
        assertTrue(answer.cards.none { it is Card.ModelUnavailable }, "got ${answer.cards}")
        assertTrue(answer.diagnostics.any { "citation unresolved" in it })
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

    // ---------------------------------------------------------------- the residual split

    /** Records what the residual pass was told the quote cards cover. */
    private class RecordingGenerator : Generator by CountingGenerator() {
        override val availability = Availability.Ready
        var described: List<CardSummary> = emptyList()

        override fun residualIntent(query: String, covered: List<CardSummary>): String {
            described = covered
            return "the residual"
        }

        override fun answer(residual: String, context: List<RedactedChunk>) =
            GeneratedAnswer("prose", emptyList())
    }

    @Test
    fun `the residual pass is told kinds and heading paths, and there is nowhere to put text`() {
        // Route 2.3's boundary. residualIntent is itself a generative call, so handing it
        // the quote cards' bodies would feed exact rules text to the model through the one
        // call that exists to keep rules *intent* away from it.
        val generator = RecordingGenerator()
        router().route(
            retrieved(
                candidate(1, "rules", text = "A seizing attempt is a contested check."),
                candidate(8, "setting", text = "lore about ash"),
            ),
            generator, listOf(pack),
        )
        assertEquals(
            listOf(CardSummary("rules", "Somewhere")),
            generator.described,
            "kind and heading path, and the type has no third field to leak the body through",
        )
    }

    @Test
    fun `a card suppressed for an unresolved citation covers nothing`() {
        // Subtracting a suppressed card's intent from the residual leaves the one part of
        // the question nobody answered missing from both halves of the split: no quote card
        // renders it, and generation was told not to address it.
        val generator = RecordingGenerator()
        val answer = router(citations = { if (it.chunkId == 1L) null else citation(it.chunkId) })
            .route(
                retrieved(candidate(1, "rules"), candidate(8, "setting", text = "lore")),
                generator, listOf(pack),
            )
        assertTrue(answer.cards.none { it is Card.Verbatim }, "got ${answer.cards}")
        assertEquals(emptyList(), generator.described, "nothing reached the screen to subtract")
    }

    @Test
    fun `an unredactable chunk does not consume a context slot`() {
        // Counting positions rather than admitted chunks meant five candidates that could
        // not be redacted safely ended generation before it reached usable evidence at
        // rank six -- reported as "every chunk redacted to nothing", which was true of the
        // five it looked at and false of the answer.
        var sent: List<RedactedChunk> = emptyList()
        val generator = object : Generator by CountingGenerator() {
            override val availability = Availability.Ready
            override fun residualIntent(query: String, covered: List<CardSummary>) = query
            override fun answer(residual: String, context: List<RedactedChunk>): GeneratedAnswer {
                sent = context
                return GeneratedAnswer("prose", emptyList())
            }
        }
        val unredactable = (1..5).map {
            candidate(it.toLong() + 10, "setting", text = "lore $it", redactedText = null)
        }
        val usable = candidate(99, "setting", text = "the passage that answers it")

        val answer = router().route(
            retrieved(*(unredactable + usable).toTypedArray()),
            generator, listOf(pack),
        )

        assertEquals(1, sent.size, "the sixth candidate reached the context")
        assertEquals("the passage that answers it", sent.single().redactedText)
        assertTrue(answer.cards.any { it is Card.Generated }, "got ${answer.cards}")
        assertTrue(
            answer.diagnostics.any { "could not be redacted safely" in it },
            "and the five skips are recorded: ${answer.diagnostics}",
        )
    }

    @Test
    fun `the generation context is capped in bytes, not only in chunks`() {
        // A valid pack may carry chunks far larger than a phone-sized context window; five
        // of them would either truncate the prompt silently or fail to run at all.
        var sent: List<RedactedChunk> = emptyList()
        val generator = object : Generator by CountingGenerator() {
            override val availability = Availability.Ready
            override fun residualIntent(query: String, covered: List<CardSummary>) = query
            override fun answer(residual: String, context: List<RedactedChunk>): GeneratedAnswer {
                sent = context
                return GeneratedAnswer("prose", emptyList())
            }
        }
        val big = "ash ".repeat(3_000) // 12 KB apiece: two fit in 16 KiB, three do not.
        val answer = router().route(
            retrieved(
                candidate(8, "setting", text = big),
                candidate(9, "setting", text = big),
                candidate(10, "setting", text = big),
            ),
            generator, listOf(pack),
        )
        assertEquals(1, sent.size, "the second chunk does not fit and is dropped whole")
        assertEquals(big, sent.single().redactedText, "and the first is not truncated")
        assertTrue(
            answer.diagnostics.any { "bytes" in it && "2 lower-ranked chunks dropped" in it },
            "the drop is recorded rather than absorbed: ${answer.diagnostics}",
        )
    }

    // ---------------------------------------------------------------- the model failing

    /** Throws where a real one runs out of memory loading several gigabytes of weights. */
    private class FailingGenerator(private val where: String) : Generator {
        override val availability: Availability = Availability.Ready
        override fun normalize(query: String, history: List<Turn>): String = query
        override fun residualIntent(query: String, covered: List<CardSummary>): String? =
            if (where == "residual") error("out of memory") else query
        override fun answer(residual: String, context: List<RedactedChunk>): GeneratedAnswer =
            error("out of memory")
        override fun describeImage(image: ImageBuffer): String = error("not under test")
    }

    @Test
    fun `a model that throws costs the generated card and nothing else`() {
        // Inference fails in ways no caller can anticipate -- weights that will not load,
        // memory pressure, a driver fault. Letting that escape `route` discarded the quote
        // cards the same question had already produced correctly, so "the optional prose is
        // unavailable" was reported as "this question failed". The quoted rules are the
        // part the app exists for; the generated card is the part it is designed to do
        // without.
        val answer = router().route(
            retrieved(candidate(1, "rules"), candidate(2, "setting")),
            generator = FailingGenerator("answer"),
            activePacks = listOf(pack),
        )

        assertTrue(
            answer.cards.any { it is Card.Verbatim && it.ref == ref(1) },
            "the rules quote survives: $answer",
        )
        assertTrue(answer.cards.none { it is Card.Generated })
        assertTrue(
            answer.diagnostics.any { "the model failed" in it },
            "and the failure is reported rather than swallowed: ${answer.diagnostics}",
        )
    }

    @Test
    fun `a model that throws while splitting the residual is the same story`() {
        val answer = router().route(
            retrieved(candidate(1, "rules"), candidate(2, "setting")),
            generator = FailingGenerator("residual"),
            activePacks = listOf(pack),
        )
        assertTrue(answer.cards.any { it is Card.Verbatim })
        assertTrue(answer.diagnostics.any { "the model failed" in it }, "${answer.diagnostics}")
    }

    // ---------------------------------------------------------------- roll controls

    @Test
    fun `a table deduplicated into its parent quote keeps its roll control`() {
        // Nesting drops the child so the table's text is not on screen twice -- but the
        // capability stays keyed to the child's ref, so the surviving card contained a
        // table and offered no way to roll on it. The control belongs to whichever card
        // ended up holding the text.
        val answer = router(rollable = RollableChunks { it == ref(2) }).route(
            retrieved(candidate(1, "rules", absorbed = listOf(ref(2)))),
            generator = null,
            activePacks = listOf(pack),
        )
        val card = answer.cards.filterIsInstance<Card.Verbatim>().single()
        assertEquals(listOf(ref(2)), card.rollableRefs)
        assertTrue(card.rollable)
    }

    @Test
    fun `a card with no rollable chunk offers nothing`() {
        val answer = router().route(
            retrieved(candidate(1, "rules")), generator = null, activePacks = listOf(pack),
        )
        assertTrue(answer.cards.filterIsInstance<Card.Verbatim>().single().rollableRefs.isEmpty())
    }

    @Test
    fun `a heading path counts against the generation budget`() {
        // The heading travels into the prompt with the body. A budget that measures a
        // subset of what it is budgeting is not a budget: five chunks with one-line bodies
        // and megabyte headings cleared 16 KiB and expanded the prompt by tens of MiB.
        val fatHeading = "h".repeat(20_000)
        val generator = CountingGenerator()
        val answer = router().route(
            retrieved(candidate(1, "setting", text = "short", headingPath = fatHeading)),
            generator = generator,
            activePacks = listOf(pack),
        )
        assertTrue(
            answer.diagnostics.any { "bytes" in it && "capped" in it },
            "the heading has to be charged: ${answer.diagnostics}",
        )
    }
}
