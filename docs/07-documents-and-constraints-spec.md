# Documents, Trackers, and Constraints — Specification

Characters and the rules that judge them.

`android-app-design.md` §6 establishes what this subsystem is for and what it must not
become. It leaves one thing explicitly open — *"the exact five predicate forms and their
arguments"* — which `00-pack-schema.md` §8 in turn records as the reason the constraint
vocabulary is unpinned. This document closes that.

Storage is `01-app-state-spec.md`'s concern; this document defines the model and the
semantics. Where the two touch, this one is normative about *meaning* and that one is
normative about *columns*.

Implementation order: **7 of 8** — see the README for the full sequence and why it runs in this order.

Status: specified, unimplemented.

---

## 1. Scope

Three things, in the order they matter during play:

- **Documents** — characters and anything else worth keeping, grouped by campaign.
- **Trackers** — the named values on a document. What people reach for mid-session.
- **Constraints** — declared predicates, shipped by packs, that judge a document
  against its game's rules and explain themselves when they fail.

Explicitly out of scope: guided character creation. Per §9 tracking ships first, and
creation is a wizard over this same vocabulary that lands later. Nothing here should
assume it exists, and nothing here should make it harder to add.

---

## 2. Documents

A document is a durable, user-owned record. The governing rule, from which the rest of
this section follows:

> **A character sheet outlives the software that checks it.** Losing a validator is an
> inconvenience. Losing or silently rewriting a character is not recoverable.

Fields:

| field | meaning |
|---|---|
| `document_id` | app-local identity, stable for the life of the document |
| `campaign` | free-text grouping label; documents with no campaign group under *Unfiled* |
| `title` | what the user calls it |
| `ruleset_id` | the ruleset this document is played under, or NULL for *unbound* |
| `draft` | whether the document is still being entered (see §4.5) |
| `created_at`, `updated_at` | timestamps |

`campaign` is a label rather than an entity because campaigns have no behaviour in this
app — no shared state, no session, no membership. A table with one column and no
lifecycle is a table that will grow features nobody asked for.

---

## 3. Trackers

A tracker is a named typed value on a document.

### 3.1 Keys

A key is one or more dot-separated segments, each matching `[a-z0-9-]+`:

```
hp.current
attribute.strength
discipline.auspex
virtue.keen-sight
condition.prone
```

**Keys are lowercase and normalized on entry.** Case-folding is not cosmetic here: a
constraint declared over `attribute.strength` that silently fails to match a tracker the
user typed as `Attribute.Strength` produces no violation and no error — the validator
appears to work and checks nothing. Normalizing at the only point where keys are
authored removes the failure entirely rather than diagnosing it.

Hierarchy is a naming convention, not a structure. `attribute.strength` has no parent
row; the dots exist so selectors (§4.2) can address groups.

### 3.2 Types

Three, and no more:

| type | holds | notes |
|---|---|---|
| `number` | a decimal value | ratings, pools, counts, currency |
| `flag` | true / false | conditions, binary traits |
| `text` | free text | concept, notes, appearance |

**There is no pool type.** A hit-point pool is two `number` trackers — `hp.current` and
`hp.max` — because that is what makes each independently addressable by a constraint,
and because a compound type would need its own comparison rules that the constraint
forms would then have to learn. The relationship between the two is expressed as a
constraint like any other rule, using a bound that references a tracker (§4.3).

### 3.3 Presence

Several forms turn on whether a tracker is *present*. A tracker is present when it
exists on the document **and** its value is not its type's zero:

- `number` — value ≠ 0
- `flag` — value is true
- `text` — value is non-empty after trimming

So deleting a Virtue and setting it to 0 mean the same thing to the rules, which is
what a user would expect and saves every pack from having to care which the app did.

### 3.4 Where tracker keys come from

Keys are free-form: the app ships no tracker schema and packs declare none. Hand-entered
characters are the population that ships first (§9), and requiring a pack-declared schema
would mean a document could not exist until someone had built a pack for its game.

That leaves a real hazard. A pack declaring constraints over `attribute.strength` does
nothing for a user who named their tracker `str`, and the failure is silent in the worst
way: the sheet looks validated and is not.

