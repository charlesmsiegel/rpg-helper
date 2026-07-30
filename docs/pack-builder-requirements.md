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

Verbatim-class kinds are `rules`, `table`, `statblock`, `readaloud`. Their `text`
column must be byte-exact source text. The app copies these strings directly to the
screen as authoritative quotes; it has no way to detect tampering.

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

Requirements:

- Each source document is normalized once and hashed; `sources.text_sha256` pins it.
- Every chunk stores `span_start` / `span_end` into that normalized text.
- `text` is produced by slicing, never by a model writing it out.
- Boundary validation: spans must begin and end at structural boundaries recovered
  from the document (heading, paragraph, list item, table, or statblock edges).
  A span ending mid-sentence, or starting mid-list, is rejected.
- Sibling spans under one heading must not overlap, and must not leave unassigned
  text between them without an explicit gap marker.

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

---

## Rendering Is Decided by `kind` + `origin`

`kind` alone is not sufficient. `origin` is a separate required column with two
values:

- `source` — sliced from the document, byte-exact
- `derived` — produced by the builder (summaries, definitions, expansions)

**A chunk renders verbatim if and only if `origin = 'source'` AND `kind` is in the
verbatim set.** Everything else renders as generated text, attributed but never
quoted.

This resolves the `glossary` ambiguity: a glossary entry lifted from the book's own
glossary is `('glossary', 'source')` and quotes; a builder-written definition is
`('glossary', 'derived')` and does not. The app never has to guess, and a derived
definition cannot be laundered into an authoritative quotation by its `kind`.

### Chunk `kind` taxonomy

| kind | Verbatim-eligible | Notes |
|---|---|---|
| `rules` | yes | mechanics, procedures, resolution |
| `table` | yes | also gets validated rows in `tables` |
| `statblock` | yes | indivisible; creature/NPC/item stats |
| `readaloud` | yes | boxed text intended to be read at the table |
| `setting` | no | lore, history, geography, culture, factions |
| `glossary` | yes, if `origin='source'` | term definitions; feeds entity resolution |

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
- **Lexical aliases** — synonym terms written into `entities` as aliases, so BM25
  query expansion catches the same mismatch on the keyword side.

This is HyDE inverted: instead of generating a hypothetical document per query on a
phone, generate hypothetical queries per document on a desktop. Retrieval stays pure
lookup, expansions are inspectable and testable, and nothing is regenerated at
runtime.

Budget roughly 5x vector rows for verbatim-class chunks — about 19 MB on a 300-page
book at 384 dims.

Expansions are retrieval surface only. They are never rendered.

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

6. **Failing loudly beats degrading silently.** Every validation in this document
   fails the build rather than shipping a questionable pack.

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
