# Pack Schema Specification

The contract between the app and the pack builder.

`android-app-design.md` and `pack-builder-requirements.md` both defer to "the app's
schema spec" for anything requiring exact DDL — the table definitions, the dice
grammar, the probe vector constant, the form of `stable_key`, the constraint predicate
vocabulary. This is that document.

It is owned by the app, as those documents assume: the app is what refuses a pack it
cannot read, so the app is where the definition of readable lives.

Implementation order: **done** — this is the contract the rest builds on.

Status: `schema_version = 1`. Implemented by `:pack`; the DDL below is generated from
`PackSchema.DDL` and `SchemaDocSyncTest` fails the build if they drift.

---

## 1. Versioning

`pack_meta.schema_version` is a single integer. The app refuses any value it does not
recognise, and does not attempt a best-effort read of an unknown layout — the failure
mode of guessing is confident nonsense rather than an error.

The version covers the DDL, the vector BLOB layout, the probe constant, and the dice
grammar together. Adding a dice form is a version bump for the same reason adding a
column is: an older app must refuse a newer pack rather than silently mis-parse it.

`PRAGMA user_version` carries the same number, so the version is visible to tooling
that has not been taught the schema.

---

## 2. SQLite requirements

A pack is one SQLite file, extension `.rpgpack`. Not a zip, not a directory. No
external asset references.

**FTS5 is mandatory.** `chunks_fts` is an FTS5 virtual table and retrieval calls
`bm25()`. A SQLite build without FTS5 cannot open a pack at all, let alone search it.

### SQLite on the device

Android's system SQLite is not an acceptable basis for this format. FTS5 availability
varies by API level and has historically varied by vendor build, and the pack format
has no degraded mode to fall back to — without FTS5 there is no lexical retrieval and
half the hybrid ranking does not exist.

So the app bundles its own SQLite rather than linking the platform's. The cost is a few
megabytes of native library; the alternative is a pack that opens on some phones and
not others, discovered by users rather than by CI.

The `:pack` module reaches SQLite through the `Db` interface for this reason. It is
backed by `sqlite-jdbc` for desktop and test use today, and the Android implementation
slots in beneath the same interface when `:app` arrives.

### Text encoding

Normalized source text is UTF-8, Unicode NFC. All offsets in this format —
`span_start`, `span_end`, `window_start`, `window_end`, `claim_span_start`,
`claim_span_end`, `table_rows.span_start`, `table_rows.span_end` — count **UTF-8
bytes**, zero-based and end-exclusive.

Bytes rather than code points because the pack is SQLite, which already stores UTF-8:
slicing is exact and O(1), and a validator can check a span without decoding the
document.

---

## 3. The DDL

