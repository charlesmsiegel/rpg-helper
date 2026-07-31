package dev.rpghelper.state

import java.text.Normalizer

/** A character, or anything else worth keeping. */
data class Document(
    val documentId: Long,
    val title: String,
    val campaign: String?,
    val rulesetId: String?,
    val draft: Boolean,
    val extensions: String?,
)

/** A tracker's typed value. */
sealed interface TrackerValue {
    data class Number(val value: Double) : TrackerValue
    data class Flag(val value: Boolean) : TrackerValue
    data class Text(val value: String) : TrackerValue

    /**
     * Whether this counts as *present*.
     *
     * Deleting a Virtue and setting it to zero mean the same thing to the rules, which is
     * what a user would expect and saves every pack from having to care which the app did.
     * `range` is the one form that does not use this: a Strength of 0 is a value, and it
     * violates *1–5*.
     */
    val isPresent: Boolean
        get() = when (this) {
            is Number -> value != 0.0
            is Flag -> value
            is Text -> value.isNotBlank()
        }
}

/** A tracker on a document. */
data class Tracker(val key: String, val value: TrackerValue, val ordinal: Int)

/** A violation the user has accepted as a deliberate deviation. */
data class AcceptedViolation(
    val fingerprint: String,
    val rulesetId: String,
    val note: String?,
)

/** Thrown when a tracker key cannot be brought into the form constraints match against. */
class InvalidTrackerKeyException(key: String, reason: String) :
    IllegalArgumentException("tracker key '$key' $reason")

/**
 * Documents, trackers, and accepted violations.
 *
 * The governing rule, from which the rest follows: **a character sheet outlives the
 * software that checks it.** Losing a validator is an inconvenience; losing or silently
 * rewriting a character is not recoverable.
 */
