# Ludex RPG

An offline reference and play assistant for tabletop roleplaying games: answers rules
questions by quoting your books exactly, answers setting questions in generated prose
that is always cited, and tracks characters under the game's own constraints.

Everything runs on the device. No account, no server, no network at play time.

The central guarantee the whole design serves: **a quote and a paraphrase must never be
confusable, anywhere in the app.**

> **Status: proof of concept, in testing.** The specifications are pinned and the
> modules are written against them, but the build has not been exercised end to end
> by hand and the evaluation harness has not been run. Treat the component table
> below as a map of what exists in the tree, not as a claim about what has been
> demonstrated. See [Status](#status) for the honest version.

## Documents

**Design** — what the system does and why, with the arguments:

| Document | What it covers |
| --- | --- |
| [`docs/android-app-design.md`](docs/android-app-design.md) | the app as a whole — the central guarantee and everything downstream of it |
| [`docs/pack-builder-requirements.md`](docs/pack-builder-requirements.md) | what the builder (a separate project) must produce |

**Specifications** — pinned and implementable, one per subsystem, listed in the order
they should be built:

| # | Document | What it pins | Why here |
| --- | --- | --- | --- |
| — | [`00-pack-schema.md`](docs/00-pack-schema.md) | the pack format — DDL, vector layout, probe constant, dice grammar | the contract everything else reads |
| 1 | [`01-app-state-spec.md`](docs/01-app-state-spec.md) | the app's own database, install and activation, caching, migrations, backup | nothing else can run until a pack can be installed and activated |
| 2 | [`02-test-infrastructure-spec.md`](docs/02-test-infrastructure-spec.md) | the corpus, labelled query sets, the claim-support judge, CI gates | retrieval is unmeasurable without a corpus; building it afterwards means calibrating gates against nothing |
| 3 | [`03-model-runtime-spec.md`](docs/03-model-runtime-spec.md) | the three on-device models, their interfaces, the download contract | retrieval needs a real embedding space; the other two models can follow |
| 4 | [`04-retrieval-spec.md`](docs/04-retrieval-spec.md) | query grammars, supersession, gating, fusion, nesting deduplication | the core, and the least settled part of the design |
| 5 | [`05-routing-and-cards-spec.md`](docs/05-routing-and-cards-spec.md) | the three routes, redaction, residual intent, the five cards | where the central guarantee is enforced; needs candidates to route |
| 6 | [`06-ui-spec.md`](docs/06-ui-spec.md) | the four surfaces, and what the quote/paraphrase distinction must survive | first point there is a usable app: a quoting rules reference that needs no downloaded model |
| 7 | [`07-documents-and-constraints-spec.md`](docs/07-documents-and-constraints-spec.md) | documents, trackers, ruleset binding, the five constraint predicate forms | the second of the app's three promises, and independent of every one above except #1 |
| 8 | [`08-capabilities-spec.md`](docs/08-capabilities-spec.md) | the capability vocabulary, manifests, the dice roller | the one feature whose absence the design explicitly calls acceptable |

Two notes on the ordering.

**#6 is the milestone, not #8.** After it the app does the larger half of what it
promises — answering rules questions by quoting books exactly — with no generative model
downloaded. Everything after it adds capability rather than proving the design.

**#7 can move.** Documents and constraints depend only on #1, need no models, and are
schedulable anywhere after it. It sits at 7 because the app's distinguishing value is the
quoting reference and character tracking has established alternatives; if shipping
tracking sooner matters more, move it to #2 at no cost to anything else.

The order deliberately differs from the order these were written in. Writing followed
specification dependencies — what had to be decided before something else could be
described. Building follows risk and what unblocks a usable app.

## Status

This is a proof of concept under active development. The design work is the mature part;
the running system is not.

**What is settled.** Every subsystem has a written specification, and the pack and state
schemas are enforced against the code by test. The arguments behind the design — why the
quote/paraphrase distinction has to be structural rather than stylistic, why the pack is
read-only by construction, why a manifest may not carry a placeholder digest — are
written down and are the part of this repository worth reading.

**What is not established.** The build has not been installed or exercised end to end by
hand, and the evaluation harness has not been run. No retrieval number here has been
measured. The table below says what exists in the tree; it does not say what has been
demonstrated, and until a run happens those are different claims.

| Component | State |
| --- | --- |
| Specifications | written for every subsystem; pack and state schemas enforced against the code by test |
| Pack reader + activation validator (`:pack`) | written |
| App state — install journal, documents, trackers, constraints (`:state`) | written |
| Retrieval — aliases, gating, fusion, supersession, nesting (`:retrieval`) | written; **not yet evaluated** |
| Routing and the five cards (`:routing`) | written, including pack-backed citation resolvers |
| Model interfaces, attributions, claim support, downloader (`:model`) | written; no weights bundled; download path unexercised |
| Capabilities and the dice roller (`:capabilities`) | written |
| Pack builder (`:builder`) | written — anchors, claim judging, expansion generation |
| Command-line tool (`:cli`) | written — `build`, `verify`, `ask`, `roll`, `fetch-model`, `make-manifest`, library commands |
| Android app (`:app`) | started — the Ask surface and all five cards render |
| Test corpus (`corpus/srd`) | Emberlight — a temporary LLM-generated placeholder, see below |

Two things are deliberately absent rather than unfinished.

**No model weights.** `:model` defines the three models as interfaces and ships a
deterministic stand-in embedder with no weights at all, which is what makes the
*structural* test tier possible in principle: activation, routing, redaction,
capabilities, and the fusion pipeline are meant to run end to end in milliseconds,
before any weights exist. The generative half runs against the `Generator` interface, so
every card and every guard can be exercised by fakes. Point `fetch-model` at a manifest
and the download path is written to be real, resumable, and digest-verified — that path
has not been run against a live host.

**The UI is started, not finished.** `:app` is written to render the five cards with the
quote/paraphrase distinction the spec requires — a verbatim body never passes through a
rich-text renderer, and it does not re-wrap, because re-flowing a quotation is editing
it. The surfaces around it (Packs, Documents, Settings) and the wiring from the input
field to retrieval are not there yet.

Every module is a plain Kotlin/JVM library, deliberately Android-free so far. `:pack`
holds the format definition and every check a pack must survive before the app will
activate it; SQLite sits behind a small read-only `Db` interface, backed by
`sqlite-jdbc` here and by a bundled SQLite on the device later. `:state` owns the app's
own database — the install journal, documents, and trackers — and is separate rather
than a widening of `:pack`, because a pack must never be writable by construction.
`:cli` is the only module that depends on all the others: it is the wiring, and every
seam it crosses is a seam the app will have to cross too.

Building the Android app needs an SDK, which is not vendored: set `sdk.dir` in
`local.properties`, or `ANDROID_HOME`, and run `./gradlew :app:assembleDebug`.

## The test corpus

`corpus/srd` holds **Emberlight**, an original game released under CC BY 4.0 and written
specifically so this repository has a corpus it can distribute — no published rulebook
can be committed here, and testing a quoting engine against text nobody may quote is not
an option.

Emberlight in its present form is **a temporary fixture, quickly generated and not
carefully built**. It is enough to exercise the shape of the pipeline and nothing more:
it has not been designed to stress supersession, nested rules, cross-references, or the
retrieval cases the spec actually cares about, so recall measured against it would say
very little.

The plan is to replace it with a thoroughly constructed original system authored through
[rpg-bookbinder](https://github.com/charlesmsiegel/rpg-bookbinder), which exists in part
to produce exactly this: a complete, internally consistent, freely licensable game whose
structure can be built to exercise the hard retrieval cases on purpose. The evaluation
harness is waiting on that corpus more than on anything else.

## Trying it

```
./gradlew :cli:installDist CLI=cli/build/install/cli/bin/cli

$CLI build corpus/srd /tmp/srd.rpgpack # assemble, then run the app's activation gate $CLI ask /tmp/srd.rpgpack -- "how do I grapple someone" $CLI ask --why /tmp/srd.rpgpack -- "what lives in the cinder marches" $CLI roll /tmp/srd.rpgpack 1
```


The first is intended to print a quote card: the book's bytes, unwrapped and
unrendered, under its citation. The second prints a list of passages and says the model
that would phrase them has not been downloaded — which is the app's honest state before
a download, not a mode built for the command line. `--why` shows what each signal
contributed and what was gated out.

If these commands do not behave as described, that is a defect and an issue is welcome —
this path is written but not yet routinely exercised.

### Bringing your own weights

`ModelManifest` refuses a placeholder digest at parse time. A manifest is written before
the artifact it describes exists, so the digest field spends part of its life unfilled,
and tolerating that is exactly how a build comes to download several gigabytes of
executable behaviour and verify nothing — in the app's only network operation. The
refusal is only tenable if filling the field in is easy:

```
$CLI make-manifest gemma-3n-e2b "Gemma 3n E2B" gemma-terms
https://your-host/gemma ./weights > gemma.json $CLI fetch-model gemma.json ~/.ludex-rpg/models
```


`make-manifest` digests the files you already have and writes a manifest pinned to those
exact bytes; `fetch-model` is written to resume across connectivity loss and to verify
the whole assembled file rather than the newly-fetched tail, because a resume stitched
onto a corrupt prefix would otherwise pass.

## Building

Requires a JDK 21 to build — the float16 tests check the hand-written codec against
`java.lang.Float.float16ToFloat`, which arrived in JDK 20. The module itself emits
17-level bytecode so it stays loadable on Android. There is no Android SDK dependency in
the JVM modules.

```
./gradlew test
```


The suite is **written** to cover the float16 codec exhaustively (all 65 536 bit
patterns, against the JDK), the probe vector's own properties, one deliberately-corrupted
pack per rejection case the app promises to make, retrieval recall over a labelled query
set, and the whole read path from corpus directory to rendered card. Test packs are
forged in-process by `PackForge` and the corpus is assembled at test time from committed
text, so no fixture can drift from the schema it is built against.

CI (`.github/workflows/ci.yml`) runs the same command and is currently
`workflow_dispatch`-only.

Until the suite runs on a schedule and the labelled query set is exercised against a
corpus built for the purpose, treat coverage claims here as descriptions of intent.

## Issues

The tracker is a working backlog rather than a defect list — an open issue usually
records a design decision made and not yet acted on.

## License

Apache-2.0

## Related

These share a commitment: a system should not be able to assert more than its artifacts
support.

- **[hardy](https://github.com/charlesmsiegel/hardy)** — a proof is proved only if
  Lean's kernel says so, and a result cannot be reported unless the artifacts carry it.
- **[coding-skills](https://github.com/charlesmsiegel/coding-skills)** — a finding
  asserts a defect and carries a fix; a candidate reports a lead and carries the benign
  explanations. Confusing them raises.
- **[grimoire](https://github.com/charlesmsiegel/grimoire)** — the prompt a reply came
  from stays readable after everything it drew on has moved.
- **[rpg-bookbinder](https://github.com/charlesmsiegel/rpg-bookbinder)** — state lives
  in files, not in a shared prompt. Also the intended source of this project's next test
  corpus.