<!-- BEGIN GENERATED DDL: edit PackSchema.DDL, not this block -->
```sql
PRAGMA user_version = 1;

CREATE TABLE pack_meta (
    id              INTEGER PRIMARY KEY CHECK (id = 1),
    schema_version  INTEGER NOT NULL,
    pack_uid        TEXT    NOT NULL,
    pack_version    TEXT    NOT NULL,
    title           TEXT    NOT NULL,
    ruleset_id      TEXT,
    embedder_id     TEXT    NOT NULL,
    embedder_dim    INTEGER NOT NULL,
    probe_vector    BLOB    NOT NULL,
    license_id      TEXT    NOT NULL,
    license_text    TEXT,
    attribution     TEXT,
    built_at        TEXT    NOT NULL,
    builder_version TEXT    NOT NULL,
    signature       BLOB
);

CREATE TABLE sources (
    source_id      INTEGER PRIMARY KEY,
    source_uid     TEXT    NOT NULL UNIQUE,
    title          TEXT    NOT NULL,
    edition        TEXT,
    publisher      TEXT,
    text_sha256    TEXT    NOT NULL,
    locator_scheme TEXT    NOT NULL
);

CREATE TABLE source_page_labels (
    source_id   INTEGER NOT NULL REFERENCES sources(source_id),
    seq         INTEGER NOT NULL,
    phys_start  INTEGER NOT NULL,
    phys_end    INTEGER NOT NULL,
    scheme      TEXT    NOT NULL,
    start_value INTEGER,
    prefix      TEXT,
    PRIMARY KEY (source_id, seq)
);

CREATE TABLE chunks (
    chunk_id         INTEGER PRIMARY KEY,
    kind             TEXT    NOT NULL,
    origin           TEXT    NOT NULL,
    text             TEXT    NOT NULL,
    source_id        INTEGER REFERENCES sources(source_id),
    heading_path     TEXT,
    page_label_start TEXT,
    page_label_end   TEXT,
    span_start       INTEGER,
    span_end         INTEGER,
    stable_key       TEXT,
    parent_chunk_id  INTEGER REFERENCES chunks(chunk_id)
);

CREATE VIRTUAL TABLE chunks_fts USING fts5(
    text,
    heading_path,
    content='chunks',
    content_rowid='chunk_id',
    tokenize='unicode61 remove_diacritics 2'
);

CREATE TABLE vectors (
    chunk_id       INTEGER NOT NULL REFERENCES chunks(chunk_id),
    role           TEXT    NOT NULL,
    subchunk_index INTEGER NOT NULL,
    embedding      BLOB    NOT NULL,
    window_start   INTEGER,
    window_end     INTEGER,
    PRIMARY KEY (chunk_id, role, subchunk_index)
);

CREATE TABLE entities (
    entity_id INTEGER PRIMARY KEY,
    canonical TEXT    NOT NULL,
    alias     TEXT    NOT NULL,
    kind      TEXT    NOT NULL,
    chunk_id  INTEGER REFERENCES chunks(chunk_id)
);

CREATE TABLE tables (
    table_id  INTEGER PRIMARY KEY,
    chunk_id  INTEGER NOT NULL REFERENCES chunks(chunk_id),
    dice_expr TEXT    NOT NULL
);

CREATE TABLE table_rows (
    table_id   INTEGER NOT NULL REFERENCES tables(table_id),
    seq        INTEGER NOT NULL,
    lo         INTEGER NOT NULL,
    hi         INTEGER NOT NULL,
    span_start INTEGER NOT NULL,
    span_end   INTEGER NOT NULL,
    text       TEXT    NOT NULL,
    PRIMARY KEY (table_id, seq)
);

CREATE TABLE capabilities (
    capability_id INTEGER PRIMARY KEY,
    kind          TEXT    NOT NULL,
    chunk_id      INTEGER NOT NULL REFERENCES chunks(chunk_id),
    manifest      TEXT    NOT NULL
);

CREATE TABLE chunk_derivation (
    derivation_id    INTEGER PRIMARY KEY,
    derived_chunk_id INTEGER NOT NULL REFERENCES chunks(chunk_id),
    source_chunk_id  INTEGER NOT NULL REFERENCES chunks(chunk_id),
    claim_span_start INTEGER,
    claim_span_end   INTEGER
);

CREATE TABLE source_gaps (
    gap_id     INTEGER PRIMARY KEY,
    source_id  INTEGER NOT NULL REFERENCES sources(source_id),
    span_start INTEGER NOT NULL,
    span_end   INTEGER NOT NULL,
    reason     TEXT    NOT NULL,
    note       TEXT
);

CREATE TABLE constraints (
    constraint_id INTEGER PRIMARY KEY,
    form          TEXT    NOT NULL,
    args          TEXT    NOT NULL,
    chunk_id      INTEGER NOT NULL REFERENCES chunks(chunk_id)
);

CREATE TABLE supersessions (
    supersession_id         INTEGER PRIMARY KEY,
    target_source_uid       TEXT    NOT NULL,
    target_stable_key       TEXT    NOT NULL,
    superseding_chunk_id    INTEGER REFERENCES chunks(chunk_id),
    target_title            TEXT    NOT NULL,
    target_edition          TEXT,
    target_heading_path     TEXT,
    target_page_label_start TEXT,
    target_page_label_end   TEXT
);

CREATE TABLE build_report (
    report_id    INTEGER PRIMARY KEY,
    severity     TEXT    NOT NULL,
    subject_kind TEXT    NOT NULL,
    subject_id   TEXT,
    validation   TEXT    NOT NULL,
    detail       TEXT
);

CREATE INDEX idx_chunks_parent ON chunks(parent_chunk_id);
CREATE INDEX idx_chunks_source ON chunks(source_id);
CREATE INDEX idx_vectors_chunk ON vectors(chunk_id);
CREATE INDEX idx_derivation_derived ON chunk_derivation(derived_chunk_id);
CREATE INDEX idx_entities_alias ON entities(alias);
CREATE INDEX idx_supersessions_target
    ON supersessions(target_source_uid, target_stable_key);
```
<!-- END GENERATED DDL -->

