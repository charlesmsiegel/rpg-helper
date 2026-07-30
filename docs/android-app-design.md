# RPG Helper — Android App Design

The app. Runs entirely on a phone, entirely offline, against content packs built
elsewhere (see `pack-builder-requirements.md`).

This document specifies what the app does and why. It does not pin schemas — the
schema spec does that, and this document defers to it wherever DDL is involved.

Status: designed, unimplemented.

---

## 1. What This Is

An offline reference and play assistant for tabletop roleplaying games. Three things:

- **Answer rules questions** from the books in your active packs, quoting the book
  exactly.
- **Answer setting questions** in generated prose, always cited back to the pages it
  came from.
- **Track characters** — sheets, resources, conditions — with the game's own
  constraints enforced.

Everything happens on the device. No account, no server, no network at play time.

Explicit non-goals: this is not a virtual tabletop, there is no shared session or
multiplayer state, there is no cloud sync, and the app never ships game content
itself — packs come from elsewhere.

---

## 2. The Central Guarantee

**A quote and a paraphrase must never be confusable, anywhere in the app.**

Everything below is downstream of that sentence. The pack contract exists to make
byte-exact quotation possible; this document is about not squandering it between the
database and the screen.

Three consequences, in order of how easy they are to get wrong:

1. **Rules text is quoted, never generated.** If the answer is a rule, a table, a
   statblock, boxed text, or a printed glossary entry, the app renders the stored
   string and does not invoke the model at all.
2. **Setting text may be generated, but only from retrieved chunks, and always
   cited.** Every claim traces to something on a page.
3. **No retrieval means no answer.** Empty results produce a refusal, never a
   generated response. This is the failure mode most likely to survive review and
   reach a user, because a model answering a D&D question from pretraining sounds
   exactly like a model answering from a book.

### Routing is mechanical, not a judgment call

The app does not decide whether to quote. The pack already decided: a chunk renders
verbatim if and only if `origin = 'source'` and `kind` is verbatim-eligible. Routing
reads those two columns off the top-ranked results.

This matters because the alternative — asking a model "is this a rules question?" —
reintroduces at query time exactly the failure the pack contract spent its whole
design budget eliminating at build time.

### Mixed results produce two cards, never one blended answer

Reading `kind` and `origin` per chunk decides how *that chunk* renders. It does not by
itself say what to do when the top results are mixed — and they routinely are, because
real questions are mixed. *How does grappling work, and do the Underdark rules change
it?* pulls a rule and a lore passage in one query.

The policy is a partition into **three** routes, not two:

1. **Verbatim-class** (`origin='source'`, eligible kind) → a quote card each, ranked
   first. Quotes are cheap, and a rules answer is what the user most likely came for.
2. **Derived** (`origin='derived'`, any kind) → rendered **directly from its stored
   text**, as an attributed card citing through `chunk_derivation`. No model runs.
   The builder already wrote this text on a desktop with a frontier model; regenerating
   it on a phone would be slower, worse, and would put a second unverified generation
   between the user and content that was already validated at build time.
3. **`setting` with `origin='source'`** → the only input generation ever sees, and at
   most one generated card, placed below the quotes.

If only one route has results, only that card type appears. If nothing clears the
threshold, the refusal card.

Route 2 is easy to miss and matters: a `('glossary','derived')` definition or a
builder-written statblock summary is *not* setting text and must not be fed to the
model. It also is not quotable. It is stored prose with known provenance, and it
renders as exactly that.

### Generation sees neither rules text nor rules intent

Two separate exclusions, and only having the first is a trap.

**Verbatim-class chunks never enter the generation context.** Not as background, not
as supporting material. A rules chunk in the prompt is a rules chunk the model can
paraphrase.

Excluding them as *results* is not enough, because a `setting` chunk can legally
contain one. Nesting permits a lore chapter to hold a rollable table or a statblock,
and that child's authoritative text sits inside the parent's stored text. Send the
parent to generation whole and the rule goes with it — through the front door, past a
rule written specifically to keep it out. A lore query that never retrieved the child
at all is enough to trigger this.

