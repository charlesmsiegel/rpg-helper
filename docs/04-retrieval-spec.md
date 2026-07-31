# Retrieval — Specification

From a typed, spoken, or photographed question to a ranked, route-tagged candidate list.

`android-app-design.md` §3 and §4 establish the shape of this pipeline and argue for its
non-obvious parts. This document pins it: the exact stage order, the query grammars, the
fusion arithmetic, the gating mechanism, and the evidence tests that decide nesting
deduplication.

What leaves this pipeline is a ranked list of `(pack_uid, chunk_id)` with enough
attached evidence for `05-routing-and-cards-spec.md` to decide how each one renders.
Retrieval never decides whether something is quoted.

Implementation order: **4 of 8** — see the README for the full sequence and why it runs in this order.

Status: specified, unimplemented.

---

## 1. Identity

**Every comparison, grouping, and join uses the composite `(pack_uid, chunk_id)`.**

`chunk_id` is pack-local. Nothing in the pack format makes it globally unique, and
everything inside a pack — `parent_chunk_id`, `chunk_derivation`, `tables`,
`constraints`, `entities` — treats it as local by construction. Two packs built by
different people both have a chunk 1.

Using the bare id merges unrelated chunks from different books: one inherits the other's
score and the other vanishes. The symptom is a citation pointing at the wrong book, which
reads as a retrieval-quality problem rather than an identity bug and would be debugged
for a long time.

The one identity that deliberately crosses packs is supersession, and it uses
`(source_uid, stable_key)` precisely because a pack-local id cannot survive the trip.

---

## 2. Pipeline

```
  query (text | voice | camera)
    │
 1. normalize ─────────────── deterministic always; model only when the input demands it
 2. alias rewrite ─────────── entities → canonical terms, FTS5 literals
 3. apply supersession ────── candidate-level filter, precomputed per active set
 4. embed ─────────────────── once per distinct embedder contract
 5. search ────────────────── BM25 per pack; cosine per contract group
 6. gate ──────────────────── per pack, per contract group — before any fusion
 7. collapse dense → chunks ─ best vector per chunk; expansion vectors discarded
 8. fuse ──────────────────── reciprocal rank fusion over lexical, dense, entity
 9. deduplicate nesting ───── class-aware and match-aware, on evidence
    │
    ranked candidates  →  routing
```

Stages 6 and 7 sit between search and fusion for reasons §5 and §7 give. Neither is
optional and neither commutes with fusion.

---

## 3. Normalization

### 3.1 Always, deterministically, no model

Applied to every query regardless of input mode:

1. Unicode NFC.
2. Case folding to lowercase.
3. Whitespace collapsing; leading and trailing trim.
4. Punctuation folding: curly quotes and dashes to their ASCII equivalents, so a query
   typed on a phone keyboard matches text set in a book.

This is table lookup and string work. It costs nothing and it is what makes a typed
one-word lookup reach the index having invoked no model at all — which is the guarantee
§3 of the design actually claimed.

### 3.2 Only when the input demands it

The generative model runs on the query in exactly three cases:

| case | why it is unavoidable |
|---|---|
| voice input | a transcript carries disfluency and false starts |
| camera input | an image is not a query until something writes one |
| a follow-up containing an unresolved reference | *"what about at level 5?"* means nothing alone |

The third is detected by a **cheap deterministic check before the model is considered**:
the query begins with a pronoun or a demonstrative, or is elliptical (opens with *what
about*, *and*, *how about*), **and** the conversation window is non-empty. Nothing else
triggers it. A self-contained question never wakes the model, however it is phrased.

When it runs, the model sees at most the last three turns (`01-app-state-spec.md` §3) and
rewrites the query. It never substitutes for retrieval, which runs fresh every turn.

**Voice must not require the generative model.** Bundling ASR to keep voice available
before the download would be pointless if the transcript then had to pass through a model
that is not there. Without the generative model a transcript goes to retrieval after
deterministic folding and the alias rewrite — which handles the failure mode that
actually matters, a mangled proper noun. What is lost is disfluency cleanup and pronoun
resolution, not the feature.

---

## 4. The lexical query

### 4.1 Alias rewrite

`chunks_fts` indexes only `text` and `heading_path`, so an alias sitting in `entities`
has no effect on lexical search by itself. The join is a **query rewrite plus a ranking
signal**, never an index-time injection — writing aliases into the index would pollute
BM25 term statistics with text that does not appear in the book and would make an alias
change require an FTS rebuild.

