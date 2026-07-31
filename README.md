# rpg-helper

An offline reference and play assistant for tabletop roleplaying games: answers rules
questions by quoting your books exactly, answers setting questions in generated prose
that is always cited, and tracks characters under the game's own constraints.

Everything runs on the device. No account, no server, no network at play time.

The central guarantee the whole design serves: **a quote and a paraphrase must never be
confusable, anywhere in the app.**

## Documents

| Document | What it covers |
|---|---|
| [`docs/android-app-design.md`](docs/android-app-design.md) | the app — retrieval, routing, cards, capabilities, documents and constraints |
| [`docs/pack-schema.md`](docs/pack-schema.md) | the pack format — DDL, vector layout, probe constant, dice grammar, what is validated where |
| [`docs/pack-builder-requirements.md`](docs/pack-builder-requirements.md) | what the builder (a separate project) must produce |

## Status

Early. The pack contract and its activation gate are implemented; the app itself is
not.

| Component | State |
|---|---|
| Pack schema specification | written, and enforced against the code by test |
| Pack reader + activation validator (`:pack`) | implemented |
| Retrieval, answer cards, documents, capabilities | designed, unimplemented |
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

The suite covers the float16 codec exhaustively (all 65 536 bit patterns, against the
JDK), the probe vector's own properties, and one deliberately-corrupted pack per
rejection case the app promises to make. Test packs are forged in-process by
`PackForge`, so the fixtures cannot drift from the schema they are built against.