**The constraint rows are themselves the vocabulary.** Every selector in a ruleset's
constraints names a key or a namespace that ruleset cares about, so the app offers those
keys as completions when a tracker is added to a bound document. No new pack table, no
new column — the information is already there, and it covers exactly the keys where a
typo would silently disable a check.

This does not cover trackers no constraint mentions — an unconstrained hit-point pool is
still whatever the user calls it. That is acceptable: a key no rule refers to cannot be
silently mismatched against one.

---

## 4. Constraints

### 4.1 What a constraint is

One row in a pack's `constraints` table: a `form` from the closed vocabulary below, an
`args` object of that form's arguments, and a `chunk_id` citing the passage that states
the rule.

Firmly settled, and the reason the vocabulary is closed at all:

- Packs **instantiate** forms. They never compose expressions. There is no `and`, no
  `or`, no nesting, and no arithmetic beyond what a form defines.
- No code execution, no eval, no callbacks into the pack.
- Every violation is explainable in terms of the predicate that failed. A constraint the
  user cannot understand is worse than no constraint.
- Constraints **advise**. A document that violates one is flagged, never rejected.

That last point has no severity axis and deliberately so. House rules are the norm, an
app that refuses to store a legal-at-this-table character is an app that gets deleted,
and a severity column would be an invitation to add the refusing behaviour later.

### 4.2 Selectors

A selector addresses trackers. Two shapes, and nothing else:

| shape | matches |
|---|---|
| `attribute.strength` | exactly that key |
| `attribute.*` | every key with that prefix, at any depth below it |

The wildcard is only ever a trailing `.*`. No regex, no alternation, no interior
wildcards. A selector is meant to be read aloud in a violation message.

### 4.3 Bounds

`min` and `max` are **bounds**, and a bound is either a literal or a reference:

```json
{ "min": 1 }
{ "max": { "tracker": "hp.max" } }
```

The reference form is what lets one tracker constrain another — current against maximum,
spent against budget — without introducing expressions. It is the smallest extension
that covers a need every game in this genre has, and it composes with nothing, which is
the point.

**A bound referencing an absent tracker does not constrain.** A sheet that has not yet
declared `hp.max` is incomplete, not illegal, and inventing a default would be inventing
a rule the book does not contain.

Both bounds are optional and both are inclusive.

### 4.4 The five forms

#### `range`

A single tracker's value lies within bounds.

```json
{ "form": "range",
  "args": { "selector": "attribute.strength", "min": 1, "max": 5 } }

{ "form": "range",
  "args": { "selector": "hp.current", "max": { "tracker": "hp.max" } } }
```

Applies to every `number` tracker the selector matches **that exists on the document**,
regardless of its value. A prefix selector applies the same bounds to every match
independently — *no attribute above 5* is one row, not eight.

**`range` is the one form that does not use presence (§3.3).** Presence treats zero as
absence, which is right for counting Virtues and wrong here: a Strength of 0 is a value,
it violates *1–5*, and under presence semantics no form in this vocabulary could ever say
so. An *absent* tracker still does not fire — the sheet is incomplete, not illegal — but a
tracker set to zero is a tracker with a value.

`requires`, `excludes`, and `count_range` keep presence semantics, because each is asking
whether the user *took* something, and a Virtue at zero was not taken.

> Strength is 6. This game allows 1–5. — *Core Rulebook*, p. 42

#### `sum_range`

The sum of every `number` tracker matching the selector lies within bounds. Absent
trackers contribute nothing.

```json
{ "form": "sum_range",
  "args": { "selector": "attribute.physical.*", "min": 7, "max": 7 } }

{ "form": "sum_range",
  "args": { "selector": "freebie.spent.*", "max": { "tracker": "budget.freebie" } } }
```

This is point-buy, and it is the form that most needs the tracker-reference bound: a
budget that varies by campaign is a tracker, not a constant.

**A selector must not match its own bound tracker.** The second example originally
selected `freebie.*` while bounding on `freebie.budget`, which the selector matches — so
the sum was *budget plus everything spent* and the constraint failed the moment a single
point was spent. The budget now lives outside the selected namespace.