So **nested verbatim spans are redacted from the parent before it becomes context**,
replaced by a marker noting that a table or statblock was omitted. The app knows the
child's span exactly; excising it is arithmetic, not judgment. The child remains
independently retrievable and quotable, which is how the user should have been getting
it anyway.

But withholding the chunk is not enough, because the *question* still carries the
rules intent. Asked "how does grappling work, and do the Underdark rules change it?"
with only lore chunks in context, a model that has read the internet will happily
explain grappling from pretraining — the exact failure this document warns about two
sections up, arriving beside an authoritative quote that makes it look corroborated.

So the query is split too. When both routes fire, generation receives only the
**residual intent** — the part of the question the quote cards did not answer —
produced by the same normalization pass, and is instructed to answer solely from
provided context and to decline any mechanical sub-question outright. The claim-support
suite (§8) is what keeps this honest, since a prompt instruction is a request, not a
guarantee.

The user gets the rule quoted exactly and the lore explained, visibly separated,
rather than one confident paragraph that blends the two and is authoritative about
neither.

---

## 3. Models and On-Device Inference

There are **three** models on the device, in two lifecycle groups: two small ones that
ship inside the app, and one large one that downloads.

### The embedder (bundled)

A sentence-embedding model producing vectors in exactly the space named by
`pack_meta.embedder_id`, at `embedder_dim` dimensions. Dense retrieval needs the
*query* embedded in the same space the pack's vectors were built in, and a generative
model does not provide that — it has no embedding interface, and its internal
representations are not the ones the builder used. Without a real embedder there is no
cosine list, no fusion, and half the retrieval design does not run.

It is small — tens of megabytes quantized — so unlike the generative model it **ships
inside the app**. That is not just a packaging convenience:

> **Retrieval works before the big download finishes.** Install the app, install a
> pack, and rules lookups are fully functional — quoted, cited, correct — while the
> generative model is still downloading or never downloaded at all.

Which means the app's core promise survives its worst moment. A user on a metered
connection at a game table gets a working rules reference immediately, and the
multi-gigabyte download becomes the thing that unlocks *setting answers*, not the
thing standing between them and the app working.

The app knows which `embedder_id`s it can serve. A pack naming an unknown one is
refused at activation rather than retrieved against incorrectly.

**Active packs may span more than one embedder contract.** They will in practice: a
pack built two years ago and one built today can both be valid and name different
embedders. One query embedding cannot serve both — the vectors live in different
spaces, and for differing `embedder_dim` the cosine is not even computable.

So the app groups active packs by embedder contract, embeds the query **once per
distinct contract**, and scores each group in its own space.

Rank-based fusion helps here — cosine scores from two different embedding spaces are
not comparable numbers, while their rankings each describe one list — but **rank alone
is not sufficient, and treating it as sufficient introduces a new bug**. Every group
has a rank 1, including a group with nothing relevant in it. Fusing purely by rank
hands that group's best-of-a-bad-lot the same dense contribution as a genuinely strong
hit from the group that actually contains the answer, and it can ride that into the
final list and over the answer floor.

So each group is **gated before it is fused**: hits must clear a similarity threshold
calibrated for that contract, and a group with nothing above its threshold contributes
nothing. Thresholds are per-embedder because they are not transferable — a cosine of
0.6 means different things in different spaces, which is the same reason the scores
could not be pooled to begin with. They come from the labelled query sets in §8, the
same way the global relevance floor does.

The cost is one embedder inference per distinct contract per query. Keeping the
number of supported contracts small is therefore a real constraint, not just tidiness.

### Speech recognition (bundled)

Voice input needs audio turned into text before anything else in this document
applies. The embedder takes text and the generative model takes text and images, so
without a dedicated component the microphone button has nothing behind it.

