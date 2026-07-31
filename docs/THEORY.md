# The theory this code embodies

Not a spec — the numbered specs say what the app must do. This says what the code *means*,
what each type corresponds to in the world, where the model's boundary is, and where there
is currently no model at all. It exists because the diff is a lossy serialization: the
thing that gets lost when only the code is handed over is exactly what makes the next
change correct rather than merely compiling.

Read `Watch` at the bottom first if you have five minutes.

---

## 1. The theory, in three sentences

An RPG book is a **source of authority**, and the product's one guarantee is that a reader
can always tell whether they are looking at what the book says or at what a machine said
about the book. Every module is a restatement of that: a pack preserves byte-exact source
text with provenance attached; retrieval narrows to passages without altering them; routing
decides which of the two kinds of card a question deserves; the model is allowed to write
prose only where its claims can be checked against the passages it cites. **Refusal is a
correct answer** — the system is built to say *I don't have that* rather than to fill a
gap, because a plausible answer that isn't in the book is the failure this product exists
to prevent.

That theory predicts. Given a case nobody wrote a test for — say, a pack that declares a
chunk as `('statblock', 'derived')` — the theory tells you the answer without running
anything: `derived` means a machine wrote it, so it cannot be quoted, so it renders as
attributed prose with citations and never as a quotation, whatever its `kind` says. The
`origin`/`kind` partition is not two fields that happen to exist; it is the guarantee made
into data.

---

## 2. What each module models

| Module | Models | The thing it corresponds to |
|---|---|---|
| `:pack` | The **format and its gate** | A book someone else compiled, handed to you on a memory card. Untrusted by construction. |
| `:state` | The **shelf** | Which books you own, which are on the table tonight, what corrects what. |
| `:retrieval` | **Narrowing** | Finding the paragraphs, without changing a character of them. |
| `:routing` | **Which kind of answer** | The decision between showing the book and speaking about the book. |
| `:model` | **Speech, under check** | A model that may only say what the retrieved passages support. |
| `:capabilities` | **Things the book lets you do** | Rolling on a table the book actually contains. |
| `:builder` | The **compiler** | Turning a PDF or a text corpus into a pack, off-device. |
| `:session` | **Assembly** | The one place a question becomes cards. |
| `:app` / `:cli` | **Surfaces** | Two renderings of the same answer. |

Three of these boundaries are load-bearing and worth stating explicitly:

**A pack is untrusted third-party input, and its own DDL is part of the untrusted input.**
`CHECK` constraints and `NOT NULL` in a pack's schema describe what its builder chose to
write. The gate re-derives every property it needs. This is why `PackForge.relax()` exists
in the fixtures: nearly every hardening test has to strip a constraint before it can inject
the defect, because the honest attack is a builder that simply didn't write the constraint.

**The builder and the app share one validator.** `:builder` emits, `:pack` judges, and the
builder runs the app's real gate against its own output before that output replaces
anything. A builder with a private idea of the format keeps shipping files after the real
format moves.

**Refusal is a positive outcome with its own path.** The information-content gate measures
what share of a query's summed IDF the candidates actually cover; when gating leaves
nothing, that is a refusal card, not an error. Everything downstream is written so that
"nothing survived" is representable.

---

## 3. Where the theory holds, and the shape of the mistakes it doesn't cover

The content-level theory is strong and the tests pin it hard. The recurring failures are
not in *what the system means* — they are in **representation**, and they have all been the
same error wearing different clothes:

> The code is rigorous about not trusting a value's *content*, and repeatedly trusts its
> *declared type*.

Every one of these was a real defect found in review:

- `length(text)` counts characters; the limit was stated in bytes. Every non-English book
  got its ceiling silently multiplied by three or four — so the packs the bound existed to
  catch were exactly the ones that passed it.
- `schema_version` narrowed to `Int`: `4294967297` reads as `1`, and the corruption check
  waves through the corruption it was written for.
- `JsonPrimitive.content` coerces `true` and `123` and `null` into strings that satisfy a
  grammar meant for identifiers, so a malformed rule becomes a silent no-op rather than a
  reported drop.
