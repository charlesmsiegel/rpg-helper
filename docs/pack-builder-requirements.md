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
- **Sibling** spans — chunks with the same `parent_chunk_id` — must not overlap, and
  must not leave unassigned text between them without an explicit gap marker.

Chunks with `origin = 'derived'` have no spans. `span_start` / `span_end` are NULL,
and the chunk instead carries `derived_from`: the list of source `chunk_id`s it was
produced from, which must be non-empty. Requiring spans of derived text would force
the builder to either fabricate misleading offsets or violate the slicing rule.

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

Retrieval deduplicates: when a parent and its child both match a query, only the
parent is returned, so the user always sees the complete rule. A capability may
target a child directly by kind — this is how the roller reaches a table embedded in
a rule without the app ever quoting the table stripped of its surrounding rule.

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

Exact DDL is pinned by the app's schema spec. The builder targets a
`schema_version` and the app refuses packs it does not recognize.

### `chunks` columns introduced by this document

Collected here because they are established piecemeal above:

| column | notes |
|---|---|
| `kind` | see taxonomy |
| `origin` | `source` \| `derived` |
| `text` | sliced for `source`, generated for `derived` |
| `source_id`, `heading_path` | citation, required |
| `page_label_start`, `page_label_end` | **text**, printed labels, required |
| `span_start`, `span_end` | required iff `origin='source'`, else NULL |
| `parent_chunk_id` | NULL unless nested; at most one level |
| `derived_from` | required non-empty iff `origin='derived'`, else NULL |

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
check and returns confident nonsense. The app validates
`length(blob) == embedder_dim * 2` on activation and rejects mismatches.

---

## Citations and Page Labels

Every chunk needs a citation: `source_id`, `heading_path`, and page range are
required, not optional. An uncitable quote is worthless in a rules dispute.

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

4. **Vectors are little-endian float16.** Validated by byte length on activation.

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
- verbatim text not matching its span slice
- `sources.text_sha256` mismatch
- vector byte length ≠ `embedder_dim * 2`, or a missing embedder declaration
- a verbatim-class chunk with `origin = 'derived'`
- a derived chunk with an empty `derived_from`
- an atomic unit exceeding the configured maximum size
- a content-vector tiling that leaves part of a verbatim-class chunk uncovered

**Enrichment validations — drop the feature, record it, continue.** Violating one
means a capability is unavailable, never that output is wrong:

- structured table rows failing coverage, span, or round-trip checks → ship the table
  quotable but not rollable
- a generated expansion that fails its quality check → ship fewer expansions
- an unresolvable alias → drop that alias
- a capability manifest failing schema validation → ship the pack without it

Every enrichment failure is written to a build report that ships alongside the pack.
The rule is that a *missing* capability is acceptable and a *wrong* answer is not —
but the user is never left guessing which they got.

---

## Derived Content

The builder may generate content that does not exist in the source — summaries,
aliases, capability manifests, structured table rows. All such content:

- carries `origin = 'derived'`
- is never rendered as a quotation
- cites the chunks it was derived from

---

## Open Questions

- Signing and verification scheme for downloadable packs
- Handling of images (maps, diagrams) — the on-device model accepts image input, so
  storing page images is plausible but unpriced
- Incremental rebuild when a source is re-tagged, without invalidating installed packs
- How errata packs declare precedence over the book they amend
- Whether the normalized source text ships in the pack (enables span re-validation
  on-device, roughly doubles text size) or stays builder-side with only the hash
