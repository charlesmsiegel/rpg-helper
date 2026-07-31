package dev.rpghelper.pack

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.name
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * No shipped source may name `JdbcDb`.
 *
 * `sqlite-jdbc` carries native desktop libraries that cannot load on Android, and `:app`
 * excludes the artifact outright — so a production call site naming it is a crash on the
 * first pack a phone opens, and one that no desktop test can reach. Eight of them existed
 * at once, each locally correct, because the binding was chosen wherever a pack was opened
 * instead of once.
 *
 * A test rather than a comment, because "remember to use [Sqlite]" is the instruction that
 * was already implicit and already not followed. Tests may name `JdbcDb` freely: comparing
 * the two bindings is the entire reason the second one still exists.
 */
class NoDesktopBindingInProductionTest {

    @Test
    fun `production code opens packs only through the binding that runs on a phone`() {
        val sources = mainSources()
        assertTrue(sources.size > 20, "found only ${sources.size} sources; the walk is wrong")

        val offenders = sources
            // The class's own file, the one that names it as the alternative, and the
            // facade that picks between them.
            .filter { it.name !in setOf("JdbcDb.kt", "BundledDb.kt", "Sqlite.kt") }
            .filter { "JdbcDb" in it.readText() }
            .map { it.toString() }

        if (offenders.isNotEmpty()) {
            fail(
                "these shipped sources name JdbcDb, which does not load on Android:\n" +
                    offenders.joinToString("\n") { "  $it" } +
                    "\nOpen packs through Sqlite.openReadOnly instead.",
            )
        }
    }

    private fun mainSources(): List<Path> {
        // Walk up to the Gradle root: tests run with the module directory as cwd, and the
        // point of this check is the modules *other* than this one.
        var root = Path.of("").toAbsolutePath()
        while (!Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
            root = root.parent ?: fail("no settings.gradle.kts above ${Path.of("").toAbsolutePath()}")
        }
        return Files.walk(root).use { paths ->
            paths.filter { it.extension == "kt" }
                .filter { "${it.parent}".contains("src/main/kotlin") }
                .toList()
        }
    }
}
