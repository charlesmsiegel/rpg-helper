package dev.rpghelper.state

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Keeps `docs/01-app-state-spec.md` and [StateSchema] honest about each other.
 *
 * The same reasoning as the pack schema's drift gate: a specification that has quietly
 * diverged from its implementation is worse than no specification, because it is a
 * document people trust.
 */
class StateSchemaDocSyncTest {

    private val spec: String by lazy { Files.readString(locateSpec()) }

    @Test
    fun `the documented DDL is the DDL the code applies`() {
        val documented = spec
            .substringAfter("<!-- BEGIN GENERATED DDL: edit StateSchema.V1, not this block -->")
            .substringBefore("<!-- END GENERATED DDL -->")
            .trim()
            .removePrefix("```sql")
            .removeSuffix("```")
            .trim()

        assertEquals(
            StateSchema.V1.trim(),
            documented,
            "docs/01-app-state-spec.md has drifted from StateSchema.V1; regenerate the block",
        )
    }

    @Test
    fun `every tracker type is documented`() {
        for (type in StateSchema.TRACKER_TYPES) {
            assertTrue(spec.contains("`$type`"), "tracker type '$type' is missing from the spec")
        }
    }

    private fun locateSpec(): Path {
        var directory: Path? = Path.of("").toAbsolutePath()
        while (directory != null) {
            val candidate = directory.resolve("docs/01-app-state-spec.md")
            if (Files.isRegularFile(candidate)) return candidate
            directory = directory.parent
        }
        error("could not find docs/01-app-state-spec.md")
    }
}
