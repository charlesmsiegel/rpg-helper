package dev.rpghelper.pack

/**
 * The pack contract, as the app understands it.
 *
 * [DDL] is normative. `docs/00-pack-schema.md` embeds this same text, and
 * `SchemaDocSyncTest` fails if the two drift -- so the prose spec cannot quietly
 * describe a format the code does not implement.
 */
object PackSchema {

    /**
     * Bumped whenever the DDL, the vector layout, the probe constant, or the dice
     * grammar changes in a way an older app would mis-read. The app refuses any pack
     * whose version it does not recognise; it never attempts a best-effort read,
     * because the failure mode of guessing at a layout is confident nonsense rather
     * than an error.
     */
    const val SCHEMA_VERSION: Int = 1

    /**
     * Longest `pack_uid` the format accepts.
     *
     * Generous for any real identifier, and bounded so an unbounded string cannot bloat
     * the app's own install record.
     */
    const val MAX_PACK_UID_LENGTH: Int = 200

    /** `chunks.kind`. */
    val KINDS: Set<String> =
        setOf("rules", "table", "statblock", "readaloud", "glossary", "setting")

    /**
     * The pinned FTS5 tokenizer, named so activation can check it rather than assume it.
     *
     * The app tokenizes queries this way, so an index built any other way folds terms
     * differently — and the divergence is silent, appearing only for the words where the
     * two disagree.
     */
    const val TOKENIZER: String = "unicode61 remove_diacritics 2"

    /** `chunks.origin`. */
    val ORIGINS: Set<String> = setOf("source", "derived")

    /**
     * Kinds that *may* be quoted. Necessary but not sufficient: a chunk is
     * verbatim-class only when its kind is in this set AND its origin is `source`.
     * A builder-written statblock summary is `('statblock', 'derived')` and renders
     * as attributed prose, not as a quotation.
     */
    val VERBATIM_ELIGIBLE_KINDS: Set<String> =
        setOf("rules", "table", "statblock", "readaloud", "glossary")

    /** `vectors.role`. */
    val VECTOR_ROLES: Set<String> = setOf("content", "expansion")

    /** `source_gaps.reason`. */
    val GAP_REASONS: Set<String> =
        setOf("front-matter", "legal", "index", "advertising", "art-only", "other")

    /** `sources.locator_scheme`. */
    val LOCATOR_SCHEMES: Set<String> =
        setOf("page", "panel", "card", "sheet", "section", "position")

    /** `source_page_labels.scheme`. */
    val PAGE_LABEL_SCHEMES: Set<String> =
        setOf("decimal", "roman-lower", "roman-upper", "none")

    /**
     * Every table an activation-eligible pack must contain. A pack missing one is
     * refused before any other check runs: the remaining checks all read from these,
     * and a cascade of confusing secondary failures is worse than one clear refusal.
     */
    val REQUIRED_TABLES: Set<String> = setOf(
        "pack_meta",
        "sources",
        "source_page_labels",
        "chunks",
        "chunks_fts",
        "vectors",
        "entities",
        "tables",
        "table_rows",
        "capabilities",
        "chunk_derivation",
        "source_gaps",
        "constraints",
        "supersessions",
        "build_report",
    )

    /**
     * Byte size of one vector element. Pinned by the format: IEEE 754 binary16,
     * little-endian, contiguous, one vector per BLOB.
     */
    const val VECTOR_ELEMENT_BYTES: Int = 2

    /**
     * A vector whose L2 norm falls at or below this is treated as unusable. Cosine
     * similarity divides by the norm, so a zero vector is not a weak signal, it is an
     * undefined one -- and it is the ordinary result of an embedding call that failed
     * and went unchecked.
     */
    const val MIN_VECTOR_NORM: Double = 1e-6

