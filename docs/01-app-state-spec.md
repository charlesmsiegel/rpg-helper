# App State and Storage — Specification

Everything the app owns, as opposed to everything a pack ships.

Packs are read-only files (`00-pack-schema.md`). This document specifies the database the
app writes to: which packs are installed and active, the documents and trackers from
`07-documents-and-constraints-spec.md`, model download state, and the conversational
window.

Implementation order: **1 of 8** — see the README for the full sequence and why it runs in this order.

Status: specified, unimplemented.

---

## 1. The two databases, and why they stay apart

A pack is a file the user owns, produced elsewhere, verified once, and never written to.
The app's state is mutable, local, and version-migrated on every release. Putting them in
one file would mean either rewriting a pack to record that it was activated — destroying
the property that makes a pack verifiable — or migrating a file the app did not author.

So: one app database, plus N pack files opened read-only. Nothing is `ATTACH`ed for
writing, and no app table lives inside a pack.

### On-disk layout

```
<app-private>/
  state.db                 the database specified here
  packs/
    <install_id>.rpgpack   installed packs, byte-identical to what was imported
  models/
    <model_id>/            downloaded model weights
```

**`pack_uid` never appears in a path.** It is a `TEXT` column in a file a third party
built, with no validated grammar, so a pack declaring a uid of `../packs/<other-uid>` or
`../../state.db` would direct the install's final move at a file of the attacker's
choosing — overwriting another pack, or the app's own database, while the row recorded it
under the attacker's uid. The filename is `install_id`, an integer the app assigns, and
nothing derived from pack content is ever concatenated into a path.

`pack_uid` is still validated on activation — non-empty, and within a length bound — but
that validation is not what makes this safe. Not using it is.

All of it is app-private storage. Nothing the app writes is world-readable, and nothing
lives on external storage where another app could modify a pack the validator has already
approved.

### Packs are copied in, not referenced

Installation copies the file into `packs/`. Referencing a user-chosen location in place
would be cheaper and is wrong: **validation is a statement about a sequence of bytes**,
and a file the app does not control can be edited, replaced, or removed after it passes.
An activation gate that can be invalidated after the fact by anything other than the app
is not a gate.

Install is atomic: copy to a temporary name, run the full activation validation
(`00-pack-schema.md` §7), and only then move into place. A failed validation leaves nothing
behind and reports every violation.

**The file move and the database row must land together.** A rename is atomic for the
file alone; it is not atomic with inserting or updating `installed_packs`. A process
killed between the two leaves either a row describing a version, title, and size that no
longer match the bytes retrieval will open, or a row pointing at a file that was never
completed.

So installation is journalled:

1. Insert the row with `state = 'installing'` and its assigned `install_id`, committed.
2. Copy, validate, digest, and move the file into `packs/<install_id>.rpgpack`.
3. Update the row to `state = 'ready'` with the digest and metadata, committed.

**On startup the app reconciles**: every `installing` row is deleted along with any file
under its `install_id`, and every file with no matching `ready` row is deleted. Both
directions, because a crash can leave either. The reconciliation is idempotent and runs
before any pack is opened.

### Corruption after install is caught by a digest, not by the cheap checks

An earlier version of this section claimed the open-time subset — schema version, probe
vector, embedder contract — was sufficient because those bytes "cannot have changed since
install". That argument is wrong, and the failure it admits is the worst one in the app.

A bit flip in `chunks.text` leaves the schema version, the probe vector, and every
declared dimension untouched. The pack opens, validates, and **quotes mutated text as
byte-exact**. Flash storage does rot, over the years a character sheet is meant to
outlive its software.

So installation records the **SHA-256 of the whole pack file**, computed once after
validation succeeds, in `installed_packs.file_sha256`.

- At **open**, the cheap subset runs. It is fast and catches gross damage immediately.
- **Full digest verification runs in the background**, not on the open path: after the
  first launch following an install, and periodically thereafter for any pack not
  verified within the verification interval.