- A UTF-16 `Char` scan treats a surrogate pair as two separators where SQLite's tokenizer
  sees one token.
- A column declared `INTEGER` can hold a gigabyte of text, because SQLite affinity is
  advisory.

The rule that was missing, now stated: **every quantity a pack controls is measured in
bytes, read at its widest type, and type-checked before conversion.** `Preflight` follows
it by asking the file which columns exist rather than listing the ones today's loaders
read — so the column somebody adds next is bounded without anybody remembering to bound it.

The second recurring class is **unbounded work on untrusted input**: a two-billion-iteration
table-coverage loop, sixteen million retained postings during gating, a whole alias index
rebuilt per keystroke, the entire superseded set pasted into every query. The rule, also
now stated: *anything whose size a pack chooses must be bounded, streamed, or turned into a
relation before it reaches a loop.*

---

## 4. Where there is no theory yet — the trouble that needs fixing

Ranked by how much is at stake, not by effort.

### 4.1 The app was not connected to the app — *fixed*

`MainActivity.AskScreen(turns: List<Turn> = emptyList())` took a feed as a parameter and
nothing ever supplied one. `:app` depended on `:pack`, `:state`, `:retrieval`, `:routing`,
`:model` and `:capabilities` — and **not on `:session`**, which is where `AskService` lives:
the one place that owns how a question becomes cards, with the follow-up rewrite, the cache
lookup, and the feed recording in the one order that is correct. The APK assembled,
installed, showed a text field, and answered nothing.

This was the clearest possible instance of the failure this document is about: the layering
was so clean that the top of the stack was never attached, and every test passed because
every test exercises the layers below it.

`AskViewModel` now holds the `Store` and the `AskService`, and opens the active set for the
length of one question — per question rather than for the app's lifetime, because a query
is specified to capture the active set once at its start, and a held-open library would mean
a pack activated in Settings does not take effect until relaunch.

Two things that fell out of wiring it, both worth knowing:

- **A restored turn is history, not an answer.** The feed survives process death, and the
  stored text was produced by whatever was active *then*. Re-rendering it through
  `AnswerCard` would put a previous session's words inside this session's quotation styling
  — the confusion this whole product exists to prevent, arriving through the scrollback.
  `HistoryCard` is deliberately plain, dimmed, and labelled.
- **The answer cache is unreachable until weights ship.** No generator means route 3 never
  fires, so `couldGenerate` is false and no key is built. When a model does arrive, the
  cache returns a *stored render* and `Asked.answer` is null — so the app will need a
  structured serialization to store, not a display string, or a cache hit will render
  without the quote/paraphrase distinction. That is §4.2's problem, and it is the reason
  §4.2 is not merely tidiness.

### 4.2 One invariant, implemented twice, compared never — *fixed*

`cli/Render.kt` and `app/Cards.kt` each independently implemented "a quote and a paraphrase
must never be confusable" — the product's entire reason to exist — and nothing compared
them. By the time it was fixed there were **three** copies of the chip-span walk and a
fourth surface with none, which is how the stored rendering came to list `[1] Source` above
prose containing no `[1]`.

`Card.layout()` in `:routing` now decides it once, in `Block`s named by provenance rather
than appearance: `Quotation` is the only block that may be rendered as a quotation and the
only one that ever carries text a pack wrote. Every surface paints blocks. `CardLayoutTest`
pins the invariant per card kind; `RenderParityTest` in `:cli` compares the two text
renderers where **both are importable** — a parity test that reimplements one renderer to
compare against is comparing the layout to itself, and would pass on the day they actually
diverged.

Not closed: the Compose surface still paints its own tree, so only the decisions are shared
and not the painting. That is the right boundary — a terminal and a phone genuinely differ
— but it means the app's rendering is still checked by no test, because `:app` has no test
runner. See §4.5.

### 4.3 `build_report` is write-only — *fixed*

The builder recorded, per pack, what it dropped and what it could not check — including
`unchecked`, meaning model-written prose that no judge adjudicated — and **no runtime code
read the table**. So such a pack installed, activated, and rendered its prose with
well-formed citation chips, and the only person who ever saw the warning was the operator
who ran the build.

