package dev.ludex.retrieval

import dev.ludex.pack.ChunkRef

import dev.ludex.pack.PackSchema
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NestingTest {

    // The partition the format pins: a chunk renders as a quotation only when its kind is
    // verbatim-eligible *and* its origin is 'source'. Sharing the constant with the schema
    // is the point -- a private copy would keep passing after the real one changed.
    private val verbatimEligible: (NestingCandidate) -> Boolean = {
        it.kind in PackSchema.VERBATIM_ELIGIBLE_KINDS && it.origin == "source"
    }

    private val tableText = "d6 | Rumour\n1 | The mine is haunted\n2 | The baron is dead"

    /** Lore with a rumour table sitting inside it, at real byte offsets. */
    private fun setting(
        before: String = "Vashenko is a mining town on the river, ruled by a nervous baron. ",
        after: String = " Travellers are watched but not turned away.",
        window: IntRange? = null,
        role: String? = null,
    ): Pair<NestingCandidate, NestingCandidate> {
        val text = before + tableText + after
        val base = 1000
        val childStart = base + before.toByteArray(Charsets.UTF_8).size
        val parent = NestingCandidate(
            ref = ChunkRef("core", 1),
            kind = "setting",
            origin = "source",
            parent = null,
            text = text,
            spanStart = base,
            spanEnd = base + text.toByteArray(Charsets.UTF_8).size,
            denseWindow = window,
            denseRole = role,
        )
        val child = NestingCandidate(
            ref = ChunkRef("core", 2),
            kind = "table",
            origin = "source",
            parent = parent.ref,
            text = tableText,
            spanStart = childStart,
            spanEnd = childStart + tableText.toByteArray(Charsets.UTF_8).size,
            denseWindow = null,
            denseRole = null,
        )
        return parent to child
    }

    /** The pack-side lookup: every child of a chunk, whether or not retrieval found it. */
    private fun pack(vararg all: NestingCandidate): (ChunkRef) -> List<NestingCandidate> =
        { ref -> all.filter { it.parent == ref } }

    private fun survivors(
        candidates: List<NestingCandidate>,
        terms: List<String> = emptyList(),
        children: (ChunkRef) -> List<NestingCandidate> = pack(*candidates.toTypedArray()),
    ) = Nesting.deduplicate(candidates, terms, verbatimEligible, children)

    // ---------------------------------------------------------------- verbatim parent

    @Test
    fun `a verbatim parent keeps its matching child's place`() {
        // The quote of the whole rule already contains the table, at the same citation.
        // Keeping both would put the same text on screen twice.
        val (lore, child) = setting()
        val rule = lore.copy(kind = "rules")
        val table = child.copy(parent = rule.ref)

        val kept = survivors(listOf(rule, table))
        assertEquals(listOf(rule.ref), kept.map { it.candidate.ref })
    }

    // ---------------------------------------------------------------- non-verbatim parent

    @Test
    fun `a parent matching only through its child yields to the child`() {
        // Otherwise an authoritative, quotable table is delivered as generated prose --
        // the one confusion the whole app exists to prevent.
        val (parent, child) = setting()
        val kept = survivors(listOf(parent, child), terms = listOf("haunted", "mine"))
        assertEquals(listOf(child.ref), kept.map { it.candidate.ref })
    }

    @Test
    fun `a parent that also matches lexically survives beside its child`() {
        // The lore is explained and the table is quoted, from one query. Without this the
        // setting evidence is thrown away and the user gets a bare table where they asked
        // about a place.
        val (parent, child) = setting(
            before = "The haunted mine above Vashenko has not been worked in a generation. ",
        )
        val kept = survivors(listOf(parent, child), terms = listOf("haunted", "mine"))
        assertEquals(setOf(parent.ref, child.ref), kept.map { it.candidate.ref }.toSet())
    }

    @Test
    fun `a term the child never contained does not participate`() {
        // It is not evidence either way: the question is whether the parent matched
        // *because of* the child, and a term absent from the child cannot answer it.
        // "baron" is inside and outside; "travellers" is only outside. The rule turns on
        // "baron" alone, and it passes.
        val (parent, child) = setting()
        val kept = survivors(listOf(parent, child), terms = listOf("baron", "travellers"))
        assertTrue(parent.ref in kept.map { it.candidate.ref })
    }

    @Test
    fun `a purely semantic match does not pass the lexical test vacuously`() {
        // No query term is inside the child, so the set to check is empty and a bare
        // universal quantifier would pass -- declaring independent a parent in whose
        // redacted text no query term occurs at all.
        val (parent, child) = setting()
        val kept = survivors(listOf(parent, child), terms = listOf("necromancy", "wards"))
        assertEquals(listOf(child.ref), kept.map { it.candidate.ref })
    }

    // ---------------------------------------------------------------- dense evidence

    @Test
    fun `a window that is a boundary sliver past the child is not evidence`() {
        // A window containing the whole table plus one adjacent byte overlaps text outside
        // the child while having scored entirely on the child's content.
        val (bare, child) = setting()
        val childStart = child.spanStart!! - bare.spanStart!!
        val childEnd = child.spanEnd!! - bare.spanStart
        val parent = bare.copy(
            denseWindow = childStart until (childEnd + 1),
            denseRole = "content",
        )
        val kept = survivors(listOf(parent, child))
        assertEquals(listOf(child.ref), kept.map { it.candidate.ref })
    }

    @Test
    fun `a window carrying real parent prose is evidence`() {
        val (bare, child) = setting()
        val parent = bare.copy(
            denseWindow = 0 until bare.text.toByteArray(Charsets.UTF_8).size,
            denseRole = "content",
        )
        val kept = survivors(listOf(parent, child))
        assertEquals(setOf(parent.ref, child.ref), kept.map { it.candidate.ref }.toSet())
    }

    @Test
    fun `an expansion hit is independent by construction`() {
        // The builder generates a parent's expansions from its *redacted* text, so an
        // expansion that scored cannot have scored on the child.
        val (bare, child) = setting()
        val parent = bare.copy(denseWindow = null, denseRole = "expansion")
        val kept = survivors(listOf(parent, child))
        assertTrue(parent.ref in kept.map { it.candidate.ref })
    }

    // ---------------------------------------------------------------- the empty parent

    @Test
    fun `a parent covered entirely by its child is never independent`() {
        // It has nothing of its own to contribute, and sending it to generation would
        // contribute an empty context -- checked before either evidence test, so not even
        // an expansion hit can carry it through.
        val (bare, child) = setting(before = "", after = "")
        val parent = bare.copy(denseRole = "expansion")
        val kept = survivors(listOf(parent, child))
        assertEquals(listOf(child.ref), kept.map { it.candidate.ref })
    }

    // ---------------------------------------------------------------- redaction

    @Test
    fun `a surviving parent carries the spans routing must excise`() {
        val (bare, child) = setting()
        val parent = bare.copy(denseRole = "expansion")
        val kept = survivors(listOf(parent, child))

        val redact = kept.single { it.candidate.ref == parent.ref }.redact!!
        val slice = parent.text.toByteArray(Charsets.UTF_8)
            .copyOfRange(redact.single().first, redact.single().last + 1)
        assertEquals(tableText, String(slice, Charsets.UTF_8))
    }

    @Test
    fun `redaction covers a quotable child retrieval never returned`() {
        // "A lore query that never retrieved the child at all is enough to trigger this."
        // The point is that quotable text never reaches generation, and a child that
        // failed to match is no less quotable -- so this is a lookup on the pack, not a
        // filter on the candidates.
        val (bare, child) = setting()
        val parent = bare.copy(denseRole = "expansion")

        val kept = Nesting.deduplicate(
            listOf(parent), emptyList(), verbatimEligible, pack(parent, child),
        )
        assertEquals(1, kept.single().redact!!.size, "the unmatched table is still excised")
    }

    @Test
    fun `a non-quotable child is not excised`() {
        // Redaction exists to keep quotable text out of generation. Derived prose inside a
        // setting chunk is not quotable and removing it would cost context for nothing.
        val (parent, child) = setting()
        val derived = child.copy(origin = "derived")
        val text = Nesting.redactedText(parent, pack(parent, derived), verbatimEligible)
        assertTrue(tableText in text!!)
    }

    @Test
    fun `redacted text is the parent's own prose and nothing else`() {
        val (parent, child) = setting()
        val redacted = Nesting.redactedText(parent, pack(parent, child), verbatimEligible)!!
        assertFalse(tableText in redacted, "the quotable table is gone")
        assertTrue("[table omitted]" in redacted, "and a marker stands where it was")
        assertTrue("nervous baron" in redacted, "the parent's own prose stays")
        assertTrue("Travellers are watched" in redacted)
    }

    @Test
    fun `a multi-byte character before the child does not shift the excision`() {
        // An em-dash is three UTF-8 bytes and one UTF-16 char, so any offset arithmetic
        // confusing the two cuts in the wrong place -- and cuts through a character.
        val (parent, child) = setting(before = "Vashenko—a mining town—waits. ")
        assertEquals(
            "Vashenko—a mining town—waits. [table omitted] Travellers are watched but not turned away.",
            Nesting.redactedText(parent, pack(parent, child), verbatimEligible),
        )
    }

    // ---------------------------------------------------------------- failing closed

    @Test
    fun `a child span that does not land inside the parent drops the parent`() {
        // A partial redaction that leaves half a table in the prompt is the exact failure
        // this step exists to prevent, and a malformed span is not a reason to risk it.
        val (parent, child) = setting()
        val runaway = child.copy(spanEnd = parent.spanEnd!! + 50)

        assertNull(Nesting.redactedText(parent, pack(parent, runaway), verbatimEligible))

        val kept = survivors(
            listOf(parent.copy(denseRole = "expansion"), runaway),
            children = pack(parent, runaway),
        )
        assertEquals(
            listOf(runaway.ref),
            kept.map { it.candidate.ref },
            "the parent cannot be redacted, so it is out; the child stays quotable",
        )
    }

    @Test
    fun `a child span cutting through a character drops the parent`() {
        // The half-character left behind is not something the model, or a reader of the
        // diagnostics view, can interpret.
        val (parent, child) = setting(before = "Vashenko—")
        val split = child.copy(spanStart = child.spanStart!! - 1)
        assertNull(Nesting.redactedText(parent, pack(parent, split), verbatimEligible))
    }

    @Test
    fun `an unredactable parent reports null rather than an empty list`() {
        // "Nothing to redact" and "redaction is not possible" must not be the same value.
        val (bare, child) = setting()
        val runaway = child.copy(spanEnd = bare.spanEnd!! + 50, kind = "statblock")
        val rule = bare.copy(kind = "rules") // verbatim, so it survives regardless

        val kept = Nesting.deduplicate(
            listOf(rule), emptyList(), verbatimEligible, pack(rule, runaway.copy(parent = rule.ref)),
        )
        assertNull(kept.single().redact)
    }

    // ---------------------------------------------------------------- no nesting

    @Test
    fun `an excision leaves a boundary rather than joining the bytes either side`() {
        // A table abutting prose with no whitespace between them would concatenate into a
        // word neither sentence contained -- before generation reads it, and before the
        // lexical test tokenizes it, so a fabricated token could decide whether the parent
        // survives.
        val (parent, child) = setting(before = "Vashenko waits.", after = "Travellers pass.")
        val redacted = Nesting.redactedText(parent, pack(parent, child), verbatimEligible)!!
        assertTrue("waits. [table omitted] Travellers" in redacted, redacted)
        assertFalse("waits.Travellers" in redacted)
    }

    @Test
    fun `a parent that is only a marker has nothing of its own`() {
        // The marker is not the parent's prose. Judging emptiness on the marked text would
        // answer yes for a parent whose entire content was one nested table.
        val (bare, child) = setting(before = "", after = "")
        val parent = bare.copy(denseRole = "expansion")
        val kept = survivors(listOf(parent, child))
        assertEquals(listOf(child.ref), kept.map { it.candidate.ref })
    }

    @Test
    fun `unrelated candidates pass through untouched`() {
        val (parent, _) = setting()
        val other = parent.copy(ref = ChunkRef("core", 9), parent = null)
        val kept = survivors(listOf(parent, other))
        assertEquals(2, kept.size)
        assertTrue(kept.all { it.redact!!.isEmpty() })
    }

    @Test
    fun `a child whose parent did not match survives alone`() {
        val (_, child) = setting()
        assertEquals(listOf(child.ref), survivors(listOf(child)).map { it.candidate.ref })
    }

    // ---------------------------------------------------------------- mixed queries

    @Test
    fun `a term found only in the parent's own prose keeps the parent`() {
        // `haunted Vashenko`: the rumour table holds `haunted`, the lore around it holds
        // `Vashenko`. Requiring the *child's* term to also appear outside dropped the
        // parent for failing to repeat `haunted`, and the user got the table without the
        // passage it sits in -- when a term matching only outside the child is as direct
        // as lexical evidence gets that the parent matched on its own.
        val (parent, child) = setting()
        val kept = survivors(listOf(parent, child), terms = listOf("haunted", "vashenko"))
        assertTrue(
            kept.any { it.candidate.ref == parent.ref },
            "the parent must survive: ${kept.map { it.candidate.ref }}",
        )
    }

    @Test
    fun `a query matching only inside the child still absorbs the parent`() {
        // The rule this exists for, unchanged: nothing about the parent matched except
        // through the chunk it contains, so shipping both is shipping the same text twice.
        val (parent, child) = setting()
        val kept = survivors(listOf(parent, child), terms = listOf("haunted"))
        assertFalse(
            kept.any { it.candidate.ref == parent.ref },
            "the parent matched only through its child: ${kept.map { it.candidate.ref }}",
        )
    }
}