0. **Aliases are matched in normal form.** `entities.alias` is stored lowercased, NFC,
   and diacritic-folded — the same form §4.3's tokenization produces — and activation
   rejects a pack storing an alias in any other form. Otherwise a pack shipping
   `Fireball` or `Váss` has aliases that can never match anything, silently, which is the
   same failure tracker keys solve by normalizing at entry. Matching does not normalize on
   the fly: an indexed lookup against `idx_entities_alias` is the point of the index.
1. Tokenize the normalized query the same way the pinned tokenizer does (§4.3).
2. For n from 5 down to 1, match every n-gram against `entities.alias` across all active
   packs. Matching is **greedy, longest-first, and non-overlapping**: once an n-gram
   matches, its tokens are not available to a shorter match. Without this, *"blade of the
   fallen"* fires as itself, as *"the fallen"*, and as *"fallen"*, weighting one concept
   three times.
3. For each match, OR the `canonical` term into the query.
4. Where the matched alias row has a non-NULL `chunk_id`, record `(pack_uid, chunk_id)`
   as an **entity hit** for stage 8.

**The user's own tokens are never dropped.** Expansion only widens. An alias that fires
wrongly costs some precision; a rewrite that replaces the user's wording costs the query.

### 4.2 Escaping, without exception

The expression is built from two untrusted-by-construction sources: what the user typed,
and what the pack stored. Neither is interpolated raw.

FTS5 reads `AND`, `OR`, `NOT`, `NEAR`, `*`, `^`, `:`, parentheses, and double quotes as
syntax, and game vocabulary collides with all of it — `D&D`, `Ars Magica: The Divine`, a
hyphenated `fast-cast`, an entity legitimately named `Or`. The result is either a `MATCH`
syntax error that fails the whole query or, worse, a silently restructured boolean: a
canonical term containing `NOT` turns a widening expansion into an exclusion, and the
query returns *fewer* results than it would have without the feature meant to broaden it.

Required, on the user's tokens and on every canonical term alike:

- Wrap each term as an FTS5 **string literal** — enclosed in double quotes, with any
  internal double quote doubled. Quoting neutralizes operators and punctuation together,
  so no keyword blocklist is needed and none should be written.
- Compose only from already-quoted literals joined by operators **the app itself emits**.
  Stored text never contributes an operator.
- A term empty after normalization is dropped, never emitted as `""`.

### 4.3 Shape and bounds

The expression is a **disjunction** of quoted literals:

```
"grapple" OR "wrestle" OR "creature" OR "restrained"
```

Conjunction is wrong here: a natural-language question carries a dozen words, most of
them incidental, and requiring all of them to co-occur matches nothing. BM25 already
weights rare terms above common ones, which is the discrimination a conjunction would be
trying to impose bluntly.

Terms are capped at **32** per query, in order of appearance. A question longer than that
is not more precise, and an unbounded disjunction is an unbounded scan.

Tokenization must match the pack's pinned tokenizer — `unicode61 remove_diacritics 2` —
so app-side tokenizing splits on non-alphanumeric characters, folds case, and strips
diacritics. Any divergence shows up as a query term that cannot match text the index
holds.

---

## 5. Supersession is a filter, not a preference

Applied **before scoring**, at the candidate level.

### 5.1 The superseded set

Computed once per change to the active set — not per query — and held with the active-set
fingerprint from `01-app-state-spec.md` §4:

1. Collect every `supersessions` row from every **active** pack.
2. Keep a row only when its `target_source_uid` matches a `sources.source_uid` in some
   **active** pack. An errata pack whose target is not installed is inert.
3. The superseded set is every chunk whose `(source_uid, stable_key)` matches a kept row.

### 5.2 The cascade

Deactivating a chunk deactivates everything rooted at it, because capabilities address
chunks directly and never pass through this pipeline:

- its `tables` rows — otherwise an erratum's corrected table is unquotable while the
  *original* stays rollable, and the app quietly rolls on obsolete rows
- its `capabilities` manifests
- its `entities` rows
- its `constraints` rows — otherwise a superseded rule keeps flagging characters under
  text the user can no longer look up, and a validation error citing an unfindable
  passage is worse than no validation
- every **derived chunk citing it** through `chunk_derivation` — deactivated when *any*
  cited chunk is superseded, not only when all are. A summary of three rules, one of
  which was corrected, is wrong in exactly the place someone would rely on it.

A correction removes what it corrects, everywhere.

### 5.3 What the filter cannot reach

Packs are read-only and `chunks_fts` is built into them, so filtering superseded rows out
of the returned candidates does not remove them from the index's corpus statistics.
Document count, average length, and term frequencies still include them, so `bm25()`
scores for *surviving* chunks shift slightly.

