# Test Infrastructure — Specification

The corpus, the harnesses, and the gates that make the other specs' guarantees
falsifiable.

Each subsystem spec carries its own test table. This document specifies the shared
machinery those tables depend on and that none of them owns: the test corpus, the labelled
query sets, the claim-support judge, the shared conformance vectors, and what gates CI.

`android-app-design.md` §8 establishes the strategy. This document pins how it runs.

Status: specified, partially implemented — the pack rejection corpus and the schema-drift
gate exist in `:pack`.

---

## 1. The corpus

The test corpus is an **open-licensed SRD**. Freely redistributable, so fixtures live in
the repository and CI runs against real game text rather than toys.

**The same content is the bundled starter pack.** The content that proves the app works in
CI is the content that proves it works to a new user, which is the argument that earns the
bundling exception in §1 of the design: a new install otherwise has a working rules engine
and nothing to point it at.

### 1.1 Stored as text, assembled at test time

```
corpus/srd/
  source.txt          normalized UTF-8, NFC — the bytes sources.text_sha256 pins
  chunking.json       spans, kinds, origins, heading paths, page labels, stable keys
  entities.json       aliases
  tables.json         structured rows
  constraints.json    predicate instances
  LICENSE             and required attribution notices
```

**No `.rpgpack` binary is committed.** A pack is a SQLite file: opaque in review, useless
in a diff, and large. Storing the inputs as text and assembling the pack during the test
run keeps every fixture change readable, and means a schema change updates the fixture
automatically instead of requiring someone to remember to rebuild a binary.

Assembly uses the same `PackForge` path already used for the rejection corpus, which is
what keeps the fixture and the canonical DDL from drifting apart.

### 1.2 Two tiers, because embeddings need a model

| tier | vectors | used for | runs |
|---|---|---|---|
| **structural** | deterministic synthetic vectors | everything that does not measure retrieval *quality* — validation, routing, redaction, capabilities, constraints | every PR, on the JVM |
| **semantic** | real embeddings from a bundled embedder | retrieval regression, claim support | a job that has the models |

The split is not a compromise. Most of what the app must get right is structural, and
those tests should not wait on a model to load. Making the distinction explicit also stops
a structural test quietly depending on embedding quality, which would make it flaky for
reasons unrelated to what it tests.

---

## 2. Labelled query sets

One set per test pack: a query, and the passages that should be found.

```json
{
  "pack": "srd",
  "queries": [
    { "query": "how do I grapple someone",
      "relevant": [ { "source_uid": "srd:core", "stable_key": "srd:grapple" } ],
      "notes": "table slang; the book says 'grappling'" },
    { "query": "what lives in the underdark",
      "relevant": [ { "source_uid": "srd:core", "stable_key": "srd:underdark-inhabitants" } ] },
    { "query": "how much does a warhorse cost",
      "relevant": [],
      "notes": "not in this SRD — must produce the refusal card" }
  ]
}
```

**Labels reference `(source_uid, stable_key)`, never `chunk_id`.** A pack-local id does not
survive a rebuild of the corpus, and a label set that silently re-points at different
passages after a fixture change is worse than no label set — it would keep reporting a
recall number for the wrong questions. This is the same reason supersession targets a
stable key.

**Negative queries carry an empty `relevant` list** and assert the refusal card. They are
as important as the positive ones: the refusal is the visible half of the
refuse-rather-than-hallucinate rule, and a retrieval change that quietly starts answering
them is exactly the regression that would otherwise ship.

Metric is **recall@k**, gated in CI. Retrieval quality has no symptom until someone notices
the app has been quietly failing to find a rule for three releases, which is why this is a
gate and not a report.

---

## 3. Claim support

### 3.1 Why citation integrity is not enough

Asserting that every citation resolves to a chunk that was in context is a real check and
catches real bugs, but it is a **membership** test, and membership is not support. A model
that invents a detail and attaches the citation of whatever chunk was nearest passes it
cleanly: the answer is fabricated, the citation is well-formed, and the test is green —
which is worse than no test, because the green is load-bearing in review.

### 3.2 The harness

1. A **gold set** of setting questions, each with hand-annotated supporting spans — the
   passages that do license an answer.
2. The answer is **decomposed into individual claims**.
3. Each claim is checked for **entailment against the chunk it cites**. A claim citing a
   chunk that does not support it fails whether or not the claim happens to be true.
4. Scored as a **regression threshold**, not pass/fail on every claim.

Step 4 is a deliberate concession to reality. Entailment judgment is noisy, and **a suite
that flakes gets disabled** — which is the real failure mode, and one that costs more than
the imprecision it was trying to avoid.

### 3.3 The judge is itself checked

Adjudication runs **off-device**, where a frontier model can be the judge. This is a CI
gate, not something the phone does.

