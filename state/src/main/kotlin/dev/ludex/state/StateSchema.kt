package dev.ludex.state

/**
 * The app's own database, as distinct from the read-only pack files.
 *
 * Unlike a pack's DDL — which ships inside a file a third party built and therefore
 * guarantees nothing — this schema is created by the app. Its `CHECK` and `UNIQUE`
 * clauses are worth something, and the code relies on them rather than re-checking.
 *
 * `01-app-state-spec.md` is normative; `StateSchemaDocSyncTest` fails the build if the
 * two drift.
 */
object StateSchema {

    /** Bumped by every migration. Tracked in `PRAGMA user_version`. */
    const val VERSION: Int = 1

    /** Rows the install journal may hold. */
    const val STATE_INSTALLING = "installing"
    const val STATE_READY = "ready"

    val TRACKER_TYPES: Set<String> = setOf("number", "flag", "text")

    val V1: String = """
        CREATE TABLE installed_packs (
            install_id   INTEGER PRIMARY KEY AUTOINCREMENT,
            pack_uid     TEXT    NOT NULL,
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

        CREATE UNIQUE INDEX idx_packs_priority
            ON installed_packs(priority) WHERE state = 'ready';

        CREATE UNIQUE INDEX idx_packs_uid_ready
            ON installed_packs(pack_uid) WHERE state = 'ready';

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

        CREATE TABLE conversation_turns (
            turn_id  INTEGER PRIMARY KEY,
            asked_at TEXT    NOT NULL,
            query    TEXT    NOT NULL,
            cards    TEXT    NOT NULL
        );

        CREATE TABLE answer_cache (
            cache_key    TEXT PRIMARY KEY,
            card         TEXT NOT NULL,
            created_at   TEXT NOT NULL,
            last_used_at TEXT NOT NULL
        );

        CREATE TABLE models (
            model_id      TEXT    PRIMARY KEY,
            role          TEXT    NOT NULL CHECK (role IN ('embedder', 'asr', 'generative')),
            state         TEXT    NOT NULL,
            bytes_total   INTEGER,
            bytes_fetched INTEGER,
            updated_at    TEXT    NOT NULL
        );
    """.trimIndent()

    /**
     * Forward-only, applied in order, one transaction each.
     *
     * The index is the target version, so `MIGRATIONS[0]` takes an empty database to
     * version 1. Adding a migration is appending; editing one that has shipped is not a
     * thing that can be done, since a device that already ran it will never run it again.
     */
    val MIGRATIONS: List<String> = listOf(V1)

    init {
        require(MIGRATIONS.size == VERSION) {
            "VERSION is $VERSION but there are ${MIGRATIONS.size} migrations"
        }
    }
}