That is an easy authoring mistake and a silent one, so it is checked rather than merely
warned about: a constraint whose selector matches its own bound tracker is **dropped when
the engine loads it**, and reported alongside other dropped constraints. Dropping is right
here rather than rejecting the pack, because the rule is unusable but nothing else in the
book is.

> Physical attributes total 9. This game allows exactly 7. — *Core Rulebook*, p. 18

#### `count_range`

The number of *present* (§3.3) trackers matching the selector lies within bounds. Works
across all three types, since presence is defined for all three.

```json
{ "form": "count_range",
  "args": { "selector": "virtue.*", "min": 3, "max": 3 } }
```

> You have 4 Virtues. This game allows exactly 3. — *Core Rulebook*, p. 71

#### `requires`

If the subject is present, every requirement must be satisfied. A requirement is a
selector, optionally with a minimum.

```json
{ "form": "requires",
  "args": { "subject": "feat.power-attack",
            "requires": [ { "selector": "attribute.strength", "min": 13 } ] } }
```

A requirement with no `min` means *present*. A prefix selector in a requirement is
satisfied by any one match, because "requires a Discipline" means any Discipline.

Nothing fires when the subject is absent. A prerequisite is a statement about what you
took, not about what you did not.

> Power Attack requires Strength 13. Strength is 11. — *Core Rulebook*, p. 104

#### `excludes`

If the subject is present, none of the excluded selectors may be.

```json
{ "form": "excludes",
  "args": { "subject": "virtue.keen-sight", "excludes": [ "flaw.blind" ] } }
```

**Exclusion is symmetric and is evaluated once.** If a pack declares that A excludes B, a
document holding both violates that one rule — the app does not additionally report it
from B's side, and a pack declaring both directions produces one violation, not two.
Mutual exclusion is a property of a pair, and reporting it twice would make a
conscientious pack look like it had twice the problems of a careless one.

**The pair is canonicalised before anything identifies it.** `A excludes B` and
`B excludes A` are different `(form, args)` and would fingerprint differently (§4.7), so
whichever row happened to survive deduplication would decide the acceptance key — and a
pack rebuild or a change in load order could pick the other one, resurrecting a house rule
the user had already accepted.

So a violation of `excludes` is identified by the **unordered pair**, sorted: the fingerprint
is taken over `{"form": "excludes", "pair": [lower, higher]}` with the two selectors in
lexical order, never over the row as written. Where several rows produce the same pair,
the citation shown is the one from the highest-priority pack, and ties fall to the lowest
`constraint_id` — a rule stated so that two implementations pick the same passage.

> Keen Sight cannot be taken with Blind. — *Core Rulebook*, p. 83

### 4.5 Draft documents suppress minimum bounds

`sum_range` and `count_range` evaluate over whatever is present, so a half-entered sheet
violates every minimum it has not reached yet — *you have 0 Virtues, this game allows
exactly 3* — from the moment the document is created.

That is technically true and practically fatal. A user who sees eleven violations on an
empty sheet learns within one session that the flags mean nothing, and the feature is
dead for every case where it would have been right.

So while `draft` is set, **minimum bounds are not evaluated**; maximums, `requires`, and
`excludes` are. Those catch the errors that are real even mid-entry — too many dots
spent, an illegal pairing — while the incompleteness that is simply *not finished yet*
stays quiet. Clearing `draft` is a deliberate user action, and it is the moment the sheet
claims to be complete.

Documents are created with `draft` set. Import preserves whatever the file carried.

### 4.6 Evaluation

Constraints evaluate against a document's current trackers, on change, entirely locally.
No model, no retrieval, no network.

The loaded set is every constraint row from **every active pack whose `ruleset_id`
matches the document's binding**. Core book and supplement both contribute; that is how a
supplement adds rules to a game.

**No conflict resolution is attempted.** If two packs in one ruleset declare
contradictory bounds, both evaluate and a document may violate one, the other, or
neither. The app cannot know which book wins, and guessing would mean silently
suppressing a rule the user owns. Supersession is the mechanism for genuine corrections:
a superseded constraint row is deactivated along with everything else rooted at its
chunk, so an erratum removes the rule it corrects rather than competing with it.

