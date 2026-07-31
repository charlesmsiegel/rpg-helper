package dev.ludex.pack

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cheap check that runs every time a pack is lent to a reader.
 *
 * It is deliberately not the full gate — hashing a hundred megabytes per pack on every
 * launch puts seconds on cold start, and a check that makes the app feel broken is a check
 * that gets removed. What it must do is catch gross damage, so the cases below are the
 * ones where "gross damage" could look fine.
 */
class OpenCheckTest {

    private val directory: Path = Files.createTempDirectory("opencheck")

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun check(mutate: (java.sql.Connection) -> Unit = {}): String? =
        Packs.openCheck(PackForge.writePack(directory, mutate), setOf(PackForge.EMBEDDER))

    @Test
    fun `an intact pack passes`() {
        assertNull(check())
    }

    @Test
    fun `a schema version that truncates to a supported one is still refused`() {
        // 4294967297 is 2^32 + 1. Narrowed to Int it is 1, which is exactly the version
        // this build understands -- so a damaged or substituted file could declare the
        // gross metadata corruption this check exists to catch and be lent out anyway.
        val fault = check { it.exec("UPDATE pack_meta SET schema_version = 4294967297") }
        assertTrue(fault != null && "schema version" in fault, "$fault")
    }

    @Test
    fun `an embedder dimension that truncates to the bundled one is still refused`() {
        // 4294967424 is 2^32 + 128, and the forge's contract is 8-dimensional; the same
        // narrowing hides any dim whose low 32 bits happen to match.
        val fault = check { it.exec("UPDATE pack_meta SET embedder_dim = 4294967304") }
        assertTrue(fault != null && "dimensional" in fault, "$fault")
    }

    @Test
    fun `a pack needing an embedder this build does not bundle is refused`() {
        val fault = check { it.exec("UPDATE pack_meta SET embedder_id = 'not-bundled'") }
        assertTrue(fault != null && "not-bundled" in fault, "$fault")
    }

    @Test
    fun `a probe vector that does not decode to its constant is refused`() {
        // The one check that catches a byte-order or width error, which no length check
        // can see because a wrong-endian vector is exactly as long as a right-endian one.
        val fault = check { it.exec("UPDATE pack_meta SET probe_vector = x'0000'") }
        assertTrue(fault != null && "probe vector" in fault, "$fault")
    }

    @Test
    fun `a file that is not a pack at all is refused rather than thrown from`() {
        val bogus = directory.resolve("bogus.rpgpack")
        Files.writeString(bogus, "certainly not SQLite")
        val fault = Packs.openCheck(bogus, setOf(PackForge.EMBEDDER))
        assertTrue(fault != null, "a non-pack must not pass")
    }

    @Test
    fun `it does not pretend to catch what only the digest can`() {
        // Stated as a test because the value of the cheap check depends on nobody
        // mistaking it for the full one: a flipped bit in chunks.text leaves the schema,
        // the probe vector, and every declared dimension untouched, and that is the
        // failure that would quote mutated text as byte-exact.
        assertNull(check { it.exec("UPDATE chunks SET text = 'not what the book says'") })
    }
}