The guarantee that matters holds exactly — a superseded chunk never reaches the user
through any route, which is a candidate-level filter and is absolute. The scoring
perturbation is real, bounded, and errata-sized: tens of chunks against a corpus of
thousands, far below the noise the gates are calibrated against.

It becomes wrong at scale. A pack superseding a large fraction of its target is a
replacement edition, not errata, and should be modelled as one.

### 5.3.1 Supersession is unbounded, so it is made visible

Any active pack may supersede any chunk of any other, cascading through tables,
constraints, capabilities, and derived chunks. Nothing in the format restricts what a pack
may claim to correct, and until packs are signed nothing can.

What the app can do is refuse to let it happen quietly:

- **At install**, a pack carrying `supersessions` rows reports how many passages it amends
  and in which books, before the user activates it.
- **A supersession set covering more than a small fraction of its target requires explicit
  acknowledgement** — at that size it is a replacement edition, and the honest action is to
  deactivate the old pack rather than have a second one hollow it out.
- **The Packs surface lists what is in effect**, from each row's citation snapshot, so the
  state is inspectable rather than inferred from missing search results.

### 5.4 Priority is a different mechanism

Priority breaks genuine score ties and decides which book leads when two unrelated
sources both cover a topic. It cannot deliver *"our errata wins"*, because a corrected
rule and its original almost never tie — they score differently, and the superseded text
can win outright while priority is never consulted.

---

## 6. Dense search

### 6.1 One embedding per contract, not one per query

Active packs may name different `embedder_id`s, and they will in practice: a pack built
two years ago and one built today are both valid, and packs are files people own, not
subscriptions. One query embedding cannot serve both — the vectors live in different
spaces, and for differing `embedder_dim` the cosine is not even computable.

So the app groups active packs by embedder contract, embeds the query **once per distinct
contract**, and scores each group in its own space. The cost is one embedder inference
per distinct contract per query, which is why the supported set is deliberately small.

### 6.2 Scoring

Brute-force cosine over every vector in the group's packs. No approximate index: the pack
format carries none, adding one would mean the app building and storing a second index
per pack, and at this corpus size — order 10⁵ vectors across a large library — an exact
scan is affordable and loses no recall.

Pack vectors are not required to be unit-length, only to have norm above `1e-6`, so
cosine divides by both norms; the pack's norm is accumulated in the same pass that reads
it. Decoding is float16 → float32 with accumulation in float32.

Retrieval latency across increasing active-pack counts is a tracked performance gate
(`02-test-infrastructure-spec.md`), because this stage is where it degrades first.

### 6.3 Expansion vectors compete, then vanish

`role='expansion'` rows — embeddings of generated question paraphrases — are scored on
equal footing with `content` rows. They are retrieval surface and are **never rendered**;
after stage 7 collapses to chunks, nothing downstream knows a chunk was found through one.

---

## 7. Gating, and why it comes before fusion

### 7.1 The problem gating solves

Reciprocal rank fusion needs no score calibration between BM25 and cosine, which is what
makes multiple embedder contracts combinable at all. But **rank alone is not sufficient,
and treating it as sufficient introduces a new bug**: every list has a rank 1, including a
list with nothing relevant in it. Fusing purely by rank hands that list's
best-of-a-bad-lot the same contribution as a genuinely strong hit from the list that
actually contains the answer.

Activating more books would make results *worse*, which is precisely backwards.

### 7.2 Dense gating, per contract

Hits must clear a similarity threshold **calibrated for that contract**; a group with
nothing above its threshold contributes no list at all.

Thresholds are per-embedder because they are not transferable — a cosine of 0.6 means
different things in different spaces, which is the same reason the scores could not be
pooled to begin with. Each bundled embedder ships with its threshold as a constant,
derived from the labelled query sets.

### 7.3 Lexical gating, per pack

Each pack is a separate SQLite file with its own `chunks_fts`, so BM25 scores come from
**pack-local corpus statistics**. A term rare in a slim adventure and common in a core
rulebook scores differently in each, and the numbers were never on a shared scale.

Each pack's lexical results are gated by a relevance threshold before fusion. The gate is
on **score, not rank**, so a large pack cannot buy position with volume.

SQLite's `bm25()` returns a negative number where more negative is a better match; the
app negates it so higher is better, and every threshold in this document is stated on the
negated value.

