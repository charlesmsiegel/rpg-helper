# Routing and Answer Cards — Specification

From ranked candidates to what appears on screen.

This is where the central guarantee is kept or lost:

> **A quote and a paraphrase must never be confusable, anywhere in the app.**

The pack contract exists to make byte-exact quotation possible and `retrieval-spec.md`
delivers candidates without deciding anything about them. Everything in this document is
about not squandering that between the database and the screen.

Implementation order: **5 of 8** — see the README for the full sequence and why it runs in this order.

Status: specified, unimplemented.

---

## 1. Routing is mechanical

The app does not decide whether to quote. **The pack already decided.** A chunk renders
verbatim if and only if `origin = 'source'` and `kind` is in the verbatim-eligible set.
Routing reads those two columns off the candidates and partitions.

The alternative — asking a model *"is this a rules question?"* — reintroduces at query
time exactly the failure the pack contract spent its whole design budget eliminating at
build time.

### The three routes

| # | Selector | Renders as | Model runs |
|---|---|---|---|
| 1 | `origin='source'` and verbatim-eligible `kind` | a **quote card** each | no |
| 2 | `origin='derived'`, any `kind` | a **derived card** each, from stored text | no |
| 3 | `kind='setting'` and `origin='source'` | at most **one generated card** | yes |

Route 2 is easy to miss and matters. A `('glossary','derived')` definition or a
builder-written statblock summary is **not** setting text and must not be fed to the
model. It is also not quotable. It is stored prose with known provenance and it renders
as exactly that.

Regenerating derived text on the phone would be slower, worse, and would put a second
unverified generation between the user and content a frontier model already produced and
the builder already claim-checked.

If only one route has candidates, only that card type appears. If retrieval returned
nothing — which happens exactly when gating left nothing (`retrieval-spec.md` §7.4) — the
refusal card, and never a generated one.

### Card order

Quote cards, then derived cards, then the generated card.

Quotes lead because a rules answer is what the user most likely came for and because
quotes are free. The generated card is last because it is the least-verified thing on
the screen, and position is one of the several signals this document uses to say so.

Within a route, order is the fused rank from retrieval, ties broken by pack priority.

---

## 2. Generation sees neither rules text nor rules intent

Two separate exclusions. Having only the first is a trap.

### 2.1 Verbatim-class chunks never enter the generation context

Not as background, not as supporting material. A rules chunk in the prompt is a rules
chunk the model can paraphrase.

### 2.2 Nested verbatim spans are redacted from their parent

Excluding verbatim chunks as *results* is not enough, because a `setting` chunk can
legally contain one. Nesting permits a lore chapter to hold a rollable table or a
statblock, and that child's authoritative text sits inside the parent's stored text. Send
the parent to generation whole and the rule goes with it — through the front door, past a
rule written specifically to keep it out. **A lore query that never retrieved the child at
all is enough to trigger this.**

The app knows the child's span exactly, so excising it is arithmetic, not judgment.

**The algorithm.** For a route-3 parent chunk `P` about to enter the generation context:

1. Find every chunk `C` in the same pack with `parent_chunk_id = P.chunk_id` that is
   verbatim-class — `origin='source'` and a verbatim-eligible `kind`. Retrieval need not
   have returned them; this is a lookup on the pack, not a filter on the candidates.
2. Convert each child's span into an offset within `P.text`:
   `local_start = C.span_start − P.span_start`, `local_end = C.span_end − P.span_start`,
   in UTF-8 bytes.
3. Excise each `[local_start, local_end)` from `P.text`'s UTF-8 bytes, replacing it with
   the marker `[<kind> omitted]` — `[table omitted]`, `[statblock omitted]`.
4. Coalesce adjacent markers and collapse the whitespace left behind.

**If any child's offsets do not land within `P.text` or do not fall on UTF-8 sequence
boundaries, `P` is dropped from the generation context entirely.** Fail closed. A partial
redaction that leaves half a table in the prompt is the exact failure this step exists to
prevent, and a malformed span is not a reason to take the risk — the child remains
independently retrievable and quotable, which is how the user should have been getting it
anyway.

