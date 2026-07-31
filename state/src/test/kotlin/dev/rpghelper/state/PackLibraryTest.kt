package dev.rpghelper.state

import dev.rpghelper.pack.PackForge
import dev.rpghelper.pack.exec
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PackLibraryTest {

    private val root: Path = Files.createTempDirectory("rpgstate")
    private val db = StateDb.open(root.resolve("state.db"))
    private val library = PackLibrary(db, root, setOf(PackForge.EMBEDDER))
    private val incoming: Path = Files.createDirectories(root.resolve("incoming"))

    @AfterTest
    fun cleanUp() {
        db.close()
        root.toFile().deleteRecursively()
    }

    private fun forge(mutate: (java.sql.Connection) -> Unit = {}): Path =
        PackForge.writePack(incoming, mutate)

    private fun installed(result: InstallResult): InstalledPack {
        assertTrue(result is InstallResult.Installed, "expected an install, got $result")
        return result.pack
    }

    // ------------------------------------------------------------------ install

    @Test
    fun `installs a valid pack`() {
        val pack = installed(library.install(forge()))
        assertEquals("test:pack:core", pack.packUid)
        assertEquals("Forged Test Pack", pack.title)
        assertTrue(Files.exists(library.fileOf(pack.installId)))
        assertEquals(64, pack.fileSha256?.length, "a SHA-256 is recorded at install")
    }

    @Test
    fun `a newly installed pack is not active`() {
        // A pack can claim any uid it likes, so arriving active would let an imported file
        // become live content without the user deciding anything.
        assertFalse(installed(library.install(forge())).active)
    }

    @Test
    fun `refuses an invalid pack and leaves nothing behind`() {
        val result = library.install(forge { it.exec("UPDATE chunks SET kind = 'lore'") })
        assertTrue(result is InstallResult.Rejected, "expected rejection, got $result")
        assertTrue(library.installed().isEmpty())
        assertEquals(0, packFiles().size, "a rejected install leaves no file")
    }

    @Test
    fun `refuses a file that is not a pack at all`() {
        val bogus = incoming.resolve("not-a-pack.rpgpack")
        Files.writeString(bogus, "this is not a database")
        assertTrue(library.install(bogus) is InstallResult.Rejected)
        assertTrue(library.installed().isEmpty())
    }

    @Test
    fun `refuses a file beyond the size ceiling before reading it`() {
        val tiny = PackLibrary(db, root, setOf(PackForge.EMBEDDER), PackLibrary.PackLimits(16))
        assertTrue(tiny.install(forge()) is InstallResult.TooLarge)
        assertTrue(library.installed().isEmpty())
    }

    // ------------------------------------------------------------------ replacement

    @Test
    fun `replacing an installed pack requires confirmation`() {
        val first = installed(library.install(forge()))
        val result = library.install(forge())
        assertTrue(result is InstallResult.NeedsConfirmation, "expected confirmation, got $result")
        assertEquals(first.installId, result.existing.installId)
        assertEquals(1, library.installed().size, "an unconfirmed replacement changes nothing")
    }

    @Test
    fun `a confirmed replacement supersedes the previous install`() {
        val first = installed(library.install(forge()))
        val second = installed(library.install(forge(), confirmReplacing = PackForge.PACK_UID))

        assertEquals(listOf(second.installId), library.installed().map { it.installId })
        assertFalse(Files.exists(library.fileOf(first.installId)), "the old file is removed")
        assertTrue(Files.exists(library.fileOf(second.installId)))
    }

    @Test
    fun `replacing an inactive pack leaves it inactive`() {
        installed(library.install(forge()))
        val replacement = installed(library.install(forge(), confirmReplacing = PackForge.PACK_UID))
        assertFalse(replacement.active, "activation is not something an install can grant")
    }

    @Test
    fun `replacing an active pack keeps it active and keeps its priority`() {
        val first = installed(library.install(forge()))
        library.setActive(first.installId, true)
        val replacement = installed(library.install(forge(), confirmReplacing = PackForge.PACK_UID))
        assertTrue(replacement.active, "updating a book you were using keeps it in use")
        assertEquals(first.priority, replacement.priority)
    }

    @Test
    fun `a failed replacement leaves the previous pack installed and intact`() {
        val first = installed(library.install(forge()))
        library.setActive(first.installId, true)

        val broken = forge { it.exec("UPDATE vectors SET embedding = x'00'") }
        assertTrue(library.install(broken, confirmReplacing = PackForge.PACK_UID) is InstallResult.Rejected)

        val survivors = library.installed()
        assertEquals(listOf(first.installId), survivors.map { it.installId })
        assertTrue(survivors.single().active, "the working pack is untouched, still active")
        assertTrue(Files.exists(library.fileOf(first.installId)))
        assertEquals(1, packFiles().size, "the staged replacement is gone")
    }

    @Test
    fun `confirmation names the pack it agreed to replace`() {
        // A boolean says yes without saying yes to what. If the source behind the URI
        // changes between the prompt and the install, a bare `true` would let the second
        // file consume an agreement made about the first.
        installed(library.install(forge()))
        val result = library.install(forge(), confirmReplacing = "test:pack:something-else")
        assertTrue(result is InstallResult.NeedsConfirmation, "expected confirmation, got $result")
        assertEquals(1, library.installed().size)
    }

    @Test
    fun `a pack is measured by the bytes that arrive, not by the size it claims`() {
        val strict = PackLibrary(
            db, root, setOf(PackForge.EMBEDDER), PackLibrary.PackLimits(maxFileBytes = 1024),
        )
        val result = strict.install(forge())
        assertTrue(result is InstallResult.TooLarge, "expected a size refusal, got $result")
        assertEquals(0, strict.installed().size)
        assertEquals(0, packFiles().size, "the partial copy is swept")
    }

    @Test
    fun `a cleanup failure does not unwind a committed replacement`() {
        // Past the swap the new row is ready and the old row is gone. Rolling back on a
        // failure to delete the old *file* would remove the new row and file too, leaving
        // neither installation registered.
        val first = installed(library.install(forge()))

        // Stand in for a reader holding the file open on a locking platform: a non-empty
        // directory where the old file was makes the step-3 delete throw.
        val old = library.fileOf(first.installId)
        Files.delete(old)
        Files.createDirectory(old)
        Files.createFile(old.resolve("held"))

        val second = installed(library.install(forge(), confirmReplacing = PackForge.PACK_UID))

        assertEquals(listOf(second.installId), library.installed().map { it.installId })
        assertTrue(Files.exists(library.fileOf(second.installId)), "the replacement survives")
    }

    @Test
    fun `uninstalling a pack a reader holds defers the delete`() {
        // Unlinking a SQLite file out from under an open connection is not a crash on
        // every platform, which is worse than if it were: where it succeeds, the in-flight
        // query keeps reading and answers citing a book the user just removed.
        val pack = installed(library.install(forge()))
        val lease = library.borrow(pack.installId)!!

        library.uninstall(pack.installId)
        assertEquals(0, library.installed().size, "the row goes when the user says so")
        assertTrue(Files.exists(lease.file), "the bytes stay while someone is reading them")

        lease.close()
        assertFalse(Files.exists(library.fileOf(pack.installId)), "and go when they stop")
    }

    @Test
    fun `the last reader out does the deleting`() {
        val pack = installed(library.install(forge()))
        val first = library.borrow(pack.installId)!!
        val second = library.borrow(pack.installId)!!

        library.uninstall(pack.installId)
        first.close()
        assertTrue(Files.exists(library.fileOf(pack.installId)), "one reader remains")
        second.close()
        assertFalse(Files.exists(library.fileOf(pack.installId)))
    }

    @Test
    fun `a reader that is never uninstalled keeps its file`() {
        val pack = installed(library.install(forge()))
        library.borrow(pack.installId)!!.close()
        assertTrue(Files.exists(library.fileOf(pack.installId)), "closing is not uninstalling")
    }

    @Test
    fun `borrowing a pack that is not there yields nothing rather than a broken lease`() {
        assertNull(library.borrow(404))
    }

    @Test
    fun `reconcile does not sweep a file a reader holds`() {
        // Reconcile is specified to run before any pack is opened, so this should not
        // arise -- but the rule is "a file with a reader is not unlinked", and a rule that
        // only holds when approached from one direction is not a rule.
        val pack = installed(library.install(forge()))
        val lease = library.borrow(pack.installId)!!
        library.uninstall(pack.installId)

        library.reconcile()
        assertTrue(Files.exists(lease.file))
        lease.close()
    }

    @Test
    fun `a condemned pack cannot be borrowed even while its file exists`() {
        // The race the lock closes: deciding on Files.exists alone would hand out a lease
        // on bytes whose deletion is already committed, and the query would read a file
        // that vanishes underneath it.
        val pack = installed(library.install(forge()))
        val holder = library.borrow(pack.installId)!!
        library.uninstall(pack.installId)

        assertTrue(Files.exists(library.fileOf(pack.installId)), "still there for its reader")
        assertNull(library.borrow(pack.installId), "but not lendable to anyone new")
        holder.close()
        assertFalse(Files.exists(library.fileOf(pack.installId)))
    }

    @Test
    fun `verification fails rather than throwing when the file has gone`() {
        // External storage cleanup and a concurrent removal both arrive here, and neither
        // is a digest mismatch. Letting the read throw leaves the row active for a pack
        // every subsequent open will fail on.
        val pack = installed(library.install(forge()))
        library.setActive(pack.installId, true)
        Files.delete(library.fileOf(pack.installId))

        assertFalse(library.verify(pack.installId))
        assertFalse(library.installed().single().active, "and the row is deactivated")
    }

    @Test
    fun `a pack under the size ceiling is still refused for its row counts`() {
        // A file-size limit alone is not a bound. The validator scans and materializes
        // everything before any check could reject it, so the refusal would otherwise
        // arrive as an out-of-memory kill rather than as a refusal.
        val strict = PackLibrary(
            db, root, setOf(PackForge.EMBEDDER),
            PackLibrary.PackLimits(content = dev.rpghelper.pack.PackLimits(maxChunks = 2)),
        )
        val result = strict.install(forge())
        assertTrue(result is InstallResult.TooLarge, "got $result")
        assertTrue(result.limit.contains("chunks"), result.limit)
        assertEquals(0, packFiles().size)
    }

    @Test
    fun `a single oversized value is refused without being loaded`() {
        val strict = PackLibrary(
            db, root, setOf(PackForge.EMBEDDER),
            PackLibrary.PackLimits(content = dev.rpghelper.pack.PackLimits(maxTextBytes = 16)),
        )
        val result = strict.install(forge())
        assertTrue(result is InstallResult.TooLarge, "got $result")
        // Named as table.column, because "some value was too big" is not something anyone
        // can act on and the refusal is the only thing the user ever sees.
        assertTrue(Regex("""^\w+\.\w+: \d+ bytes exceeds""").containsMatchIn(result.limit), result.limit)
    }

    @Test
    fun `the bound covers every column, not only chunk text`() {
        // The ceiling used to be two columns: `chunks.text` and the embedding BLOB. A pack
        // under the file-size limit could still carry one enormous `entities.alias` -- or a
        // capability manifest, or a constraint payload -- and the loaders call `Row.string`
        // on all of them, so the heap was exhausted before the refusal could be returned.
        // Enumerating the columns the loaders read today would have left the next column
        // anyone adds unbounded, so the check asks the file which columns it has.
        val fat = "x".repeat(5 * 1024 * 1024) // over the format's own 4 MiB cell ceiling
        val result = library.install(
            forge { c ->
                // Bound, not interpolated: SQLite caps the length of a *statement* well
                // below the length of a value, so the oversized cell cannot be written by
                // pasting it into SQL.
                c.prepareStatement("UPDATE entities SET alias = ?").use {
                    it.setString(1, fat)
                    it.executeUpdate()
                }
            },
        )
        assertTrue(result is InstallResult.TooLarge, "got $result")
        assertTrue(result.limit.startsWith("entities.alias:"), result.limit)
    }

    @Test
    fun `every relation the loaders materialize has a row ceiling`() {
        // The ceilings named four relations, and the rest were unbounded because nobody had
        // argued about their sizes. `PackValidator.collect` builds maps from every `sources`
        // row and `Supersession.compute` reads every `supersessions` row, so millions of
        // small rows in either exhausts the heap while staying under the per-cell ceiling
        // *and* under the install file-size limit -- refusing a pack by being killed rather
        // than by refusing it.
        val strict = PackLibrary(
            db, root, setOf(PackForge.EMBEDDER),
            PackLibrary.PackLimits(content = dev.rpghelper.pack.PackLimits(maxRelationRows = 1)),
        )
        val result = strict.install(forge())
        assertTrue(result is InstallResult.TooLarge, "got $result")
        assertTrue(result.limit.contains("rows exceeds"), result.limit)
    }

    @Test
    fun `an install reports what the pack admits about itself`() {
        // `build_report` was written by every build and read by nothing. A pack shipping
        // model-written prose that no judge adjudicated says so in that table -- and said
        // it only to whoever ran the builder, while the person installing it got prose with
        // well-formed citation chips and no way to know. The gate cannot re-check the work;
        // carrying the admission forward is the whole of what the app can do.
        val result = library.install(
            forge { c ->
                c.exec(
                    "INSERT INTO build_report (report_id, severity, subject_kind, " +
                        "subject_id, validation, detail) VALUES " +
                        "(90, 'unchecked', 'chunk', '4', 'claim-support', " +
                        "'shipped without a judge')",
                )
            },
        )
        assertTrue(result is InstallResult.Installed, "got $result")
        val unchecked = result.buildNotes.filter { it.severity == "unchecked" }
        assertEquals(1, unchecked.size, "${result.buildNotes}")
        assertEquals("claim-support", unchecked.single().validation)
        assertTrue(dev.rpghelper.pack.BuildReport.shipsUnchecked(result.buildNotes))
    }

    @Test
    fun `unchecked outranks dropped, because absence announces itself and presence does not`() {
        val result = library.install(
            forge { c ->
                c.exec(
                    "INSERT INTO build_report (report_id, severity, subject_kind, " +
                        "subject_id, validation, detail) VALUES " +
                        "(91, 'dropped', 'chunk', '5', 'claim-support', 'invented a detail')," +
                        "(92, 'unchecked', 'chunk', '4', 'claim-support', 'no judge ran')",
                )
            },
        )
        assertTrue(result is InstallResult.Installed, "got $result")
        assertEquals(
            "unchecked",
            result.buildNotes.first().severity,
            "a dropped item is absent and self-announcing; unchecked content is present " +
                "and indistinguishable from checked content: ${result.buildNotes}",
        )
    }

    @Test
    fun `an unreadable file is rejected as malformed, not as too large`() {
        // Preflight reports limit breaches only. A file that cannot be queried at all is
        // not too large, it is not a pack -- and the validator says which.
        val bogus = incoming.resolve("garbage.rpgpack")
        Files.writeString(bogus, "not a database")
        assertTrue(library.install(bogus) is InstallResult.Rejected)
    }

    // ------------------------------------------------------------------ reconciliation

    @Test
    fun `reconcile removes a journal row whose install never completed`() {
        // Exactly what a crash between steps 1 and 3 leaves behind.
        db.execute(
            "INSERT INTO installed_packs (pack_uid, pack_version, title, embedder_id, " +
                "byte_size, state, installed_at, active, priority) " +
                "VALUES ('', '', '', '', 0, ?, 'now', 0, 99)",
            StateSchema.STATE_INSTALLING,
        )
        val orphanId = db.lastInsertId()
        Files.writeString(library.fileOf(orphanId), "half a pack")

        library.reconcile()

        assertTrue(library.installed().isEmpty())
        assertFalse(Files.exists(library.fileOf(orphanId)), "its file goes with it")
    }

    @Test
    fun `reconcile removes a file with no row`() {
        val stray = library.fileOf(4242)
        Files.writeString(stray, "orphan")
        library.reconcile()
        assertFalse(Files.exists(stray))
    }

    @Test
    fun `reconcile leaves a healthy install alone and is idempotent`() {
        val pack = installed(library.install(forge()))
        library.reconcile()
        library.reconcile()
        assertEquals(listOf(pack.installId), library.installed().map { it.installId })
        assertTrue(Files.exists(library.fileOf(pack.installId)))
    }

    // ------------------------------------------------------------------ ordering

    @Test
    fun `priorities stay unique and total across reordering`() {
        val a = installed(library.install(forge()))
        val b = installed(library.install(forge { it.exec("UPDATE pack_meta SET pack_uid = 'b'") }))
        val c = installed(library.install(forge { it.exec("UPDATE pack_meta SET pack_uid = 'c'") }))

        library.reorder(listOf(c.installId, a.installId, b.installId))

        val order = library.installed()
        assertEquals(listOf(c.installId, a.installId, b.installId), order.map { it.installId })
        assertEquals(listOf(0, 1, 2), order.map { it.priority })
    }

    // ------------------------------------------------------------------ integrity

    @Test
    fun `verify passes for an untouched pack`() {
        val pack = installed(library.install(forge()))
        assertTrue(library.verify(pack.installId))
    }

    @Test
    fun `verify deactivates a pack whose bytes changed after install`() {
        // The failure the digest exists for: a bit flip in chunks.text leaves the schema
        // version, probe vector and every declared dimension untouched, so the pack opens
        // and quotes mutated text as byte-exact.
        val pack = installed(library.install(forge()))
        library.setActive(pack.installId, true)

        val file = library.fileOf(pack.installId)
        val bytes = Files.readAllBytes(file)
        bytes[bytes.size / 2] = (bytes[bytes.size / 2] + 1).toByte()
        Files.write(file, bytes)

        assertFalse(library.verify(pack.installId))
        assertFalse(library.installed().single().active, "a corrupt pack is deactivated")
    }

    // ------------------------------------------------------------------ uninstall

    @Test
    fun `uninstalling a pack never touches documents`() {
        val documents = DocumentStore(db)
        val doc = documents.create(title = "Ysabeau", rulesetId = "test-srd-1e")
        documents.putTracker(doc.documentId, "attribute.strength", TrackerValue.Number(3.0))

        val pack = installed(library.install(forge()))
        library.uninstall(pack.installId)

        assertTrue(library.installed().isEmpty())
        val after = documents.get(doc.documentId)
        assertEquals("Ysabeau", after?.title)
        assertEquals("test-srd-1e", after?.rulesetId, "the binding survives; only checking stops")
        assertEquals(1, documents.trackers(doc.documentId).size)
    }

    private fun packFiles(): List<Path> =
        Files.list(root.resolve("packs")).use { it.toList() }
            .filter { it.fileName.toString().endsWith(".rpgpack") }

    @Test
    fun `installing does not leave the pack file open`() {
        val pack = installed(library.install(forge()))
        // If validation or metadata reading held a connection, deletion would fail on
        // platforms that lock open files — and would silently pass here, which is why the
        // assertion is on the delete rather than on the absence of a handle.
        assertTrue(Files.deleteIfExists(library.fileOf(pack.installId)))
        assertNull(library.installed().firstOrNull { it.installId == pack.installId }?.let {
            if (Files.exists(library.fileOf(it.installId))) it else null
        })
    }

    // ------------------------------------------------------------------ open-time checks

    @Test
    fun `borrowing runs the open-time checks and deactivates a damaged pack`() {
        // The cheap half of the bargain: the full digest pass runs in the background, so
        // between two of its runs a pack whose schema, probe vector, or embedder metadata
        // has been damaged would otherwise go straight to retrieval and be queried.
        val pack = installed(library.install(forge()))
        library.setActive(pack.installId, true)
        assertTrue(library.borrow(pack.installId) != null, "intact, so it lends")

        java.sql.DriverManager.getConnection("jdbc:sqlite:${library.fileOf(pack.installId)}")
            .use { it.exec("UPDATE pack_meta SET probe_vector = x'00'") }

        assertNull(library.borrow(pack.installId), "damaged, so it does not")
        assertFalse(
            library.installed().single { it.installId == pack.installId }.active,
            "and it is deactivated rather than left to be lent again next query",
        )
    }

    @Test
    fun `a pack needing an embedder this build lacks is not lent`() {
        val pack = installed(library.install(forge()))
        java.sql.DriverManager.getConnection("jdbc:sqlite:${library.fileOf(pack.installId)}")
            .use { it.exec("UPDATE pack_meta SET embedder_id = 'not-bundled'") }
        assertNull(library.borrow(pack.installId))
    }

    // ------------------------------------------------------------------ install ids

    @Test
    fun `an install id is never handed out twice, even after an uninstall`() {
        // A plain INTEGER PRIMARY KEY assigns max(rowid) + 1, so deleting the highest row
        // frees its id -- and uninstall deletes the row immediately while deferring the
        // file's unlink until the last reader closes. Reuse would stage the next install
        // into `packs/<id>.rpgpack` while a live query is reading that exact path,
        // truncating it under the reader, after which releasing the old lease deletes the
        // *new* pack's bytes.
        val first = installed(library.install(forge()))
        val lease = library.borrow(first.installId)
        assertTrue(lease != null, "held open, as a query in flight would hold it")

        library.uninstall(first.installId)
        val second = installed(library.install(forge()))

        assertTrue(
            second.installId > first.installId,
            "got #${second.installId} after #${first.installId}",
        )
        assertTrue(Files.exists(lease!!.file), "the leased file is still the leased file")
        lease.close()
    }

    // ------------------------------------------------------------------ the byte ceiling

    @Test
    fun `the chunk-text ceiling counts bytes, not characters`() {
        // SQLite's length() on TEXT counts characters. Measured that way, the limit is
        // silently three times larger for CJK and four for emoji -- so the packs this
        // bound exists to catch are exactly the ones that pass it.
        val small = PackLibrary(
            db, root, setOf(PackForge.EMBEDDER),
            PackLibrary.PackLimits(content = dev.rpghelper.pack.PackLimits(maxTextBytes = 400)),
        )
        // Each of these is one character and four UTF-8 bytes; 150 of them is 600 bytes
        // and 150 characters, so it passes a character count and fails a byte count.
        val text = "\uD83D\uDD25".repeat(150)
        val pack = forge { c ->
            c.prepareStatement("UPDATE chunks SET text = ? WHERE chunk_id = 3").use { statement ->
                statement.setString(1, text)
                statement.executeUpdate()
            }
        }
        val result = small.install(pack)
        assertTrue(result is InstallResult.TooLarge, "expected a size refusal, got $result")
    }

    // ------------------------------------------------------------------ broad supersession

    /** A pack that withdraws [keys] from the core book, under its own uid. */
    private fun errata(uid: String, vararg keys: String): Path = forge { c ->
        c.exec("UPDATE pack_meta SET pack_uid = '$uid'")
        c.exec("UPDATE sources SET source_uid = 'errata:' || source_uid")
        c.exec("DELETE FROM supersessions")
        keys.forEachIndexed { index, key ->
            c.exec(
                "INSERT INTO supersessions (supersession_id, target_source_uid, " +
                    "target_stable_key, superseding_chunk_id, target_title) " +
                    "VALUES (${index + 1}, '${PackForge.CORE_SOURCE_UID}', '$key', 1, " +
                    "'Test Core Rulebook')",
            )
        }
    }

    @Test
    fun `a narrow errata pack activates without asking`() {
        // The ordinary case, and it must stay ordinary: a user trained to click through
        // this prompt is a user for whom the prompt that matters does nothing.
        val core = installed(library.install(forge()))
        library.setActive(core.installId, true)

        val patch = installed(library.install(errata("errata:narrow", "core:grapple")))
        assertEquals(
            ActivationResult.Changed,
            library.setActive(patch.installId, true),
        )
        assertTrue(library.installed().single { it.installId == patch.installId }.active)
    }

    @Test
    fun `a pack withdrawing most of an active book asks first`() {
        // At that size it is a replacement edition, and activating it silently leaves the
        // old book installed, active, and mostly unreachable -- with nothing on screen
        // saying so. Supersession is unbounded; what the app can do is refuse to let it
        // happen quietly.
        val core = installed(library.install(forge()))
        library.setActive(core.installId, true)

        val replacement = installed(
            library.install(
                errata("errata:broad", "core:grapple", "core:underdark", "core:ogre"),
            ),
        )
        val result = library.setActive(replacement.installId, true)

        assertTrue(result is ActivationResult.NeedsAcknowledgement, "got $result")
        val impact = result.impacts.single()
        assertEquals(PackForge.CORE_SOURCE_UID, impact.targetSourceUid)
        assertEquals(3, impact.withdrawnChunks)
        assertTrue(impact.fraction > 0.25, "got ${impact.fraction}")
        assertFalse(
            library.installed().single { it.installId == replacement.installId }.active,
            "and nothing changed while the question was unanswered",
        )
    }

    @Test
    fun `the same pack activates once the impact is accepted`() {
        val core = installed(library.install(forge()))
        library.setActive(core.installId, true)
        val replacement = installed(
            library.install(
                errata("errata:broad", "core:grapple", "core:underdark", "core:ogre"),
            ),
        )

        // The acknowledgement is the measured impact itself, not a yes: a yes cannot say
        // what it is a yes to.
        val shown = library.setActive(replacement.installId, true)
        assertTrue(shown is ActivationResult.NeedsAcknowledgement, "got $shown")
        assertEquals(
            ActivationResult.Changed,
            library.setActive(replacement.installId, true, shown.impacts),
        )
        assertTrue(library.installed().single { it.installId == replacement.installId }.active)
    }

    @Test
    fun `a supersession naming a book that is switched off withdraws nothing today`() {
        // Measured against the active set, not against everything installed. Warning about
        // a book the user already deactivated is how a prompt becomes noise.
        installed(library.install(forge())) // installed, never activated
        val replacement = installed(
            library.install(
                errata("errata:broad", "core:grapple", "core:underdark", "core:ogre"),
            ),
        )
        assertEquals(ActivationResult.Changed, library.setActive(replacement.installId, true))
    }

    @Test
    fun `a key the errata invented does not inflate the figure`() {
        // What the user is asked to accept has to be what actually goes dark, so the count
        // is the intersection with the target's own keys rather than the row count.
        val core = installed(library.install(forge()))
        library.setActive(core.installId, true)
        val patch = installed(
            library.install(errata("errata:typo", "core:grapple", "core:not-a-real-key")),
        )
        val impact = library.supersessionImpact(
            library.installed().single { it.installId == patch.installId },
        ).single()
        assertEquals(1, impact.withdrawnChunks)
    }

    @Test
    fun `a replacement is not measured against the edition it replaces`() {
        // The pack being replaced is still an active row while the measurement runs and is
        // deleted moments later in the same transaction. Counting it meant a new edition
        // whose supersessions target passages in its own previous edition measured as
        // broad, installed itself inactive for review, and then deleted the very copy it
        // had been measured against -- leaving no active edition of the book at all, over
        // a withdrawal the swap had already made moot.
        val core = installed(library.install(forge()))
        library.setActive(core.installId, true)

        val replacement = library.install(
            errata(PackForge.PACK_UID, "core:grapple", "core:underdark", "core:ogre"),
            confirmReplacing = PackForge.PACK_UID,
        )
        assertTrue(replacement is InstallResult.Installed, "got $replacement")
        assertTrue(
            replacement.deactivatedForReview.isEmpty(),
            "the only book it withdraws from is the one it is replacing: " +
                "${replacement.deactivatedForReview}",
        )
        assertTrue(replacement.pack.active, "so the replacement stays live")
    }

    @Test
    fun `deactivating never asks`() {
        // Switching a pack off can only ever restore reachability.
        val core = installed(library.install(forge()))
        library.setActive(core.installId, true)
        val replacement = installed(
            library.install(
                errata("errata:broad", "core:grapple", "core:underdark", "core:ogre"),
            ),
        )
        val shown = library.setActive(replacement.installId, true)
                as ActivationResult.NeedsAcknowledgement
        library.setActive(replacement.installId, true, shown.impacts)
        assertEquals(ActivationResult.Changed, library.setActive(replacement.installId, false))
    }

    @Test
    fun `activating a pack that is not installed reports so rather than silently doing nothing`() {
        assertEquals(ActivationResult.NotInstalled, library.setActive(999, true))
    }
}
