package dev.rpghelper.state

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** Why a `.rpgdoc` could not be imported. Refusal is whole-file: never a partial import. */
class DocumentTransferException(message: String) : IllegalArgumentException(message)

/** What importing one file produced. */
data class Imported(
    val documentId: Long,
    /**
     * Acceptances the file carried that were not restored, and why.
     *
     * Reported rather than silently dropped: an acceptance is a record of a table decision,
     * and a user whose Storyteller's ruling quietly failed to arrive would find out by
     * seeing a flag they thought they had settled.
     */
    val droppedAcceptances: List<String>,
)

/**
 * Documents as files, because **a character sheet outlives the software that checks it**.
 *
 * There is no cloud sync — local is the premise — so a file is also how a document moves
 * between devices. The format is `rpgdoc/1`, one document per file.
 *
 * The properties that are decisions rather than serialization:
 *
 * - **`document_id` is not exported.** Import always mints a new one. A file is a copy, and
 *   two devices holding one file hold two documents; there is no sync to reconcile them, so
 *   pretending they share an identity would only produce a conflict nothing can resolve.
 * - **The ruleset binding travels**, so a document imported onto a device without the
 *   matching pack is *unvalidated*, not *unbound* — validation resumes when the pack
 *   arrives rather than requiring the user to remember what game it was.
 * - **Accepted violations travel, with their notes and their own `ruleset_id`** rather than
 *   the document's current binding. Acceptances survive rebinding: a document bound to game
 *   B may carry dormant acceptances from game A, and stamping every row with the current
 *   binding on import would lose A's and wrongly activate them against B.
 * - **No computed state travels.** Violations are derived, and are recomputed on import.
 * - **Unknown top-level keys are preserved**, so a document exported by a later version and
 *   re-imported by an earlier one does not quietly lose data.
 */
object DocumentTransfer {

    const val FORMAT: String = "rpgdoc/1"

    private val json = Json { prettyPrint = true }
    private val parser = Json { ignoreUnknownKeys = true }

    private val FINGERPRINT = Regex("[0-9a-f]{64}")

    /** One document as a file. Deterministic: the same document exports byte-identically. */
    fun export(
        documents: DocumentStore,
        documentId: Long,
        /**
         * The text of a file this document came from, if there is one.
         *
         * Its unknown top-level keys are carried into the new file, so a document exported
         * by a later build and re-imported by this one does not quietly lose what this
         * build cannot read. Taken as **text rather than as parsed JSON** so that no caller
         * needs a JSON library to round-trip a document faithfully — the surfaces that call
         * this are a command line and an Android view model, and neither should have to
         * hold a `JsonElement` to avoid losing a user's data.
         */
        preservedFrom: String? = null,
    ): String {
        val preserved = preservedFrom?.let(::preservedKeys) ?: emptyMap()
        val document = documents.get(documentId)
            ?: throw DocumentTransferException("no document $documentId")
        val root = buildJsonObject {
            // Preserved keys first, so the format's own keys always win a collision: a file
            // that carried a stale `document` object must not overwrite the real one.
            preserved.forEach { (key, value) -> if (key !in OWN_KEYS) put(key, value) }
            put("format", FORMAT)
            put(
                "document",
                buildJsonObject {
                    put("title", document.title)
                    document.campaign?.let { put("campaign", it) }
                    document.rulesetId?.let { put("ruleset_id", it) }
                    put("draft", document.draft)
                    document.extensions?.let { put("extensions", it) }
                },
            )
            put(
                "trackers",
                buildJsonArray {
                    documents.trackers(documentId).forEach { tracker ->
                        add(
                            buildJsonObject {
                                put("key", tracker.key)
                                when (val value = tracker.value) {
                                    is TrackerValue.Number -> {
                                        put("type", "number"); put("value", value.value)
                                    }
                                    is TrackerValue.Flag -> {
                                        put("type", "flag"); put("value", value.value)
                                    }
                                    is TrackerValue.Text -> {
                                        put("type", "text"); put("value", value.value)
                                    }
                                }
                            },
                        )
                    }
                },
            )
            put(
                "accepted_violations",
                buildJsonArray {
                    documents.acceptances(documentId).forEach { acceptance ->
                        add(
                            buildJsonObject {
                                put("fingerprint", acceptance.fingerprint)
                                put("ruleset_id", acceptance.rulesetId)
                                acceptance.note?.let { put("note", it) }
                            },
                        )
                    }
                },
            )
        }
        // Trailing newline: this is a file a user will diff, cat, and put in a git repo
        // beside their campaign notes, and a text file without one is a small rudeness to
        // every tool that reads it.
        return json.encodeToString(JsonObject.serializer(), root) + "\n"
    }

