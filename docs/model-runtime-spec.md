# Model Runtime — Specification

The three models on the device, what each is allowed to do, and what the app does when
one is missing.

`android-app-design.md` §3 establishes the two lifecycle groups and the argument for
bundling the small models. This document pins the interfaces, the availability states,
the download contract, and the degradation behaviour each absence produces.

Implementation order: **3 of 8** — see the README for the full sequence and why it runs in this order.

Status: specified, unimplemented.

---

## 1. Three models, two lifecycles

| model | role | ships | size |
|---|---|---|---|
| embedder | text → vector, in a named space | **inside the app** | tens of MB, quantized |
| ASR | audio → text | **inside the app** | tens of MB, quantized |
| generative | text+image → text | **downloads on first run** | a few GB, 4-bit |

The split is not a packaging convenience. It is what makes this true:

> **Retrieval works before the big download finishes.** Install the app, install a pack,
> and rules lookups are fully functional — quoted, cited, correct — while the generative
> model is still downloading, or was never downloaded at all.

The app's core promise therefore survives its worst moment. A user on a metered
connection at a game table gets a working rules reference immediately, and the
multi-gigabyte download becomes the thing that unlocks *setting answers* rather than the
thing standing between them and the app working.

---

## 2. The embedder

### 2.1 Supporting a contract means bundling its weights

There is no way to embed a query in a space whose model you do not have. So **the set of
`embedder_id` values the app can serve is exactly the set shipped in its binary** —
enumerated, versioned with the app, and small. Adding one is an app release, not a pack
decision. A pack naming a contract that is not bundled is refused at activation rather
than retrieved against incorrectly.

```kotlin
interface Embedder {
    val contract: EmbedderContract      // id + dim, matching pack_meta
    fun embed(text: String): FloatArray // length == contract.dim
}

interface EmbedderRegistry {
    val bundled: Set<EmbedderContract>
    fun forContract(id: String): Embedder?
}
```

`EmbedderRegistry.bundled` is what `PackValidator` receives as its supported set. That
the validator takes it as a parameter rather than reading a global is the reason the
`:pack` module can stay honest about owning no weights.

### 2.2 Active packs may span contracts, and must

A pack built two years ago and one built today can both be valid and name different
embedders, and the older one must keep working — **packs are files people own, not
subscriptions.** One query embedding cannot serve both: the vectors live in different
spaces, and for differing `embedder_dim` the cosine is not even computable.

So the app groups active packs by contract and embeds the query once per distinct
contract. Each group is scored, gated, and fused in its own space
(`retrieval-spec.md` §6–7).

The cost is one embedder inference per distinct contract per query, which is the real
constraint keeping the supported set small — not tidiness.

### 2.3 Retiring a contract

Dropping an `embedder_id` strands every pack built against it. That is a genuine cost to
a user who cannot rebuild a pack they were given, and it is the argument for changing the
supported set rarely rather than for pretending one embedder will last forever.

A contract may only be removed in a release that can **name** what breaks: on upgrade, any
installed pack whose contract is gone is listed by title, kept installed, and marked
unusable with an explanation. Silently failing to activate it would look like corruption.

### 2.4 Determinism

The same text embeds to the same vector within a device and a build. Across devices,
small numeric variation from different hardware paths is tolerable and expected.

Nothing in the design depends on cross-device bit-identical embeddings: the answer cache
keys on query text rather than on vectors (`app-state-spec.md` §4), and the retrieval
regression suite measures recall@k on a fixed runtime rather than asserting scores.

---

## 3. Speech recognition

Voice input needs audio turned into text before anything else applies. The embedder takes
text and the generative model takes text and images, so without a dedicated component the
microphone button has nothing behind it.

```kotlin
interface Transcriber {
    fun transcribe(audio: AudioBuffer): String
}
```

### 3.1 Platform recognition is not an acceptable substitute unless it can be pinned

The platform's default path may route audio to a server, and **an app whose premise is
that nothing leaves the device cannot ship a microphone that quietly uploads what people
say at their table.**

If the platform can *guarantee* on-device recognition, using it is a legitimate
optimization. If it cannot — or if the guarantee is advisory, version-dependent, or
silently ignored — the bundled model is the only path. The test is whether offline
operation can be asserted, not whether it usually happens.

### 3.2 Voice must not require the generative model

Bundling ASR to keep voice available before the download would be pointless if the
transcript then had to pass through a model that is not there.

Generative normalization is an *improvement* to a voice query, not a precondition. The
transcript is already text, and deterministic normalization plus the alias rewrite handles
the failure mode that actually matters — a mangled proper noun, which the `entities` table
fixes by construction.

So voice degrades the way everything else does. With the generative model present, a
transcript gets the full normalization pass. Without it, the transcript goes to retrieval
deterministically and the query still finds the rule. What is lost is disfluency cleanup
and pronoun resolution on follow-ups, not the feature.

---

## 4. The generative model

### 4.1 Three jobs, and a closed interface

```kotlin
interface Generator {
    val availability: Availability

    /** Voice, camera, or a follow-up with an unresolved reference. */
    fun normalize(query: String, history: List<Turn>): String

    /** The part of the question the quote cards did not answer; null if they answered it. */
    fun residualIntent(query: String, covered: List<CardSummary>): String?

    /** Setting answers, over ('setting','source') chunks only. */
    fun answer(residual: String, context: List<RedactedChunk>): String

    /** Camera input. Produces a retrieval query and nothing else. */
    fun describeImage(image: ImageBuffer): String
}
```

