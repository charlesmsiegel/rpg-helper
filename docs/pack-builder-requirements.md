# Pack Builder — Running Requirements

Living document. The pack builder is a **separate project** (likely a separate repo),
built after the Android app. This file accumulates everything the app assumes about
packs, so the builder can be specified without re-deriving it.

The builder runs on a desktop. It may use frontier models and may include
hand-authoring tools. It is not resource-constrained.

Status: accumulating. Nothing here is implemented.

---

## The One Inviolable Rule

**The builder may classify, bound, and annotate chunks. It may never rewrite the text
of a verbatim-class chunk.**

The **verbatim-eligible set** is defined once, here, and referenced everywhere else:

> `rules`, `table`, `statblock`, `readaloud`, `glossary`

A chunk is **verbatim-class** when its `kind` is in that set **and** its `origin` is
`source` (see *Rendering* below). Verbatim-class `text` must be byte-exact source
text. The app copies these strings directly to the screen as authoritative quotes; it
has no way to detect tampering.

This rule exists because it is exactly what a capable model will violate. Asked to
extract a rules passage, a frontier model will fix the grammar, expand an
abbreviation, resolve a pronoun, or normalize a dice notation — all improvements, all
fatal.

### Extraction is by span, not by transcription

A model must never emit verbatim text. It emits **character offsets** into the
normalized source document; the builder slices the text mechanically.

Checking a model-transcribed string against the source with an exact substring match
is insufficient, and the failure is silent. If the model drops the opening sentence of
a rule, or stops before its exception clause, the shortened text still occurs verbatim
in the source, still passes the check, and still renders as an authoritative quote —
now missing the part that changes its meaning. Substring matching proves the text
appeared somewhere, not that the passage is complete.

Requirements, applying **only to chunks with `origin = 'source'`**:

- Each source document is normalized once and hashed; `sources.text_sha256` pins it.
- Every source chunk stores `span_start` / `span_end` into that normalized text.
- `text` is produced by slicing, never by a model writing it out.
- Boundary validation: spans must begin and end at structural boundaries recovered
  from the document (heading, paragraph, list item, table, or statblock edges).
  A span ending mid-sentence, or starting mid-list, is rejected.
- **Sibling** spans must not overlap, and must not leave unassigned text between them
  without an explicit gap marker. Siblings are defined by `(source_id,
  parent_chunk_id)` — see *The span tree is per source*, below.

Chunks with `origin = 'derived'` have no spans. `span_start` / `span_end` are NULL,
and the chunk instead references the source chunks it was produced from through the
`chunk_derivation` relation, which must be non-empty. Requiring spans of derived text
would force the builder to either fabricate misleading offsets or violate the slicing
rule.

#### Offsets count UTF-8 bytes

"Character offset" is not a unit. Python slices by code point, Kotlin by UTF-16 code
unit, and other tooling by byte. A source containing an em-dash, an accented name, or
an emoji in a designer's sidebar will slice to three different strings from the same
pair of integers, and the resulting quote is wrong in a way no length check catches.

Pinned:

- Normalized source text is **UTF-8**, Unicode **NFC**.
- `span_start` / `span_end` count **UTF-8 bytes**, zero-based, end-exclusive.
- Both offsets must land on a UTF-8 sequence boundary. A span splitting a multi-byte
  character is rejected.
- `sources.text_sha256` is the SHA-256 of exactly those normalized UTF-8 bytes.

Bytes win over code points because the pack is SQLite, which already stores UTF-8:
slicing is exact and O(1) on both sides, and a validator can verify a span without
decoding the document at all.

#### The span tree is per source

A pack holds several books. Their offsets are measured independently, so two books
both have a chunk starting at byte 0, and every top-level chunk in the pack has
`parent_chunk_id = NULL`.

Grouping siblings by `parent_chunk_id` alone therefore breaks in both directions: read
naively, every book's opening chunk collides with every other book's; implemented with
SQL `NULL = NULL`, which is never true, top-level chunks are silently skipped and get
no overlap validation at all. The second failure is the dangerous one, because it
looks like a passing build.

So:

- The sibling group key is `(source_id, parent_chunk_id)`, with NULL treated as a
  group value rather than as SQL NULL.
- Spans are only ever comparable within one `source_id`.
- A nested child must have the same `source_id` as its parent.

