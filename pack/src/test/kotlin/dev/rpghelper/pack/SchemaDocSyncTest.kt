package dev.rpghelper.pack

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps `docs/pack-schema.md` and the code honest about each other.
 *
 * The spec is the contract a second team will build a pack builder against, and a
 * specification that has quietly diverged from the implementation is worse than no
 * specification: it is a document people trust. These assertions make drift a build
 * failure instead of a discovery.
 */
class SchemaDocSyncTest {

    private val spec: String by lazy { Files.readString(locateSpec()) }

    @Test
    fun `the documented DDL is the DDL the code uses`() {
        val documented = between(
            spec,
            "<!-- BEGIN GENERATED DDL: edit PackSchema.DDL, not this block -->",
            "<!-- END GENERATED DDL -->",
        ).trim().removePrefix("```sql").removeSuffix("```").trim()

        assertEquals(
            PackSchema.DDL.trim(),
            documented,
            "docs/pack-schema.md has drifted from PackSchema.DDL; regenerate the block",
        )
    }

    @Test
    fun `the documented probe encoding is the encoding the code produces`() {
        // The hex block in the spec is what a builder implementer will type against.
        val hex = between(spec, "Canonical little-endian encoding:", "The constant was chosen")
            .substringAfter("```")
            .substringBefore("```")
            .filter { !it.isWhitespace() }

        assertEquals(
            ProbeVector.canonicalBytes().joinToString("") { "%02x".format(it) },
            hex,
            "the probe encoding in docs/pack-schema.md does not match ProbeVector",
        )
    }

    @Test
    fun `the documented schema version is the one the code enforces`() {
        assertTrue(
            spec.contains("`schema_version = ${PackSchema.SCHEMA_VERSION}`"),
            "docs/pack-schema.md does not state schema_version ${PackSchema.SCHEMA_VERSION}",
        )
    }

    @Test
    fun `every chunk kind is documented`() {
        for (kind in PackSchema.KINDS) {
            assertTrue(spec.contains("| `$kind` |"), "kind '$kind' is missing from the taxonomy table")
        }
    }

    @Test
    fun `every locator scheme is documented`() {
        for (scheme in PackSchema.LOCATOR_SCHEMES) {
            assertTrue(
                spec.contains("| `$scheme` |"),
                "locator scheme '$scheme' is missing from the spec",
            )
        }
    }

    private fun between(text: String, start: String, end: String): String {
        val from = text.indexOf(start)
        assertTrue(from >= 0, "marker not found in spec: $start")
        val to = text.indexOf(end, from)
        assertTrue(to >= 0, "marker not found in spec: $end")
        return text.substring(from + start.length, to)
    }

    /** Walks up from the working directory, so this does not depend on how tests are launched. */
    private fun locateSpec(): Path {
        var directory: Path? = Path.of("").toAbsolutePath()
        while (directory != null) {
            val candidate = directory.resolve("docs/pack-schema.md")
            if (Files.isRegularFile(candidate)) return candidate
            directory = directory.parent
        }
        error("could not find docs/pack-schema.md above ${Path.of("").toAbsolutePath()}")
    }
}
