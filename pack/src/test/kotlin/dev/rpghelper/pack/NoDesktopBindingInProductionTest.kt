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

    /**
     * Modules that ship inside the APK. `:builder` is a desktop tool by design — it turns a
     * corpus into a pack off-device, on a machine with a JDK — so it is exempt, and saying
     * so here is what keeps the exemption a decision rather than an oversight.
     */
    private val shipped = listOf("pack", "state", "retrieval", "routing", "model", "capabilities", "session", "app")

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

    @Test
    fun `nothing that ships reaches JDBC at all`() {
        // Not only the pack reader. `StateDb` opened the app's *writable* database through
        // `DriverManager`, so constructing the Ask view model died at startup with "no
        // suitable driver" -- the same mistake as the pack binding, one layer up, and
        // invisible for the same reason: every test runs on a JVM where the driver is
        // present. A rule about one file was never the rule; this is.
        val offenders = mainSources()
            .filter { path -> shipped.any { "/$it/src/main/kotlin" in "$path" } }
            .filter { it.name !in setOf("JdbcDb.kt", "BundledDb.kt", "Sqlite.kt") }
            .filter { source ->
                val code = code(source)
                "java.sql" in code || "DriverManager" in code
            }
            .map { it.toString() }

        if (offenders.isNotEmpty()) {
            fail(
                "these shipped sources reach JDBC, which does not load on Android:\n" +
                    offenders.joinToString("\n") { "  $it" } +
                    "\nUse Sqlite.openReadOnly for packs and StateDb for app state.",
            )
        }
    }

    /**
     * [source] with its comments stripped.
     *
     * A scan over raw text fails on the KDoc that *explains* the rule — the paragraph in
     * `StateDb` describing the `DriverManager` bug it no longer has would flag the file
     * that fixed it. A check that punishes writing down why is a check that gets the
     * explanation deleted instead of the defect.
     */
    private fun code(source: Path): String = source.readText()
        .lineSequence()
        .filterNot { it.trimStart().let { line -> line.startsWith("//") || line.startsWith("*") || line.startsWith("/*") } }
        .joinToString("\n")

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