### Nesting is explicit and legal

A complete rule may contain a rollable table. The rule must stay one display chunk to
be quotable in full; the table needs its own `table` chunk so its structured rows can
round-trip against a canonical table render. Those two spans necessarily overlap.

So overlap is permitted **only** as parent/child nesting, declared via
`parent_chunk_id`:

- A child's span must be **fully contained** within its parent's span.
- A child's `kind` must be verbatim-eligible if its parent's is.
- Nesting is at most one level deep. Deeper structures are a signal the parent was
  chunked too coarsely.
- Round-trip validation for a child runs against the child's span, not the parent's.

Retrieval deduplicates when a parent and its child both match, but **which one
survives depends on the parent's class**, not on the nesting alone:

- **Verbatim-class parent** → the parent wins. The user sees the complete rule rather
  than a table torn out of the middle of it.
- **Non-verbatim parent** (a `setting` chapter containing a rollable rumor table, or a
  statblock inside a lore passage) → the **child** wins.

The second case is not symmetry for its own sake. A `setting` parent's text contains
its child's text, so any lexical search that finds the table also finds the enclosing
lore. Preferring the parent unconditionally would take an authoritative, quotable
table and deliver it as generated prose — the app paraphrasing content it was holding
a byte-exact copy of. Class-aware dedup is what keeps a verbatim child from being
swallowed by a parent that cannot be quoted.

A capability may also target a child directly by kind — this is how the roller reaches
a table embedded in a rule without the app ever quoting the table stripped of its
surrounding rule.

`setting` chunks are also stored as sliced source text (generation happens on-device
from them), but drift there degrades quality rather than breaking a guarantee.

---

## Output Format

A single SQLite file, extension `.rpgpack`. Not a zip, not a directory.
Must be internally complete — no external asset references.

### Required tables

| Table | Purpose |
|---|---|
| `pack_meta` | identity, versioning, embedder contract, license, signature |
| `sources` | per-book identity, edition, page-label map, normalized-text hash |
| `chunks` | the content; `kind` + `origin` drive rendering |
| `chunks_fts` | FTS5 virtual table over `text` + `heading_path` |
| `vectors` | retrieval vectors; many per chunk |
| `entities` | proper nouns + aliases, for voice normalization and linking |
| `tables` | structured random tables: dice expression + validated rows |
| `capabilities` | declarative capability manifests shipped by this pack |
| `chunk_derivation` | which source chunks each derived chunk was built from |
| `supersessions` | chunks this pack replaces in another pack (errata) |
| `build_report` | every enrichment that was dropped, and why |

Exact DDL is pinned by the app's schema spec. The builder targets a
`schema_version` and the app refuses packs it does not recognize.

### `chunks` columns introduced by this document

Collected here because they are established piecemeal above:

| column | notes |
|---|---|
| `kind` | see taxonomy |
| `origin` | `source` \| `derived` |
| `text` | sliced for `source`, generated for `derived` |
| `source_id`, `heading_path` | citation; required iff `origin='source'`, else NULL |
| `page_label_start`, `page_label_end` | **text**, printed labels; same condition |
| `span_start`, `span_end` | required iff `origin='source'`, else NULL |
| `parent_chunk_id` | NULL unless nested; at most one level |

### Derived chunks cite by reference, not by column

A derived chunk has no citation columns of its own. A summary of how travel works may
draw on three chapters of one book and an appendix of another; `source_id` holds one
book and `page_label_start`/`page_label_end` describe one continuous run of pages, so
storing its citation inline means either dropping sources or printing a page range
that spans material the chunk never used. A fabricated range is worse than none — it
is checkable-looking and wrong.

So derived chunks carry their citations in `chunk_derivation`, one row per cited
source chunk. The app renders the citation by resolving those rows and reading each
cited chunk's own `source_id`, `heading_path`, and page labels. This is exactly the
"generated from N sources" footer the app already shows, and it means a derived
chunk's citation cannot drift from the chunks it was actually built from.

Requirements:

- `chunk_derivation` is non-empty for every `origin='derived'` chunk and empty for
  every `origin='source'` chunk.
- Every referenced `chunk_id` must exist in the same pack and have `origin='source'`.
  Derived-from-derived is not permitted; it makes provenance untraceable after two
  hops.

---

## Rendering Is Decided by `kind` + `origin`

