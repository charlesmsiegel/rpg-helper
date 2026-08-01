#!/usr/bin/env python3
"""SRD 5.2.1 PDF -> rpg-helper corpus.

Converts Wizards of the Coast's System Reference Document 5.2.1 (CC-BY-4.0) into the
corpus format the pack builder consumes: one plain-text source file plus a pack.json
locating every chunk by text anchors.

Why a converter rather than a hand-authored corpus: the SRD is ~360 pages, and the corpus
format was designed so that "swapping in a real open-licensed SRD is a change to this
directory and nothing else" (corpus/srd/pack.json). This is that change, automated,
because hand-chunking 360 pages is a transcription job with a thousand chances to drop a
paragraph -- and the builder's tiling check will catch a *declared* hole, not a paragraph
that never made it out of the PDF.

The layout facts this leans on (surveyed, not assumed):
  - two text columns per page, split at the horizontal midline;
  - a bold heading ladder by font size: 26 chapter / 18 / 15 / 14 / 12;
  - footer furniture ("System Reference Document 5.2.1 <n>") in the bottom band;
  - front matter (legal, contents, stat-block index) on pages 1-4, declared as a gap.

Anchors are the contract that keeps this honest. The builder refuses an anchor that
matches twice, so every from/to anchor emitted here is extended until it is unique in the
region the builder will search -- the whole document for `from`, the document at-or-after
`from` for `to`. An SRD edit that breaks that uniqueness breaks the build loudly, which is
the failure mode the format chose on purpose.

Usage:
  python3 tools/srd_convert.py <SRD_CC_v5.2.1.pdf> <output-corpus-dir>
"""

import json
import re
import sys
import unicodedata
from collections import Counter

import pdfplumber

FOOTER_BAND = 45          # points from the bottom edge that hold only page furniture
COLUMN_SPLIT_RATIO = 0.5  # the midline; the SRD's two columns never cross it
LINE_TOP_TOLERANCE = 3.0  # words within this vertical distance are one line
PARAGRAPH_GAP = 5.0       # vertical gap above which two lines are two paragraphs
FRONT_MATTER_PAGES = 4    # legal page, contents, index of stat blocks
MAX_CHUNK_CHARS = 4500    # retrieval granularity; long sections split at paragraphs

# The bold ladder. Ranges rather than exact values because embedded-font metrics wobble.
def heading_level(size, bold_fraction):
    if size >= 25:
        return 1
    if not bold_fraction >= 0.8:
        return None
    if size >= 16:
        return 2
    if size >= 14.5:
        return 3
    if size >= 13:
        return 4
    if size >= 11.5:
        return 5
    return None


def clean(text):
    # Soft hyphens and zero-width characters are PDF layout residue, not the book's words.
    text = text.replace("­", "").replace("​", "").replace("﻿", "")
    # Control characters would trip the pack's UTF-8 validation, and none are content.
    text = "".join(ch for ch in text if unicodedata.category(ch) != "Cc" or ch in "\n\t")
    return re.sub(r"[ \t]+", " ", text).strip()


def lines_of(page):
    """The page's text as (top, size, bold_fraction, text) lines, in reading order."""
    height, width = page.height, page.width
    words = [
        w for w in page.extract_words(extra_attrs=["size", "fontname"])
        if w["top"] < height - FOOTER_BAND
    ]
    columns = ([], [])
    for w in words:
        columns[0 if w["x0"] < width * COLUMN_SPLIT_RATIO else 1].append(w)

    out = []
    for column in columns:
        column.sort(key=lambda w: (w["top"], w["x0"]))
        current, top = [], None
        for w in column:
            if top is None or abs(w["top"] - top) <= LINE_TOP_TOLERANCE:
                current.append(w)
                top = w["top"] if top is None else top
            else:
                out.append(current)
                current, top = [w], w["top"]
        if current:
            out.append(current)

    lines = []
    for group in out:
        group.sort(key=lambda w: w["x0"])
        text = clean(" ".join(w["text"] for w in group))
        if not text:
            continue
        size = max(w["size"] for w in group)
        bold = sum(1 for w in group if "Bold" in w["fontname"] or "SemiBo" in w["fontname"])
        lines.append({
            "top": min(w["top"] for w in group),
            "bottom": max(w["bottom"] for w in group),
            "size": round(size, 1),
            "bold": bold / len(group),
            "text": text,
        })
    return lines


def slugify(text):
    slug = re.sub(r"[^a-z0-9]+", "-", text.lower()).strip("-")
    return slug[:60] or "section"


