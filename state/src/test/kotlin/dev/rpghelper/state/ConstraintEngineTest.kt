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
    fun `a misspelled argument key is dropped rather than silently weakening the rule`() {
        // The failure this catches is invisible without it: every optional field a pack
        // misspells reads as absent, so "attributes are 1 to 5" quietly becomes "at least
        // 1", the document validates, and nothing anywhere says a rule was weakened.
        // Unlike a capability, whose absence shows as a control that never appears, a
        // half-loaded constraint leaves the document looking checked.
        val result = ConstraintParser.parse(
            1, ruleset, "range", """{"selector":"attribute.*","min":1,"mx":5}""", 1,
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()!!.message!!.contains("unknown argument key"))
    }

    @Test
    fun `unknown keys are refused inside a requirement and inside a tracker bound too`() {
        // The nested objects are where a typo is least likely to be noticed by eye.
        val requirement = ConstraintParser.parse(
            1, ruleset, "requires",
            """{"subject":"merit.x","requires":[{"selector":"attribute.y","minimum":3}]}""", 1,
        )
        assertTrue(requirement.isFailure)
        assertTrue(requirement.exceptionOrNull()!!.message!!.contains("unknown argument key"))

        val bound = ConstraintParser.parse(
            1, ruleset, "range", """{"selector":"attribute.*","max":{"traker":"cap"}}""", 1,
        )
        assertTrue(bound.isFailure)
        assertTrue(bound.exceptionOrNull()!!.message!!.contains("unknown argument key"))
    }

    @Test
    fun `a number too large to be a number is dropped`() {
        // `1e309` is syntactically valid JSON and parses to infinity. An infinite maximum
        // turns a range into a rule nothing can violate; an infinite minimum turns it into
        // one every finite value violates. Both are silent -- the parser reports success,
        // and the dropped-constraint report says nothing.
        for (args in listOf(
            """{"selector":"attribute.*","max":1e309}""",
            """{"selector":"attribute.*","min":-1e309}""",
        )) {
            val result = ConstraintParser.parse(1, ruleset, "range", args, 1)
            assertTrue(result.isFailure, "accepted $args")
            assertTrue(
                result.exceptionOrNull()!!.message!!.contains("finite"),
                result.exceptionOrNull()!!.message!!,
            )
        }
    }

    @Test
    fun `a range with neither bound is dropped, because it can never fire`() {
        // Not a permissive rule -- a rule that does nothing. Unlike a capability, whose
        // absence shows as a control that never appears, it leaves the document displaying
        // as validated against a constraint incapable of failing.
        for (form in listOf("range", "sum_range", "count_range")) {
            val result = ConstraintParser.parse(1, ruleset, form, """{"selector":"a.*"}""", 1)
            assertTrue(result.isFailure, "$form accepted a bound-less range")
            assertTrue(
                result.exceptionOrNull()!!.message!!.contains("never fire"),
                result.exceptionOrNull()!!.message!!,
            )
        }
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

    @Test
    fun `a malformed selector is a dropped constraint, not a rule that matches nothing`() {
        // Each of these parses happily under a permissive reader and then matches no
        // tracker, so the document displays as validated against a rule that never ran.
        for (selector in listOf("attribute*", "attribute.*.strength", "", ".", "a..b", "A.b")) {
            val result = ConstraintParser.parse(
                1, ruleset, "range", """{"selector":"$selector","min":1}""", 1,
            )
            assertTrue(result.isFailure, "'$selector' must be refused, not silently inert")
        }
    }

    @Test
    fun `a bound referencing a wildcard is refused`() {
        // A reference resolves one tracker's value. A wildcard resolves nothing and then
        // reads as "unbounded", which is the opposite of the rule as written.
        val result = ConstraintParser.parse(
            1, ruleset, "range",
            """{"selector":"a.b","max":{"tracker":"cap.*"}}""", 1,
        )
        assertTrue(result.isFailure)
    }

    @Test
    fun `a self-excluding pair is refused rather than crashing evaluation`() {
        // The engine identifies an exclusion by the unordered pair, which collapses to one
        // element when both halves are the same selector -- and the message needs two.
        val result = ConstraintParser.parse(
            1, ruleset, "excludes", """{"subject":"merit.brave","excludes":["merit.brave"]}""", 1,
        )
        assertTrue(result.isFailure, "'X excludes X' is an authoring bug, not a rule")
    }

    @Test
    fun `a present but nonnumeric prerequisite minimum is refused`() {
        // Degrading these to null would silently weaken "you must have it at 3" into
        // "you must have it at all", and report nothing.
        for (min in listOf(""""3"""", "true", "null", "{}", """"three"""")) {
            val result = ConstraintParser.parse(
                1, ruleset, "requires",
                """{"subject":"merit.brave","requires":[{"selector":"attr.wits","min":$min}]}""", 1,
            )
            if (min == "null") {
                // An explicit null is an absent minimum: presence is the rule.
                assertTrue(result.isSuccess, "an explicit null min means presence")
                continue
            }
            assertTrue(result.isFailure, "min $min must be refused")
        }
    }

    @Test
    fun `statically inverted literal bounds are refused`() {
        // Every value violates one side or the other, so the rule flags every document and
        // the UI offers an impossible correction -- "this game allows 5-1".
        assertTrue(
            ConstraintParser.parse(
                1, ruleset, "range", """{"selector":"a.b","min":5,"max":1}""", 1,
            ).isFailure,
        )
        // A tracker-referenced bound is left alone: it depends on values the parser cannot
        // see, and an inversion there is a fact about one document rather than the rule.
        assertTrue(
            ConstraintParser.parse(
                1, ruleset, "range",
                """{"selector":"a.b","min":5,"max":{"tracker":"cap.total"}}""", 1,
            ).isSuccess,
        )
    }

    @Test
    fun `a numeric prerequisite is not satisfied by a non-numeric tracker`() {
        // Defaulting a text or flag tracker to 0.0 satisfies any minimum at or below zero,
        // so a prerequisite silently passes on a value that is not a rating at all.
        val constraint = parse(
            "requires",
            """{"subject":"merit.brave","requires":[{"selector":"attr.wits","min":0}]}""",
        )
        val violations = ConstraintEngine(listOf(constraint)).evaluate(
            trackers(
                "merit.brave" to TrackerValue.Flag(true),
                "attr.wits" to TrackerValue.Text("quick"),
            ),
            draft = false,
        )
        assertEquals(1, violations.size, "a text value is not a rating of zero")
    }

    @Test
    fun `a shared exclusion pair cites the highest-priority pack`() {
        // The spec pins which passage a shared violation cites so that two implementations
        // pick the same one. Left to loader order, a rebuild could change the passage shown
        // beneath a rule the user has already read.
        fun excludes(chunk: Long, id: Long, priority: Int) = Constraint.Excludes(
            chunkId = chunk, rulesetId = ruleset,
            subject = Selector("merit.keen-sight"), excluded = listOf(Selector("flaw.blind")),
            constraintId = id, packPriority = priority,
        )
        val document = trackers(
            "merit.keen-sight" to TrackerValue.Flag(true),
            "flaw.blind" to TrackerValue.Flag(true),
        )

        val rows = listOf(excludes(70, 9, 2), excludes(40, 3, 0), excludes(50, 1, 1))
        for (order in listOf(rows, rows.reversed(), rows.shuffled())) {
            val violation = ConstraintEngine(order).evaluate(document, draft = false).single()
            assertEquals(40, violation.chunkId, "the highest-priority pack's passage")
        }
    }

    @Test
    fun `ties within one pack fall to the lowest constraint id`() {
        fun excludes(chunk: Long, id: Long) = Constraint.Excludes(
            chunkId = chunk, rulesetId = ruleset,
            subject = Selector("merit.keen-sight"), excluded = listOf(Selector("flaw.blind")),
            constraintId = id, packPriority = 0,
        )
        val document = trackers(
            "merit.keen-sight" to TrackerValue.Flag(true),
            "flaw.blind" to TrackerValue.Flag(true),
        )
        val rows = listOf(excludes(80, 12), excludes(20, 4), excludes(60, 7))
        assertEquals(
            20,
            ConstraintEngine(rows.shuffled()).evaluate(document, draft = false).single().chunkId,
        )
    }

    @Test
    fun `a quoted number is not a bound`() {
        assertTrue(
            ConstraintParser.parse(1, ruleset, "range", """{"selector":"a.b","min":"1"}""", 1)
                .isFailure,
            "a JSON string is not a JSON number, however it reads",
        )
    }
}