A compact ASR model ships with the app, in the same bundled tier as the embedder and
for the same reason: voice is a play-table feature, and a feature that only works
after a multi-gigabyte download is not available when it is most wanted.

Platform speech recognition is **not** an acceptable substitute unless it can be
pinned to on-device recognition. The default path may route audio to a server, and an
app whose premise is that nothing leaves the device cannot ship a microphone that
quietly uploads what people say at their table. If the platform can guarantee
offline recognition it is a legitimate optimization; if it cannot, the bundled model
is the only path.

Transcription output feeds the normalization step, where the `entities` alias table
fixes the proper nouns ASR reliably mangles.

### The generative model (downloaded)

A small multimodal model, quantized to 4 bits, accepting image input — which is what
makes camera queries possible.

At a few GB it exceeds what can ship in an installable package, so it downloads on
first run: resumable, an explicit Wi-Fi prompt, an honest size figure, and a clear
statement that it happens once. Everything above about the embedder is what keeps
this screen from being a wall.

It has exactly three jobs, and **none of them is unconditional**:

| Job | When |
|---|---|
| Query normalization | only for voice or camera input, a follow-up containing an unresolved reference, or splitting residual intent on a mixed result |
| Setting answers | only over `('setting','source')` chunks — never verbatim-class, never derived |
| Image understanding | only for camera queries, and only to produce a retrieval query |

Derived chunks are absent from that table deliberately: their text was generated at
build time and is rendered as stored, so they cost no inference at all.

### When the generative model is absent

Advertising that the app works before the download finishes creates a state the card
set has to handle: a setting question that retrieves perfectly good
`('setting','source')` chunks and has no model to turn them into prose. That is not
empty retrieval and not a bad pack, and answering it with "not found in your active
packs" would be a lie about the one thing the refusal card exists to tell the truth
about.

So there is a fourth card: **model unavailable**. It names what happened, lists the
chunks that *would* have been used with their citations so the user can go read the
pages themselves, and offers the download. The retrieval was not wasted; only the
prose is missing.

Mixed results degrade rather than fail. Quote cards render exactly as they always do —
they never needed the model — and the generated card is replaced by this one. A user
who declines the download permanently still has a complete, quoting rules reference,
which is the larger half of the app.

### Normalization is conditional, or the guarantee is a lie

Running the generative model on every query would contradict the invariant two
sections above — rules answers would invoke the model after all — and would put every
one-word lookup on the model's latency and thermal path. A typed "grapple" during
combat must not wake a multi-gigabyte model.

So normalization splits by cost:

- **Always, deterministically, no model:** casing, punctuation and whitespace
  folding, and the alias rewrite from `entities`. This is table lookup and string
  work. It is what turns "wrestle" into `grapple`, and it is the step that does most
  of the useful work.
- **Only when the input demands it:** the generative model. Voice and camera input
  need it by construction. A follow-up needs it only when a cheap check finds an
  unresolved reference — a leading pronoun, an elliptical "what about…" — against a
  non-empty history.

A typed, self-contained rules question therefore reaches retrieval having invoked no
model at all, which is what the guarantee actually claimed. Most queries in real play
are exactly that, and it is why the app survives a four-hour session.

---

## 4. Retrieval

Hybrid, per query: BM25 over `chunks_fts` and cosine similarity over the float16
vectors, fused.

The pipeline:

1. **Normalize.** Deterministic folding always; the generative model only for voice,
   camera, or a follow-up with an unresolved reference (§3). Where it does run, it
   folds in the last two or three turns so "what about at level 5?" resolves. This
   rewrites the *query*; it never substitutes for retrieval, which runs fresh every
   turn.
2. **Rewrite via aliases.** Match token n-grams against `entities.alias`, longest
   first, and OR each `canonical` into the lexical query alongside the user's own
   wording. Expansion only ever widens. This is what turns "wrestle" into a grapple
   hit, and the same table normalizes proper nouns mangled by speech-to-text. Every
   term — the user's and the pack's alike — is quoted as an FTS5 string literal
   before it reaches `MATCH`.
