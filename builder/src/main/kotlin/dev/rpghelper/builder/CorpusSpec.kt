package dev.rpghelper.builder

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The text inputs a pack is assembled from.
 *
 * No `.rpgpack` binary is committed anywhere: a pack is a SQLite file, opaque in review,
 * useless in a diff, and large. Storing the inputs as text and assembling during the test
 * run keeps every fixture change readable, and means a schema change updates the fixture
 * automatically instead of waiting for someone to remember to rebuild a binary.
 */
class CorpusSpec(val root: Path, private val json: JsonObject) {

    val packUid = json.text("pack_uid")
    val packVersion = json.text("pack_version")
    val title = json.text("title")
    val rulesetId = json.optionalText("ruleset_id")
    val licenseId = json.text("license_id")
    val attribution = json.optionalText("attribution")
    val builtAt = json.text("built_at")
    val builderVersion = json.text("builder_version")

    val sources: List<SourceSpec> = json.array("sources").map { SourceSpec(it.jsonObject) }
    val derived: List<DerivedSpec> = json.optionalArray("derived").map { DerivedSpec(it.jsonObject) }
    val tables: List<TableSpec> = json.optionalArray("tables").map { TableSpec(it.jsonObject) }
    val capabilities: List<CapabilitySpec> =
        json.optionalArray("capabilities").map { CapabilitySpec(it.jsonObject) }
    val entities: List<EntitySpec> = json.optionalArray("entities").map { EntitySpec(it.jsonObject) }
    val constraints: List<ConstraintSpec> =
        json.optionalArray("constraints").map { ConstraintSpec(it.jsonObject) }
    val supersessions: List<SupersessionSpec> =
        json.optionalArray("supersessions").map { SupersessionSpec(it.jsonObject) }

    class SourceSpec(private val json: JsonObject) {
        val sourceUid = json.text("source_uid")
        val file = json.text("file")
        val title = json.text("title")
        val edition = json.optionalText("edition")
        val publisher = json.optionalText("publisher")
        val locatorScheme = json.text("locator_scheme")
        val pageLabels = json.optionalArray("page_labels").map { PageLabelSpec(it.jsonObject) }
        val gaps = json.optionalArray("gaps").map { GapSpec(it.jsonObject) }
        val chunks = json.array("chunks").map { ChunkSpec(it.jsonObject) }
    }

    class PageLabelSpec(json: JsonObject) {
        val seq = json.number("seq").toInt()
        val physStart = json.number("phys_start").toInt()
        val physEnd = json.number("phys_end").toInt()
        val scheme = json.text("scheme")
        val startValue = json.optionalNumber("start_value")?.toInt()
        val prefix = json.optionalText("prefix")
    }

    class GapSpec(json: JsonObject) {
        val reason = json.text("reason")
        val from = json.text("from")
        val to = json.text("to")
    }

    /**
     * A chunk located by **anchors** rather than by byte offsets.
     *
     * The emitted pack carries real UTF-8 byte spans. Hand-written offsets in the fixture
     * would rot silently the moment a word changed in the source, which defeats the reason
     * for storing the corpus as text. An anchor that no longer matches — or that matches
     * twice — fails the build instead.
     */
    class ChunkSpec(private val json: JsonObject) {
        val stableKey = json.text("stable_key")
        val kind = json.text("kind")
        val headingPath = json.optionalText("heading_path")
        val pageLabelStart = json.optionalText("page_label_start")
        val pageLabelEnd = json.optionalText("page_label_end")
        val from = json.text("from")
        val to = json.text("to")
        val children = json.optionalArray("children").map { ChunkSpec(it.jsonObject) }
    }

    class DerivedSpec(private val json: JsonObject) {
        val kind = json.text("kind")
        val text = json.text("text")
        val cites = json.array("cites").map { CitationSpec(it.jsonObject) }
    }

    class CitationSpec(json: JsonObject) {
        val stableKey = json.text("stable_key")

        /** The sentence this citation anchors, located within the derived text. */
        val claim = json.optionalText("claim")
    }

    class TableSpec(private val json: JsonObject) {
        val chunk = json.text("chunk")
        val diceExpr = json.text("dice_expr")
        val rows = json.array("rows").map { RowSpec(it.jsonObject) }
    }

    class RowSpec(json: JsonObject) {
        // Long, because `DiceExpression.min`/`max` and the `table_rows` columns are. An
        // endpoint past Int wrapped silently, so a correctly declared row for a legal
        // expression like `d6+2147483647` -- whose first result is 2147483648 -- parsed as
        // a negative range and took its table and roll control down with it.
        val lo = json.number("lo").toLong()
        val hi = json.number("hi").toLong()
        val text = json.text("text")
    }

    class CapabilitySpec(json: JsonObject) {
        val kind = json.text("kind")
        val chunk = json.text("chunk")
        val label = json.text("label")
    }

    class EntitySpec(json: JsonObject) {
        val canonical = json.text("canonical")
        val alias = json.text("alias")
        val kind = json.text("kind")
        val chunk = json.optionalText("chunk")
    }

    class ConstraintSpec(private val json: JsonObject) {
        val form = json.text("form")
        val chunk = json.text("chunk")
        val args: JsonObject = json.getValue("args").jsonObject
    }

    class SupersessionSpec(private val json: JsonObject) {
        val targetSourceUid = json.text("target_source_uid")
        val targetStableKey = json.text("target_stable_key")
        val supersedingChunk = json.optionalText("superseding_chunk")
        val targetTitle = json.text("target_title")
        val targetEdition = json.optionalText("target_edition")
        val targetHeadingPath = json.optionalText("target_heading_path")
        val targetPageLabelStart = json.optionalText("target_page_label_start")
        val targetPageLabelEnd = json.optionalText("target_page_label_end")
    }

    /** The source document's bytes, exactly as `sources.text_sha256` will pin them. */
    fun textOf(source: SourceSpec): String = Files.readString(root.resolve(source.file))

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        fun load(directory: Path, file: String = "pack.json"): CorpusSpec =
            CorpusSpec(
                directory,
                json.parseToJsonElement(Files.readString(directory.resolve(file))).jsonObject,
            )
    }
}

// The corpus is a fixture the project writes, not untrusted input, so a malformed field
// is a programming error and throws. Packs get the opposite treatment for the opposite
// reason: they arrive from elsewhere and are refused rather than trusted.
private fun JsonObject.text(field: String): String = getValue(field).jsonPrimitive.content

private fun JsonObject.optionalText(field: String): String? =
    this[field]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

private fun JsonObject.number(field: String): Long =
    getValue(field).jsonPrimitive.content.toLong()

private fun JsonObject.optionalNumber(field: String): Long? =
    this[field]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content?.toLong()

private fun JsonObject.array(field: String): List<JsonElement> = getValue(field).jsonArray

private fun JsonObject.optionalArray(field: String): List<JsonElement> =
    this[field]?.takeIf { it !is JsonNull }?.jsonArray ?: emptyList()