class Corpus:
    """Accumulates chunks and renders the source file with computed anchors."""

    def __init__(self):
        self.front_matter = []
        self.chunks = []          # {path: [..], kind, page_start, page_end, paragraphs: [..]}
        self.key_counts = Counter()

    def add_chunk(self, path, kind, page_start, page_end, heading_lines, paragraphs):
        text_parts = heading_lines + paragraphs
        if not any(p.strip() for p in text_parts):
            return
        self.chunks.append({
            "path": list(path),
            "kind": kind,
            "page_start": page_start,
            "page_end": page_end,
            "parts": text_parts,
        })

    def merge_undersized(self, minimum=250):
        # A very short section cannot carry a unique end anchor of its own, and is too
        # small to be worth a citation card anyway; it joins the section before it.
        merged = []
        for chunk in self.chunks:
            if merged and sum(len(p) for p in chunk["parts"]) < minimum:
                merged[-1]["parts"].extend(chunk["parts"])
                merged[-1]["page_end"] = chunk["page_end"]
            else:
                merged.append(chunk)
        self.chunks = merged

    def split_oversized(self):
        split = []
        for chunk in self.chunks:
            parts, budget, piece, index = chunk["parts"], 0, [], 1
            pieces = []
            for p in parts:
                if piece and budget + len(p) > MAX_CHUNK_CHARS:
                    pieces.append(piece)
                    piece, budget = [], 0
                piece.append(p)
                budget += len(p)
            if piece:
                pieces.append(piece)
            for i, piece_parts in enumerate(pieces, start=1):
                copy = dict(chunk)
                copy["parts"] = piece_parts
                copy["piece"] = i if len(pieces) > 1 else None
                split.append(copy)
        self.chunks = split

    def key_for(self, chunk):
        base = "srd:" + slugify(" ".join(chunk["path"][-2:]))
        if chunk.get("piece"):
            base += f"-p{chunk['piece']}"
        self.key_counts[base] += 1
        n = self.key_counts[base]
        return base if n == 1 else f"{base}-{n}"

    def render(self):
        """The document string, and per-chunk / gap character spans within it."""
        blocks = []
        front = "\n\n".join(p for p in self.front_matter if p.strip())
        cursor = 0
        front_span = (0, len(front))
        cursor = len(front)
        blocks.append(front)
        spans = []
        for chunk in self.chunks:
            text = "\n\n".join(chunk["parts"])
            start = cursor + 2  # the joiner
            spans.append((start, start + len(text)))
            cursor = start + len(text)
            blocks.append(text)
        return "\n\n".join(blocks), front_span, spans


def unique_from(doc, start, end):
    """A prefix starting at `start` that occurs exactly once in the whole document.

    The builder uses only the anchor's *position*; its length does not bound the span. So
    a start anchor may extend past the chunk's own end to disambiguate -- which is what
    rescues a chunk whose opening sentence recurs elsewhere in a 360-page book.
    """
    ceiling = min(len(doc) - start, max(end - start, 2000))
    for length in range(40, ceiling + 1, 20):
        needle = doc[start:start + length]
        if doc.count(needle) == 1:
            return needle
    raise SystemExit(f"cannot make a unique start anchor at {start}: {doc[start:start+60]!r}")


def unique_to(doc, from_index, start, end):
    """A suffix of doc[start:end] occurring exactly once at-or-after from_index."""
    region = doc[from_index:]
    for length in range(40, end - start + 1, 20):
        needle = doc[end - length:end]
        if region.count(needle) == 1:
            return needle
    needle = doc[start:end]
    if region.count(needle) == 1:
        return needle
    raise SystemExit(f"cannot make a unique end anchor at {end}: {doc[end-60:end]!r}")