`kind` alone is not sufficient. `origin` is a separate required column with two
values:

- `source` — sliced from the document, byte-exact
- `derived` — produced by the builder (summaries, definitions, expansions)

**A chunk renders verbatim if and only if `origin = 'source'` AND `kind` is in the
verbatim-eligible set defined above.** Everything else renders as generated text,
attributed but never quoted.

This resolves the `glossary` ambiguity: a glossary entry lifted from the book's own
glossary is `('glossary', 'source')` and quotes; a builder-written definition is
`('glossary', 'derived')` and does not. The app never has to guess, and a derived
definition cannot be laundered into an authoritative quotation by its `kind`.

The pairing generalizes past `glossary`. **Every** verbatim-eligible kind may also
appear with `origin = 'derived'` — a written-up summary of a statblock, a condensed
version of a long table — and every such chunk renders as generated text. A
verbatim-eligible `kind` is therefore never on its own a defect; only the pair
decides, and the pair is always well-defined.

### Chunk `kind` taxonomy

Every kind below is verbatim-eligible except `setting`. Eligibility is necessary but
not sufficient — `origin` must also be `source`.

| kind | In verbatim-eligible set | Notes |
|---|---|---|
| `rules` | yes | mechanics, procedures, resolution |
| `table` | yes | also gets validated rows in `tables` |
| `statblock` | yes | indivisible; creature/NPC/item stats |
| `readaloud` | yes | boxed text intended to be read at the table |
| `glossary` | yes | term definitions; feeds entity resolution |
| `setting` | **no** | lore, history, geography, culture, factions |

Classification quality is the builder's core value. Misfiling a rule as `setting`
means it gets paraphrased — the exact failure the design prevents everywhere else.
When genuinely ambiguous, classify as `rules`: an unnecessary verbatim quote is a
minor annoyance, a paraphrased rule is a correctness bug.

---

## Display Chunks vs Retrieval Vectors

An atomic unit must never be split for display. It must often be split for embedding:
a full statblock or a long table can exceed the embedder's input limit, and embedding
a truncated prefix silently drops everything after the cutoff out of dense retrieval.

These are therefore separate concerns:

- **One display chunk** per atomic unit. Indivisible. What gets quoted.
- **Many vectors** per display chunk. Each row in `vectors` carries `chunk_id`,
  `subchunk_index`, `role`, and the embedding.

`role` distinguishes:

- `content` — an embedding of some window of the chunk's own text
- `expansion` — an embedding of a generated question paraphrase (see below)

Retrieval scores vectors, then resolves to distinct `chunk_id` values, taking each
chunk's best-scoring vector. The user always sees the whole unit.

Requirements:

- Content subchunk windows must tile the chunk with overlap; no region of a
  verbatim-class chunk may be absent from every window.
- If a single atomic unit exceeds a configured maximum size, the builder fails loudly
  rather than truncating.

### Vector BLOB layout

Element width alone is not a contract. Pinned:

- IEEE 754 binary16 (`float16`)
- **little-endian**
- contiguous, one vector per BLOB, element order matching `embedder_dim`

Java's `ByteBuffer` defaults to big-endian while most builder toolchains write native
little-endian. Left unpinned, a pack passes every `embedder_id` and `embedder_dim`
check and returns confident nonsense.

#### Byte length does not prove byte order

`length(blob) == embedder_dim * 2` is a necessary check and the app runs it on
activation, but it detects a truncated or wrong-dimension vector, not a
byte-swapped one. A big-endian vector is exactly as long as its little-endian
counterpart. Validating only length means the pinned endianness is a rule with no
enforcement behind it, and the failure it lets through — plausible-looking retrieval
that returns the wrong chunks — is the hardest kind to notice.

So `pack_meta` carries a **probe vector**: a fixed, known sequence of float16 values,
pinned in the schema spec, encoded by the builder through the same writer that
encodes every other vector in the pack. On activation the app decodes the probe with
its own reader and compares against the constant it holds internally. A mismatch
rejects the pack.

The probe works because it shares the encoding path with real vectors: it catches
byte order, but also a builder that wrote float32, wrote NaN-boxed values, or padded
its rows — a whole class of layout bugs that no per-vector inspection would find,
since real embeddings have no expected value to compare against.

---

## Citations and Page Labels

