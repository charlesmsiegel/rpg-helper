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
fatal. The builder must treat extraction and generation as separate passes with
separate outputs, and must verify verbatim text against source by exact string match
before writing it to the pack.

`setting` chunks are also stored as source text (generation happens on-device from
them), but drift there degrades quality rather than breaking a guarantee.

---

## Output Format

A single SQLite file, extension `.rpgpack`. Not a zip, not a directory.
Must be internally complete — no external asset references.

### Required tables

| Table | Purpose |
|---|---|
| `pack_meta` | identity, versioning, embedder contract, license, signature |
| `sources` | per-book identity, edition, page-number offset |
| `chunks` | the content; `kind` drives verbatim-vs-generated |
| `chunks_fts` | FTS5 virtual table over `text` + `heading_path` |
| `vectors` | `chunk_id` → float16 embedding BLOB |
| `entities` | proper nouns + aliases, for voice normalization and linking |
| `tables` | structured random tables: dice expression + rows JSON |
| `capabilities` | declarative capability manifests shipped by this pack |

Exact DDL is pinned by the app's schema spec. The builder targets a
`schema_version` and the app refuses packs it does not recognize.

---

## Hard Constraints

1. **Embedder contract.** `pack_meta.embedder_id` and `embedder_dim` must exactly
   match what the app has on-device. Mismatched vectors are silently meaningless —
   retrieval still returns results, just wrong ones. The app hard-fails activation on
   mismatch; the builder must record the true embedder used, never a default.

2. **Atomic units are never split.** A statblock, a table, or a complete rule must
   live in exactly one chunk. A chunk boundary through the middle of a grapple rule
   yields a quote that is faithful and useless.

3. **Every chunk needs a citation.** `source_id`, `heading_path`, and page range are
   required, not optional. An uncitable quote is worthless in a rules dispute.

4. **Page numbers are book pages, not PDF pages.** `sources.page_offset` captures the
   difference. Users cross-check against physical books.

5. **Vectors are float16.** Halves pack size at no measurable retrieval cost.

6. **Packs are read-only at runtime.** The app stores activation state, entitlement,
   and user data in its own database. The builder must not expect writes.

---

## Chunk `kind` Taxonomy

| kind | Rendering | Notes |
|---|---|---|
| `rules` | verbatim | mechanics, procedures, resolution |
| `table` | verbatim | also gets a structured row in `tables` |
| `statblock` | verbatim | indivisible; creature/NPC/item stats |
| `readaloud` | verbatim | boxed text intended to be read at the table |
| `setting` | generated | lore, history, geography, culture, factions |
| `glossary` | either | term definitions; feeds entity resolution |

Classification quality is the builder's core value. Misfiling a rule as `setting`
means it gets paraphrased — the exact failure the design prevents everywhere else.
When genuinely ambiguous, classify as `rules`: an unnecessary verbatim quote is a
minor annoyance, a paraphrased rule is a correctness bug.

---

## Query Expansion (offline HyDE)

Players ask in table slang; books are written in rules jargon. Someone asks how to
"wrestle" a goblin and the rule is called "grapple". Lexical search misses entirely
and dense search is unreliable across that gap.

The fix runs at build time rather than query time. For each verbatim-class chunk the
builder generates:

- **Question paraphrases** — the ways a player would actually ask for this rule,
  including colloquialisms, common misnamings, and table shorthand. Target ~5 per
  chunk. These are embedded and stored in `vectors` as additional rows pointing at
  the same `chunk_id`.
- **Lexical aliases** — synonym terms written into `entities` as aliases, so BM25
  query expansion catches the same mismatch on the keyword side.

This is HyDE inverted: instead of generating a hypothetical document per query on a
phone, generate hypothetical queries per document on a desktop. Retrieval stays pure
lookup, expansions are inspectable and testable, and nothing is regenerated at
runtime.

Budget roughly 5x vector rows for verbatim-class chunks. At float16/384-dim this is
about 19 MB on a 300-page book — acceptable.

Expansions are derived content and must obey the rules below: they are never
presented to the user as text, only used as retrieval surface.

---

## Derived Content

The builder may generate content that does not exist in the source — summaries,
aliases, capability manifests, structured table rows. All such content:

- must be flagged as derived (never presented as a quotation)
- must never carry a verbatim-class `kind`
- should cite the chunks it was derived from

---

## Open Questions

- Signing and verification scheme for downloadable packs
- Handling of images (maps, diagrams) — the on-device model accepts image input, so
  storing page images is plausible but unpriced
- Incremental rebuild when a source is re-tagged, without invalidating installed packs
- How errata packs declare precedence over the book they amend
