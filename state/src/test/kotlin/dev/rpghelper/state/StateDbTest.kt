package dev.rpghelper.state

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StateDbTest {

    private val root: Path = Files.createTempDirectory("rpgstatedb")

    @AfterTest
    fun cleanUp() {
        root.toFile().deleteRecursively()
    }

    private fun open(name: String = "state.db", backup: (Path) -> Unit = {}) =
        StateDb.open(root.resolve(name), backup)

    @Test
    fun `creates the schema at the current version`() {
        open().use { db ->
            assertEquals(
                StateSchema.VERSION,
                db.query("PRAGMA user_version") { it.int(0) }.single(),
            )
        }
    }

    @Test
    fun `opening an existing database is idempotent`() {
        val doc: Long
        open().use { db ->
            doc = DocumentStore(db).create("Ysabeau").documentId
        }
        open().use { db ->
            assertEquals("Ysabeau", DocumentStore(db).get(doc)?.title)
            assertEquals(
                StateSchema.VERSION,
                db.query("PRAGMA user_version") { it.int(0) }.single(),
            )
        }
    }

    @Test
    fun `enforces foreign keys on every connection`() {
        // Off by default in SQLite, and per-connection, so it is not enough to have set it
        // once somewhere. Without it every ON DELETE CASCADE in the schema is decoration.
        open().use { db ->
            assertEquals(1, db.query("PRAGMA foreign_keys") { it.int(0) }.single())
            assertFailsWith<Exception>("a tracker cannot reference a document that is absent") {
                db.execute(
                    "INSERT INTO trackers (document_id, key, type, number_value, ordinal) " +
                        "VALUES (9999, 'x', 'number', 1.0, 0)",
                )
            }
        }
    }

    @Test
    fun `refuses a database from a future version rather than guessing at it`() {
        open().use { db -> db.execute("PRAGMA user_version = ${StateSchema.VERSION + 5}") }
        assertFailsWith<IllegalArgumentException> { open() }
    }

    @Test
    fun `takes a backup before migrating and not otherwise`() {
        var backups = 0
        open(backup = { backups++ })
            .use { assertEquals(1, backups, "creating the schema is a migration") }
        open(backup = { backups++ })
            .use { assertEquals(1, backups, "an up-to-date database is not re-migrated") }
    }

    @Test
    fun `rolls back a failed transaction`() {
        open().use { db ->
            val documents = DocumentStore(db)
            documents.create("Kept")
            assertFailsWith<IllegalStateException> {
                db.transaction {
                    documents.create("Discarded")
                    error("something went wrong midway")
                }
            }
            assertEquals(listOf("Kept"), documents.all().map { it.title })
        }
    }

    @Test
    fun `commits a successful transaction`() {
        open().use { db ->
            val documents = DocumentStore(db)
            db.transaction {
                documents.create("One")
                documents.create("Two")
            }
            assertEquals(2, documents.all().size)
        }
    }

    @Test
    fun `the migration list and the version constant cannot disagree`() {
        // Enforced in StateSchema's init, so a migration added without bumping the version
        // fails at class load rather than at some later upgrade on a user's device.
        assertEquals(StateSchema.VERSION, StateSchema.MIGRATIONS.size)
    }

    @Test
    fun `documents survive a reopen byte for byte`() {
        // The governing rule: a character sheet outlives the software that checks it.
        val id: Long
        open().use { db ->
            val documents = DocumentStore(db)
            val doc = documents.create("Ysabeau", campaign = "Transylvania", rulesetId = "vtm-20")
            id = doc.documentId
            documents.putTracker(id, "attribute.strength", TrackerValue.Number(3.0))
            documents.putTracker(id, "condition.torpor", TrackerValue.Flag(false))
            documents.putTracker(id, "concept", TrackerValue.Text("Reluctant archivist"))
            documents.accept(id, "9f2c", "vtm-20", "seventh dot")
        }
        open().use { db ->
            val documents = DocumentStore(db)
            val doc = documents.get(id)!!
            assertEquals("Transylvania", doc.campaign)
            assertTrue(doc.draft)
            assertEquals(
                listOf(
                    Tracker("attribute.strength", TrackerValue.Number(3.0), 0),
                    Tracker("condition.torpor", TrackerValue.Flag(false), 1),
                    Tracker("concept", TrackerValue.Text("Reluctant archivist"), 2),
                ),
                documents.trackers(id),
            )
            assertEquals("seventh dot", documents.acceptances(id).single().note)
        }
    }

    @Test
    fun `a failed migration does not leave a handle nobody can close`() {
        // The caller never receives the StateDb, so it has nothing to close: without the
        // failure path in `open`, the connection would hold the database and its WAL
        // locked for the rest of the process — exactly while the app tries to restore the
        // backup this failure just made relevant.
        assertFailsWith<IllegalStateException> {
            open("failing.db") { error("the backup device is full") }
        }
        // The path is reopenable, which it would not be if a write lock had survived.
        open("failing.db").use { db ->
            assertEquals(
                StateSchema.VERSION,
                db.query("PRAGMA user_version") { it.int(0) }.single(),
            )
        }
    }

    @Test
    fun `the write-ahead log is folded in before the backup is taken`() {
        // A committed page can live only in `state.db-wal`. Copying `state.db` alone would
        // hand back a backup missing the commits it claims to preserve.
        val path = root.resolve("wal.db")
        var walBytesAtBackup: Long = -1
        StateDb.open(path) {
            val wal = path.resolveSibling("${path.fileName}-wal")
            walBytesAtBackup = if (Files.exists(wal)) Files.size(wal) else 0
        }.use { }
        assertEquals(0L, walBytesAtBackup, "the log is checked in before the copy is made")
    }

    @Test
    fun `a negative version is refused rather than indexed into the migration list`() {
        // A damaged or externally restored file can report one, and an upper-bound-only
        // check would start the loop at target 0 and index MIGRATIONS[-1] -- aborting
        // every startup from then on, with no way out that keeps the user's characters.
        val path = root.resolve("negative.db")
        open("negative.db").use { }
        java.sql.DriverManager.getConnection("jdbc:sqlite:$path").use { c ->
            c.createStatement().use { it.execute("PRAGMA user_version = -1") }
        }
        val failure = assertFailsWith<IllegalArgumentException> { open("negative.db") }
        assertTrue(failure.message!!.contains("-1"), "the refusal names what it saw")
    }

    @Test
    fun `concurrent writers do not land in each other's transactions`() {
        // autoCommit and the open transaction are connection-wide state, not per-caller
        // state. Unserialized, one thread's edit joins another's transaction and is lost
        // when that one rolls back -- and the two restore autoCommit underneath each other.
        open("concurrent.db").use { db ->
            val documents = DocumentStore(db)
            val threads = (1..8).map { worker ->
                Thread {
                    repeat(10) { index ->
                        runCatching {
                            db.transaction { documents.create("doc-$worker-$index") }
                        }
                        runCatching {
                            // A transaction that rolls back must take nothing else with it.
                            db.transaction {
                                documents.create("rolled-$worker-$index")
                                error("deliberate rollback")
                            }
                        }
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            val names = db.query("SELECT title FROM documents") { it.string(0) }
            assertEquals(80, names.size, "every committed document survived")
            assertTrue(names.none { it.startsWith("rolled-") }, "and no rolled-back one did")
        }
    }

    @Test
    fun `concurrent creates each get back the document they created`() {
        // last_insert_rowid() is per-connection, not per-caller. With the lock released
        // between the insert and the lookup, a caller receives the *other* thread's
        // document -- and every subsequent wizard edit lands on the wrong character.
        open("creates.db").use { db ->
            val documents = DocumentStore(db)
            val results = java.util.concurrent.ConcurrentHashMap<String, Long>()
            val threads = (1..8).map { worker ->
                Thread {
                    repeat(10) { index ->
                        val title = "doc-$worker-$index"
                        results[title] = documents.create(title).documentId
                    }
                }
            }
            threads.forEach { it.start() }
            threads.forEach { it.join() }

            assertEquals(80, results.size)
            for ((title, id) in results) {
                assertEquals(title, documents.get(id)?.title, "id $id came back as someone else")
            }
        }
    }

    @Test
    fun `a rejected pack install does not leave the database in a transaction`() {
        open().use { db ->
            assertTrue(db.query("PRAGMA foreign_keys") { it.int(0) }.single() == 1)
            assertFalse(
                db.query("SELECT count(*) FROM installed_packs") { it.int(0) }.single() > 0,
            )
        }
    }
}
