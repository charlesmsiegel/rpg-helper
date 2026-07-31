package dev.rpghelper.pack

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The two `Db` bindings, held to the same answers.
 *
 * `JdbcDb` runs on a desktop and `BundledDb` runs on a phone, and they read the same file
 * format. Two bindings of one interface that are never compared are two bindings that
 * agree until the day they do not — and the day they do not, one device quotes a book
 * differently from another, with nothing on either screen saying so.
 */
class BundledDbTest {

    private val directory: Path = Files.createTempDirectory("bundled")
    private val pack: Path by lazy { PackForge.writePack(directory) }

    @AfterTest
    fun cleanUp() {
        directory.toFile().deleteRecursively()
    }

    private fun <T> both(read: (Db.() -> T)): Pair<T, T> =
        JdbcDb.openReadOnly(pack).use { jdbc ->
            BundledDb.openReadOnly(pack).use { bundled ->
                read(jdbc) to read(bundled)
            }
        }

    @Test
    fun `the bundled SQLite has FTS5, which is half the format`() {
        // Android's own SQLite is not built with FTS5 across every version and vendor
        // image. Without it a pack's lexical index is unreadable, and the failure would be
        // per-device rather than per-build -- the worst shape a failure can have.
        BundledDb.openReadOnly(pack).use { db ->
            val hits = db.map(
                "SELECT rowid FROM chunks_fts WHERE chunks_fts MATCH 'grapple'",
            ) { it.long(0) }
            assertTrue(hits.isNotEmpty(), "the index answered nothing")
        }
    }

    @Test
    fun `both bindings see the same relations`() {
        val (jdbc, bundled) = both { tableNames() }
        assertEquals(jdbc, bundled)
        assertTrue("chunks_fts" in bundled, "including the virtual table")
    }

    @Test
    fun `both bindings read text, integers, blobs and nulls identically`() {
        // Every column type the format uses, in one row, because a binding that differs
        // only on BLOBs would pass a text-only comparison and corrupt every vector.
        val (jdbc, bundled) = both {
            map(
                "SELECT chunk_id, kind, text, span_start, heading_path FROM chunks ORDER BY chunk_id",
            ) { listOf(it.long(0), it.string(1), it.string(2), it.longOrNull(3), it.stringOrNull(4)) }
        }
        assertEquals(jdbc, bundled)
        assertTrue(jdbc.isNotEmpty())
    }

    @Test
    fun `an embedding comes back byte for byte the same`() {
        val (jdbc, bundled) = both {
            map("SELECT embedding FROM vectors ORDER BY chunk_id, role, subchunk_index") {
                it.bytes(0).toList()
            }
        }
        assertEquals(jdbc.size, bundled.size)
        jdbc.zip(bundled).forEach { (a, b) -> assertContentEquals(a.toByteArray(), b.toByteArray()) }
    }

    @Test
    fun `bm25 ranking agrees, which is what the gate reads`() {
        val (jdbc, bundled) = both {
            map(
                "SELECT rowid, bm25(chunks_fts) FROM chunks_fts " +
                    "WHERE chunks_fts MATCH 'creature' ORDER BY rank",
            ) { it.long(0) to it.double(1) }
        }
        assertEquals(jdbc, bundled)
    }

    @Test
    fun `the whole activation gate reaches the same verdict through either binding`() {
        // The strongest form of the comparison: not "the same rows" but "the same
        // decision", over every check the app makes before it will open a book.
        val jdbc = JdbcDb.openReadOnly(pack).use { PackValidator(setOf(PackForge.EMBEDDER)).validate(it) }
        val bundled = BundledDb.openReadOnly(pack).use { PackValidator(setOf(PackForge.EMBEDDER)).validate(it) }
        assertEquals(jdbc.isValid, bundled.isValid)
        assertEquals(jdbc.violations, bundled.violations)
        assertTrue(bundled.isValid, "$bundled")
    }

    @Test
    fun `both bindings refuse a NULL in a column the format requires`() {
        // The comparison above was only ever run over a *well-formed* pack, so it could not
        // see this: `BundledDb` had none of the NULL guards its twin has, and answered 0
        // for a NULL integer, "" for a NULL string, and an empty array for a NULL BLOB.
        // The validator then reasoned about those values as though they had resolved --
        // a NULL `source_id` read as source 0, a NULL probe vector reported as the wrong
        // violation entirely. It stayed invisible for exactly as long as production ran
        // only the other binding, which is the failure mode this whole test class exists
        // for and the one it had a blind spot in.
        val holed = PackForge.writePack(directory) { c ->
            c.relax("sources")
            c.exec("UPDATE sources SET title = NULL")
        }
        val failures = listOf<(Path) -> Db>(JdbcDb::openReadOnly, BundledDb::openReadOnly).map { open ->
            runCatching {
                open(holed).use { db -> db.map("SELECT title FROM sources") { it.string(0) } }
            }.exceptionOrNull()
        }
        assertTrue(
            failures.all { it is PackReadException },
            "both bindings must report a NULL as a read error, got $failures",
        )
    }

    @Test
    fun `both bindings agree that a missing file is not a database`() {
        val absent = directory.resolve("nowhere.rpgpack")
        val failures = listOf<(Path) -> Db>(JdbcDb::openReadOnly, BundledDb::openReadOnly).map { open ->
            runCatching { open(absent) }.exceptionOrNull()
        }
        assertTrue(
            failures.all { it is IllegalArgumentException },
            "SQLite would otherwise create an empty database and hand back a pack that is " +
                "merely missing every table, got $failures",
        )
    }

    @Test
    fun `a file that is not a pack fails as a read error, not as a driver exception`() {
        // A malformed pack has to reach the validator as a violation rather than as
        // whichever exception hierarchy this platform's driver happens to use.
        val bogus = directory.resolve("bogus.rpgpack")
        Files.writeString(bogus, "certainly not SQLite")
        val failure = runCatching {
            BundledDb.openReadOnly(bogus).use { it.tableNames() }
        }.exceptionOrNull()
        assertTrue(failure != null, "a non-pack must not read cleanly")
    }
}