    /**
     * Reads a `.rpgdoc` and creates a document from it.
     *
     * **Import is an entry point, and normalizes like one.** It is the second way tracker
     * keys enter the app and the first way a key nobody typed can arrive: a file carrying
     * `Attribute.Strength` would otherwise sit beside a constraint selecting
     * `attribute.strength` and never match it, recreating exactly the silent-validation
     * failure normalize-on-entry exists to eliminate, on a document the user has every
     * reason to believe is checked.
     *
     * **A file it cannot read is refused whole**, never imported partially: half a
     * character is worse than none, because the half that is missing is invisible.
     */
    fun import(documents: DocumentStore, text: String): Imported {
        val root = runCatching { parser.parseToJsonElement(text).jsonObject }
            .getOrElse { throw DocumentTransferException("this is not a .rpgdoc file: ${it.message}") }

        val format = root["format"]?.jsonPrimitive?.contentOrNullSafe()
            ?: throw DocumentTransferException("no format field; this is not a .rpgdoc file")
        if (format != FORMAT) {
            throw DocumentTransferException(
                "this file is '$format' and this build reads '$FORMAT'",
            )
        }

        val documentObject = root["document"]?.jsonObject
            ?: throw DocumentTransferException("no document object")
        val title = documentObject["title"]?.jsonPrimitive?.contentOrNullSafe()
            ?: throw DocumentTransferException("the document has no title")

        // Everything is parsed and checked **before** anything is written, so a refusal
        // leaves no half-imported document behind.
        val trackers = parseTrackers(root["trackers"])
        val acceptances = mutableListOf<AcceptedViolation>()
        val dropped = mutableListOf<String>()
        for (element in root["accepted_violations"]?.jsonArray ?: JsonArray(emptyList())) {
            val row = element.jsonObject
            val fingerprint = row["fingerprint"]?.jsonPrimitive?.contentOrNullSafe()
            val rulesetId = row["ruleset_id"]?.jsonPrimitive?.contentOrNullSafe()
            // A fingerprint is a SHA-256 of a canonical rule object, and a digest cannot be
            // checked against a claimed `ruleset_id` without its preimage — which this
            // format does not carry (see the note in `docs/THEORY.md` §4.8). What *can* be
            // checked is that the row is well formed, and a row that is not is dropped and
            // named rather than stored: a malformed acceptance would sit in the table
            // suppressing nothing while the user believed a ruling had travelled.
            when {
                fingerprint == null || !FINGERPRINT.matches(fingerprint) ->
                    dropped += "an acceptance has no usable fingerprint"
                rulesetId.isNullOrBlank() ->
                    dropped += "acceptance ${fingerprint.take(8)}… names no ruleset"
                else -> acceptances += AcceptedViolation(
                    fingerprint,
                    rulesetId,
                    row["note"]?.jsonPrimitive?.contentOrNullSafe(),
                )
            }
        }

        val document = documents.create(
            title = title,
            campaign = documentObject["campaign"]?.jsonPrimitive?.contentOrNullSafe(),
            rulesetId = documentObject["ruleset_id"]?.jsonPrimitive?.contentOrNullSafe(),
            draft = documentObject["draft"]?.jsonPrimitive?.booleanOrNullSafe() ?: true,
            extensions = documentObject["extensions"]?.jsonPrimitive?.contentOrNullSafe(),
        )
        trackers.forEach { (key, value) -> documents.putTracker(document.documentId, key, value) }
        acceptances.forEach {
            documents.accept(document.documentId, it.fingerprint, it.rulesetId, it.note)
        }
        return Imported(document.documentId, dropped)
    }

    /** Top-level keys this format owns. Anything else round-trips untouched. */
    private val OWN_KEYS = setOf("format", "document", "trackers", "accepted_violations")

