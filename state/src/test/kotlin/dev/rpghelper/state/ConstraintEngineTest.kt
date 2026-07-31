package dev.rpghelper.state

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class ConstraintEngineTest {

    private val ruleset = "vtm-20"

    private fun parse(form: String, args: String, chunkId: Long = 1): Constraint =
        ConstraintParser.parse(1, ruleset, form, args, chunkId).getOrThrow()

    private fun trackers(vararg pairs: Pair<String, TrackerValue>): List<Tracker> =
        pairs.mapIndexed { index, (key, value) -> Tracker(key, value, index) }

    private fun evaluate(
        constraint: Constraint,
        trackers: List<Tracker>,
        draft: Boolean = false,
    ) = ConstraintEngine(listOf(constraint)).evaluate(trackers, draft)

    private fun n(value: Double) = TrackerValue.Number(value)

    // ------------------------------------------------------------------ range

    @Test
    fun `range flags a value outside its bounds`() {
        val c = parse("range", """{"selector":"attribute.strength","min":1,"max":5}""")
        val v = evaluate(c, trackers("attribute.strength" to n(6.0))).single()
        assertEquals("attribute.strength is 6. This game allows 1–5.", v.explanation)
        assertEquals(1L, v.chunkId)
    }

    @Test
    fun `range flags an explicit zero`() {
        // The finding presence semantics would have hidden: zero counts as absent for
        // counting Virtues, and a Strength of 0 is a value that violates 1-5.
        val c = parse("range", """{"selector":"attribute.strength","min":1,"max":5}""")
        assertEquals(1, evaluate(c, trackers("attribute.strength" to n(0.0))).size)
    }

    @Test
    fun `range stays silent on an absent tracker`() {
        // Incomplete is not illegal.
        val c = parse("range", """{"selector":"attribute.strength","min":1,"max":5}""")
        assertTrue(evaluate(c, trackers("attribute.dexterity" to n(3.0))).isEmpty())
    }

    @Test
    fun `range applies to every match of a prefix selector independently`() {
        val c = parse("range", """{"selector":"attribute.*","max":5}""")
        val violations = evaluate(
            c,
            trackers(
                "attribute.strength" to n(6.0),
                "attribute.dexterity" to n(3.0),
                "attribute.wits" to n(7.0),
            ),
        )
        assertEquals(2, violations.size, "one row, one violation per offending tracker")
    }

    @Test
    fun `range can bound one tracker by another`() {
        // What "current may not exceed maximum" needs, and what no composition-free set of
        // five forms could express with literal bounds only.
        val c = parse("range", """{"selector":"hp.current","max":{"tracker":"hp.max"}}""")
        assertEquals(
            1,
            evaluate(c, trackers("hp.current" to n(12.0), "hp.max" to n(10.0))).size,
        )
        assertTrue(evaluate(c, trackers("hp.current" to n(9.0), "hp.max" to n(10.0))).isEmpty())
    }

    @Test
    fun `a bound referencing an absent tracker does not constrain`() {
        val c = parse("range", """{"selector":"hp.current","max":{"tracker":"hp.max"}}""")
        assertTrue(evaluate(c, trackers("hp.current" to n(999.0))).isEmpty())
    }

    // ------------------------------------------------------------------ sum_range

    @Test
    fun `sum_range totals every numeric match`() {
        val c = parse("sum_range", """{"selector":"attribute.physical.*","min":7,"max":7}""")
        val v = evaluate(
            c,
            trackers(
                "attribute.physical.strength" to n(4.0),
                "attribute.physical.dexterity" to n(3.0),
                "attribute.physical.stamina" to n(2.0),
            ),
        ).single()
        assertEquals("attribute.physical.* totals 9. This game allows exactly 7.", v.explanation)
    }

    @Test
    fun `sum_range can bound against a budget tracker outside its namespace`() {
        val c = parse(
            "sum_range",
            """{"selector":"freebie.spent.*","max":{"tracker":"budget.freebie"}}""",
        )
        assertTrue(
            evaluate(
                c,
                trackers("freebie.spent.melee" to n(5.0), "budget.freebie" to n(15.0)),
            ).isEmpty(),
        )
        assertEquals(
            1,
            evaluate(
                c,
                trackers("freebie.spent.melee" to n(20.0), "budget.freebie" to n(15.0)),
            ).size,
        )
    }

    @Test
    fun `a selector matching its own bound tracker is dropped`() {
        // The broken normative example: freebie.* matches freebie.budget, so the sum was
        // budget plus everything spent and the rule failed the moment a point was spent.
        val result = ConstraintParser.parse(
            1, ruleset, "sum_range",
            """{"selector":"freebie.*","max":{"tracker":"freebie.budget"}}""", 1,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("its own bound tracker"))
    }

    // ------------------------------------------------------------------ count_range

    @Test
    fun `count_range counts present trackers only`() {
        val c = parse("count_range", """{"selector":"virtue.*","min":3,"max":3}""")
        val v = evaluate(
            c,
            trackers(
                "virtue.conscience" to n(3.0),
                "virtue.self-control" to n(2.0),
                "virtue.courage" to n(0.0), // zero is absence, for counting
            ),
        ).single()
        assertEquals("You have 2 of virtue.*. This game allows exactly 3.", v.explanation)
    }

    @Test
    fun `count_range counts flags and text too`() {
        val c = parse("count_range", """{"selector":"condition.*","max":1}""")
        assertEquals(
            1,
            evaluate(
                c,
                trackers(
                    "condition.prone" to TrackerValue.Flag(true),
                    "condition.note" to TrackerValue.Text("stunned"),
                ),
            ).size,
        )
    }

    // ------------------------------------------------------------------ requires

    @Test
    fun `requires fires only when the subject is present`() {
        val c = parse(
            "requires",
            """{"subject":"feat.power-attack","requires":[{"selector":"attribute.strength","min":13}]}""",
        )
        assertTrue(
            evaluate(c, trackers("attribute.strength" to n(11.0))).isEmpty(),
            "a prerequisite says nothing about what you did not take",
        )
        val v = evaluate(
            c,
            trackers("feat.power-attack" to TrackerValue.Flag(true), "attribute.strength" to n(11.0)),
        ).single()
        assertEquals("feat.power-attack requires attribute.strength 13.", v.explanation)
    }

    @Test
    fun `a requirement with no minimum means present`() {
        val c = parse(
            "requires",
            """{"subject":"discipline.advanced","requires":[{"selector":"discipline.basic"}]}""",
        )
        val held = trackers("discipline.advanced" to TrackerValue.Flag(true))
        assertEquals(1, evaluate(c, held).size)
        assertTrue(
            evaluate(
                c,
                held + Tracker("discipline.basic", TrackerValue.Flag(true), 1),
            ).isEmpty(),
        )
    }

    // ------------------------------------------------------------------ excludes

    @Test
    fun `excludes fires once when both are held`() {
        val c = parse("excludes", """{"subject":"virtue.keen-sight","excludes":["flaw.blind"]}""")
        val v = evaluate(
            c,
            trackers(
                "virtue.keen-sight" to TrackerValue.Flag(true),
                "flaw.blind" to TrackerValue.Flag(true),
            ),
        ).single()
        assertEquals("flaw.blind cannot be taken with virtue.keen-sight.", v.explanation)
    }

    @Test
    fun `a pack declaring both directions still produces one violation`() {
        // Reporting it twice would make a conscientious pack look like it had twice the
        // problems of a careless one.
        val forward = parse("excludes", """{"subject":"virtue.keen-sight","excludes":["flaw.blind"]}""")
        val backward = parse("excludes", """{"subject":"flaw.blind","excludes":["virtue.keen-sight"]}""")
        val held = trackers(
            "virtue.keen-sight" to TrackerValue.Flag(true),
            "flaw.blind" to TrackerValue.Flag(true),
        )
        assertEquals(1, ConstraintEngine(listOf(forward, backward)).evaluate(held, false).size)
    }

    @Test
    fun `both directions share one fingerprint, whichever loads first`() {
        // Otherwise a rebuild could pick the other row and resurrect an accepted house rule.
        val forward = parse("excludes", """{"subject":"virtue.keen-sight","excludes":["flaw.blind"]}""")
        val backward = parse("excludes", """{"subject":"flaw.blind","excludes":["virtue.keen-sight"]}""")
        val held = trackers(
            "virtue.keen-sight" to TrackerValue.Flag(true),
            "flaw.blind" to TrackerValue.Flag(true),
        )
        assertEquals(
            ConstraintEngine(listOf(forward)).evaluate(held, false).single().fingerprint,
            ConstraintEngine(listOf(backward)).evaluate(held, false).single().fingerprint,
        )
    }

    // ------------------------------------------------------------------ drafts

    @Test
    fun `minimum bounds are silent while a document is a draft`() {
        // Without this a user sees eleven violations on an empty sheet and learns within
        // one session that the flags mean nothing.
        val c = parse("count_range", """{"selector":"virtue.*","min":3,"max":3}""")
        assertTrue(evaluate(c, emptyList(), draft = true).isEmpty())
        assertEquals(1, evaluate(c, emptyList(), draft = false).size)
    }

    @Test
    fun `maximums stay live while a document is a draft`() {
        // Those catch errors that are real mid-entry.
        val c = parse("range", """{"selector":"attribute.strength","min":1,"max":5}""")
        assertEquals(1, evaluate(c, trackers("attribute.strength" to n(9.0)), draft = true).size)
    }

    @Test
    fun `requires and excludes stay live while a document is a draft`() {
        val c = parse("excludes", """{"subject":"a","excludes":["b"]}""")
        assertEquals(
            1,
            evaluate(
                c,
                trackers("a" to TrackerValue.Flag(true), "b" to TrackerValue.Flag(true)),
                draft = true,
            ).size,
        )
    }

    // ------------------------------------------------------------------ fingerprints

    @Test
    fun `the fingerprint changes when an argument changes`() {
        val loose = parse("range", """{"selector":"attribute.strength","min":1,"max":6}""")
        val tight = parse("range", """{"selector":"attribute.strength","min":1,"max":5}""")
        val held = trackers("attribute.strength" to n(9.0))
        assertNotEquals(
            evaluate(loose, held).single().fingerprint,
            evaluate(tight, held).single().fingerprint,
            "accepting max 5 is not accepting max 6",
        )
    }

    @Test
    fun `the fingerprint changes with the ruleset`() {
        // Acceptances survive rebinding, so one made under game A must not suppress an
        // identical generic predicate under game B.
        val a = ConstraintEngine.fingerprint("game-a", "range", listOf("selector" to "hp.current"))
        val b = ConstraintEngine.fingerprint("game-b", "range", listOf("selector" to "hp.current"))
        assertNotEquals(a, b)
    }

    @Test
    fun `the fingerprint ignores argument order`() {
        assertEquals(
            ConstraintEngine.fingerprint("r", "range", listOf("a" to "1", "b" to "2")),
            ConstraintEngine.fingerprint("r", "range", listOf("b" to "2", "a" to "1")),
        )
    }

    // ------------------------------------------------------------------ parsing

    @Test
    fun `an unknown form is dropped rather than crashing the load`() {
        val result = ConstraintParser.parse(1, ruleset, "tracker-range", "{}", 1)
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("unknown constraint form"))
    }

    @Test
    fun `malformed JSON is dropped rather than crashing the load`() {
        assertTrue(ConstraintParser.parse(1, ruleset, "range", "{not json", 1).isFailure)
        assertTrue(ConstraintParser.parse(1, ruleset, "range", """{"min":1}""", 1).isFailure)
    }

    // ------------------------------------------------------------------ selectors

    @Test
    fun `a prefix selector matches at any depth and does not match a sibling prefix`() {
        val selector = Selector("a.*")
        assertTrue(selector.matches("a.b"))
        assertTrue(selector.matches("a.b.c"))
        assertFalse(selector.matches("ab.c"), "the dot is part of the prefix")
        assertFalse(selector.matches("a"))
    }

    @Test
    fun `an exact selector matches only itself`() {
        val selector = Selector("a.b")
        assertTrue(selector.matches("a.b"))
        assertFalse(selector.matches("a.b.c"))
    }
}