- A pack whose digest no longer matches is **deactivated**, not silently repaired, and
  the user is told to re-import it. Its documents are untouched, exactly as for an
  uninstall.

Hashing a hundred megabytes on every launch, for every pack, would put seconds on cold
start — and a check that makes the app feel broken is a check that gets removed. Moving
it off the open path is what makes it survivable enough to keep.

### One version of a pack at a time

`pack_uid` is the primary key of `installed_packs`. Installing a pack whose uid is already
present **replaces** it, after validating the incoming file, and preserves its activation
state and priority.

Two versions of one book installed at once would compete in retrieval as if they were
different sources, producing duplicate quotes with different page numbers and no way for
a user to tell which is current. Supersession exists for corrections; parallel versions
are not a feature.

---

## 2. Schema

```sql
PRAGMA user_version = 1;

-- Installed packs -------------------------------------------------------------

CREATE TABLE installed_packs (
    install_id   INTEGER PRIMARY KEY,
    pack_uid     TEXT    NOT NULL UNIQUE,
    pack_version TEXT    NOT NULL,
    title        TEXT    NOT NULL,
    ruleset_id   TEXT,
    embedder_id  TEXT    NOT NULL,
    byte_size    INTEGER NOT NULL,
    file_sha256  TEXT,
    state        TEXT    NOT NULL CHECK (state IN ('installing', 'ready')),
    installed_at TEXT    NOT NULL,
    verified_at  TEXT,
    active       INTEGER NOT NULL DEFAULT 0,
    priority     INTEGER NOT NULL
);

CREATE UNIQUE INDEX idx_packs_priority ON installed_packs(priority);

-- Documents -------------------------------------------------------------------

CREATE TABLE documents (
    document_id INTEGER PRIMARY KEY,
    title       TEXT    NOT NULL,
    campaign    TEXT,
    ruleset_id  TEXT,
    draft       INTEGER NOT NULL DEFAULT 1,
    extensions  TEXT,
    created_at  TEXT    NOT NULL,
    updated_at  TEXT    NOT NULL
);

CREATE TABLE trackers (
    document_id  INTEGER NOT NULL REFERENCES documents(document_id) ON DELETE CASCADE,
    key          TEXT    NOT NULL,
    type         TEXT    NOT NULL CHECK (type IN ('number', 'flag', 'text')),
    number_value REAL,
    flag_value   INTEGER,
    text_value   TEXT,
    ordinal      INTEGER NOT NULL,
    PRIMARY KEY (document_id, key),
    CHECK ((type = 'number') = (number_value IS NOT NULL)),
    CHECK ((type = 'flag')   = (flag_value   IS NOT NULL)),
    CHECK ((type = 'text')   = (text_value   IS NOT NULL))
);

CREATE TABLE accepted_violations (
    document_id INTEGER NOT NULL REFERENCES documents(document_id) ON DELETE CASCADE,
    fingerprint TEXT    NOT NULL,
    ruleset_id  TEXT    NOT NULL,
    note        TEXT,
    accepted_at TEXT    NOT NULL,
    PRIMARY KEY (document_id, fingerprint)
);

-- Conversation ----------------------------------------------------------------

CREATE TABLE conversation_turns (
    turn_id  INTEGER PRIMARY KEY,
    asked_at TEXT    NOT NULL,
    query    TEXT    NOT NULL,
    cards    TEXT    NOT NULL
);

-- Generated answer cache ------------------------------------------------------

CREATE TABLE answer_cache (
    cache_key    TEXT PRIMARY KEY,
    card         TEXT NOT NULL,
    created_at   TEXT NOT NULL,
    last_used_at TEXT NOT NULL
);

-- Models ----------------------------------------------------------------------

CREATE TABLE models (
    model_id      TEXT PRIMARY KEY,
    role          TEXT    NOT NULL,
    state         TEXT    NOT NULL,
    bytes_total   INTEGER,
    bytes_fetched INTEGER,
    updated_at    TEXT    NOT NULL
);
```

