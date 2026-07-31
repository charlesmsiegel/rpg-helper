# UI Surfaces — Specification

What the app looks like, and the one visual decision everything upstream depends on.

`android-app-design.md` §7 names four surfaces and five card kinds, and says the visual
distance between the first two cards is the most important design decision in the app.
This document specifies what that distance must survive, and what each surface must
contain.

It does not specify an aesthetic. Colours, spacing, and motion are implementation
choices; the requirements below constrain them without picking them.

Implementation order: **6 of 8** — see the README for the full sequence and why it runs in this order.

Status: specified, unimplemented.

---

## 1. The distinction

> **If a user ever mistakes generated prose for book text, every guarantee upstream was
> wasted effort.**

The pack format's byte-exactness, the routing partition, the redaction, the claim-support
suite — all of it terminates in whether a person glancing at a phone at a noisy table can
tell a quotation from a paraphrase. That is a visual problem, and it is the last place the
guarantee can be lost.

### 1.1 Three independent signals, not one

The distinction is carried redundantly, because each individual signal has a condition
under which it disappears:

| signal | carries | fails when |
|---|---|---|
| **a text label on every card** | *Quoted from <book>, p. 42* / *Generated from 3 sources* | never |
| **typeface** | serif for quotations, the UI sans for everything generated | a user has forced a system font |
| **container** | quotations sit in a bounded block with a rule; generated prose is flush | at very large text scales, where chrome is squeezed |

**Colour is not on this list and may not be the primary carrier.** It disappears under
colour-vision deficiency, in grayscale screenshots, and in high-contrast modes, and it is
the signal designers reach for first.

The text label is the floor. It is the only signal that survives every failure mode
including all of the above at once, and it is what makes the requirement testable rather
than a matter of taste.

### 1.2 What it must survive

The distinction must hold, simultaneously:

- in **light and dark** themes
- at **every system text scale**, including the largest Android offers
- in **grayscale**, and under each common colour-vision deficiency
- with **animations disabled** and in high-contrast mode
- **in a screenshot** — which loses every interactive affordance and is how a rules
  argument actually gets settled in a group chat

The screenshot case is the demanding one and the most realistic. A distinction that
depends on tapping, hovering, or expanding is not a distinction at the moment it matters
most.

### 1.3 Screen readers announce the kind first

A card's accessibility label states what kind of card it is **before** its content:
*"Quotation, Core Rulebook page 42: To grapple a creature…"* against *"Generated answer
from 3 sources: The Underdark is…"*.

Announcing the text first and the provenance last would put the entire guarantee after
the part a listener stops attending to. This is not an accessibility afterthought bolted
onto a visual rule — for a user navigating by screen reader it *is* the rule, and it is
the same requirement expressed in the medium they use.

### 1.4 Quotations are never truncated

A quote body is never clipped with an ellipsis in the answer feed. It scrolls, or it
expands, or the card grows.

Truncating a rule mid-sentence produces a **different rule** on screen — one that may drop
the exception clause that changes its meaning, while still looking like an authoritative
quotation with a citation under it. That is the same failure the pack contract rejects
substring matching to prevent, arriving at the last possible moment.

Generated and derived prose may be truncated with an expand control. They are already
marked as not-authoritative, and no claim about completeness was made.

---

## 2. Surfaces

Three top-level destinations, plus one detail screen.

The design calls this "four surfaces", and the document view is the fourth — but it is a
detail of Documents rather than a peer, and presenting it as a fourth tab would mean
answering "which document?" before the user has chosen one.

### 2.1 Ask

The primary destination and the app's opening screen.

| element | contents |
|---|---|
| query bar | text field, microphone, camera |
| capability chips | what the active packs can do (`08-capabilities-spec.md` §5) |
| answer feed | the conversation, newest at the bottom |
| new topic | clears the feed, and therefore the context |

The feed **is** the conversational window (`01-app-state-spec.md` §3). One control clears
both, because a hidden context that silently changes what a question means is at odds with
an app whose pitch is that you can tell where its answers came from.

The microphone is hidden, with an explanation, when no on-device transcriber is available.
The camera is hidden when the generative model is absent, since there is no non-model path
from an image to a query. Controls that are visible and then fail teach users to distrust
every control.

### 2.2 Packs

