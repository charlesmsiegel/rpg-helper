# Capabilities — Specification

Packs ship declarative manifests; the app ships the executors.

A manifest says *this table is rollable with this dice expression*. It does not say *here
is how to roll it*, and it certainly does not ship code.

`android-app-design.md` §5 establishes the shape. This document pins the vocabulary, the
manifest schemas, the roller's semantics, and the conformance vectors that keep the app's
executor and the builder's validator agreeing about what an expression means.

Implementation order: **8 of 8** — see the README for the full sequence and why it runs in this order.

Status: implemented by `:capabilities`. The dice **grammar** lives in `00-pack-schema.md`
§6 and `:pack`, because the outcome range is needed to validate a table's coverage at
activation; the **executor** is here, and the sampler vectors in
`conformance/dice-vectors.json` now have a consumer on this side of the contract.

---

## 1. The vocabulary is closed

A pack cannot introduce a new capability kind.

| kind | manifest | executor |
|---|---|---|
| `roll-table` | `{ "table_id": <int>, "label": <string> }` | §3 |

One member, deliberately. Rolling on a validated table is the first capability and the
model for the rest; a vocabulary invented ahead of its second entry would be a guess about
what the second entry needs.

### 1.1 Adding a kind is an app release, not a schema bump

Unknown capability kinds are **ignored**. A pack built against a newer app carries
capabilities an older app does not recognise, and the older app renders everything else
normally.

This is deliberately different from the dice grammar, where adding a form *is* a
`schema_version` bump. The distinction is what the failure looks like:

- An unrecognised **capability** is a feature that does not appear. The user sees a
  quotable table with no roll control, which is exactly what they would see for a table
  that failed validation. Nothing is wrong on screen.
- An unrecognised **dice form** would be mis-parsed by an older app's grammar, and the
  roll would land outside its validated range. The result is wrong and looks right.

Ignorable degradation is versioned by the app. Misinterpretable meaning is versioned by
the pack.

### 1.2 Two levels of validation, and where the line is

`00-pack-schema.md` §7 rejects a pack whose `capabilities` row references a chunk that does
not exist. That is structural: the row is unusable and its presence means the pack was
built wrong.

**Manifest content is not structural, and does not reject the pack.** A manifest that
fails its schema — an unknown kind, a missing field, a `table_id` with no matching
`tables` row — causes that one capability to be dropped. Refusing a 300-page book because
one manifest is malformed is the "builder nobody can use" failure, applied to activation.

**A `roll-table` manifest must also target its own chunk**: `capabilities.chunk_id` and
the referenced `tables.chunk_id` must be the same verbatim `table` chunk, and the
capability is dropped when they are not.

Requiring only that `table_id` resolves would let a pack attach table B's roller to quote
card A. Worse, it breaks supersession: the cascade deactivates capabilities rooted at the
superseded chunk (`04-retrieval-spec.md` §5.2), so superseding B removes B's table while
leaving a capability rooted at A still offering to roll on it — the app rolling on text a
correction has withdrawn, which is the exact outcome the cascade reaches capabilities to
prevent.

The rule underneath is the design's: a *missing* capability is acceptable and a *wrong*
answer is not — but the user is never left guessing which they got. Dropped capabilities
are listed on the Packs surface alongside the pack's own `build_report` rows.

---

## 2. Why the user can tell

A table can arrive quotable-but-not-rollable by two different routes, and they look
identical on screen:

- the **builder** dropped its structured rows for failing coverage, span, or round-trip
  validation, and wrote a `build_report` row saying so
- the **app** dropped the capability for a malformed manifest

In both cases the answer feed shows a quote card with no roll control. The Packs surface
is where the difference is legible, reading `build_report` directly out of the pack —
which is the reason that table ships *inside* the pack file rather than beside it. A
report shipped alongside a pack is a report that does not arrive, and the promise that a
user can tell a missing capability from a working one quietly becomes untrue for every
pack not built on the machine reading it.

---

## 3. The roller

### 3.1 Inputs

A `tables` row: `table_id`, the `chunk_id` of the verbatim `table` chunk it was parsed
from, and `dice_expr`. Plus its `table_rows`: `seq`, `lo`, `hi`, a span, and the outcome
text.

