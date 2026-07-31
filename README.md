# rpg-helper

An offline reference and play assistant for tabletop roleplaying games: answers rules
questions by quoting your books exactly, answers setting questions in generated prose
that is always cited, and tracks characters under the game's own constraints.

Everything runs on the device. No account, no server, no network at play time.

The central guarantee the whole design serves: **a quote and a paraphrase must never be
confusable, anywhere in the app.**

## Documents

**Design** — what the system does and why, with the arguments:

| Document | What it covers |
|---|---|
| [`docs/android-app-design.md`](docs/android-app-design.md) | the app as a whole — the central guarantee and everything downstream of it |
| [`docs/pack-builder-requirements.md`](docs/pack-builder-requirements.md) | what the builder (a separate project) must produce |

**Specifications** — pinned and implementable, one per subsystem, listed in the order
they should be built:

| # | Document | What it pins | Why here |
|---|---|---|---|
| — | [`00-pack-schema.md`](docs/00-pack-schema.md) | the pack format — DDL, vector layout, probe constant, dice grammar | **built**; the contract everything else reads |
| 1 | [`01-app-state-spec.md`](docs/01-app-state-spec.md) | the app's own database, install and activation, caching, migrations, backup | nothing else can run until a pack can be installed and activated |
| 2 | [`02-test-infrastructure-spec.md`](docs/02-test-infrastructure-spec.md) | the corpus, labelled query sets, the claim-support judge, CI gates | retrieval is unmeasurable without a corpus; building it afterwards means calibrating gates against nothing |
| 3 | [`03-model-runtime-spec.md`](docs/03-model-runtime-spec.md) | the three on-device models, their interfaces, the download contract | retrieval needs a real embedding space; the other two models can follow |
| 4 | [`04-retrieval-spec.md`](docs/04-retrieval-spec.md) | query grammars, supersession, gating, fusion, nesting deduplication | the core, and the least settled part of the design — attack it early, now that it is measurable |
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

Early. The pack contract and its activation gate are implemented; the app itself is
not.

| Component | State |
|---|---|
| Specifications | written for every subsystem; the pack schema is enforced against the code by test |
| Pack reader + activation validator (`:pack`) | implemented |
| Retrieval, answer cards, model runtime, documents, capabilities, UI | specified, unimplemented |
| Android app module | not started |
| Pack builder | separate project, not started |

`:pack` is a plain Kotlin/JVM library — it holds the format definition and every check
a pack must survive before the app will activate it. It is deliberately Android-free so
far: SQLite sits behind a small `Db` interface, backed by `sqlite-jdbc` here and by a
bundled SQLite on the device later.

## Building

Requires a JDK 21 to build — the float16 tests check the hand-written codec against
`java.lang.Float.float16ToFloat`, which arrived in JDK 20. The module itself emits
17-level bytecode so it stays loadable on Android. There is no Android SDK dependency
yet.

```sh
./gradlew :pack:test
```

CI (`.github/workflows/ci.yml`) runs the same command, but is currently
`workflow_dispatch`-only: this repository is private and Actions is not provisioned
with runner minutes, so an automatic trigger would post a check that fails in seconds
on every pull request. Restoring the `push`/`pull_request` triggers is a one-line
change once Actions is available.

The suite covers the float16 codec exhaustively (all 65 536 bit patterns, against the
JDK), the probe vector's own properties, and one deliberately-corrupted pack per
rejection case the app promises to make. Test packs are forged in-process by
`PackForge`, so the fixtures cannot drift from the schema they are built against.