`BuildReport.of` reads it, `install` returns it, and the CLI prints it. `unchecked` sorts
above `dropped` deliberately: a dropped item is *absent*, and absence announces itself — the
summary is not there, the control never appears. Unchecked content is **present and
indistinguishable from checked content**, which is the failure this product is built
against.

The gate cannot re-check this work; the app has no frontier judge and no source document.
Carrying the builder's own admission forward is the whole of what it can do, and it is
labelled as a claim rather than a check.

### 4.4 The gate checks shape, never origin — *stated*

`pack_meta.signature` is a `BLOB` written NULL and verified by nothing. Implementing
verification needs a distribution and key model, and pack distribution is one of the
questions still open — so the resolution here is the honest one rather than a guess at a
scheme: the boundary is now **written where it is read**, in `Packs`' own documentation and
in `00-pack-schema.md`.

Activation answers *is this a well-formed pack this build can read?* Every check it makes is
derivable from the bytes in hand, and none is evidence of authorship. A hostile pack that
satisfies all of them activates. What the gate buys is that such a pack cannot crash the
app, exhaust it before it can refuse, or launder invented prose into quotation styling
beneath a real citation — a great deal, and not the same as knowing where the book came
from. Saying so matters because *validated* reads as *trusted* to everyone who has not read
the validator.

### 4.5 One invariant, still implemented twice — the leftover

`:app` is the only module with **no tests at all**, because it is the only one needing an
Android test runner. Its Compose tree is now the last place a rendering decision lives
unchecked — the labels and marker placement come from `layout()`, but nothing verifies that
the gutter is drawn, that a `Quotation` block never reaches `ProseCard`, or that the roll
control is offered only where a table exists. Every UI bug this session was in that file.

**Fix: a Robolectric or instrumented test asserting the block-to-composable mapping**, which
is a small surface now that the decisions live elsewhere.

### 4.6 Assumptions no test enforces

Worth more to a reviewer than another green assertion:

- **Nothing has run on an Android device.** `BundledDb` is exercised on the JVM. The bundled
  driver ships both variants and the APK assembles, but "FTS5 works on a phone" is inferred,
  not observed.
- **No real weights.** The whole generative half — route 3, attribution validation, claim
  support — is tested against `HashingEmbedder` and hand-written judges. The structural
  tests prove the plumbing; they cannot prove the gate thresholds are calibrated.
- **Concurrency is designed but untested.** `StateDb` serializes one connection and
  `PackLibrary`'s leases are lock-guarded, and no test runs two threads.
- **`temp.withdrawn` is per-connection.** `ActivePack.exclusion` now checks
  `sqlite_temp_master` rather than trusting its own field, so a second `ActivePack` over one
  `Db` rebuilds the table instead of filtering against one that is not there. Written down
  because the failure it prevents is a filter failing *open* — corrected passages returned
  as answers, with nothing on screen saying so — and that is the one direction in this
  codebase where a bug is invisible rather than loud.

---

## Theory note

```
Theory:      A pack is an untrusted compilation of an authoritative book; every layer
             preserves the distinction between what the book says and what a machine
             said about it, and refusal is a correct answer.
Reused:      One validator for builder and app; one SQLite binding (Sqlite/BundledDb);
             one file digest (FileDigest) that the answer cache's key depends on.
New concept: `Card.layout()` — a card as Blocks named by provenance, so every surface
             paints the same decision instead of re-deciding it. `temp.withdrawn` —
             supersession as a relation rather than as query text, so a question's cost
             stops scaling with how many corrections were accepted.
Assumes:     The bundled SQLite behaves on-device as it does on the JVM; gate thresholds
             calibrated against a stand-in embedder transfer to real weights.
Cost:        One full scan per relation at preflight; one staged copy per direct-file CLI
             invocation; one indexed subquery plus one sqlite_temp_master lookup per
             gated query.
Watch:       §4.5 — :app has no tests, and its Compose tree is the last place a rendering
             decision lives unchecked. Every UI defect this session was in that one file.
```