**The interface is a closed set of jobs, not a generic `complete(prompt)`.** Each job has
its own prompt contract and its own guarantee — `answer` must never see verbatim-class
text, `describeImage` must never produce an answer — and a generic completion method
would let a fourth job appear without any of that being considered. This is the same
reasoning that closes the capability and constraint vocabularies.

`RedactedChunk` is the type carrying the redaction from `routing-and-cards-spec.md` §2.2.
That generation cannot be handed a raw chunk is enforced by the type, not by a comment.

### 4.2 None of the jobs is unconditional

| job | when it runs |
|---|---|
| `normalize` | voice or camera input, or a follow-up whose cheap check found an unresolved reference against a non-empty history |
| `residualIntent` | only when routes 1 and 3 both fire |
| `answer` | only over `('setting','source')` chunks — never verbatim-class, never derived |
| `describeImage` | camera queries only |

Derived chunks are absent from that table deliberately: their text was generated at build
time and renders as stored, costing no inference at all.

**A typed "grapple" during combat must not wake a multi-gigabyte model.** Running the
model on every query would contradict the guarantee — rules answers would invoke the model
after all — and would put every one-word lookup on the model's latency and thermal path. A
typed, self-contained rules question reaches retrieval having invoked no model at all,
which is what the guarantee actually claimed, and it is why the app survives a four-hour
session.

### 4.3 Availability

```kotlin
sealed interface Availability {
    object Ready : Availability
    object NotDownloaded : Availability
    data class Downloading(val fetched: Long, val total: Long) : Availability
    data class Failed(val reason: String) : Availability
}
```

Anything other than `Ready` produces the *model unavailable* card for route 3, and changes
nothing about routes 1 and 2. `Failed` shows its reason and offers a retry; it is distinct
from `NotDownloaded` because a user who already chose to download should not be asked to
choose again.

### 4.4 The download

At a few GB the model exceeds what can ship in an installable package.

- **Resumable.** A partial download survives app death, connectivity loss, and reboot.
- **An explicit Wi-Fi prompt**, with an honest size figure, and a clear statement that it
  happens once.
- **Cancellable**, with partial bytes discarded on cancel rather than left occupying
  storage the user cannot see.
- **Verified before use.** The expected SHA-256 of the weights is pinned in the app
  binary; a file that does not match is deleted and reported, never loaded.

That last point is not in the design and needs to be. This download is the app's only
network operation, and it is the one moment where several gigabytes of executable
behaviour arrive from outside. A truncated file, a captive-portal HTML page saved under
the model's name, or a substituted artifact would otherwise be loaded and run. Transport
security establishes who sent the bytes; the hash establishes that the bytes are the ones
this build was tested against, and only the second survives a compromised or
misconfigured mirror.

The app also states plainly, at the prompt, that this is the one time it uses the network
and what it is fetching. An app that claims to be offline should account for its single
exception rather than let a user discover it.

### 4.5 Residency

The generative model is loaded lazily on first use and released under memory pressure. It
is far too large to keep resident alongside everything else a phone is doing, and a
foreground app holding gigabytes is a foreground app that gets killed.

The embedder and ASR models stay resident. They are small, and reloading the embedder per
query would put a cold load on the path of the one operation that must stay fast.

**Cold generative load time is a tracked performance gate**, because it is what a user
experiences as the app hanging after they ask their first setting question of the session.

---

## 5. Degradation summary

The single table an implementer needs:

| absent | rules lookups | setting answers | voice | camera |
|---|---|---|---|---|
| generative model | **unaffected** | *model unavailable* card | works, without disfluency or pronoun cleanup | unavailable |
| ASR | unaffected | unaffected | microphone hidden | unaffected |
| embedder | *cannot occur* — bundled; packs naming an unbundled contract are refused at activation |

Camera input is unavailable rather than degraded when the generative model is absent,
because there is no non-model path from an image to a query. The control is hidden with an
explanation, not shown and then failing.

---

## 6. Testing

| Area | What it asserts |
|---|---|
| Contract registry | `bundled` matches the weights actually packaged — a test over the shipped assets, since a mismatch is invisible until a pack is refused wrongly |
| Contract grouping | packs with different embedders each get one inference per query, and no more |
| Conditional invocation | a typed self-contained query performs **zero** generative calls; asserted by a counting fake |
| Follow-up detection | the cheap check fires on pronouns and ellipsis against non-empty history, and not otherwise |
| Voice without generation | a transcript reaches retrieval and finds the rule with the generative model absent |
| Job isolation | `answer` receives only redacted `('setting','source')` chunks — enforced by type and asserted at the boundary |
| Availability transitions | each state produces the right card; `Failed` offers retry and `NotDownloaded` offers download |
| Download integrity | a truncated file, a wrong-hash file, and an HTML error page under the model's name are all rejected and deleted |
| Resume | a download interrupted at an arbitrary byte resumes to a byte-identical, hash-verified file |
| Residency | the generative model is released under simulated memory pressure and reloads correctly |
| Cold load | tracked as a performance gate, not a pass/fail assertion |

The zero-generative-calls test is the one that guards the central guarantee from the
performance side. It is easy to satisfy today and easy to lose to a refactor that makes
normalization unconditional for tidiness.

---

## 7. Deferred

- **Which specific models.** The embedder, ASR, and generative model identities are
  build-time choices constrained by size, licence, and quality. Nothing above depends on
  which is picked, and picking early would date the document.
- **Where the generative model is hosted.** Needs an operator decision and interacts with
  the pack distribution question, which is open on both sides.
- **Multiple bundled embedders.** The design permits it and the pipeline handles it; the
  first release ships one, and the second is what proves the grouping code was ever
  exercised.
