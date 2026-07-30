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

---

## 3. Model and On-Device Inference

A small multimodal model runs locally, quantized to 4 bits. It accepts image input,
which is what makes camera queries possible — point the phone at a page, ask about it.

The model is **not bundled**. At a few GB it exceeds what can ship in an installable
package, so it downloads on first run. This is the app's worst moment and it happens
before the user has seen anything work: it needs a resumable download, an explicit
Wi-Fi prompt, an honest size figure, and a clear statement that it happens once.

The model has exactly three jobs:

| Job | When |
|---|---|
| Query normalization | every query — voice transcription cleanup, pronoun resolution, conversational context |
| Setting answers | only when the top results are `setting` chunks |
| Image understanding | only for camera queries |

Most queries in actual play are rules lookups, and rules lookups never reach
generation. That is a correctness property first, but it is also why the app is
usable for a four-hour session without cooking the phone.

---

## 4. Retrieval

Hybrid, per query: BM25 over `chunks_fts` and cosine similarity over the float16
vectors, fused.

The pipeline:

1. **Normalize.** Fold in the last two or three turns of conversation so follow-ups
   resolve — "what about at level 5?" is meaningless alone. This rewrites the
   *query*; it never substitutes for retrieval, which runs fresh every turn.
2. **Rewrite via aliases.** Match token n-grams against `entities.alias`, longest
   first, and OR each `canonical` into the lexical query alongside the user's own
   wording. Expansion only ever widens. This is what turns "wrestle" into a grapple
   hit, and the same table normalizes proper nouns mangled by speech-to-text.
3. **Search both indexes** over active packs only.
4. **Fuse** the two ranked lists. Reciprocal rank fusion is the default — it needs no
   score calibration between BM25 and cosine, which is the part that otherwise
   requires per-pack tuning. Treat the fusion method as tunable; treat the
   requirement that both signals contribute as not.
5. **Resolve vectors to chunks**, taking each chunk's best-scoring vector.
   `expansion`-role vectors compete here on equal footing and are then discarded —
   they are retrieval surface and are never rendered.
6. **Deduplicate nesting.** When a parent and its child both match, keep the parent,
   so the user sees the complete rule rather than a table torn out of it.
7. **Boost** chunks named by a matched alias with a non-NULL `chunk_id`.

Pack activation is the user's scoping tool: which books are live, and in what
priority order when two disagree. Priority breaks ranking ties — it is how a group
says "our errata pack wins" without deleting anything.

If nothing clears the relevance floor, the app refuses. See §6.

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
| **Packs** | installed list, active toggles, priority order, install from file, storage used, build report |
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
| **Grounding** | every citation in a generated answer resolves to a chunk that was actually in context; empty retrieval produces the refusal card and never a generated one |
| **Retrieval regression** | a labelled query set per test pack, measuring recall@k, gated in CI |
| **Constraint engine** | unit and property tests over the closed predicate vocabulary |
| **Pack rejection** | one deliberately broken pack per rejection case — wrong endianness, span violation, dimension mismatch, uncovered vector tiling |
| **On-device performance** | cold model load, tokens/sec, retrieval latency across increasing active-pack counts, tracked as a gate |

Two of these deserve emphasis. **Grounding** catches the model answering from
pretraining, which is the failure most likely to look correct in review. **Retrieval
regression** catches the thing that degrades silently and continuously, and that
nothing else will catch — retrieval quality has no symptom until someone notices the
app has been quietly failing to find a rule for three releases.

The test corpus is an **open-licensed SRD**. It is freely redistributable, so
fixtures live in the repo and CI runs against real game text rather than toys. The
same pack doubles as a free starter pack, which gives the first-run experience
something to demonstrate against — worth something, given that first run opens with a
multi-gigabyte download.

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
- Camera query flow — whether a photographed page is answered directly from the image
  or used only as a retrieval query against the pack.
- Whether the conversational window is user-visible and clearable, or purely internal.