Every chunk needs a citation. For source chunks that means `source_id`,
`heading_path`, and a page range, required and not optional; for derived chunks it
means a non-empty `chunk_derivation` resolving to those same fields on the cited
chunks. An uncitable quote is worthless in a rules dispute.

Citations use **the label printed on the physical page**, not the PDF page index.
A single integer offset cannot express this. Real books have Roman-numeral front
matter, numbering restarts in compilations and boxed sets, and unnumbered inserted
plates.

So `sources` carries a **page-label map**: an ordered list of ranges, each mapping a
span of physical page indices to a labelling scheme (`decimal`, `roman-lower`,
`roman-upper`, `none`) with a starting value and optional prefix. Chunks store the
resolved `page_label_start` / `page_label_end` as **text**, not integers.

Pages under a `none` range produce a nearest-labelled-page citation with an explicit
marker rather than a fabricated number.

---

## Structured Tables

When `tables` rows drive the roller capability, flagging them as derived is not
sufficient protection. A parser or model can drop an outcome range, shift a boundary,
or pair an outcome with the wrong text while the verbatim `table` chunk it came from
remains perfectly correct. The user rolls a 47, gets the wrong result, and the quoted
table on screen looks right.

Structured rows must therefore be **deterministically validated against the source
before packaging**:

- Parse `dice_expr` and compute its full outcome range.
- Rows must cover that range **exactly**: no gaps, no overlaps, no out-of-range rows.
- Each row's outcome text must be a verified span of its parent `table` chunk —
  same span discipline as verbatim extraction.
- Round-trip: re-render the parsed rows into a canonical table and diff against the
  source chunk. Any difference beyond whitespace normalization fails the build.

A table that cannot pass validation is shipped as a verbatim chunk with **no**
structured rows. It stays quotable and simply is not rollable. Degrading a capability
is acceptable; rolling a wrong result is not.

This is an **enrichment validation**, and is a deliberate exemption from the
build-failure rule below — see *Two Classes of Validation*. Dropping the rows must be
recorded in the build report; silently omitting them is not permitted.

### `dice_expr` is a closed, versioned grammar

Coverage validation is only worth running if the builder's parser and the app's roller
agree on what the expression means. They are different codebases in different
languages, and dice notation is a folk grammar with no standard: `d%` is 1–100 in most
games and 0–99 in some, `00` on percentile dice reads as 100 at one table and 0 at
another, and `2d6` versus `d6+d6` differ in distribution while sharing a range. Any of
those disagreements produces rows that validate cleanly on the desktop and roll
outside their validated range on the phone — the exact failure this section exists to
prevent, arriving by a different door.

So the grammar is closed and pinned in the schema spec alongside the DDL, not left to
each implementation:

- A small fixed set of forms — `NdS`, `dS`, `d%`, with an optional integer modifier.
  Nothing else parses. Exploding dice, drop-lowest, and rerolls are not expressible;
  a table needing them ships quotable and not rollable.
- `d%` is defined as exactly 1–100. Books printing `00` map to 100 at build time, and
  the mapping is recorded rather than assumed.
- The expression's outcome range and its distribution are both part of the definition,
  so `2d6` and `d6+d6` are distinguishable and only one of them is what the book meant.
- The grammar is versioned with `schema_version`. Adding a form is a version bump, so
  an older app never silently mis-parses a newer expression — it refuses the pack.
- Builder and app ship the **same** conformance vectors: a fixed list of expressions
  with their expected ranges and outcome distributions, run as a test on both sides.

Without shared vectors this is a specification two teams can both believe they
implement. With them, disagreement is a failing test rather than a wrong roll at
someone's table.

---

## Query Expansion (offline HyDE)

Players ask in table slang; books are written in rules jargon. Someone asks how to
"wrestle" a goblin and the rule is called "grapple". Lexical search misses entirely
and dense search is unreliable across that gap.

The fix runs at build time rather than query time. For each verbatim-class chunk the
builder generates:

- **Question paraphrases** — the ways a player would actually ask for this rule,
  including colloquialisms, common misnamings, and table shorthand. Target ~5 per
  chunk, stored in `vectors` with `role='expansion'`.
- **Lexical aliases** — synonym terms recorded in `entities`, used to rewrite the
  query before it reaches FTS (see below).