The app validates every property this DDL declares rather than trusting it. A pack is a
file someone else built, and its `CHECK` constraints are whatever its builder chose to
write; reading them as a guarantee would mean trusting the artifact under inspection.

---

## 4. Column semantics

### `chunks`

`kind` and `origin` together decide rendering. Nothing else does.

| kind | verbatim-eligible | notes |
|---|---|---|
| `rules` | yes | mechanics, procedures, resolution |
| `table` | yes | also gets validated rows in `tables` |
| `statblock` | yes | indivisible; creature/NPC/item stats |
| `readaloud` | yes | boxed text intended to be read at the table |
| `glossary` | yes | term definitions; feeds entity resolution |
| `setting` | **no** | lore, history, geography, culture, factions |

A chunk is **verbatim-class** — quoted byte-exactly, never paraphrased — if and only if
its `kind` is verbatim-eligible **and** `origin = 'source'`. Every verbatim-eligible
kind may also appear with `origin = 'derived'`, and every such chunk renders as
attributed prose. There is deliberately no validation rejecting "a verbatim-class chunk
with derived origin": verbatim-class is *defined* to exclude that, so the condition is
unsatisfiable, and an implementer reading it as "verbatim-eligible kind with derived
origin" would reject every legitimate `('glossary', 'derived')` chunk.

Columns required by origin:

| column | `origin='source'` | `origin='derived'` |
|---|---|---|
| `text` | sliced from the source | generated by the builder |
| `source_id`, `heading_path` | required | must be NULL |
| `page_label_start`, `page_label_end` | required | must be NULL |
| `span_start`, `span_end` | required | must be NULL |
| `stable_key` | required | must be NULL |
| `parent_chunk_id` | NULL unless nested | NULL |
| `chunk_derivation` rows | must be absent | must be non-empty |

Derived chunks carry no citation columns because a summary may draw on three chapters
of one book and an appendix of another. `source_id` holds one book and the page labels
describe one continuous run, so an inline citation would mean either dropping sources
or printing a fabricated range — and a fabricated range is worse than none, because it
looks checkable.

### `stable_key`

The identity by which an errata pack names a passage, targeted as
`(sources.source_uid, chunks.stable_key)`. Required on every source chunk: a chunk
without one can never be amended.

It must survive a rebuild of the pack that contains it, which rules out `chunk_id`.
**The derivation rule is not yet pinned** — see §8.

### Nesting

`parent_chunk_id` declares the only legal form of span overlap: a complete rule
containing a rollable table, where the rule must stay one display chunk to be quotable
in full and the table needs its own chunk for its structured rows.

- A child's span must be fully contained in its parent's.
- A child must have the same `source_id` as its parent.
- Nesting is at most one level deep. Deeper structures signal the parent was chunked
  too coarsely.
- Sibling spans must not overlap. The sibling group key is
  `(source_id, parent_chunk_id)` with NULL as an ordinary group value — grouping by
  parent alone collides every book's opening chunk with every other book's, and an
  implementation using SQL `NULL = NULL` skips top-level chunks entirely, which looks
  like a passing build.

### `vectors`

One display chunk, many vectors. An atomic unit is never split for display and must
often be split for embedding.

- `role='content'` — an embedding of a window of the chunk's own text.
  `window_start`/`window_end` are required and must lie within the chunk's text.
- `role='expansion'` — an embedding of a generated question paraphrase.
  Windows are NULL.

Content windows must tile the chunk with overlap; no region of any retrievable chunk
may be absent from every window — `setting` and derived chunks included, not
verbatim-class only. (Tiling completeness is a builder validation; the app checks that
each window is in range.)

### `chunk_derivation`

One row per cited source chunk. Every referenced chunk must exist in the same pack and
have `origin='source'`: derived-from-derived is not permitted, because provenance
becomes untraceable after two hops and the resulting card cites a chunk with no page
fields behind it.

A row may carry a claim span into the derived chunk's **own** `text`, anchoring an
inline citation chip to the sentence that source supports. A NULL span means the source
backs the chunk generally and appears in the footer. Claim spans may overlap freely —
two sources supporting one sentence is corroboration, not a conflict.

### `sources` and page labels

Citations use the label printed on the physical page, never the PDF page index. Real
books have Roman-numeral front matter, restarts in compilations, and unnumbered plates,
so `source_page_labels` maps ranges of physical page indices to a labelling scheme.
Chunks store the *resolved* label as text.