> **This is the least settled part of retrieval, and it is stated as such rather than
> given a false precision.** A single global threshold on the negated BM25 score is the
> specified mechanism, calibrated on the SRD corpus. Its weakness is known: BM25's IDF
> term varies with corpus size, so one constant is only approximately comparable across a
> slim pamphlet and a 300-page core book.
>
> If measurement against the labelled sets shows size sensitivity, the fallback is
> normalizing each score by the summed IDF of the query's matched terms in that pack —
> turning the gate into "how much of the query's information content did this match",
> which is comparable by construction. It is not the default because it costs one
> document-frequency query per term per pack, and paying that on every query to fix a
> problem that may not appear is the wrong order to do things in.

### 7.4 The refusal condition is the gates — there is no separate relevance floor

`android-app-design.md` §10 lists the relevance floor as an open question, needing the
labelled query sets before it can be set honestly. Working the pipeline through dissolves
it.

**A floor on the fused score would be meaningless.** RRF scores are functions of rank, so
the top-ranked candidate scores approximately `1/(k+1)` whether it is the right answer or
the least-wrong of a list of junk. Thresholding it measures position, not relevance.

The gates already carry the information. They are the only place in the pipeline where an
absolute measure of match quality exists, and they are applied per pack and per contract
where the scales are actually comparable.

> **The app refuses when gating leaves no candidates.** Not when a fused score is low —
> when nothing cleared its own gate.

So the numbers that need calibrating are the per-contract and per-pack thresholds, which
this document already names, and not a third constant standing between them and the user.
This is one fewer tunable, and the one that would have been hardest to reason about.

---

## 8. Fusion

### 8.1 Collapse dense hits to chunks first

BM25 ranks chunks. Dense search ranks **vector rows**, and a long statblock may own a
dozen between content windows and question expansions.

Fusing first would either compare identifiers that never match, or let one chunk occupy a
dozen consecutive dense ranks and crowd the fused list with itself — worst for exactly
the long, heavily-vectored chunks that matter most. So each chunk collapses to its
best-scoring vector, keeping that vector's window offsets for §9.

### 8.2 Reciprocal rank fusion

Over three kinds of list:

- one **lexical** list per surviving pack
- one **dense** list per surviving contract group
- one **entity** list, holding chunks named by a matched alias with a non-NULL `chunk_id`
  — **restricted to chunks that already appear in a gated list**

```
score(c) = Σ  1 / (k + rank_L(c))
           L
```

with `k = 60`. Lists a chunk does not appear in contribute nothing.

**The entity list is how the alias boost is applied**, and treating it as a list rather
than as a multiplier is deliberate: an entity hit is another piece of evidence, so it
belongs in the same arithmetic as the other evidence. Its members all sit at rank 1,
since an entity match has no internal ordering — it is a flat contribution of `1/(k+1)`,
which is the same weight as topping one other list.

**An entity hit reinforces a candidate; it never introduces one.** The entity list passes
through no gate — an alias n-gram either matched or it did not, and there is no score to
threshold. If it could contribute candidates of its own, a query where every pack and
every contract gated out could still produce an answer whose entire evidence is a
substring match, and §7.4's refusal rule would be false as written.

So the list is intersected with the union of the gated lexical and dense lists before
fusion. A chunk nothing else found is not promoted by an alias alone, and **"gating left
no candidates" and "the app refuses" stay the same statement.**

Priority breaks exact ties: among candidates with equal fused scores, the one from the
higher-priority pack leads.

Treat the fusion method as tunable. Treat the requirement that both retrieval signals
contribute as not.

---

## 9. Nesting deduplication

Nesting is legal and one level deep: a complete rule may contain a rollable table, and
the two spans necessarily overlap. When both match, which survives depends on the
**parent's class**, not on the nesting alone.

| case | survivor | why |
|---|---|---|
| Verbatim-class parent, matching child | **parent** | the user sees the complete rule, not a table torn out of it |
| Non-verbatim parent matching **only** through its child's text | **child** | otherwise an authoritative, quotable table is delivered as generated prose |
| Non-verbatim parent that **also matches independently** | **both** | the lore is explained and the table is quoted, from one query |

The third case exists because the unconditional version threw away the setting evidence.
A query hitting both the lore of a region and a rumour table inside it would drop the
parent, leaving routing with nothing to generate from and the user with a bare table where
they asked about a place.

### 9.1 Deciding "matched independently" needs evidence, not inference

Both signals supply it, and a parent is independent if **either** test passes:

Before either test runs: **a parent whose redacted text is empty or whitespace-only is
never independent.** A `setting` chunk entirely covered by its children has nothing of its
own to contribute, and sending it to generation would contribute an empty context.