### 4.7 Violations, and living with them

A violation carries the form, the failing values, the rendered explanation, and the
citation resolved through `chunk_id` — book, heading path, page label. Tapping it opens
the passage.

Because house rules are normal, a user may **accept** a violation on a document. An
accepted violation stops being surfaced and is listed separately as a deliberate
deviation. This is not dismissal-as-suppression: the sheet still says it departs from the
book, which is exactly what a house rule is.

Acceptance must survive a pack rebuild, and `constraints.constraint_id` cannot carry it —
it is pack-local and there is nothing in the format that keeps it stable across a
rebuild, the same reason supersession does not use `chunk_id`.

So acceptance is keyed by a **constraint fingerprint**: the SHA-256 of the canonical JSON
object

```json
{ "ruleset_id": "<the pack's ruleset>", "form": "<form>", "args": { … } }
```

with object keys sorted, no insignificant whitespace, and numbers in their shortest
round-tripping form. For `excludes`, `args` is the canonical unordered pair from §4.4
rather than the row as written.

**`ruleset_id` is inside the hash, not merely "scoping" it.** Acceptances survive
rebinding (§5), so a document that accepted a generic `range` on `hp.current` under one
game would otherwise carry that acceptance into another game whose pack declares the
identical predicate — silently suppressing a rule the user never saw. Including the
ruleset means an acceptance goes dormant on rebinding and becomes live again if the
document returns, which is the behaviour §5 actually promises.

This needs no new pack column, because a constraint's identity genuinely *is* what it
says. Rebuild the pack and the fingerprint is unchanged; edit the rule's arguments and it
changes — which is correct, since a user who accepted *max 5* has not accepted *max 6*.

---

## 5. Ruleset binding

Constraints cannot apply merely because a pack is active. Someone runs two games, both
packs are installed, and a fantasy character gets flagged for violating a cyberpunk
edition's rules. Applying every active constraint to every document is not strict, it is
nonsense.

So the binding lives on the document, and:

- A document **created from a pack** inherits that pack's `ruleset_id`.
- A document **entered by hand** is asked once. *Unbound* is a legitimate answer:
  trackers work, nothing is validated.
- Deactivating or uninstalling a pack **never modifies a document**. Its constraints stop
  loading and the document reads as *unvalidated* — not as suddenly legal, and not as
  suddenly broken. Reinstalling restores validation exactly.
- **Rebinding is a user action with a visible diff**, showing what newly passes and what
  newly fails, before it is applied. It never happens implicitly because a pack appeared.

Accepted violations (§4.7) are retained across rebinding rather than cleared. A
fingerprint that no longer corresponds to any loaded constraint is inert, and becomes
live again if the rule returns — which is what makes reinstalling a pack restore the
document's state *exactly*, rather than approximately.

---

## 6. Export and import

Documents export as files. There is no cloud sync — local is the premise — so a file is
also how a document moves between devices.

Format is JSON, one document per file, `.rpgdoc` extension:

```json
{
  "format": "rpgdoc/1",
  "document": {
    "title": "Ysabeau of House Tremere",
    "campaign": "Transylvania Chronicles",
    "ruleset_id": "vtm-20",
    "draft": false
  },
  "trackers": [
    { "key": "attribute.physical.strength", "type": "number", "value": 3 },
    { "key": "condition.torpor", "type": "flag", "value": false },
    { "key": "concept", "type": "text", "value": "Reluctant archivist" }
  ],
  "accepted_violations": [
    { "fingerprint": "9f2c…", "ruleset_id": "vtm-20",
      "note": "Storyteller allowed the seventh dot" }
  ]
}
```

Deliberate properties:

- **`document_id` is not exported.** Import always mints a new one. A file is a copy, and
  two devices holding the same file hold two documents — there is no sync to reconcile
  them, so pretending they are one identity would only produce a conflict the app has no
  way to resolve.