The redacted text is also what `retrieval-spec.md` §9.1 re-runs the query against for the
lexical independence test, so the two uses agree by construction.

### 2.3 The query is split too

Withholding the chunk is not enough, because the *question* still carries the rules
intent. Asked *"how does grappling work, and do the Underdark rules change it?"* with only
lore chunks in context, a model that has read the internet will happily explain grappling
from pretraining — arriving beside an authoritative quote that makes it look
corroborated.

So when routes 1 and 3 both fire, generation receives only the **residual intent**: the
part of the question the quote cards did not answer.

- The residual is produced by the same generative pass that does normalization, given the
  original query and a description of what the quote cards cover — their kinds and
  heading paths, never their text.
- **The model may return an empty residual**, meaning the quotes answered everything. Then
  no generated card is produced at all. This is a normal outcome, not a failure.
- When route 1 does not fire, there is nothing to subtract and the residual is the whole
  query. No model call is needed for the split.

### 2.4 The prompt contract

Generation is instructed to answer solely from the provided context and to decline any
mechanical sub-question outright.

**A prompt instruction is a request, not a guarantee.** The claim-support suite in
`test-infrastructure-spec.md` is what keeps this honest, and it is the reason none of the
above is treated as sufficient on its own.

---

## 3. The five cards

### 3.1 Verbatim

The stored string, rendered as an authoritative quotation.

| element | requirement |
|---|---|
| body | `chunks.text`, byte-exact |
| citation | source title, edition, heading path, page label range, pack badge |
| copy | copies the quote **and its citation** |
| expand | reveals surrounding context — the parent chunk, or adjacent siblings |

**The body is never passed through a markdown or HTML renderer.** Game text is full of
asterisks, underscores, brackets, and pipe characters; a renderer would interpret them,
and the string on screen would no longer be the string in the pack. Byte-exactness that
survives the database and dies in the view layer is not byte-exactness. Verbatim bodies
render as preformatted text with whitespace preserved, and selection yields exactly the
stored bytes.

Copy includes the citation because a quote pasted into a group chat without its source is
precisely the artifact this app exists to prevent.

### 3.2 Generated

Prose the on-device model produced from route-3 chunks.

| element | requirement |
|---|---|
| body | model output, rendered as prose |
| inline chips | resolve to the chunks that were in context |
| footer | *generated from N sources* |
| appearance | typographically unmistakable against a verbatim card |

That last row is the most important design decision in the app and it belongs to
`ui-spec.md`. What this document requires of it: the distinction must hold **at a glance,
in both themes, at every text size**, and must not depend on colour alone.

### 3.3 Derived

Builder-written text rendered as stored — a summary, a written-up definition.

Styled as generated, **not** as a quote, because that is what it is. Its chips come from
`chunk_derivation`:

- a row with a **claim span** anchors an inline chip to exactly that run of the derived
  text
- a row with a **NULL span** contributes a footer citation

Each chip resolves through the cited chunk's own `source_id`, `heading_path`, and page
labels — a derived chunk has no citation columns of its own, so its citation cannot drift
from the chunks it was actually built from.

A pack recording only whole-chunk rows is valid; its derived cards simply show footer
citations.

### 3.4 Model unavailable

Retrieval succeeded, route 3 has chunks, and the generative model has not been
downloaded.

| element | requirement |
|---|---|
| statement | what happened, in one sentence |
| listing | the chunks that *would* have been used, with full citations |
| action | offer the download, with an honest size figure |

**Distinct from *empty* on purpose.** The app found the answer and cannot currently
phrase it, which is a different statement and a different remedy. Answering this with
*not found in your active packs* would be a lie about the one thing the refusal card
exists to tell the truth about.

Mixed results degrade rather than fail: quote cards and derived cards render exactly as
they always do — they never needed the model — and only the generated card is replaced by
this one. A user who declines the download permanently still has a complete, quoting rules
reference, which is the larger half of the app.