**Dense.** The parent's best-scoring `content` vector carries `window_start` /
`window_end`. Overlap alone is not evidence: a window containing the whole nested table
plus one adjacent byte overlaps text outside the child while having scored entirely on the
child's content, and routing would then keep a parent whose relevant material redaction is
about to remove.

So the window must carry **at least 64 bytes outside the child's span, and at least a
quarter of its own length** — enough that the window is plausibly scoring on the parent's
own prose rather than on a boundary sliver. An `expansion` hit counts as independent by
construction, because the builder generates a parent's expansions from its *redacted*
text.

**Lexical.** Re-run the query terms against the parent's redacted text — the parent's
`text` with every nested verbatim child's span excised, tokenized by §4.3's rules.

The criterion is not "any term matches", which almost any English prose satisfies through
a word like *check* or *creature*, nor "all terms match", which nothing satisfies. It is:
**every query term that matched inside the child's span must also match outside it.** That
asks the question the rule is actually about — did the parent match *because of* the child
— rather than a proxy for it. A term the child never contained is not evidence either way
and does not participate.

Both thresholds are tunable and are measured by the dedup rows of the regression suite.
The 64-byte and quarter-length figures are starting points, not findings.

Without these, "independent match" would be a rule an implementer could only guess at,
and the guess would fall one way or the other: discard lore that was genuinely relevant,
or keep a parent whose entire contribution gets redacted before generation and which
therefore contributes an empty context.

---

## 10. What crosses the boundary

Each surviving candidate carries:

| field | used by routing for |
|---|---|
| `pack_uid`, `chunk_id` | identity, citation resolution |
| `kind`, `origin` | the route partition — mechanically, never by judgment |
| fused score, per-list ranks | ordering, and explaining ordering in diagnostics |
| best dense window | the independence evidence, retained for redaction |
| surviving nested children | which spans routing must redact before generation |
| entity-hit flag | ordering only |

Retrieval hands over **at most 10** candidates. Routing consumes the top 5 for card
construction; the remainder exist so an *expand* control can show what else matched
without re-querying.

Per-list depth is 50 per pack per index before gating. These are tunable and are the
numbers the retrieval regression suite measures recall against.

---

## 11. Testing

| Area | What it asserts |
|---|---|
| Escaping | every FTS5 metacharacter and a term named `OR`, `NOT`, `NEAR` survive as literals; a pack-supplied canonical term can never restructure the boolean |
| Alias matching | greedy longest-first non-overlapping; the user's tokens are never dropped |
| Tokenizer parity | app-side tokenization matches `unicode61 remove_diacritics 2` over a corpus, including diacritics and punctuation |
| Supersession | superseded chunks are unreachable by every route — search, quote, roll, cite, generate; the cascade reaches tables, entities, capabilities, constraints, and derived chunks |
| Inert errata | an errata pack whose target is absent changes nothing |
| Contract grouping | two packs with different embedders both contribute; one embedding per contract per query |
| Gating | a pack or group with nothing above threshold contributes nothing; adding an irrelevant pack never changes the top results |
| Refusal | with everything gated out, the refusal card fires and no generated card is produced |
| Collapse-before-fuse | a chunk with a dozen vectors occupies one fused position |
| Dedup | all three cases, with both independence tests exercised in both directions, including a window that clears the child by one byte and a parent whose redaction leaves nothing |
| Alias normal form | a pack storing an alias with capitals or diacritics is rejected at activation |
| Entity reinforcement | an alias hit never introduces a candidate no gated list contained; with everything gated out the refusal still fires |
| Composite identity | two packs each holding a chunk 1 never merge; citations resolve to the right book |
| Regression | labelled query sets per test pack, recall@k, gated in CI |
| Performance | retrieval latency across increasing active-pack counts, tracked as a gate |

The "adding an irrelevant pack never changes the top results" row is the one that most
directly tests the design's claim, and it is the one that would fail if gating were ever
quietly removed as an optimization.

---

## 12. Pending measurement

Not open questions — mechanisms are specified. These are constants that need the labelled
query sets to exist before they can be set honestly:

- the per-contract dense similarity thresholds, one per bundled embedder
- the dense independence minimums in §9.1 (64 bytes, one quarter of the window)
- the global lexical gate threshold, and whether IDF normalization (§7.3) is needed
- per-list depth and final candidate count
- the RRF `k`, if 60 proves wrong for lists this short

Shipping with guessed constants and no measurement would be the failure the regression
suite exists to prevent: retrieval quality has no symptom until someone notices the app
has been quietly failing to find a rule for three releases.