### Every connection sets its pragmas

```sql
PRAGMA foreign_keys = ON;
```

**SQLite disables foreign-key enforcement by default, per connection.** Without this the
`ON DELETE CASCADE` clauses above are decoration: deleting a document leaves its trackers
and accepted violations behind forever, and nothing reports it.

This is the same trap the pack validator documents from the other direction — a pack's
`REFERENCES` clauses guarantee nothing about rows inserted while enforcement was off.
Here the app owns the database and *can* rely on the constraints, but only if it turns
them on, on every connection, including the ones a migration opens.

### Notes on choices that are not obvious

**Trackers use typed columns, not one value column.** `sum_range` sums numeric trackers;
a single TEXT column would make that a scan-and-parse, and would let a `flag` silently
sum as text. Three nullable columns with a `type` discriminator keeps the arithmetic in
SQLite.

The `CHECK` clauses are what make that a schema violation rather than a runtime surprise —
without them SQLite accepts an unknown `type`, a `number` carrying only `text_value`, or
several populated value columns at once, after which constraint evaluation silently reads
NULL or the wrong representation. Unlike a pack's DDL, this one is written and created by
the app, so its constraints are worth something.

**`accepted_violations.ruleset_id` is stored as well as hashed.** The fingerprint already
covers the ruleset (`07-documents-and-constraints-spec.md` §4.7), which is what stops an
acceptance from one game suppressing an identical generic predicate in another after a
rebinding. The column exists so the Documents surface can list a document's deliberate
deviations by game without reversing a hash.

**`documents.extensions` holds unknown top-level keys from an imported `.rpgdoc`.** §6 of
the documents spec promises they survive a round trip, and a promise kept only in the
importer's transient object is broken by the next app restart.

**`trackers.ordinal` is display order.** Constraints never read it. It exists because a
character sheet has an order the user chose and losing it on every edit is the kind of
small indignity that makes an app feel careless.

**`installed_packs.priority` is a unique ordinal, not a score.** Priority breaks genuine
ties and decides which book leads when two unrelated sources cover a topic
(`android-app-design.md` §4). Ties in the tie-breaker are not useful, so reordering
rewrites the affected ordinals in one transaction.

**There is no `entitlements` table.** §7 of the design lists entitlements among app
state, but pack distribution and entitlement are open questions that depend on the
signing scheme, which is open on the builder side too. Install-from-file — the only
settled path — needs no entitlement record. Shipping an empty table now would be
committing to a shape before the mechanism it serves exists, and migrating away from it
later costs more than adding it once there is something to store.

**There is no `recent_queries` table either**, though §7 lists one. It would duplicate
`conversation_turns`, which already holds every query with its answer. Recent queries are
a read over that table, not a second copy that can disagree with it.

---

## 3. The conversational window is the answer feed

`android-app-design.md` §10 leaves open *"whether the conversational window is
user-visible and clearable, or purely internal."*

**It is the feed.** There is no second, hidden context.

The window matters because follow-up normalization uses it: *"what about at level 5?"*
only resolves against the last few turns. An invisible memory that silently changes what
a question means is at odds with an app whose entire pitch is that you can tell where its
answers came from. When resolution goes wrong — and pronoun resolution does go wrong —
the user needs to be able to see the thing that caused it, and the cheapest way to
guarantee that is for the thing to be the screen they are already looking at.

So:

- `conversation_turns` holds the visible feed, in order.
- Normalization reads at most the **last three turns**, matching §4's "last two or three
  turns". A bounded window keeps a four-hour session from growing an unbounded prompt.
- **New topic** clears the feed and therefore clears the context. One control, one
  meaning, nothing hidden.
- The feed persists across app restarts, so backgrounding the app mid-lookup at a table
  does not lose the thread.