### 3.5 Empty

*Not found in your active packs.*

| element | requirement |
|---|---|
| statement | the refusal, unhedged |
| context | the list of currently active packs |
| action | offer to search the inactive ones |

This card is load-bearing, not a courtesy. It is the visible half of the
refuse-rather-than-hallucinate rule, and the reason a user learns to trust that silence
means silence.

**Searching inactive packs is an explicit user action**, never automatic. It runs the same
pipeline over the inactive set, and every result is labelled as coming from an inactive
pack. Finding something does **not** activate that pack; activation stays a deliberate
choice, because it changes the answers to every future question.

---

## 4. Citation resolution

One resolver, used by every card kind.

For a **source** chunk: `sources.title`, `edition`, the chunk's `heading_path`, and the
`page_label_start`–`page_label_end` range as stored text.

Page labels are text and are rendered as text. A source whose `locator_scheme` is not
`page` reads in its own vocabulary — *GM screen, panel 3*; *Hazard deck, card 14* —
because that is how people at a table refer to it. The scheme is a closed vocabulary
validated at activation, so there is always a defined rendering.

For a **derived** chunk: resolve `chunk_derivation` and render each cited chunk's citation
as above.

A citation that cannot be resolved is a bug, not a display case: activation rejects packs
with dangling source references, derivations that do not terminate at a source chunk, and
claim spans outside their text. **No card renders a partial or placeholder citation.** If
resolution fails at render time the card is not shown and the failure is reported, because
an attributed card with a broken citation is indistinguishable to a user from one with a
working one.

---

## 5. Diagnostics

Every answer can show *why these results* — the per-list ranks, which gates each candidate
cleared, and which route it took.

This is not a debugging leftover. Retrieval quality degrades silently and continuously,
and a user who can see that their question matched a book on lexical score alone is a user
who can rephrase it. It also makes the routing claim auditable by the person best placed
to notice it is wrong.

Off by default; a setting, not a gesture.

---

## 6. Testing

| Area | What it asserts |
|---|---|
| Verbatim identity | every rendered quote is byte-identical to its chunk — property-tested over a corpus, not spot-checked |
| Renderer safety | text containing markdown metacharacters renders unchanged; selection and copy yield the stored bytes |
| Route partition | the three routes are assigned from `kind` and `origin` alone, over an exhaustive matrix of the two |
| Redaction | nested verbatim spans never appear in a generation context, including when the child was never retrieved |
| Redaction fail-closed | a child span outside its parent, or off a UTF-8 boundary, drops the parent from context rather than partially redacting |
| Context exclusion | verbatim-class and derived chunks never enter the generation context by any path |
| Residual intent | when both routes fire, generation receives only the residual; an empty residual produces no card |
| Citation integrity | every citation resolves to a chunk that was actually in context |
| Claim support | every claim in a generated answer is entailed by the chunk it cites — see `test-infrastructure-spec.md` |
| Empty | empty retrieval produces the refusal card and never a generated one |
| Model absent | route 3 with no model produces the *model unavailable* card, listing the chunks with citations; quote and derived cards are unaffected |
| Card order | quotes, then derived, then generated, across mixed results |

**Citation integrity is a membership test and membership is not support.** A model that
invents a detail and attaches the citation of whatever chunk was nearest passes it
cleanly — the answer fabricated, the citation well-formed, the test green, and the green
load-bearing in review. Claim support is the separate and harder half, and it lives in the
test infrastructure spec because it needs an off-device judge.

---

## 7. Settled, and worth restating

**Camera queries never answer from the image.** Image understanding produces a retrieval
query and nothing else. The photograph is a way of typing; once the query exists the path
is identical to a typed one — same retrieval, same routing, same cards, same refusal.

A photographed page whose content is not in any active pack gets the refusal card. That
feels wrong and is right: the app's claim is that its answers come from packs, and an
answer read off a photo is not that. It would also answer when retrieval found nothing,
defeating the refusal rule from the other direction.

If the book is worth asking about repeatedly, it is worth building a pack for.