class DocumentStore(
    private val db: StateDb,
    private val clock: () -> String = { java.time.Instant.now().toString() },
) {

    /**
     * Runs [body] as one database transaction.
     *
     * Exposed because whole-file import is an all-or-nothing operation across three tables,
     * and a caller cannot promise that without the connection that owns it. Reentrant, so a
     * store method that opens its own transaction inside this one joins rather than
     * committing early.
     */
    fun <T> transaction(body: () -> T): T = db.transaction(body)

    fun create(
        title: String,
        campaign: String? = null,
        rulesetId: String? = null,
        draft: Boolean = true,
        extensions: String? = null,
    ): Document {
        val now = clock()
        // Insert and id-lookup together. `last_insert_rowid()` is per-connection, not
        // per-caller, so with the connection lock released between the two a concurrent
        // insert would hand this caller the *other* document -- and every subsequent
        // wizard edit would land on the wrong character.
        return db.transaction {
            db.execute(
                "INSERT INTO documents (title, campaign, ruleset_id, draft, extensions, " +
                    "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                title, campaign, rulesetId, draft, extensions, now, now,
            )
            requireNotNull(get(db.lastInsertId()))
        }
    }

    fun get(documentId: Long): Document? = db.query(
        "SELECT document_id, title, campaign, ruleset_id, draft, extensions " +
            "FROM documents WHERE document_id = ?",
        documentId,
    ) { it.toDocument() }.firstOrNull()

    fun all(): List<Document> = db.query(
        "SELECT document_id, title, campaign, ruleset_id, draft, extensions " +
            "FROM documents ORDER BY campaign, title",
    ) { it.toDocument() }

    /** Deletes a document and, through the cascade, everything hanging off it. */
    fun delete(documentId: Long) {
        db.execute("DELETE FROM documents WHERE document_id = ?", documentId)
    }

    fun setDraft(documentId: Long, draft: Boolean) {
        db.execute(
            "UPDATE documents SET draft = ?, updated_at = ? WHERE document_id = ?",
            draft, clock(), documentId,
        )
    }

    /**
     * Rebinds a document to a ruleset.
     *
     * Acceptances are deliberately retained: their fingerprints carry the ruleset they
     * were made under, so one from a previous binding goes dormant rather than following
     * the document into a new game, and becomes live again if the document returns.
     */
    fun rebind(documentId: Long, rulesetId: String?) {
        db.execute(
            "UPDATE documents SET ruleset_id = ?, updated_at = ? WHERE document_id = ?",
            rulesetId, clock(), documentId,
        )
    }

    // ---------------------------------------------------------------- trackers

    /**
     * Sets a tracker, normalizing its key first.
     *
     * Normalization happens at every entry point, because a key that misses the form
     * constraints select on does not fail loudly — it silently matches nothing, and the
     * sheet looks validated while nothing checked it.
     */
    fun putTracker(documentId: Long, key: String, value: TrackerValue) {
        val normalized = normalizeKey(key)
        // The ordinal lookup, the upsert, and the timestamp are one operation. The
        // connection lock covers each call and not the sequence, so two threads adding
        // different trackers can both read the same MAX(ordinal) before either inserts --
        // and `ORDER BY ordinal` then stops preserving the order the user chose.
        db.transaction {
            val ordinal = db.query(
                "SELECT COALESCE(MAX(ordinal), -1) + 1 FROM trackers WHERE document_id = ?",
                documentId,
            ) { it.int(0) }.single()

            db.execute(
                "INSERT INTO trackers (document_id, key, type, number_value, flag_value, " +
                    "text_value, ordinal) VALUES (?, ?, ?, ?, ?, ?, " +
                    "COALESCE((SELECT ordinal FROM trackers WHERE document_id = ? AND key = ?), ?)) " +
                    "ON CONFLICT (document_id, key) DO UPDATE SET " +
                    "type = excluded.type, number_value = excluded.number_value, " +
                    "flag_value = excluded.flag_value, text_value = excluded.text_value",
                documentId, normalized, typeOf(value),
                (value as? TrackerValue.Number)?.value,
                (value as? TrackerValue.Flag)?.value,
                (value as? TrackerValue.Text)?.value,
                documentId, normalized, ordinal,
            )
            db.execute(
                "UPDATE documents SET updated_at = ? WHERE document_id = ?", clock(), documentId,
            )
        }
    }

    fun removeTracker(documentId: Long, key: String) {
        db.execute(
            "DELETE FROM trackers WHERE document_id = ? AND key = ?",
            documentId, normalizeKey(key),
        )
    }

    fun trackers(documentId: Long): List<Tracker> = db.query(
        "SELECT key, type, number_value, flag_value, text_value, ordinal FROM trackers " +
            "WHERE document_id = ? ORDER BY ordinal",
        documentId,
    ) { row ->
        val value = when (val type = row.string(1)) {
            "number" -> TrackerValue.Number(row.double(2))
            "flag" -> TrackerValue.Flag(row.boolean(3))
            "text" -> TrackerValue.Text(row.string(4))
            else -> error("unknown tracker type '$type'; the schema CHECK should have refused it")
        }
        Tracker(row.string(0), value, row.int(5))
    }

    // ---------------------------------------------------------------- acceptances

    fun accept(documentId: Long, fingerprint: String, rulesetId: String, note: String? = null) {
        db.execute(
            "INSERT INTO accepted_violations (document_id, fingerprint, ruleset_id, note, " +
                "accepted_at) VALUES (?, ?, ?, ?, ?) " +
                "ON CONFLICT (document_id, fingerprint) DO UPDATE SET note = excluded.note",
            documentId, fingerprint, rulesetId, note, clock(),
        )
    }

    fun unaccept(documentId: Long, fingerprint: String) {
        db.execute(
            "DELETE FROM accepted_violations WHERE document_id = ? AND fingerprint = ?",
            documentId, fingerprint,
        )
    }

    fun acceptances(documentId: Long): List<AcceptedViolation> = db.query(
        "SELECT fingerprint, ruleset_id, note FROM accepted_violations WHERE document_id = ?",
        documentId,
    ) { AcceptedViolation(it.string(0), it.string(1), it.stringOrNull(2)) }

    // ---------------------------------------------------------------- internals

    private fun typeOf(value: TrackerValue) = when (value) {
        is TrackerValue.Number -> "number"
        is TrackerValue.Flag -> "flag"
        is TrackerValue.Text -> "text"
    }

    private fun StateDb.Row.toDocument() = Document(
        documentId = long(0),
        title = string(1),
        campaign = stringOrNull(2),
        rulesetId = stringOrNull(3),
        draft = boolean(4),
        extensions = stringOrNull(5),
    )

    companion object {
        private val SEGMENT = Regex("[a-z0-9-]+")

        /**
         * Brings a key into the single form constraints are matched against.
         *
         * Case folding is not cosmetic: a constraint over `attribute.strength` that
         * silently fails to match a tracker typed as `Attribute.Strength` produces no
         * violation and no error, so the validator appears to work and checks nothing.
         * Normalizing at the only points where keys are authored removes the failure
         * rather than diagnosing it.
         *
         * A key that cannot be normalized is rejected rather than stored as written,
         * because storing it would recreate exactly that silence.
         */
        fun normalizeKey(key: String): String {
            val folded = Normalizer.normalize(key.trim(), Normalizer.Form.NFC).lowercase()
            if (folded.isEmpty()) throw InvalidTrackerKeyException(key, "is empty")

            val segments = folded.split('.')
            for (segment in segments) {
                if (!SEGMENT.matches(segment)) {
                    throw InvalidTrackerKeyException(
                        key,
                        "has segment '$segment'; segments must match [a-z0-9-]+",
                    )
                }
            }
            return segments.joinToString(".")
        }
    }
}