- **The ruleset binding travels.** A document imported onto a device without the matching
  pack is unvalidated, not unbound: the binding is intact and validation resumes when the
  pack arrives.
- **Accepted violations travel, with their notes and their ruleset.** They are a record of
  table decisions, which is exactly the sort of thing that is lost and missed.

  `ruleset_id` is exported per acceptance rather than taken from the document's current
  binding, because acceptances survive rebinding (§5): a document bound to game B may
  carry dormant acceptances from game A, and assigning every row the current binding on
  import would both lose A's and wrongly activate them against B. On import each row's
  `ruleset_id` is checked against its fingerprint, which contains the same value (§4.7) —
  a row whose two disagree has been edited and is dropped rather than trusted.
- **No computed state travels.** Violations are derived and are recomputed on import.
- **Unknown top-level keys are preserved on round-trip** so a document exported by a
  later version and re-imported by an earlier one does not quietly lose data.

### Import is an entry point, and normalizes like one

Import validates the format version and the tracker types, and refuses a file it cannot
read rather than importing it partially.

**It also applies §3.1's key rules**, because import is the second way tracker keys enter
the app and the first way a key nobody typed can arrive. A `.rpgdoc` carrying
`Attribute.Strength` or `str` would otherwise sit alongside a constraint selecting
`attribute.strength` and never match it — recreating exactly the silent-validation failure
normalize-on-entry exists to eliminate, on a document the user has every reason to think
is checked.

So keys are lowercased and NFC-folded on import, and a key with a malformed segment is a
refusal rather than a silent pass. A file that normalizes two distinct keys onto the same
key is also refused: merging them would discard a value.

**Unknown top-level keys are stored, not just remembered.** They go into
`documents.extensions` (`01-app-state-spec.md` §2) so they survive an app restart. Keeping
them only in the importer's transient object would satisfy an export taken immediately and
lose them on every later one, which is worse than not promising it.

---

## 7. Testing

| Area | What it asserts |
|---|---|
| Predicate semantics | each form against a table of documents and expected violations, including presence edge cases and absent bound references |
| Selector matching | exact and prefix, including that `a.*` matches `a.b.c` and does not match `ab.c` |
| Key normalization | case folding and rejection of malformed segments |
| Draft suppression | minimum bounds silent while draft, live after; maximums live throughout |
| Exclusion symmetry | one violation per pair regardless of how many directions the pack declares |
| Fingerprint stability | unchanged across a pack rebuild and across key reordering in `args`; changed when an argument changes, and when the ruleset changes |
| Binding lifecycle | deactivation leaves the document byte-identical; reinstall restores validation and acceptances exactly |
| Export round-trip | property-tested: export → import → **restart** → export is stable, and unknown keys survive |
| Import normalization | mixed-case and malformed keys are normalized or refused, never stored as written; colliding keys refuse |
| Zero values | `range` fires on a tracker explicitly set to 0 and stays silent on an absent one; `count_range` does the opposite |
| Selector self-reference | a `sum_range` whose selector matches its own bound tracker is dropped and reported |
| Exclusion identity | `A excludes B` and `B excludes A` produce one violation with one fingerprint, whichever order they load in |
| Multi-pack rulesets | constraints from two packs both load; a superseded constraint does not |

The binding-lifecycle row is the one that most deserves a property test rather than an
example: the promise is that no pack operation modifies a document, and that promise is
easy to break with an innocuous-looking migration.

---

## 8. Deferred

- **Guided creation.** A wizard over this vocabulary, per §9. It needs no new predicate
  forms; it needs an ordering over them, which is a separate question.
- **Tracker display metadata.** Nothing here says how a tracker is *shown* — grouping,
  ordering, labels, widget. That belongs to `06-ui-spec.md`, and pushing it into the pack
  format would mean packs shipping layout, which is a much larger commitment than
  shipping rules.
- **Derived trackers.** Values computed from other values (a dice pool from two
  attributes) are common in this genre and are deliberately absent: they need an
  expression language, which is the thing §6 exists to prevent. If they become
  unavoidable, the honest form is a sixth predicate that *checks* a stated total rather
  than a mechanism that computes one.