Products with no page numbers at all — a GM screen, a card deck, a fold-out map, a
pamphlet — declare a `locator_scheme` instead:

| scheme | citation reads |
|---|---|
| `page` | Core Rulebook, p. 42 |
| `panel` | GM screen, panel 3 |
| `card` | Hazard deck, card 14 |
| `sheet` | Reference sheets, sheet 2 |
| `section` | Pamphlet, §4 |
| `position` | last resort: ordinal position in the source |

`page_label_start`/`page_label_end` hold the resolved locator text either way — the
column was always text precisely so it would not assume numbers.

### `pack_meta.ruleset_id`

The ruleset this pack's constraints govern, or NULL for a setting-only pack.

Documents name the ruleset they are played under, and constraints load only from packs
matching that binding. Without this column there is nothing for a document to bind
*to*, and the alternative — applying every active pack's constraints to every document
— flags a fantasy character for violating a cyberpunk edition's rules.

### `constraints`

`form` names a predicate from a closed vocabulary; `args` is a JSON object of that
form's arguments. Packs instantiate forms and never compose expressions; no code, no
eval, no callbacks. `chunk_id` cites the passage stating the rule, so a flagged
violation can link to the text behind it.

The vocabulary is pinned in `07-documents-and-constraints-spec.md` §4: five forms, a
two-shape selector grammar, and bounds that may reference another tracker. The app
validates a row's `chunk_id` reference at activation and its `form`/`args` when the
constraint engine loads it.

---

## 5. Vector BLOB layout

- IEEE 754 binary16 (`float16`)
- **little-endian**
- contiguous, one vector per BLOB, element order matching `embedder_dim`

`length(blob) == embedder_dim * 2` is necessary and is checked, but it cannot detect
byte order: a big-endian vector is exactly as long as its little-endian counterpart.
Java's `ByteBuffer` defaults to big-endian while most builder toolchains write native
little-endian, so left unenforced this is a rule with nothing behind it, and the failure
it admits — plausible-looking retrieval returning wrong chunks — is the hardest kind to
notice.

### The probe vector

`pack_meta.probe_vector` is a fixed 16-element float16 vector, encoded by the builder
through the same writer that encodes every real vector in the pack. The app decodes it
with its own reader and compares elementwise. A mismatch rejects the pack.

Sharing the encoding path is what makes it work: it catches byte order, and also a
builder that wrote float32, wrote NaN-boxed values, or padded its rows — a class of
layout bug no per-vector inspection could find, since a real embedding has no expected
value to compare against.

Its length is fixed at 16 elements regardless of `embedder_dim`, so a pack cannot
satisfy it by handing back one of its own vectors.

| # | value | LE bits | as big-endian |
|---|---|---|---|
| 0 | `1.0` | `0x3C00` | subnormal |
| 1 | `-2.0` | `0xC000` | subnormal |
| 2 | `1.37109375` | `0x3D7C` | **NaN** |
| 3 | `-1.37109375` | `0xBD7C` | **NaN** |
| 4 | `0.5` | `0x3800` | subnormal |
| 5 | `-0.25` | `0xB400` | subnormal |
| 6 | `3.140625` | `0x4248` | `0x4842` |
| 7 | `-3.140625` | `0xC248` | `0x48C2` |
| 8 | `65504.0` | `0x7BFF` | **NaN** |
| 9 | `-6.103515625e-5` | `0x8400` | `0x0084` |
| 10 | `1.0009765625` | `0x3C01` | `0x013C` |
| 11 | `-1023.5` | `0xE3FF` | **NaN** |
| 12 | `0.333251953125` | `0x3555` | `0x5535` |
| 13 | `-0.66650390625` | `0xB955` | `0x55B9` |
| 14 | `1.75` | `0x3F00` | subnormal |
| 15 | `-1.9990234375` | `0xBFFF` | **NaN** |

Canonical little-endian encoding:

```
003c 00c0 7c3d 7cbd 0038 00b4 4842 48c2
ff7b 0084 013c ffe3 5535 55b9 003f ffbf
```

The constant was chosen so the check provably works rather than plausibly works. Every
element is exactly representable in binary16 (so the comparison needs no tolerance);
none is zero (which is byte-swap invariant); the sequence is not a palindrome (so
element order is checked too); no element is swap-invariant; and five elements decode
to NaN if read big-endian. `ProbeVectorTest` asserts each of those properties, so a
future edit cannot weaken the probe into something that still looks like one.