def convert(pdf_path, out_dir):
    corpus = Corpus()
    attribution = None

    with pdfplumber.open(pdf_path) as pdf:
        heading_stack = []      # (level, text)
        pending_heading = []    # heading lines not yet attached to a chunk
        paragraphs = []         # body paragraphs of the open chunk
        open_pages = [None, None]
        open_path = []

        def close_chunk():
            # Emits only when there is body text. A heading-only chunk is unanchorable --
            # its whole text also appears in the contents pages -- so bare headings ride
            # forward and land at the top of the next real chunk, which keeps the tiling
            # exact and puts the words where a reader expects them anyway.
            nonlocal paragraphs, pending_heading, open_pages
            if paragraphs:
                chapter = open_path[0] if open_path else ""
                kind = "statblock" if "Monsters" in chapter and len(open_path) >= 2 else "rules"
                corpus.add_chunk(
                    open_path or ["Untitled"], kind,
                    open_pages[0], open_pages[1], pending_heading, paragraphs,
                )
                paragraphs, pending_heading = [], []
                open_pages = [None, None]

        for page_number, page in enumerate(pdf.pages, start=1):
            page_lines = lines_of(page)

            if page_number <= FRONT_MATTER_PAGES:
                for line in page_lines:
                    corpus.front_matter.append(line["text"])
                    if attribution is None and "This work includes material" in line["text"]:
                        attribution = line["text"]
                continue

            previous = None
            previous_level = None
            for line in page_lines:
                level = heading_level(line["size"], line["bold"])
                if level is not None:
                    # A wrapped heading arrives as consecutive lines of one level with no
                    # body between them; they are one title, not two sections.
                    if (level == previous_level and not paragraphs and heading_stack
                            and heading_stack[-1][0] == level):
                        merged = heading_stack[-1][1] + " " + line["text"]
                        heading_stack[-1] = (level, merged)
                        open_path[:] = [h[1] for h in heading_stack]
                        pending_heading[-1] = merged
                        previous = dict(line, page=page_number)
                        continue
                    close_chunk()
                    heading_stack[:] = [h for h in heading_stack if h[0] < level]
                    heading_stack.append((level, line["text"]))
                    open_path[:] = [h[1] for h in heading_stack]
                    pending_heading.append(line["text"])
                    open_pages = [page_number, page_number]
                else:
                    joined = False
                    if (paragraphs and previous is not None
                            and previous["page"] == page_number
                            and line["top"] - previous["bottom"] < PARAGRAPH_GAP):
                        # De-hyphenate a line-break split: "con-" + "denses" is one word
                        # the layout broke, and a quote card should not preserve the
                        # accident. Only when the continuation is lowercase -- "two-" +
                        # "handed" across a break stays a real compound often enough that
                        # guessing the other way mangles rules text.
                        head = paragraphs[-1]
                        if head.endswith("-") and line["text"][:1].islower():
                            paragraphs[-1] = head[:-1] + line["text"]
                        else:
                            paragraphs[-1] = head + " " + line["text"]
                        joined = True
                    if not joined:
                        paragraphs.append(line["text"])
                    if open_pages[0] is None:
                        open_pages = [page_number, page_number]
                    open_pages[1] = page_number
                previous = dict(line, page=page_number)
                previous_level = level
        close_chunk()

    corpus.merge_undersized()
    corpus.split_oversized()
    doc, front_span, spans = corpus.render()

    source_file = "sources/srd.txt"
    chunks_json = []
    for chunk, (start, end) in zip(corpus.chunks, spans):
        from_anchor = unique_from(doc, start, end)
        to_anchor = unique_to(doc, start, start, end)
        chunks_json.append({
            "stable_key": corpus.key_for(chunk),
            "kind": chunk["kind"],
            "heading_path": " > ".join(chunk["path"]),
            "page_label_start": str(chunk["page_start"]),
            "page_label_end": str(chunk["page_end"]),
            "from": from_anchor,
            "to": to_anchor,
        })

    gap_from = unique_from(doc, front_span[0], front_span[1])
    gap_to = unique_to(doc, front_span[0], front_span[0], front_span[1])

    pack = {
        "_comment": [
            "Generated by tools/srd_convert.py from SRD_CC_v5.2.1.pdf. Do not hand-edit;",
            "regenerate. The SRD text is (c) Wizards of the Coast LLC, released under the",
            "Creative Commons Attribution 4.0 International licence.",
        ],
        "pack_uid": "wotc:srd:5.2",
        "pack_version": "5.2.1",
        "title": "D&D System Reference Document 5.2.1",
        "ruleset_id": "dnd-5.2",
        "license_id": "CC-BY-4.0",
        "attribution": attribution or (
            "This work includes material from the System Reference Document 5.2.1 "
            "by Wizards of the Coast LLC, available at https://www.dndbeyond.com/srd, "
            "licensed under the Creative Commons Attribution 4.0 International License."
        ),
        "built_at": "2026-08-01T00:00:00Z",
        "builder_version": "srd-convert/1.0.0",
        "sources": [{
            "source_uid": "wotc:srd:5.2:core",
            "file": source_file,
            "title": "System Reference Document",
            "edition": "5.2.1",
            "publisher": "Wizards of the Coast",
            "locator_scheme": "page",
            "page_labels": [{
                "seq": 0, "phys_start": 1, "phys_end": 364,
                "scheme": "decimal", "start_value": 1,
            }],
            "gaps": [{"reason": "front-matter", "from": gap_from, "to": gap_to}],
            "chunks": chunks_json,
        }],
    }

    import pathlib
    out = pathlib.Path(out_dir)
    (out / "sources").mkdir(parents=True, exist_ok=True)
    (out / source_file).write_text(doc + "\n", encoding="utf-8")
    (out / "pack.json").write_text(
        json.dumps(pack, indent=2, ensure_ascii=False) + "\n", encoding="utf-8",
    )
    print(f"chunks: {len(chunks_json)}  document: {len(doc)} chars")
    by_kind = Counter(c["kind"] for c in chunks_json)
    print("kinds:", dict(by_kind))


if __name__ == "__main__":
    convert(sys.argv[1], sys.argv[2])