**Persisted cards are history, not live content.** A quote card already on screen stays
there after its chunk is superseded or its pack uninstalled — the feed records what the
app said at the time it said it. That does not weaken the supersession guarantee, which is
about what retrieval can *reach*: no new answer will contain the superseded text, and
scrolling back to an old one shows what was true when it was asked.

Two things follow. A card whose pack is no longer active is marked as such rather than
re-resolved, since its citation can no longer be opened. And serialising a quote card must
round-trip its text byte-for-byte, because the feed is now a second path by which a
quotation reaches the screen — the verbatim-identity test covers rendering from the pack,
and has to cover this too.

The alternative — an internal window with its own lifetime — would need its own clear
control, its own explanation, and would still leave a user unable to answer "why did it
think I meant that?"

---

## 4. Caching generated answers

Also open in §10: *"whether generated setting answers are cached, and what invalidates the
cache when pack activation or priority changes."*

**Cached, keyed by everything that could change the answer.** On-device generation is the
slowest and most power-hungry thing the app does, and repeated questions at a table are
normal.

The key is a hash over:

- **the query retrieval actually ran** — after deterministic folding, the alias rewrite,
  *and* any generative rewrite (`04-retrieval-spec.md` §3.2)
- the **active-set fingerprint**: the ordered list of `(pack_uid, file_sha256)` for every
  active pack, in priority order
- the generative model's identity and quantization

Both of the first two are more specific than they first appear, and each was wrong in an
earlier version of this section.

**The key is the rewritten query, not the typed one.** A follow-up is resolved against the
conversation window before retrieval sees it, so *"what about at level 5?"* means
different things after a grappling question and after a stealth question. Keying on the
pre-rewrite text hashes both to the same entry, and the second user is served the first
conversation's card — a wrong answer with well-formed citations, indistinguishable from a
fresh generation. Using the query that was actually run also covers voice and camera
input, which reach retrieval only after a rewrite.

**The active set is fingerprinted by content, not by declared version.** Installing a pack
whose `pack_uid` already exists replaces it (§1), and nothing requires the replacement to
declare a new `pack_version` — a corrected rebuild may keep it. `file_sha256` changes
whenever the bytes do, which is the property the cache actually needs.

With both, **there is no invalidation logic**. Activate a pack, deactivate one, reorder
priority, replace a book with a corrected rebuild, ask a differently-resolved follow-up,
or change models, and the key changes — old entries stop being reachable rather than
needing to be found and deleted. Invalidation logic is where cache bugs live, and the
surest way to have none is to have nothing to invalidate.

That includes the case that would otherwise be subtle: installing an errata pack changes
the active set, so answers generated before the correction cannot be served after it.
Supersession is a function of the active set, so the fingerprint already covers it.

Entries store the rendered card **with its citations**, so a cache hit and a fresh
generation are indistinguishable on screen. The cache is LRU-bounded by entry count and
total bytes, and is clearable from settings. Only generated cards are cached; quote cards
are a database read and caching them would be slower than not.

---

## 5. Backup

**Android auto-backup is disabled for this app.**

It is on by default and would upload the app's private data — every character, every
tracker, every query — to the user's Google Drive. That is a direct contradiction of the
premise: no account, no server, nothing leaves the device. An app that says so on its
first screen and then quietly syncs a character sheet to a cloud provider has broken the
one promise a user cannot verify for themselves.

The sanctioned path off the device is export (`07-documents-and-constraints-spec.md` §6):
explicit, per-document, and a file the user places where they choose.

This costs something real — a user who loses their phone loses their characters unless
they exported. That is the honest trade, it is stated plainly in the app rather than
buried, and the remedy is one tap per document. The alternative is a privacy claim that
is false.

Packs are not backed up either, and need not be: they are files the user already has, and
re-importing one is the same operation as importing it the first time.

---

## 6. Migrations

The rule from `07-documents-and-constraints-spec.md` §2 governs here too: a character sheet
outlives the software that checks it.

