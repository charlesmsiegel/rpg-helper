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

**Specifications** — pinned and implementable, one per subsystem:

| Document | What it pins |
|---|---|
| [`docs/pack-schema.md`](docs/pack-schema.md) | the pack format — DDL, vector layout, probe constant, dice grammar, what is validated where |
| [`docs/retrieval-spec.md`](docs/retrieval-spec.md) | the pipeline — query grammars, supersession, gating, fusion, nesting deduplication |
| [`docs/routing-and-cards-spec.md`](docs/routing-and-cards-spec.md) | the three routes, redaction, residual intent, the five cards, citation resolution |
| [`docs/model-runtime-spec.md`](docs/model-runtime-spec.md) | the three on-device models, their interfaces, the download contract, degradation |
| [`docs/documents-and-constraints-spec.md`](docs/documents-and-constraints-spec.md) | documents, trackers, ruleset binding, and the five constraint predicate forms |
| [`docs/capabilities-spec.md`](docs/capabilities-spec.md) | the capability vocabulary, manifests, and the dice roller |
| [`docs/app-state-spec.md`](docs/app-state-spec.md) | the app's own database, install and activation, caching, migrations, backup |
| [`docs/ui-spec.md`](docs/ui-spec.md) | the four surfaces, and what the quote/paraphrase distinction must survive |
| [`docs/test-infrastructure-spec.md`](docs/test-infrastructure-spec.md) | the corpus, labelled query sets, the claim-support judge, and the CI gates |

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