`65504.0` and `-6.103515625e-5` are the largest finite and smallest normal binary16
values; a builder that converted through the wrong width tends to fail on exactly those.

### Numeric usability

Layout says the bytes were arranged correctly; it says nothing about whether the
numbers mean anything. Every vector element must be finite, and every vector's L2 norm
must exceed `1e-6`.

- **NaN** propagates through the dot product and compares false against everything, so
  depending on the sort the chunk either vanishes from retrieval permanently or lands
  wherever the comparator leaves it.
- **Infinity** produces a similarity that outranks every real result, for every query.
- **Zero norm** makes cosine a division by zero — and an all-zero vector is the
  ordinary result of an embedding call that failed and was not checked.

---

## 6. The dice grammar

Pinned here because the builder's parser and the app's roller are different codebases
in different languages, and dice notation is a folk grammar with no standard. A
disagreement produces rows that validate cleanly on the desktop and roll outside their
validated range on the phone.

Grammar, and nothing else parses:

```
expr     := form modifier?
form     := NdS | dS | 'd%'
modifier := ('+' | '-') integer
N, S     := positive integers
```

- `d%` is exactly 1–100. Books printing `00` map to 100 at build time, and the mapping
  is recorded rather than assumed.
- Exploding dice, drop-lowest, and rerolls are not expressible. A table needing them
  ships quotable and not rollable.
- Both the outcome **range** and the **distribution** are part of the definition.
  `2d6` and `d11+1` share the range 2–12 and are not interchangeable: the first is
  triangular and peaks at 7, the second is flat. A roller that picked the wrong one
  would return outcomes at wrong frequencies forever — a bug no coverage check can see,
  because every outcome remains reachable.
- Versioned with `schema_version`. Adding a form is a version bump.

Builder and app ship the **same** conformance vectors — a fixed list of expressions
with expected ranges and distributions, run as a test on both sides. Without them this
is a specification two teams can each believe they implement.

> The grammar is pinned; the roller is not yet implemented. It lands with the
> capabilities work, against these conformance vectors.

---

## 7. Validation: who checks what

Two lists, split by what the artifact can prove about itself. The pack ships the *hash*
of its normalized source text, not the text, so anything needing the source bytes stays
in the builder.

### The app checks, at activation

Every one of these rejects the pack. There is no partial activation: by the time a
violation is visible the builder's guarantees have demonstrably not held.

| area | check |
|---|---|
| format | required tables present; `pack_meta` is exactly one row; `schema_version` recognised |
| readability | every query the format requires succeeds — a relation present in name but missing a column is a violation, never a thrown exception |
| lexical index | `chunks_fts` is declared as an FTS5 virtual table **and** answers a `MATCH` for a term taken from a chunk's own text with that chunk |
| embedder | `embedder_id` is bundled; `embedder_dim` matches that contract and is positive |
| vector layout | probe decodes to the pinned constant; `length(blob) == embedder_dim * 2` |
| vector numerics | all elements finite; L2 norm above `1e-6` |
| vector windows | `content` rows have an in-range window; `expansion` rows have none |
| chunk shape | `kind` and `origin` in vocabulary; citation and span columns present or absent per origin; derived chunks declare no parent |
| required columns | a NULL where the format requires a value is a violation, not an exception — the pack's own `NOT NULL` declarations are not evidence |
| spans | `span_end - span_start` equals the UTF-8 byte length of `text`; spans not inverted |
| nested text | a child's `text` byte-equals its parent's `text` sliced at the child's offset |
| stable keys | `(source_uid, stable_key)` is unique |
| source identity | `sources.source_uid` is unique |
| table rows | every `table_id` resolves; ranges are non-overlapping and not inverted; each row's span lies inside its table chunk's span, and its `text` byte-equals that slice |
| nesting | child contained in parent; same source; at most one level; siblings do not overlap |
| derivation | derived chunks cite at least one chunk; every cited chunk exists and has `origin='source'` |
| claim spans | in range of the derived text, non-empty, not inverted, on UTF-8 boundaries |
| chunk references | `entities`, `tables`, `capabilities`, `constraints`, `supersessions` resolve to chunks that exist |
| source references | `chunks`, `source_page_labels`, `source_gaps` resolve to sources that exist |
| closed vocabularies | `locator_scheme`, page-label `scheme`, and gap `reason` are all in their sets |
| ruleset binding | a pack shipping `constraints` rows declares a `ruleset_id` |

