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
recorded in `pack-schema.md`, where they are useful now. `:pack` is plain Kotlin/JVM,
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

All three moved into the activation gate. §8 has been amended, and `pack-schema.md` §7
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

`SchemaDocSyncTest` asserts `docs/pack-schema.md` contains the DDL the code uses, the
probe encoding the code produces, and every kind and locator scheme in the vocabulary.
A spec that has quietly diverged from its implementation is worse than none, because
people trust it.

## Not in this slice

Retrieval, the answer cards, documents and trackers, the constraint engine, the dice
roller, the Android module, and the bundled models. The dice *grammar* is pinned in the
spec because it is cheap now and expensive once packs exist; its executor is not built.