The builder validates, deterministically and against the source, that the rows cover the
expression's outcome range exactly and that re-rendering them reproduces the source chunk.
A table that could not pass ships with no rows at all.

**That is the builder's guarantee, and the app does not take it on trust.** An earlier
version of this section concluded that "the app never has to handle a partial table" — a
guarantee about untrusted input, sourced from the artifact under inspection. Activation
now checks what is decidable on-device: that every `table_id` resolves, that ranges do not
overlap and are not inverted, that each row's span lies inside its table chunk's span, and
that each row's `text` byte-equals that slice (`00-pack-schema.md` §7).

The last of those is the one that matters most, because §3.4 renders outcome text under
the quotation rule: without it, a bad pack puts arbitrary prose into the app's most
authoritative rendering, beneath the table's own citation.

Full coverage of the expression's outcome range is checked at activation as well, now
that the grammar is implemented. A roll that somehow matches no row still **fails
closed** — no result, and the capability reported unusable — because a defence that
depends on an earlier check having run is not a defence.

### 3.2 Evaluation

1. Parse `dice_expr` under the pinned grammar. A parse failure drops the capability
   (§1.2); it cannot happen for a pack whose builder used the same grammar version, and
   the app does not guess at expressions it does not recognise.
2. Roll each die **independently** and sum, then apply the modifier.
3. Find the row whose `[lo, hi]` contains the result. Activation has established that
   exactly one will: rows are non-overlapping, non-inverted, and cover the expression's
   range. If none matches anyway, the roll fails closed — no result, and the capability
   reported unusable — because a defence that assumes an earlier check ran is not one.
4. Present the result.

**Independent dice, not a uniform draw over the range.** `2d6` and `d11+1` share the range
2–12 and are not interchangeable: the first is triangular and peaks at 7, the second is
flat. A roller that collapsed `NdS` into one uniform draw would return outcomes at wrong
frequencies forever — a bug no coverage check can see, because every outcome remains
reachable, and one no user would report because a single roll looks fine.

### 3.3 Randomness

- Seeded from the platform's cryptographic entropy source at construction. Not from the
  clock: two rolls in the same millisecond must not agree.
- **Uniform integer generation must be free of modulo bias.** `next() % 6` is biased
  toward low faces whenever the generator's range is not a multiple of 6, and the bias is
  small enough to be invisible and real enough to matter to the one population that cares
  most about dice being fair. Use bounded generation with rejection sampling.
- Injectable for tests. The production path uses the real source; the conformance suite
  drives a deterministic one.

### 3.4 Presentation

A roll result shows:

| element | requirement |
|---|---|
| the expression | as printed in the book |
| the result | the number rolled, and the individual dice when more than one |
| the outcome | the row's text |
| provenance | a link to the quoted `table` chunk, with its citation |

Showing the individual dice is not decoration: it is how a user at a table confirms the
app rolled what it said it rolled, and it is the only visible evidence that `2d6` was two
dice.

The outcome text is a validated span of the table chunk, so it renders under the same
rule as any quotation (`05-routing-and-cards-spec.md` §3.1): plain text, no markdown
interpretation, byte-exact.

---

## 4. Conformance vectors

The builder's parser and the app's roller are different codebases in different languages,
and dice notation is a folk grammar with no standard. Without shared vectors this is a
specification two teams can each believe they implement.

**Both sides run the same file.** It lives at
[`conformance/dice-vectors.json`](../conformance/dice-vectors.json) in this repository and
is consumed verbatim by the builder project. It carries three sections:

| section | contents |
|---|---|
| `vectors` | 18 expressions with their range and **exact** probability mass function, as rationals |
| `unparseable` | 20 expressions the grammar must refuse, including `d6+d6`, `4d6kh3`, `0d6`, `d0`, `2D6`, and `d6 + 1` |
| `sampler` | draw sequences with the dice and total they must produce |

**Everything in `unparseable` must be unparseable under the pinned EBNF, not merely
unusual.** An earlier version listed `d100` and `d%+1` there, and both are grammatical —
`d100` is `dS` with `S = 100`, `d%+1` is a form followed by a modifier. An implementer
following the grammar would have failed the vectors; one following the vectors would have
implemented restrictions the format never states. That is precisely the cross-language
disagreement this file exists to prevent, and it appeared in the file meant to prevent it.
Both are now distribution vectors, and the grammar states the case-sensitivity and
no-whitespace rules its production rules only implied.