| element | contents |
|---|---|
| installed list | title, version, size, active toggle |
| priority | drag to reorder; the order is total and visible |
| supersessions in effect | what each errata pack corrects, and in which book |
| install from file | the only install path that exists |
| storage used | three figures — packs, models, app data — per `01-app-state-spec.md` §7 |
| build report | read from the pack's own `build_report` table |

**Supersessions render from the citation snapshot** each row carries, so an errata pack
whose target is not installed still explains itself: *replaces Combat > Grappling, Other
Core Rulebook 1e, pp. 40–41*. Resolving through the target pack would leave the notice
blank precisely when it is most needed — a user reading a correction without owning the
book it corrects.

**The build report is a first-class listing, not a diagnostic panel.** It is what makes
"this table is quotable but not rollable" answerable on the device, rather than something
a user infers from a control that never appeared.

### 2.3 Documents

Characters and other documents, grouped by campaign, with *Unfiled* for those without one.

Each entry shows its validation state, and the three states are distinct because their
remedies are:

| state | meaning |
|---|---|
| **validated** | bound to a ruleset whose pack is installed and active |
| **unvalidated** | bound, but the pack is absent or inactive — install or activate it |
| **unbound** | no ruleset; trackers work and nothing is checked — bind it if you want checking |

A document with unaccepted violations shows a count. A draft shows a draft badge, because
minimum-bound violations are suppressed while it is one
(`07-documents-and-constraints-spec.md` §4.5) and a user should know that is why the sheet
looks clean.

### 2.4 Document view

| element | contents |
|---|---|
| trackers | in the user's order, grouped by key prefix |
| violations | inline against the tracker involved, with the rendered explanation |
| accepted | listed separately as deliberate deviations, with their notes |
| tap-through | from a violation to the passage that states the rule |

**Tap-through is the feature that makes constraints worth having.** A flag that says
*Physical attributes total 9, this game allows exactly 7* is useful; a flag that also
opens the paragraph on page 18 saying so is what settles the argument. It resolves through
the constraint's `chunk_id` into the same quote card the Ask surface would show.

Trackers are what people reach for mid-session, so editing one is a direct manipulation on
this screen — not a modal, not a separate edit mode.

---

## 3. Latency and offline-ness

Everything except the one model download is local. The app should never present a state
that looks like waiting on a network, because that is a mental model it would then have to
keep correcting.

- Retrieval is fast enough to feel immediate and is not given a spinner.
- Generation is slow and visibly incremental: tokens stream into the generated card, which
  is honest about what is happening and about which card is the slow one.
- The model download is the only progress bar that represents a network transfer, and it
  says so.

The app functions with the device in airplane mode, and nothing in the interface should
suggest otherwise.

---

## 4. Testing

| Area | What it asserts |
|---|---|
| Card labelling | every card kind renders its text label; property-tested over all five |
| Distinction under stress | verbatim and generated cards remain distinguishable at every text scale, both themes, and grayscale — screenshot tests, since this is exactly the kind of rule a layout refactor breaks silently |
| Colour independence | the distinction holds with colour removed |
| Screen reader order | the accessibility label states the card kind before its content |
| No quote truncation | a quote longer than the viewport is never ellipsised at any text scale |
| Renderer safety | markdown metacharacters in a quote body render literally (`05-routing-and-cards-spec.md` §3.1) |
| Hidden controls | microphone and camera are absent, with explanation, when their models are |
| Validation states | the three document states render distinctly and map to the right remedy |
| Supersession display | an errata pack whose target is absent still renders its snapshot citation |
| Airplane mode | every surface except the model download works fully offline |

The distinction-under-stress row is the one worth building infrastructure for. It is a
visual property that no unit test naturally covers, it is the terminal guarantee of the
entire design, and the most likely way to lose it is a well-intentioned change to a shared
card component.

---

## 5. Deferred

- **The aesthetic.** Palette, spacing scale, motion, iconography. Constrained by §1 and
  otherwise open.
- **Tracker display metadata.** How a tracker is grouped, labelled, or widgeted is not in
  the pack format, deliberately — packs shipping layout is a much larger commitment than
  packs shipping rules. Grouping by key prefix is the default until there is evidence it
  is insufficient.
- **Tablet and landscape layouts.** The design targets a phone at a table. Nothing above
  prevents a wider layout; nothing above assumes one.
- **Guided character creation.** A wizard over the constraint vocabulary, per §9 — after
  tracking ships.