    /** The unknown top-level keys of a file, for a later [export] to carry forward. */
    private fun preservedKeys(text: String): Map<String, JsonElement> =
        runCatching { parser.parseToJsonElement(text).jsonObject }
            .getOrNull()
            ?.filterKeys { it !in OWN_KEYS }
            ?: emptyMap()

    private fun parseTrackers(element: JsonElement?): List<Pair<String, TrackerValue>> {
        val rows = (element as? JsonArray) ?: JsonArray(emptyList())
        val parsed = mutableListOf<Pair<String, TrackerValue>>()
        val seen = mutableMapOf<String, String>()
        for (row in rows) {
            val tracker = row.jsonObject
            val raw = tracker["key"]?.jsonPrimitive?.contentOrNullSafe()
                ?: throw DocumentTransferException("a tracker has no key")
            val key = try {
                DocumentStore.normalizeKey(raw)
            } catch (e: InvalidTrackerKeyException) {
                // A refusal, not a silent pass: a key that misses the form constraints
                // select on does not fail loudly, it matches nothing, and the sheet then
                // looks validated while nothing checked it.
                throw DocumentTransferException("${e.message}")
            }
            // Two distinct keys that normalize onto one would merge, and merging discards a
            // value. The file is refused rather than losing one of them.
            seen.put(key, raw)?.let { first ->
                throw DocumentTransferException(
                    "'$first' and '$raw' are the same tracker once normalized, and importing " +
                        "both would discard one",
                )
            }
            val type = tracker["type"]?.jsonPrimitive?.contentOrNullSafe()
                ?: throw DocumentTransferException("tracker '$raw' has no type")
            val value = tracker["value"]
                ?: throw DocumentTransferException("tracker '$raw' has no value")
            parsed += key to typed(raw, type, value)
        }
        return parsed
    }

    /**
     * Types by the file's declared type, and **refuses a mismatch** rather than coercing.
     *
     * `"3"` read as a number would make a text tracker satisfy a numeric rule, and `false`
     * read as text would make a flag into the string `"false"`, which is present and
     * non-blank and therefore *true* under presence semantics. A file the schema forbids is
     * refused where it arrives.
     */
    private fun typed(key: String, type: String, value: JsonElement): TrackerValue {
        val primitive = value as? JsonPrimitive
            ?: throw DocumentTransferException("tracker '$key' has a value that is not a scalar")
        // `isString` first, always. `JsonPrimitive("3").double` parses happily, which is the
        // same content-versus-declared-type confusion that let a quoted `table_id` load a
        // capability — the JSON layer will convert anything convertible, so the check has to
        // be on what the file *is*, not on what it can be read as.
        return when (type) {
            "number" -> {
                if (primitive.isString) {
                    throw DocumentTransferException(
                        "tracker '$key' is a number but its value is the string \"${primitive.content}\"",
                    )
                }
                TrackerValue.Number(
                    runCatching { primitive.double }.getOrElse {
                        throw DocumentTransferException(
                            "tracker '$key' is a number with value ${primitive.content}",
                        )
                    },
                )
            }
            "flag" -> {
                if (primitive.isString) {
                    throw DocumentTransferException(
                        "tracker '$key' is a flag but its value is the string \"${primitive.content}\"",
                    )
                }
                TrackerValue.Flag(
                    runCatching { primitive.boolean }.getOrElse {
                        throw DocumentTransferException(
                            "tracker '$key' is a flag with value ${primitive.content}",
                        )
                    },
                )
            }
            "text" -> {
                if (!primitive.isString) {
                    throw DocumentTransferException("tracker '$key' is text but its value is not a string")
                }
                TrackerValue.Text(primitive.content)
            }
            else -> throw DocumentTransferException("tracker '$key' has unknown type '$type'")
        }
    }

    /** `content` on a JSON null yields the string "null", which is a value nobody wrote. */
    private fun JsonPrimitive.contentOrNullSafe(): String? =
        if (this is kotlinx.serialization.json.JsonNull) null else content

    private fun JsonPrimitive.booleanOrNullSafe(): Boolean? =
        if (this is kotlinx.serialization.json.JsonNull) null else runCatching { boolean }.getOrNull()
}