    /**
     * Canonical DDL. Note that the app validates every property this DDL declares
     * rather than trusting it: a pack is a file someone else built, and its CHECK
     * constraints are whatever its builder chose to write. Reading the DDL as a
     * guarantee would mean trusting the very thing under inspection.
     */
    val DDL: String = """
        PRAGMA user_version = 1;

        CREATE TABLE pack_meta (
            id              INTEGER PRIMARY KEY CHECK (id = 1),
            schema_version  INTEGER NOT NULL,
            pack_uid        TEXT    NOT NULL,
            pack_version    TEXT    NOT NULL,
            title           TEXT    NOT NULL,
            ruleset_id      TEXT,
            embedder_id     TEXT    NOT NULL,
            embedder_dim    INTEGER NOT NULL,
            probe_vector    BLOB    NOT NULL,
            license_id      TEXT    NOT NULL,
            license_text    TEXT,
            attribution     TEXT,
            built_at        TEXT    NOT NULL,
            builder_version TEXT    NOT NULL,
            signature       BLOB
        );

        CREATE TABLE sources (
            source_id      INTEGER PRIMARY KEY,
            source_uid     TEXT    NOT NULL UNIQUE,
            title          TEXT    NOT NULL,
            edition        TEXT,
            publisher      TEXT,
            text_sha256    TEXT    NOT NULL,
            locator_scheme TEXT    NOT NULL
        );

        CREATE TABLE source_page_labels (
            source_id   INTEGER NOT NULL REFERENCES sources(source_id),
            seq         INTEGER NOT NULL,
            phys_start  INTEGER NOT NULL,
            phys_end    INTEGER NOT NULL,
            scheme      TEXT    NOT NULL,
            start_value INTEGER,
            prefix      TEXT,
            PRIMARY KEY (source_id, seq)
        );

        CREATE TABLE chunks (
            chunk_id         INTEGER PRIMARY KEY,
            kind             TEXT    NOT NULL,
            origin           TEXT    NOT NULL,
            text             TEXT    NOT NULL,
            source_id        INTEGER REFERENCES sources(source_id),
            heading_path     TEXT,
            page_label_start TEXT,
            page_label_end   TEXT,
            span_start       INTEGER,
            span_end         INTEGER,
            stable_key       TEXT,
            parent_chunk_id  INTEGER REFERENCES chunks(chunk_id)
        );

        CREATE VIRTUAL TABLE chunks_fts USING fts5(
            text,
            heading_path,
            content='chunks',
            content_rowid='chunk_id',
            tokenize='unicode61 remove_diacritics 2'
        );

        CREATE TABLE vectors (
            chunk_id       INTEGER NOT NULL REFERENCES chunks(chunk_id),
            role           TEXT    NOT NULL,
            subchunk_index INTEGER NOT NULL,
            embedding      BLOB    NOT NULL,
            window_start   INTEGER,
            window_end     INTEGER,
            PRIMARY KEY (chunk_id, role, subchunk_index)
        );

        CREATE TABLE entities (
            entity_id INTEGER PRIMARY KEY,
            canonical TEXT    NOT NULL,
            alias     TEXT    NOT NULL,
            kind      TEXT    NOT NULL,
            chunk_id  INTEGER REFERENCES chunks(chunk_id)
        );

        CREATE TABLE tables (
            table_id  INTEGER PRIMARY KEY,
            chunk_id  INTEGER NOT NULL REFERENCES chunks(chunk_id),
            dice_expr TEXT    NOT NULL
        );

        CREATE TABLE table_rows (
            table_id   INTEGER NOT NULL REFERENCES tables(table_id),
            seq        INTEGER NOT NULL,
            lo         INTEGER NOT NULL,
            hi         INTEGER NOT NULL,
            span_start INTEGER NOT NULL,
            span_end   INTEGER NOT NULL,
            text       TEXT    NOT NULL,
            PRIMARY KEY (table_id, seq)
        );

        CREATE TABLE capabilities (
            capability_id INTEGER PRIMARY KEY,
            kind          TEXT    NOT NULL,
            chunk_id      INTEGER NOT NULL REFERENCES chunks(chunk_id),
            manifest      TEXT    NOT NULL
        );

        CREATE TABLE chunk_derivation (
            derivation_id    INTEGER PRIMARY KEY,
            derived_chunk_id INTEGER NOT NULL REFERENCES chunks(chunk_id),
            source_chunk_id  INTEGER NOT NULL REFERENCES chunks(chunk_id),
            claim_span_start INTEGER,
            claim_span_end   INTEGER
        );

        CREATE TABLE source_gaps (
            gap_id     INTEGER PRIMARY KEY,
            source_id  INTEGER NOT NULL REFERENCES sources(source_id),
            span_start INTEGER NOT NULL,
            span_end   INTEGER NOT NULL,
            reason     TEXT    NOT NULL,
            note       TEXT
        );

        CREATE TABLE constraints (
            constraint_id INTEGER PRIMARY KEY,
            form          TEXT    NOT NULL,
            args          TEXT    NOT NULL,
            chunk_id      INTEGER NOT NULL REFERENCES chunks(chunk_id)
        );

        CREATE TABLE supersessions (
            supersession_id         INTEGER PRIMARY KEY,
            target_source_uid       TEXT    NOT NULL,
            target_stable_key       TEXT    NOT NULL,
            superseding_chunk_id    INTEGER REFERENCES chunks(chunk_id),
            target_title            TEXT    NOT NULL,
            target_edition          TEXT,
            target_heading_path     TEXT,
            target_page_label_start TEXT,
            target_page_label_end   TEXT
        );

        CREATE TABLE build_report (
            report_id    INTEGER PRIMARY KEY,
            severity     TEXT    NOT NULL,
            subject_kind TEXT    NOT NULL,
            subject_id   TEXT,
            validation   TEXT    NOT NULL,
            detail       TEXT
        );

        CREATE INDEX idx_chunks_parent ON chunks(parent_chunk_id);
        CREATE INDEX idx_chunks_source ON chunks(source_id);
        CREATE INDEX idx_vectors_chunk ON vectors(chunk_id);
        CREATE INDEX idx_derivation_derived ON chunk_derivation(derived_chunk_id);
        CREATE INDEX idx_entities_alias ON entities(alias);
        CREATE INDEX idx_supersessions_target
            ON supersessions(target_source_uid, target_stable_key);
    """.trimIndent()
}