3. **Apply supersession.** Drop every chunk targeted by an active pack's
   `supersessions` rows, before either index is scored. Superseded text is not
   outranked, it is absent: it cannot be quoted, cited, or fed to generation.
4. **Embed the query**, once per distinct embedder contract across the active packs
   (§3).
5. **Search both indexes** over active packs only — BM25 producing a ranked list of
   chunks, dense search producing a ranked list of *vector rows*.
6. **Collapse dense hits to chunks**, keeping each chunk's best-scoring vector.
   `expansion`-role vectors compete here on equal footing and are then discarded —
   they are retrieval surface and are never rendered.
7. **Fuse** the two lists, now both ranked over chunks. Reciprocal rank fusion is the
   default — it needs no score calibration between BM25 and cosine, which is the part
   that otherwise requires per-pack tuning, and it is what makes multiple embedder
   contracts combinable at all. Treat the fusion method as tunable; treat the
   requirement that both signals contribute as not.
8. **Deduplicate nesting**, class-aware: a verbatim-class parent wins over its child,
   but a **non-verbatim parent loses to a verbatim child**. Otherwise a rollable table
   inside a lore chapter would be delivered as generated prose despite the app holding
   a byte-exact copy of it.
9. **Boost** chunks named by a matched alias with a non-NULL `chunk_id`.

**Steps 6 and 7 are in that order for a reason.** BM25 ranks chunks; dense search
ranks vector rows, and a long statblock may own a dozen of them between content
windows and question expansions. Fusing first would either compare identifiers that
never match, or let one chunk occupy a dozen consecutive dense ranks and crowd the
fused list with itself. Either way the hybrid ranking is quietly distorted, worst
for exactly the long, heavily-vectored chunks that matter most. Collapse to one hit
per chunk, then fuse.

### Chunk identity is `(pack_id, chunk_id)` everywhere

`chunk_id` is **pack-local**. Nothing in the pack contract makes it globally unique,
and everything inside a pack — `parent_chunk_id`, `chunk_derivation`, `tables`,
`constraints`, `entities` — treats it as local by construction. Two packs built by
different people will both have a chunk 1.

Retrieval spans packs, so every step that compares, groups, or joins on chunk identity
must use the composite: collapsing dense hits, fusion, nesting dedup, alias boosts,
capability targets, and citation resolution. Using the bare id merges unrelated chunks
from different books — one inheriting another's score, the other vanishing from the
results — and the symptom is a citation pointing at the wrong book, which reads as a
retrieval quality problem rather than an identity bug and would be debugged for a long
time.

The one identity that deliberately crosses packs is supersession, and it does not use
`chunk_id` at all: it targets `(source_uid, stable_key)` precisely because a
pack-local id cannot survive the trip.

Pack activation is the user's scoping tool: which books are live, and in what order.

**Priority and supersession are different mechanisms and only one of them makes errata
work.** Priority breaks genuine score ties and decides which book leads when two
unrelated sources both cover a topic — a preference. It cannot deliver "our errata
wins", because the corrected rule and the original almost never tie: they score
differently, and the superseded text can simply win outright while priority never gets
consulted. Errata therefore rides on `supersessions`, applied at step 3 as a filter
before scoring.

**Supersession must also reach everything rooted at the superseded chunk**, not just
the retrieval indexes. Capabilities address chunks directly and never pass through
this pipeline: a roller invokes a `tables` row by id. Filtering only the indexes would
leave an erratum's corrected table unquotable-but-superseded in search while the
*original* table stayed rollable — the app quietly rolling on obsolete rows, which is
worse than the stale quote the filter was added to prevent. Deactivating a chunk
therefore deactivates its capability manifests, its `tables` rows, its `entities`
rows, and its `constraints` rows together.

