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

The policy is a partition, not a choice:

1. Split the results into **verbatim-class** and **everything else**.
2. Every verbatim-class result above the answer threshold renders as its own quote
   card, ranked first. Quotes are cheap, and a rules answer is what the user most
   likely came for.
3. Generation runs **only over the non-verbatim results**, and produces at most one
   generated card, placed below the quotes.
4. If only one class is present, only that kind of card appears. If neither clears the
   threshold, the refusal card.

Step 3 carries the weight: **verbatim-class chunks never enter the generation
context.** Not as background, not as supporting material. A rules chunk in the prompt
is a rules chunk the model can paraphrase, and a paraphrased rule reaching the screen
in a generated card is the precise failure this whole design exists to prevent. The
model cannot restate a rule it was never shown.

The user gets the rule quoted exactly and the lore explained, visibly separated,
rather than one confident paragraph that blends the two and is authoritative about
neither.

---

## 3. Models and On-Device Inference

There are **two** models on the device, with different sizes, different jobs, and
crucially different lifecycles.

### The embedder

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

### The generative model

A small multimodal model, quantized to 4 bits, accepting image input — which is what
makes camera queries possible.

At a few GB it exceeds what can ship in an installable package, so it downloads on
first run: resumable, an explicit Wi-Fi prompt, an honest size figure, and a clear
statement that it happens once. Everything above about the embedder is what keeps
this screen from being a wall.

It has exactly three jobs, and **none of them is unconditional**:

| Job | When |
|---|---|
| Query normalization | only for voice or camera input, or a follow-up containing an unresolved reference |
| Setting answers | only over `setting` chunks, never over verbatim-class ones |
| Image understanding | only for camera queries, and only to produce a retrieval query |

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
4. **Embed the query** with the on-device embedder, in the space named by the active
   packs' `embedder_id`.
5. **Search both indexes** over active packs only.
6. **Fuse** the two ranked lists. Reciprocal rank fusion is the default — it needs no
   score calibration between BM25 and cosine, which is the part that otherwise
   requires per-pack tuning. Treat the fusion method as tunable; treat the
   requirement that both signals contribute as not.
7. **Resolve vectors to chunks**, taking each chunk's best-scoring vector.
   `expansion`-role vectors compete here on equal footing and are then discarded —
   they are retrieval surface and are never rendered.
8. **Deduplicate nesting**, class-aware: a verbatim-class parent wins over its child,
   but a **non-verbatim parent loses to a verbatim child**. Otherwise a rollable table
   inside a lore chapter would be delivered as generated prose despite the app holding
   a byte-exact copy of it.
9. **Boost** chunks named by a matched alias with a non-NULL `chunk_id`.

Pack activation is the user's scoping tool: which books are live, and in what order.

**Priority and supersession are different mechanisms and only one of them makes errata
work.** Priority breaks genuine score ties and decides which book leads when two
unrelated sources both cover a topic — a preference. It cannot deliver "our errata
wins", because the corrected rule and the original almost never tie: they score
differently, and the superseded text can simply win outright while priority never gets
consulted. Errata therefore rides on `supersessions`, applied at step 3 as a filter
before anything is scored. A correction removes what it corrects.

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

Three kinds, and the visual distance between the first two is the most important
design decision in the app:

- **Verbatim.** The stored string, in a distinct typeface on a distinct background,
  with a citation rule beneath it — book, printed page label, pack badge. Copy button.
  Expand to show surrounding context.
- **Generated.** Prose with inline citation chips that resolve to their chunks, and a
  "generated from N sources" footer. Typographically unmistakable against a verbatim
  card at a glance, across both themes, at every text size.
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
| **Pack rejection** | one deliberately broken pack per rejection case — wrong endianness (caught by the probe vector, not by length), span violation, dimension mismatch, uncovered vector tiling, mid-character span offset |
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

The two tests catch different things and neither substitutes for the other. Membership
catches plumbing bugs — a citation pointing at a chunk that was never retrieved.
Support catches the model, which is the thing actually capable of lying.

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