- Migrations are **forward-only**, numbered, and applied in order inside one transaction
  per version. A failed migration rolls back and the app reports it rather than starting
  with a half-migrated database.
- Before any migration runs, the app writes a **copy of `state.db`** alongside it. A
  migration that corrupts data is a bug that ships occasionally; a migration that
  corrupts data with no way back is a bug that ends the app's usefulness.
- **No migration may drop or narrow a column holding document data.** Widening, adding,
  and backfilling are permitted. Removing a document-bearing column requires exporting it
  into its replacement first, in the same migration.
- Pack-derived state — activation, priority, cached answers — may be dropped freely. It
  is all reconstructible from the pack files and the user's choices.

Every migration ships with a test that runs it against a fixture database populated at
the *previous* version and asserts every document and tracker survives byte-identically.
That fixture is committed, not generated, so the test keeps testing the real historical
shape rather than whatever the current code would produce.

---

## 7. Storage accounting

The Packs surface reports storage used. It reports three figures separately, because they
have different remedies:

| figure | source | how the user reduces it |
|---|---|---|
| packs | sum of `installed_packs.byte_size` | uninstall a pack |
| models | size of `models/` | delete the generative model; retrieval keeps working |
| app data | size of `state.db` plus the cache | clear the answer cache |

Verification state is visible here too: a pack whose background digest check has not run
since install is marked as pending rather than silently unverified, and one that failed is
deactivated with an explanation (§1).

A single total would tell a user their library is large without telling them that most of
it is one model they can delete and re-download, or a cache that costs nothing to clear.

**Uninstalling a pack never touches a document.** Its constraints stop loading and bound
documents read as unvalidated. This is the guarantee from §5 of the documents spec, and
it is stated again here because uninstall is where it would actually get broken.

---

## 8. Testing

| Area | What it asserts |
|---|---|
| Install atomicity | a pack failing validation leaves no file and no row; an interrupted copy leaves no partial install |
| Reinstall | same `pack_uid` replaces, preserving activation and priority; a failed reinstall leaves the previous version intact and active |
| Open-time checks | corrupting an installed pack's probe vector is caught at open |
| Priority | reordering keeps ordinals unique and total; no two packs ever share one |
| Cache keying | changing activation, priority, pack version, or model all change the key; asking the same question twice does not |
| Cache correctness | a cached card and a freshly generated one render identically, citations included |
| Conversation window | normalization sees at most three turns; New topic empties the feed and the context together |
| Migrations | per-version fixture databases; every document and tracker survives byte-identically |
| Uninstall | documents byte-identical before and after; reinstall restores validation and acceptances exactly |
| Backup opt-out | the manifest disables auto-backup — asserted by a test, since this is one flag away from silently regressing |
| Foreign keys | deleting a document removes its trackers and acceptances, on a connection opened the way the app opens them |
| Tracker discriminator | an unknown `type`, a mismatched value column, and two populated value columns are all rejected by the schema |
| Install reconciliation | a crash simulated between each pair of install steps leaves no `installing` row and no orphan file, in either direction |
| Digest verification | flipping one byte of an installed pack's `chunks.text` is caught by the background check and deactivates the pack |
| Cache keying, follow-ups | the same elliptical follow-up after two different conversations produces two different keys |
| Cache keying, rebuilds | a replacement pack with an unchanged `pack_version` but different bytes produces a different key |
| Extensions round-trip | unknown top-level keys survive import, an app restart, and re-export |

That last row is deliberately a test rather than a review checklist item. It is a single
boolean in a manifest, its default is the wrong value, and nothing about the app's
behaviour changes visibly if it regresses.

---

## 9. Deferred

- **Entitlements and pack distribution.** Blocked on the signing scheme, open on both
  sides. Install-from-file needs nothing.
- **Multi-device document movement beyond files.** Export is the mechanism; sync is a
  non-goal, not a gap.
- **Per-campaign pack profiles** — activating a set of packs together when switching
  games. Plausibly useful, entirely additive, and not needed to ship.