Two disciplines keep it from being one model grading another:

- A **hand-annotated subset is asserted exactly.** The judge's verdicts on those are
  compared against ground truth, and a judge that disagrees with the humans fails the run
  before its opinion on anything else is counted.
- **The judge model version is pinned**, and changing it is a deliberate commit that
  re-baselines the thresholds. Otherwise a provider-side model update silently moves the
  bar, and a suite whose threshold drifts on its own is a suite reporting noise.

### 3.4 The same check covers derived chunk text

Testing only live generation misses it entirely. A derived chunk is model-written prose
that the app renders with citations — the identical trust claim as a generated answer, made
by a different model at a different time. Its `chunk_derivation` rows guarantee the cited
chunks exist and nothing else.

A builder-written summary that invents a detail carries a well-formed citation, renders as
attributed text, and passes every validation in the format, because the only entailment
check runs against answers the phone generates and this text was never generated on a
phone.

Derived text is therefore claim-checked **in the builder**, against the chunks in its own
`chunk_derivation`, as an enrichment validation: a summary that fails is dropped and
recorded, not shipped. The builder is the natural home — it already has the frontier model,
the source, and no latency budget — and this harness is shared with it so both sides judge
by the same standard.

**The three checks catch different things and none substitutes for another.** Membership
catches plumbing bugs. On-device support catches the small model answering live.
Builder-side support catches the large model that wrote the pack — the one nobody thinks to
distrust, because its output looks like data by the time the app sees it.

---

## 4. Shared conformance vectors

`conformance/dice-vectors.json` (`capabilities-spec.md` §4) is consumed by both the app's
roller and the builder's validator. Neither owns it.

Without shared vectors, the dice grammar is a specification two teams can each believe they
implement, and the disagreement surfaces as a roll landing outside its validated range at
someone's table. With them, it is a failing test.

The same pattern should be used for any future contract the two projects must implement
independently.

---

## 5. Adversarial checks on the suite itself

A passing test proves nothing if it cannot fail.

For the suites that carry a guarantee — verbatim identity, pack rejection, routing, claim
support — **the suite is periodically mutation-checked**: the implementation under test is
deliberately neutered and the run must fail. A test that stays green against a stubbed-out
implementation is measuring nothing, and the failure is invisible in every ordinary run.

This is not hypothetical hygiene. It is how the activation gate's coverage was established:
with `PackValidator.validate` stubbed to return no violations, 36 of 38 tests failed, and
the two survivors were exactly the tests asserting *validity* — which is the correct
result, and which would have been an unexamined assumption otherwise.

Recorded expectations, so a later change that weakens coverage is visible:

| suite | expected to fail when neutered | expected survivors |
|---|---|---|
| pack rejection | every test asserting a violation | the two asserting a valid pack validates |
| verbatim identity | all | none |
| routing partition | all asserting a route | none |

Run on release branches rather than every PR; the point is to catch erosion, not to gate
each commit.

---

## 6. Gates

| suite | where | gates |
|---|---|---|
| unit and property tests | JVM, every PR | **blocking** |
| pack rejection corpus | JVM, every PR | **blocking** |
| dice conformance vectors | JVM, every PR | **blocking** |
| schema/doc drift | JVM, every PR | **blocking** |
| migration fixtures | JVM, every PR | **blocking** |
| constraint predicate semantics | JVM, every PR | **blocking** |
| UI distinction screenshots | device or emulator, every PR | **blocking** |
| retrieval recall@k | semantic tier, every PR | **blocking on threshold** |
| claim support | off-device judge, scheduled and on release branches | **blocking on threshold** |
| mutation checks | release branches | **blocking** |
| on-device performance | device, scheduled | **tracked**, alerts outside a band |

Claim support is not on every PR because a frontier judge over the gold set costs real
money per run. A **small fixed subset** runs per PR to catch outright breakage; the full
set runs on a schedule and before release. Pretending the full suite is affordable per-PR
would end with it being disabled, which is the outcome §3.2 is written to avoid.

Performance is tracked rather than gated because absolute numbers depend on the runner.
The measurements that matter:

- cold generative model load
- generation throughput
- **retrieval latency across increasing active-pack counts** — the one that degrades
  first, since dense scoring is a brute-force scan over every active pack's vectors

---

## 7. Deferred

- **Which SRD.** Constrained by licence and by having enough rules, lore, tables, and
  statblocks to exercise every route. Picking one is a task, not a design question.
- **Gold set size.** Needs a first pass to know what threshold is meaningful; too small and
  the score is noise, too large and the judge is unaffordable.
- **A second test pack.** Cross-pack behaviour — federation, gating, supersession, multiple
  embedder contracts — needs two packs, and much of it is currently exercised only by
  forged fixtures. A second real corpus is what would test it honestly.