This is HyDE inverted: instead of generating a hypothetical document per query on a
phone, generate hypothetical queries per document on a desktop. Retrieval stays pure
lookup, expansions are inspectable and testable, and nothing is regenerated at
runtime.

Budget roughly 5x vector rows for verbatim-class chunks — about 19 MB on a 300-page
book at 384 dims.

Expansions are retrieval surface only. They are never rendered.

### How aliases actually reach BM25

`chunks_fts` indexes only `text` and `heading_path`, so an alias sitting in `entities`
has no effect on lexical search by itself. Someone typing "wrestle" still misses the
grapple rule, which is the entire case this feature exists to handle. The join has to
be specified, not assumed.

`entities` therefore carries:

| column | meaning |
|---|---|
| `canonical` | the term as the book writes it (`grapple`) |
| `alias` | one surface form per row (`wrestle`, `wrestling`, `pin`) |
| `kind` | `rules-term`, `proper-noun`, `place`, `creature`, … |
| `chunk_id` | the chunk this term is defined or governed by; may be NULL |

Query-time behavior, which the app must implement and the builder must assume:

1. Tokenize the query and match token n-grams (longest first) against `alias`.
2. For each match, OR the `canonical` term into the FTS query alongside the original
   token. The user's own wording is never dropped — expansion only widens.
3. Where a matched alias has a non-NULL `chunk_id`, apply that chunk the same rank
   boost an exact entity hit receives.

#### Every term is escaped before it reaches `MATCH`

Step 2 builds an FTS5 expression out of two untrusted-by-construction strings: what
the user typed, and what the pack stored. Neither can be interpolated raw.

FTS5 reads `AND`, `OR`, `NOT`, `NEAR`, `*`, `^`, `:`, parentheses, and double quotes
as syntax. Game vocabulary collides with all of it — `D&D`, `Ars Magica: The Divine`,
a hyphenated `fast-cast`, an entity legitimately named `Or`. The result is either a
`MATCH` syntax error that fails the whole query, or, worse, a silently restructured
boolean: a canonical term containing `NOT` turns a widening expansion into an
exclusion, and the query returns fewer results than it would have without the
feature meant to broaden it.

Required, on both the user's tokens and every canonical term pulled from `entities`:

- Wrap each term as an FTS5 **string literal** — enclosed in double quotes, with any
  internal double quote doubled. Quoting neutralizes operators and punctuation
  together, so no keyword blocklist is needed and none should be written.
- Compose the expression only from already-quoted literals joined by operators the
  app itself emits. Stored text never contributes an operator.
- A term that is empty after normalization is dropped, not emitted as `""`.

This is ordinary injection discipline, and it applies to pack content for the same
reason it applies to user input: the app is building a query language expression out
of strings it did not author.

Aliases are therefore a **query rewrite plus a rank signal**, not an index-time
injection. Writing aliases into `chunks_fts` was the alternative; it was rejected
because it pollutes BM25 term statistics with text that does not appear in the book,
and makes alias changes require an FTS rebuild.

Proper-noun aliases serve double duty here: the same table that turns "wrestle" into
`grapple` for retrieval also supplies the entity list that normalizes spoken input.

---

## Hard Constraints

1. **Embedder contract.** `pack_meta.embedder_id` and `embedder_dim` must exactly
   match what the app has on-device, and the BLOB layout above is part of that
   contract. Mismatched vectors are silently meaningless — retrieval still returns
   results, just wrong ones. The app hard-fails activation on mismatch.

2. **Atomic units are never split for display.** A statblock, a table, or a complete
   rule lives in exactly one display chunk.

3. **Verbatim text is sliced from a hashed source by validated span.** Never
   transcribed, never matched-after-the-fact.

4. **Vectors are little-endian float16.** Byte length and the `pack_meta` probe
   vector are both validated on activation; length alone cannot detect byte order.

5. **Packs are read-only at runtime.** The app stores activation state, entitlement,
   and user data in its own database.

6. **Failing loudly beats degrading silently** — see the two validation classes
   below for which failures stop a build and which drop a feature.

---

## Two Classes of Validation

Not every check can carry the same penalty. Refusing to build a 300-page book because
one random table has a malformed dice range is not caution, it is a builder nobody
can use. But the distinction has to be stated, or two builders will handle the same
invalid table differently.

**Correctness validations — fail the build.** Violating one means the pack can render
something false as authoritative:

- span boundary / containment / sibling-overlap violations
- a span offset that is not on a UTF-8 sequence boundary
- a nested child whose `source_id` differs from its parent's
- verbatim text not matching its span slice
- `sources.text_sha256` mismatch
- vector byte length ≠ `embedder_dim * 2`, or a missing embedder declaration
- a probe vector that does not decode to its pinned constant
- a `source` chunk missing `source_id`, `heading_path`, or page labels
- a `derived` chunk with no `chunk_derivation` rows, or one citing a chunk that is
  itself derived
- an atomic unit exceeding the configured maximum size
- a content-vector tiling that leaves part of a verbatim-class chunk uncovered

There is deliberately no check for "a verbatim-class chunk with `origin = 'derived'`".
Verbatim-class is *defined* as `origin='source'` plus an eligible kind, so the
condition is unsatisfiable and the check would never fire. Worse, an implementer
reading it as "verbatim-eligible kind with derived origin" would reject
`('glossary', 'derived')` and every other legitimate derived chunk — turning a dead
check into a live bug.

**Enrichment validations — drop the feature, record it, continue.** Violating one
means a capability is unavailable, never that output is wrong:

- structured table rows failing coverage, span, or round-trip checks → ship the table
  quotable but not rollable
- a generated expansion that fails its quality check → ship fewer expansions
- an unresolvable alias → drop that alias
- a capability manifest failing schema validation → ship the pack without it

### The build report ships *inside* the pack

Every enrichment failure is written to the `build_report` table, in the pack file
itself. Not a sidecar, not a log on the builder's disk.

The output contract is one internally complete `.rpgpack`, and every path the file
travels — a download, a copy to a phone, an import — moves exactly that one file. A
report shipped alongside it is a report that does not arrive, and the promise that a
user can tell a missing capability from a working one quietly becomes untrue for
every pack that was not built on the machine reading it.

Each row records what was dropped, which chunk or table it belonged to, and which
validation rejected it. The app's Packs screen reads this directly, which is what
makes "this table is quotable but not rollable" answerable on the device rather than
something the user infers from a capability that never appears.

The rule is that a *missing* capability is acceptable and a *wrong* answer is not —
but the user is never left guessing which they got.

---

## Supersession (Errata Packs)

An errata pack has to be able to *replace* text, not merely outrank it. Pack priority
is a tie-break, and two chunks competing for the same query almost never tie: the
original rule and its correction score differently, so the superseded text can win on
score alone while the higher-priority pack sits there having no effect. A precedence
mechanism that only fires on exact ties does not deliver "our errata wins".

So supersession is a **filter applied before scoring**, declared by the pack doing the
superseding:

- `supersessions` rows name a target by `(source_uid, stable_key)` — the identity of
  the book being amended, plus a stable identifier for the passage within it. Exact
  form of `stable_key` is pinned in the schema spec; it must survive a rebuild of the
  target pack, which rules out `chunk_id`.
- When both the superseding pack and its target are active, the targeted chunks are
  **removed from the candidate set entirely**, before either index is scored. They
  cannot be retrieved, quoted, or fed to generation.
- When the target is not installed, the rows are inert. An errata pack is valid on its
  own and simply has nothing to amend.
- A superseding chunk cites what it replaced, so the app can show "this replaces
  [book, page]" rather than silently differing from the printed book the user owns.

Priority keeps its narrower job: ordering genuine score ties, and deciding which pack
answers first when two unrelated books both cover a topic. It is a preference.
Supersession is a fact about the content, and the two should not be conflated —
which is what the previous framing did.

---

## Derived Content

The builder may generate content that does not exist in the source — summaries,
aliases, capability manifests, structured table rows. All such content:

- carries `origin = 'derived'`
- is never rendered as a quotation
- cites the chunks it was derived from, through `chunk_derivation`

---

## Open Questions

- Signing and verification scheme for downloadable packs
- Handling of images (maps, diagrams) — the on-device model accepts image input, so
  storing page images is plausible but unpriced
- Incremental rebuild when a source is re-tagged, without invalidating installed packs
- The exact form of `stable_key`, which decides whether supersession survives a
  rebuild of the pack being amended
- Whether the normalized source text ships in the pack (enables span re-validation
  on-device, roughly doubles text size) or stays builder-side with only the hash
