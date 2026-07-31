# Pack Contract and Activation Gate — Design

Date: 2026-07-31
Status: implemented

## Context

Two design documents existed — `android-app-design.md` and
`pack-builder-requirements.md` — both marked unimplemented, and both deferring to "the
app's schema spec" for anything needing exact DDL. That document did not exist, which
made it the blocking dependency for both subsystems it sits between.

The question was therefore not "what do we build first" but "what is everything else
waiting on".

## Decisions

**Start with the app, not the builder.** The app owns the schema spec — it is what
refuses a pack it cannot read — so writing the spec *is* app work, and doing it here
avoids the builder implicitly defining a two-sided contract from one side.

**First slice: the pack contract and the activation gate.** Specifically: the schema
spec pinned as DDL in the repo; a read-only pack reader; and the validator that decides
whether a pack may be activated. Retrieval, cards, and documents all need packs to
exist, and all would otherwise each invent their own fixtures.

**No `:app` module yet.** This container has no Android SDK, and in this slice the
module would hold no logic — an empty artifact whose only claim is that it compiles.
The Android-specific decisions it would have forced (bundled SQLite, minSdk) are
recorded in `00-pack-schema.md`, where they are useful now. `:pack` is plain Kotlin/JVM,
so the whole slice is verifiable with `./gradlew :pack:test`.

**Defer the constraint predicate vocabulary rather than invent it.** It is the open
question `android-app-design.md` §10 says blocks the schema spec. The `constraints`
table *shape* is pinned so packs are writable and their references validated; the
closed set of forms lands with the constraint engine. That unblocks the spec without
guessing at an answer.

## What the DDL forced into the open

Two columns both existing documents assume and neither names:

- **`chunks.stable_key`** — supersession targets `(source_uid, stable_key)`, so every
  source chunk needs one or it can never be amended by errata. The column is pinned and
  required; the rule for computing a value that survives a rebuild stays open, since the
  app only ever compares values.
- **`pack_meta.ruleset_id`** — §6 has documents name the ruleset they are played under,
  with constraints loading only from packs matching that binding. Nothing said where a
  pack declares its ruleset, leaving the binding with no counterpart.

## An amendment to `android-app-design.md` §8

§8 placed containment, sibling overlap, and claim-span validation on the builder-only
side, reasoning that span validation needs the normalized source bytes. True of
boundary validation and slice equality; not true of those three:

- Containment and sibling overlap compare integers already stored in the pack.
- Claim spans index into the derived chunk's own `text`, which ships in the pack.

All three moved into the activation gate. §8 has been amended, and `00-pack-schema.md` §7
carries the full split.

## Design

`PackValidator` walks a pack and returns every violation it finds, each as an enum code
plus detail. Activation refuses on any. Codes rather than messages so tests assert on
the defect they introduced rather than on prose — a message-matching test survives a
copy edit and, worse, passes when the pack was rejected for an unrelated reason.

All SQLite access sits behind a small read-only `Db` interface. That is a near-term
swap, not speculative generality: `sqlite-jdbc` ships desktop native libraries that
cannot run on a device, and the platform's SQLite cannot be relied on for FTS5.

The float16 codec is hand-written rather than delegated to `java.lang.Float`'s JDK 20
intrinsic, which does not exist on Android — and is then checked against that intrinsic
across all 65 536 bit patterns, so hand-rolling it costs nothing in confidence.

The probe vector constant was chosen by computation against stated properties, not by
eye: exactly representable, no zeros, no swap-invariant elements, not a palindrome, and
at least one element that decodes to NaN when read big-endian. `ProbeVectorTest`
asserts each property, so a later edit cannot weaken the probe into something that
still looks like one.

## Testing

`PackForge` builds one valid pack and hands the test an open connection to break
exactly one thing. A knob-per-defect constructor would grow a parameter per rejection
case and still could not express corruptions nobody anticipated; arbitrary SQL can.

Every rejection test also asserts the *unmutated* pack validates, so no test can go
green because the fixture was broken for a reason it was not testing.

Verified by mutation: with `PackValidator.validate` stubbed to return no violations, 36
of 38 validator tests fail. The two that survive are the two asserting validity, which
is correct.

`SchemaDocSyncTest` asserts `docs/00-pack-schema.md` contains the DDL the code uses, the
probe encoding the code produces, and every kind and locator scheme in the vocabulary.
A spec that has quietly diverged from its implementation is worse than none, because
people trust it.

## Review round

Automated review found eight gaps, all real, all of the same shape: properties the
schema spec implies that the validator did not check, letting a pack activate while
being unusable in a way the design forbids.

Three were structural rather than missing conditions:

- **The validator could throw instead of refuse.** A pack with every relation name but a
  missing column raised a driver exception straight through `validateFile`, putting a
  corrupt pack *past* the activation gate as a crash. Driver failures now become
  `PackReadException` at the `Db` boundary — which also keeps the abstraction honest for
  the Android implementation — and the validator turns that into `MALFORMED_SCHEMA`.
- **`chunks_fts` was checked by name only.** `sqlite_master` lists virtual tables as
  ordinary tables, so a plain table passed. Nothing else in the validator queries the
  index, so the failure would have surfaced at the user's first search. It is now probed
  with a term drawn from a chunk's own text, which also catches an index that was never
  populated — the worse of the two failures, since it is silent.
- **Foreign keys are documentation.** SQLite does not validate rows inserted while
  enforcement was off, so `chunks.source_id` could name a book the pack does not
  contain, leaving a quotation uncitable.

The remaining five: derived chunks could declare a parent (passing every nesting check
vacuously, since both spans are NULL); `stable_key` was required but not unique, which
would make an erratum match two passages; sibling overlap compared only adjacent spans
after sorting, so one long sibling enclosing several short ones reported one pair and
missed the rest, contradicting the promise to report every violation; three closed
vocabularies were declared and never consulted; and a pack could ship constraints with
no `ruleset_id`, making them permanently unloadable.

`PackValidator` was split at this point — `ChunkValidation`, `VectorValidation`,
`ReferenceValidation`, and a thin orchestrator — because the additions took it past 800
lines, which the design above named as the signal that a file is doing too much.

## Not in this slice

Retrieval, the answer cards, documents and trackers, the constraint engine, the dice
roller, the Android module, and the bundled models. The dice *grammar* is pinned in the
spec because it is cheap now and expensive once packs exist; its executor is not built.