`constraints` belongs in that list for the same reason as the rest: the engine loads
those rows directly and never consults retrieval, so a superseded constraint keeps
flagging characters under a rule the user can no longer even look up. A validation
error citing a passage that has been corrected is worse than no validation, because
the user cannot find the text to argue with.

A correction removes what it corrects, everywhere.

If nothing clears the relevance floor, the app refuses. See §7.

---

## 5. Capabilities

Packs ship **declarative capability manifests**; the app ships the executors. A
manifest says *this table is rollable with this dice expression*, not *here is how to
roll it*.

The vocabulary is closed. A pack cannot introduce a new capability kind, and it
certainly cannot ship code. Anything the app does not recognize is ignored, and a
manifest that fails schema validation causes the pack to ship without that capability
— recorded in the build report, never silently dropped.

Rolling on a validated table is the first capability and the model for the rest: the
pack supplies structured rows that were deterministically round-tripped against the
verbatim chunk at build time, and the app supplies the RNG and the presentation. A
table that failed validation is still quotable, just not rollable. Degrading a
capability is acceptable; rolling a wrong result is not.

The UI surfaces available capabilities as chips on the query bar, so what the active
packs can actually do is visible rather than discovered by accident.

---

## 6. Documents, Trackers, and Constraints

Documents are characters and anything else worth keeping — grouped by campaign,
stored in the app's own database, exportable as files.

A document carries **trackers**: named values with types (a hit point pool, a spell
slot count, a condition flag, a resource that refreshes on a rest). Trackers are the
thing people actually reach for mid-session, which is why they come first.

### The constraint engine

Games have rules about what a legal sheet looks like, and those rules belong to the
game, not the app. So packs declare constraints — and the vocabulary of declarable
constraints is **closed**.

Firmly settled, because these are the properties that keep this from becoming an
embedded scripting language:

- A fixed, small set of predicate forms. Packs instantiate them; packs cannot compose
  arbitrary expressions.
- No code execution, no eval, no callbacks into the pack.
- Every constraint is inspectable and every violation is explainable in terms of the
  predicate that failed — a constraint the user cannot understand is worse than no
  constraint.
- Constraints advise by default. A sheet that violates one is flagged, not rejected;
  house rules are the norm, and an app that refuses to store a legal-at-this-table
  character is an app that gets deleted.

Constraints ship in the pack's `constraints` table, each row instantiating one
predicate form and citing the chunk that states the rule — so a flagged violation can
link to the passage behind it. Without that table the engine has nothing to load and
every game's rules would end up compiled into the app, which is the outcome this whole
mechanism exists to avoid.

**Still to pin: the exact five predicate forms and their arguments.** These go in the
schema spec alongside the DDL, since the builder and the app must agree on them
byte-for-byte. Everything above constrains what they may be.

---

## 7. UI and State

Four surfaces:

| Surface | Contents |
|---|---|
| **Ask** | query bar (text / mic / camera), capability chips, answer feed |
| **Packs** | installed list, active toggles, priority order, supersessions in effect, install from file, storage used, build report (read from the pack's own `build_report` table) |
| **Documents** | characters and other documents, grouped by campaign |
| **Document view** | the sheet, its trackers, tap-through from a tracker to the rule behind it |

### Answer cards

Five kinds, and the visual distance between the first two is the most important design
decision in the app:

- **Verbatim.** The stored string, in a distinct typeface on a distinct background,
  with a citation rule beneath it — book, printed page label, pack badge. Copy button.
  Expand to show surrounding context.
- **Generated.** Prose with inline citation chips that resolve to their chunks, and a
  "generated from N sources" footer. Typographically unmistakable against a verbatim
  card at a glance, across both themes, at every text size.
- **Derived.** Builder-written text rendered as stored — a summary, a written-up
  definition. Styled as generated, not as a quote, because that is what it is. Its
  chips come from `chunk_derivation`, claim-scoped where the builder recorded which
  source supports which sentence.
- **Model unavailable.** The generative model has not been downloaded, but retrieval
  succeeded. Lists the chunks that would have been used, with citations, and offers
  the download. Distinct from *empty* on purpose: the app found the answer and cannot
  currently phrase it, which is a different statement and a different remedy.
- **Empty.** *Not found in your active packs* — with the list of what is active and an
  offer to search the inactive ones. This card is load-bearing, not a courtesy: it is
  the visible half of the refuse-rather-than-hallucinate rule, and the reason a user
  learns to trust that silence means silence.

If a user ever mistakes generated prose for book text, every guarantee upstream was
wasted effort.

### State

The app owns a database for installed packs, activation, priority, documents,
trackers, entitlements, and recent queries. Packs stay read-only at runtime.

No cloud sync — local is the premise, not a limitation to be fixed later. Documents
export and import as files, which is also how they move between devices.

---

## 8. Testing

The guarantees above are only real if they are mechanically enforced. The test
strategy is mostly about making the invariants falsifiable:

| Area | What it asserts |
|---|---|
| **Verbatim identity** | every rendered quote is byte-identical to its chunk in the pack — property-tested over a corpus, not spot-checked |
| **Citation integrity** | every citation resolves to a chunk that was actually in context; verbatim-class chunks never appear in a generation context; empty retrieval produces the refusal card and never a generated one |
| **Claim support** | every claim in a generated answer is actually supported by the chunk it cites — see below |
| **Retrieval regression** | a labelled query set per test pack, measuring recall@k, gated in CI |
| **Constraint engine** | unit and property tests over the closed predicate vocabulary |
| **Dice grammar conformance** | shared expression/range/distribution vectors, run identically here and in the builder |
| **Pack rejection** | one deliberately broken pack per *on-device* rejection case — wrong endianness (caught by the probe vector, not by length), dimension mismatch, span length disagreeing with its text, derived chunk with no `chunk_derivation`, unknown `embedder_id`, unrecognized `schema_version` |
| **Builder validation** | span boundaries, containment, sibling overlap, slice equality, `text_sha256` — run against the source, in the builder, not on the phone |
| **On-device performance** | cold model load, tokens/sec, retrieval latency across increasing active-pack counts, tracked as a gate |

### Citation integrity is not grounding

Asserting that every citation resolves to a chunk that was in context is a real check
and it catches real bugs, but it is a **membership** test, and membership is not
support. A model that invents a detail and attaches the citation of whatever chunk was
nearest passes it cleanly. The answer is fabricated, the citation is well-formed, and
the test is green — which is worse than no test, because the green is load-bearing in
review.

So claim support is tested separately, and it is the harder half:

- A gold set of setting questions, each with hand-annotated supporting spans — the
  passages that do license an answer.
- Answers are decomposed into individual claims, and each claim is checked against the
  chunk it cites for entailment. A claim citing a chunk that does not support it fails
  whether or not the claim happens to be true.
- Adjudication runs **off-device at build time**, where a frontier model can be the
  judge — this is a CI gate, not something the phone does. A hand-annotated subset is
  asserted exactly, so the judge itself is checked against ground truth rather than
  trusted.
- Scored as a regression threshold, not pass/fail on every claim. Entailment judgment
  is noisy, and a suite that flakes gets disabled, which is the real failure mode.

**The same check has to cover derived chunk text**, and testing only live generation
misses it entirely. A derived chunk is model-written prose that the app renders with
citations — the identical trust claim as a generated answer, made by a different
model at a different time. Its `chunk_derivation` rows guarantee that the cited chunks
exist, and nothing else. A builder-written summary that quietly invents a detail
carries a well-formed citation, renders as attributed text, and passes every
validation in this document, because the only entailment check runs against answers
the phone generates and this text was never generated on a phone.

Derived text is therefore claim-checked **in the builder**, against the chunks in its
own `chunk_derivation`, as an enrichment validation: a summary that fails is dropped
and recorded, not shipped. That is the natural home for it — the builder already has
the frontier model, the source, and no latency budget.

The tests catch different things and none substitutes for another. Membership catches
plumbing bugs. On-device support catches the small model answering live. Builder-side
support catches the large model that wrote the pack — the one nobody thinks to
distrust, because its output looks like data by the time the app sees it.

### What the phone can actually check

The two rows above are split deliberately. Span validation — boundaries, containment,
sibling overlap, and whether `text` equals its slice — requires the normalized source
bytes, and the pack ships only their hash. The app cannot recompute `text_sha256`
without the text it hashes, and it cannot tell that an offset lands mid-character
without the character. Listing those as on-device rejection tests would have been a
suite that could not be written.

They are builder validations, and the builder is where they run: it has the source in
hand and fails the build. What crosses to the device is the guarantee that they ran.

The phone still gets one real span check that needs no source: `span_end -
span_start` must equal the UTF-8 byte length of `text`. Cheap, and it catches
truncation and offset drift — the corruptions most likely to survive a build and a
transfer.

If the normalized source text ever ships in the pack (still open — it roughly doubles
text size), full span re-validation becomes possible on-device and these rows merge.
Until then the split is the honest description.

**Retrieval regression** deserves the same emphasis for a different reason: it catches
what degrades silently and continuously. Retrieval quality has no symptom until
someone notices the app has been quietly failing to find a rule for three releases.

The test corpus is an **open-licensed SRD**. It is freely redistributable, so
fixtures live in the repo and CI runs against real game text rather than toys. The
same pack doubles as a free starter pack, which gives the first-run experience
something to demonstrate against: with the embedder bundled, a new install can answer
real rules questions from the starter pack before the generative download has
finished — or been started.

---

## 9. Release Sequencing

**Tracking ships before creation.** Characters can be entered and tracked before the
app can build one from scratch. Guided creation is a wizard over the same constraint
vocabulary and lands after.

The rationale is that tracking is what people reach for during a session, and it is
useful against a character that already exists on paper. Creation is a one-time
activity per character and there are established tools for it.

This is a sequencing decision, not an architectural one. Flipping it changes what
ships in which release and nothing above it.

---

## 10. Open Questions

- The five constraint predicate forms and their arguments (§6) — blocks the schema
  spec.
- Relevance floor for triggering the refusal card. Too low and it never fires; too
  high and it fires on good queries. Needs the labelled query sets from §8 before it
  can be set with any honesty.
- Whether generated setting answers are cached, and what invalidates the cache when
  pack activation or priority changes.
- Pack distribution and entitlement: install-from-file is settled, a catalog is not.
  Depends on the signing scheme, which is open on the builder side too.
- Whether the conversational window is user-visible and clearable, or purely internal.

### Settled: camera queries never answer from the image

This was listed as open, between answering directly from the photograph and using it
only to build a retrieval query. It should not have been. The first option is not a
UX preference, it is a hole in the central guarantee: a model reading a page and
answering from it produces uncited rules text that never passed through a pack, never
matched a stored chunk, and cannot be quoted byte-exactly — while looking exactly like
every other answer in the feed. It would also answer when retrieval found nothing,
defeating the refusal rule from the same direction.

So the flow is fixed: **image understanding produces a retrieval query and nothing
else.** The photograph is a way of typing. Once the query exists, the path is
identical to a typed one — same retrieval, same routing, same cards, same refusal —
and a photographed page whose content is not in any active pack gets the refusal card,
which is the correct answer even though the text is visibly right there in the
viewfinder.

That last case is the one worth being sure about. Refusing to answer a question about
a page the user is physically holding feels wrong, and it is right: the app's claim is
that its answers come from packs, and an answer read off a photo is not that. If the
book is worth asking about repeatedly, it is worth building a pack for.