The `unparseable` list is part of the contract, not a courtesy. Exploding dice,
drop-lowest, and rerolls are not expressible, so a table needing them ships quotable and
not rollable — and an implementation that helpfully accepted `4d6kh3` would diverge from
the builder that rejected it. `d100` is there because it is *not* `d%`: the grammar admits
one spelling, and accepting the other is how two implementations start disagreeing about
whether the range is 1–100.

The `sampler` section pins what no distribution can: `draws` are zero-based uniform
integers in `[0, S)` and `face = draw + 1`, so `{"expr": "2d6", "draws": [0, 5]}` must
yield dice `[1, 6]` and total `7`. An implementation that collapsed `NdS` into a single
draw over the whole range cannot satisfy it, which is the difference between testing the
distribution and testing the roller.

### 4.1 Distributions are compared exactly, not statistically

The probability mass function of `NdS` is computed by **convolution**, not by sampling and
not by enumerating `S^N` outcomes — `10d10` has 10¹⁰ of those, and a statistical test of
the sampler would flake, and *a suite that flakes gets disabled*, which is the real failure
mode.

Probabilities are exact rationals, so `2d6` and `d11+1` are distinguishable by an equality
test rather than by a confidence interval. Both are in the file, sharing the range 2–12
and differing in every interior probability — the pair exists precisely so a roller that
confuses them fails.

The **sampler** is tested separately from the distribution: driven by a deterministic
generator, it must produce a specified sequence of individual dice, which proves it rolls
`N` times rather than once.

### 4.2 Where the file is generated from

The distributions are computed by convolution rather than typed by hand, and the file is
regenerated rather than edited. A hand-written probability table is a place for a
transcription error to live indefinitely, in the one artifact whose entire purpose is that
two independent implementations agree with it.

---

## 5. Surfacing capabilities

Available capabilities appear as **chips on the query bar**, so what the active packs can
actually do is visible rather than discovered by accident.

Chips reflect the active set: activating a pack that ships a roller adds its chips,
deactivating removes them. A chip whose underlying chunk has been superseded disappears
along with it — capabilities, `tables`, `entities`, and `constraints` rows are all
deactivated together with the chunk they are rooted at (`04-retrieval-spec.md` §5.2), because
each is addressed by id and never passes through retrieval. Without that, an erratum's
corrected table would be unquotable in search while the *original* stayed rollable, and
the app would quietly roll on obsolete rows.

A roll is also reachable from the quote card of a rollable table, which is where a user
who has just looked the table up actually is.

---

## 6. Testing

| Area | What it asserts |
|---|---|
| Grammar conformance | the shared vectors, run identically here and in the builder |
| Refusals | every `unparseable` entry is refused, with no partial parse |
| Sampler sequences | every `sampler` entry produces exactly its dice and total from its draws |
| Capability targeting | a manifest whose `table_id` belongs to a different chunk is dropped |
| Distribution | exact PMF by convolution for every `NdS` vector; `2d6` and `d11+1` distinguishable |
| Sampler | a deterministic generator produces the specified individual dice; `NdS` draws `N` times |
| Modulo bias | over the full generator range, every face is equally reachable |
| `d%` | exactly 1–100 |
| Row selection | every outcome in the range maps to exactly one row, over every fixture table |
| Manifest validation | unknown kind, missing field, and dangling `table_id` each drop one capability and no more |
| Degradation | a pack with a malformed manifest still activates and still quotes |
| Supersession | a superseded table is neither rollable nor reachable from a chip |
| Presentation | outcome text renders byte-exactly, with no markdown interpretation |

The modulo-bias test earns its place by being the one defect here that ships silently,
survives review, and is invisible in use — the population most likely to notice is also
the population least able to prove it.

---

## 7. Deferred

- **Further capability kinds.** Initiative tracking, generators over multiple tables,
  cross-referenced lookups. Each needs its own manifest schema and executor, and the
  vocabulary is designed to gain them one at a time.
- **Roll history.** Plausibly useful at a table, entirely additive, and not needed to
  prove the mechanism.
- **Rolling on a superseded table via an explicit override.** No: the correction removed
  it, and an escape hatch here would defeat the reason supersession reaches capabilities
  at all.