Three of these deserve a note, because the reason they exist is not obvious from the
rule:

- **The lexical index is probed, not just named.** `sqlite_master` lists a virtual table
  as an ordinary `table`, so checking the name admits a plain table wearing it — and
  nothing else in the validator queries the index. The failure would surface at the
  user's first search, either as a thrown `MATCH` error or, for an FTS5 table that was
  simply never populated, as an empty lexical result on every query forever. The probe
  uses a term drawn from a chunk's own text, so a pass proves the index exists, is
  queryable, and indexes the content it claims to.
- **`stable_key` uniqueness is enforced here rather than by a `UNIQUE` constraint.** The
  DDL is shipped inside the pack, so its constraints describe what its builder chose to
  declare and guarantee nothing about the file in hand. A duplicate does not make a pack
  unreadable — it makes it un-amendable, since an erratum targeting
  `(source_uid, stable_key)` would match both chunks and filter an unrelated passage
  alongside the one it meant to correct.
- **Foreign keys are not enforcement.** SQLite does not validate rows inserted while
  foreign-key enforcement was off, which is the default, so every `REFERENCES` clause in
  the DDL above is documentation. Reference checks are code. The same applies to
  `NOT NULL` and `UNIQUE`: the DDL ships *inside* the pack, so it records what that
  builder chose to declare. Every one of those properties is checked here.
- **Nested text agreement is decidable and load-bearing.** Slice equality against the
  normalized *source* is builder-only, because the pack ships only its hash. Slice
  equality of a child against its *parent's own shipped text* needs nothing the pack does
  not carry — and without it, containment and span-length both pass for a child whose
  span points at the wrong region, so redaction excises innocuous prose and the child's
  real rule text passes into the generation context.
- **`table_rows` feeds quotation-styled output.** The roller renders outcome text under
  the quotation rule, so unchecked rows would launder arbitrary prose into the app's most
  authoritative rendering beneath the table's own citation. Coverage of the dice
  expression's full outcome range needs the grammar parser and lands with the roller;
  everything decidable without it is checked at activation.

### The builder checks, at build time

These need the normalized source bytes and cannot run on the device:

- span **boundary** validation — spans begin and end at structural boundaries recovered
  from the document
- slice equality — `text` equals the source sliced by its span
- `sources.text_sha256` matching the normalized bytes
- span offsets landing on UTF-8 sequence boundaries *in the source*
- top-level coverage — chunks plus declared `source_gaps` tile each source exactly
- content-window tiling completeness
- structured table round-trip against the verbatim chunk
- claim-level entailment of derived prose against its cited chunks

### An amendment to the app design's §8

`android-app-design.md` §8 originally placed containment, sibling overlap, and claim-span
validation on the builder-only side, on the grounds that span validation "requires the
normalized source bytes". That is true of boundary validation and slice equality. It is
not true of the other three:

- **Containment** and **sibling overlap** compare integers already stored in the pack.
  No source text is involved.
- **Claim spans** index into the derived chunk's own `text`, which ships in the pack —
  so range, emptiness, and UTF-8 boundary alignment are all checkable on the device.

All three are checked at activation. The cost is nil and the alternative was trusting a
builder that does not exist yet. §8 has been amended to match.

If the normalized source text ever ships in packs (open — it roughly doubles text size),
the two lists above merge into one suite run on both sides.

---

## 8. Not yet pinned

Deliberately deferred, with what unblocks each:

- ~~The constraint predicate vocabulary.~~ **Closed.** The five forms — `range`,
  `sum_range`, `count_range`, `requires`, `excludes` — their selector and bound grammars,
  and their evaluation semantics are pinned in `07-documents-and-constraints-spec.md` §4.
  The `constraints` row shape here is unchanged; `form` and `args` now have a defined
  vocabulary rather than an open one.
- **The derivation rule for `stable_key`.** The column is pinned and required; how a
  builder computes a value that survives a rebuild is open. The app only ever compares
  values, so this can be settled without a schema change.
- **The signing scheme.** `pack_meta.signature` exists and is nullable. Verification is
  unspecified, and depends on the pack distribution question, which is open on both
  sides.
- **Whether normalized source text ships in the pack.** Would let the two validation
  lists in §7 merge; roughly doubles text size.
- **Images.** The on-device model accepts image input, so storing page images is
  plausible but unpriced. No table is reserved for it.
