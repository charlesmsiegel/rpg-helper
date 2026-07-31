package dev.rpghelper.cli

import dev.rpghelper.session.AskService
import dev.rpghelper.session.BUNDLED
import dev.rpghelper.session.EMBEDDER
import dev.rpghelper.session.GATES
import dev.rpghelper.session.Library
import dev.rpghelper.session.Rules
import dev.rpghelper.session.Store
import dev.rpghelper.builder.CorpusSpec
import dev.rpghelper.builder.PackBuilder
import dev.rpghelper.capabilities.Roller
import dev.rpghelper.capabilities.SecureDiceSource
import dev.rpghelper.model.DownloadResult
import dev.rpghelper.model.ModelDownloader
import dev.rpghelper.model.ModelManifest
import dev.rpghelper.pack.Packs
import dev.rpghelper.state.ActivationResult
import dev.rpghelper.state.AnswerCache
import dev.rpghelper.state.Conversation
import dev.rpghelper.state.DocumentStore
import dev.rpghelper.state.DocumentTransfer
import dev.rpghelper.state.InstallResult
import dev.rpghelper.state.SupersessionImpact
import dev.rpghelper.state.TrackerValue
import dev.rpghelper.retrieval.Pipeline
import dev.rpghelper.routing.LoadedRollables
import dev.rpghelper.routing.PackCitations
import dev.rpghelper.routing.PackDerivations
import dev.rpghelper.routing.Router
import java.nio.file.Files
import java.nio.file.Path
import kotlin.system.exitProcess

private const val USAGE = """
rpg-helper — build packs, and answer questions out of them.

  build <corpus-dir> <out.rpgpack>   assemble a pack and put it through the activation gate
  verify <pack.rpgpack>...           run the activation gate and report
  ask [--why] <pack.rpgpack>... -- <question>
  ask [--why] [--inactive] --library <dir> -- <question>
                                     retrieve, route, and print the cards
                                     --inactive searches packs you switched off

  install <dir> <pack.rpgpack> [--replace]   install into a library
  packs <dir>                                list what is installed
  activate <dir> <install-id> [--accept]     make a pack live
  deactivate <dir> <install-id>              take it out of the active set
  uninstall <dir> <install-id>               forget it, and unlink when unread
  roll <pack.rpgpack> <table-id>     roll on a validated table

  sheet <dir> new <title> [--ruleset <id>]   start a document
  sheet <dir> list | show <id>               what is stored, and what the rules say
  sheet <dir> set <id> <key> <value>         set a tracker and re-check
  sheet <dir> clear <id> <key>               remove one
  sheet <dir> ready <id>                     leave draft: minimums start being checked
  sheet <dir> accept <id> <fingerprint> [note]
                                     keep a deliberate deviation from the book
  sheet <dir> export <id> <file.rpgdoc> | import <file.rpgdoc>
                                     a document as a file, which is how it moves
  fetch-model <manifest.json> <dir>  download a model and verify it against its digests
  make-manifest <id> <name> <license> <base-url> <dir>
                                     pin a manifest to weights you already have

This tool bundles no model weights, so `ask` runs the deterministic stand-in embedder and
no generative model: setting questions answer as citations rather than prose. That is the
same path the app takes before a model is downloaded, not a degraded mode built for the
command line.
"""

fun main(arguments: Array<String>) {
    // Explicitly UTF-8, never the platform default. A quote card is the pack's bytes and
    // nothing else; printing them through a stream that substitutes '?' for every em-dash
    // in the book breaks byte-exactness in the last place it could still be broken.
    System.setOut(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.out), true, "UTF-8"))
    System.setErr(java.io.PrintStream(java.io.FileOutputStream(java.io.FileDescriptor.err), true, "UTF-8"))

    val command = arguments.firstOrNull()
    val rest = arguments.drop(1)
    val status = runCatching {
        when (command) {
            "build" -> build(rest)
            "verify" -> verify(rest)
            "ask" -> ask(rest)
            "roll" -> roll(rest)
            "install" -> install(rest)
            "packs" -> listPacks(rest)
            "activate" -> setActive(rest, true)
            "deactivate" -> setActive(rest, false)
            "uninstall" -> uninstall(rest)
            "sheet" -> sheet(rest)
            "fetch-model" -> fetchModel(rest)
            "make-manifest" -> makeManifest(rest)
            "help", "--help", "-h", null -> {
                println(USAGE.trim())
                0
            }
            else -> {
                System.err.println("unknown command '$command'")
                println(USAGE.trim())
                2
            }
        }
    }.getOrElse {
        // The message, not the stack. Every failure this tool produces on purpose --
        // a pack that will not activate, an anchor that no longer matches, a digest
        // mismatch -- says what is wrong in its message, and a trace above it buries that.
        System.err.println("error: ${it.message}")
        1
    }
    exitProcess(status)
}

// ---------------------------------------------------------------------- build

private fun build(arguments: List<String>): Int {
    require(arguments.size == 2) { "usage: build <corpus-dir> <out.rpgpack>" }
    val corpus = CorpusSpec.load(Path.of(arguments[0]))
    val target = Path.of(arguments[1])
    Files.createDirectories(target.toAbsolutePath().parent)

    val outcome = PackBuilder(corpus, EMBEDDER).buildTo(target)
    outcome.notes.forEach { println("${it.severity}: ${it.subjectKind} ${it.subjectId} — ${it.detail}") }

    // The builder's output goes through the app's own gate, always -- and it goes through
    // it *before* the staged file replaces whatever was at the target, so a refused build
    // leaves the last good pack where it was. `buildTo` owns that ordering; re-validating
    // here would only be a second opinion arriving too late to matter.
    println(
        if (outcome.valid) {
            "$target activates"
        } else {
            "$target was NOT written; the build would not activate:\n${outcome.report}"
        },
    )
    return if (outcome.valid) 0 else 1
}

// ---------------------------------------------------------------------- verify

private fun verify(arguments: List<String>): Int {
    require(arguments.isNotEmpty()) { "usage: verify <pack.rpgpack>..." }
    var worst = 0
    for (argument in arguments) {
        val path = Path.of(argument)
        val report = Packs.validateFile(path, BUNDLED.bundled)
        if (report.isValid) {
            val meta = Packs.readMeta(path)
            println("$path: activates — ${meta.title} (${meta.packUid} ${meta.packVersion})")
        } else {
            println("$path: refused")
            println(report)
            worst = 1
        }
    }
    return worst
}

// ---------------------------------------------------------------------- ask

private fun ask(arguments: List<String>): Int {
    var tail = arguments
    val why = tail.firstOrNull() == "--why"
    if (why) tail = tail.drop(1)
    // The refusal card's offer, on the command line. Explicit, never a fallback: a
    // deactivated pack is deactivated because the user said so.
    val includeInactive = tail.firstOrNull() == "--inactive"
    if (includeInactive) tail = tail.drop(1)

    val separator = tail.indexOf("--")
    require(separator > 0 && separator < tail.size - 1) {
        "usage: ask [--why] [--inactive] <pack.rpgpack>... -- <question>"
    }
    val head = tail.take(separator)
    val question = tail.drop(separator + 1).joinToString(" ")

    // Either an explicit list of files or a library. The second is the app's own path --
    // priority as `installed_packs` records it, deactivated packs absent because the user
    // said so rather than because they were not typed.
    val store = if (head.firstOrNull() == "--library") {
        require(head.size == 2) { "usage: ask [--why] [--inactive] --library <dir> -- <question>" }
        Store(Path.of(head[1]))
    } else {
        require(!includeInactive) { "--inactive applies to --library, which is what has a shelf" }
        null
    }

    val opened = when {
        store == null -> Library.open(head.map { Path.of(it) })
        includeInactive -> Library.openAll(store.library)
        else -> Library.openActive(store.library)
    }

    try {
        opened.use { library ->
            library.dropped.forEach { System.err.println("note: $it") }

            // **`AskService`, not a second copy of the sequence.** This function used to
            // rebuild retrieval and routing by hand, which meant the command line had no
            // answer cache, no follow-up resolution, and no feed -- three behaviours the
            // app has and the tool silently did not, in the one place they were supposed to
            // be shared. The file-list path below keeps the hand-built call because it has
            // no state database to hold a cache or a window, which is a real difference
            // rather than a second implementation.
            if (store != null) {
                val service = AskService(
                    cache = AnswerCache(store.db),
                    conversation = Conversation(store.db),
                )
                val asked = service.ask(
                    question = question,
                    library = library,
                    // No weights are bundled, so route 3 never fires and the cache is
                    // never consulted. That is the app's honest state before a download.
                    generator = null,
                    render = { renderAnswer(it, diagnostics = false) },
                    hasInactivePacks = !includeInactive &&
                        store.library.installed().any { !it.active },
                )
                val answer = asked.answer
                if (answer == null) {
                    // A cache hit: the stored render is the answer, and re-assembling
                    // cards from it would be a second rendering under different code.
                    print(asked.rendered)
                    return 0
                }
                print(renderAnswer(answer, diagnostics = why))
                return if (answer.refused) 1 else 0
            }

            // One embedding per distinct contract, because a pack built two years ago and
            // one built today can name different embedders and both must keep working --
            // and it happens *inside* retrieval, after the alias rewrite. Embedding what
            // the user typed would give the phrasing bridge to lexical search alone.
            val retrieved = Pipeline.retrieve(
                question,
                library.active,
                gates = GATES,
                embed = { rewritten, contracts -> BUNDLED.embedPerContract(rewritten, contracts) },
            )
            val router = Router(
                PackCitations(library.packs),
                PackDerivations(library.packs),
                LoadedRollables(library.rollableChunks),
            )
            val answer = router.route(
                retrieved,
                generator = null,
                activePacks = library.packs.map { it.packUid },
            )
            print(renderAnswer(answer, diagnostics = why))
            return if (answer.refused) 1 else 0
        }
    } finally {
        store?.close()
    }
}

// ---------------------------------------------------------------------- the library

private fun install(arguments: List<String>): Int {
    require(arguments.size in 2..3) { "usage: install <dir> <pack.rpgpack> [--replace]" }
    val replace = arguments.size == 3 && arguments[2] == "--replace"
    require(arguments.size == 2 || replace) { "unknown option '${arguments.getOrNull(2)}'" }
    val source = Path.of(arguments[1])

    Store(Path.of(arguments[0])).use { store ->
        // Replacing is a two-step on purpose. A pack claims its own uid, so an install
        // that silently replaced one would let an imported file overwrite the user's
        // copy of a book by naming it -- and the confirmation names the pack it would
        // replace rather than asking in the abstract.
        val existing = if (replace) Packs.readMeta(source).packUid else null
        return when (val result = store.library.install(source, confirmReplacing = existing)) {
            is InstallResult.Installed -> {
                val pack = result.pack
                println("installed #${pack.installId}: ${pack.title} (${pack.packUid} ${pack.packVersion})")
                // Replacing a live book keeps it live -- the user activated that pack and
                // did not ask for it to go dark. Saying "inactive" regardless would be
                // the tool describing a state the library is not in, and the very next
                // `ask` would answer from content it had just called inactive.
                println(
                    if (pack.active) {
                        "  active, replacing the copy that was active before"
                    } else {
                        "  inactive until you activate it"
                    },
                )
                // Every install reports what it would withdraw, at every size. The
                // acknowledgement threshold decides whether the user must *answer*; it was
                // never meant to decide whether they are told, and an ordinary errata pack
                // -- the common case, and the one below the threshold -- used to withdraw
                // real passages from a real book and say nothing on any surface.
                result.impact.forEach {
                    println(
                        "  amends %s: %d of %d passages (%.0f%%)".format(
                            it.targetTitle, it.withdrawnChunks, it.totalChunks, it.fraction * 100,
                        ),
                    )
                }
                // **What the pack admits about itself.** `build_report` is the builder's
                // record of what it dropped and what it shipped without a judge, and until
                // now nothing read it -- so a pack carrying unadjudicated model prose said
                // so only to whoever ran the build, never to whoever installed it.
                result.buildNotes.forEach {
                    println(
                        "  ${it.severity}: ${it.subjectKind}${it.subjectId?.let { id -> " $id" } ?: ""}" +
                            " — ${it.validation}${it.detail?.let { d -> ": $d" } ?: ""}",
                    )
                }
                if (dev.rpghelper.pack.BuildReport.shipsUnchecked(result.buildNotes)) {
                    println(
                        "  this pack ships prose no judge adjudicated; it renders with " +
                            "citations and the app cannot re-check it",
                    )
                }
                if (result.deactivatedForReview.isNotEmpty()) {
                    println(
                        "  installed inactive: it replaces an active pack and now withdraws " +
                            "more than a quarter of another active book, which is a decision " +
                            "to make rather than one to inherit",
                    )
                }
                0
            }
            is InstallResult.NeedsConfirmation -> {
                System.err.println(
                    "${result.existing.packUid} is already installed as #${result.existing.installId} " +
                        "(${result.existing.title} ${result.existing.packVersion}). " +
                        "Pass --replace to replace it.",
                )
                1
            }
            is InstallResult.TooLarge -> {
                System.err.println("refused: ${result.limit}")
                1
            }
            is InstallResult.Rejected -> {
                System.err.println("refused: this pack would not activate")
                System.err.println(result.report)
                1
            }
        }
    }
}

private fun listPacks(arguments: List<String>): Int {
    require(arguments.size == 1) { "usage: packs <dir>" }
    Store(Path.of(arguments[0])).use { store ->
        val installed = store.library.installed()
        if (installed.isEmpty()) {
            println("nothing installed")
            return 0
        }
        for (pack in installed) {
            // The digest is rechecked rather than trusted: the file lives where the user
            // can reach it, and a pack edited after install is one whose every span is
            // subtly wrong and whose every quotation is subtly not what the book says.
            val intact = store.library.verify(pack.installId)
            println(
                "#${pack.installId}  ${if (pack.active) "active  " else "inactive"}  " +
                    "priority ${pack.priority}  ${pack.title} " +
                    "(${pack.packUid} ${pack.packVersion}, ${pack.byteSize} bytes)" +
                    if (intact) "" else "  [BYTES CHANGED — deactivated]",
            )
        }
        return 0
    }
}

/**
 * Names the share per book.
 *
 * Supersession is unbounded: any active pack may withdraw any chunk of any other. What the
 * app can do is refuse to let it happen quietly, and "a third of your core rulebook" is the
 * sentence that does that where "a third of everything installed" does not.
 */
private fun report(installId: Long, impacts: List<SupersessionImpact>) {
    System.err.println(
        "#$installId would withdraw a large part of a book that is already active:",
    )
    impacts.forEach {
        System.err.println(
            "  %s: %d of %d passages (%.0f%%)".format(
                it.targetTitle, it.withdrawnChunks, it.totalChunks, it.fraction * 100,
            ),
        )
    }
}

private fun setActive(arguments: List<String>, active: Boolean): Int {
    val accept = active && arguments.lastOrNull() == "--accept"
    val head = if (accept) arguments.dropLast(1) else arguments
    require(head.size == 2) {
        "usage: ${if (active) "activate <dir> <install-id> [--accept]" else "deactivate <dir> <install-id>"}"
    }
    val installId = head[1].toLongOrNull() ?: error("'${head[1]}' is not an install id")

    Store(Path.of(head[0])).use { store ->
        // `--accept` is not passed straight through. The library now requires the *impact*
        // rather than a yes, because a yes cannot say what it is a yes to -- so the flag
        // means "measure it, print it, and then accept exactly that", with no window
        // between the measurement and the acceptance for the active set to change.
        val acknowledged = if (!accept) null else {
            (store.library.setActive(installId, true) as? ActivationResult.NeedsAcknowledgement)
                ?.also { report(installId, it.impacts) }
                ?.impacts
        }
        return when (val result = store.library.setActive(installId, active, acknowledged)) {
            is ActivationResult.Changed -> {
                println("#$installId is now ${if (active) "active" else "inactive"}")
                0
            }
            is ActivationResult.NotInstalled -> {
                System.err.println("no pack #$installId is installed")
                1
            }
            // Supersession is unbounded: any active pack may withdraw any chunk of any
            // other. What the app can do is refuse to let it happen quietly, so the share
            // is named per book before it is accepted.
            is ActivationResult.NeedsAcknowledgement -> {
                report(installId, result.impacts)
                System.err.println(
                    if (result.stale) {
                        "The active set changed while this was being decided, so what would " +
                            "be withdrawn is no longer what was accepted. Nothing changed; " +
                            "run it again."
                    } else {
                        "At that size it is a replacement edition. Deactivating the old pack " +
                            "is usually the honest action; pass --accept to activate anyway."
                    },
                )
                1
            }
        }
    }
}

private fun uninstall(arguments: List<String>): Int {
    require(arguments.size == 2) { "usage: uninstall <dir> <install-id>" }
    val installId = arguments[1].toLongOrNull() ?: error("'${arguments[1]}' is not an install id")
    Store(Path.of(arguments[0])).use { store ->
        val pack = store.library.installed().singleOrNull { it.installId == installId }
            ?: error("no pack #$installId is installed")
        store.library.uninstall(installId)
        // The row goes now -- the pack is uninstalled the moment the user says so -- and
        // the bytes go when the last reader closes, or at the next open if this process
        // dies first. Documents are never touched; only their validation stops.
        println("uninstalled #$installId (${pack.title})")
        return 0
    }
}

// ---------------------------------------------------------------------- sheet

/**
 * Documents, trackers, and the rule check — **the production caller the constraints engine
 * did not have.**
 *
 * `ConstraintParser` and `ConstraintEngine` were complete and tested and nothing outside
 * their own tests ever built a `ConstraintSet` from an installed pack, so the state layer
 * could store a character and could not check one. This is where a sheet meets the active
 * set: every mutation re-evaluates and prints what changed, because a validator that has to
 * be asked is a validator nobody asks.
 */
private fun sheet(arguments: List<String>): Int {
    require(arguments.size >= 2) { SHEET_USAGE }
    val rest = arguments.drop(2)
    Store(Path.of(arguments[0])).use { store ->
        val documents = DocumentStore(store.db)
        return when (arguments[1]) {
            "new" -> {
                require(rest.isNotEmpty()) { "usage: sheet <dir> new <title> [--ruleset <id>]" }
                val ruleset = rest.indexOf("--ruleset").takeIf { it >= 0 }?.let { rest[it + 1] }
                val title = rest.first()
                val document = documents.create(title = title, rulesetId = ruleset)
                println(
                    "#${document.documentId}  $title" +
                        (ruleset?.let { " (bound to $it)" } ?: " (unbound: nothing will check it)"),
                )
                0
            }

            "list" -> {
                val all = documents.all()
                if (all.isEmpty()) println("no documents") else all.forEach {
                    println(
                        "#${it.documentId}  ${it.title}  ${it.rulesetId ?: "unbound"}" +
                            if (it.draft) "  [draft]" else "",
                    )
                }
                0
            }

            "set" -> {
                require(rest.size == 3) { "usage: sheet <dir> set <document-id> <key> <value>" }
                val id = documentId(rest[0])
                // Typed by what was written, not by what the key looks like. A `range`
                // constraint reads numbers and skips everything else, so storing `3` as text
                // would leave the tracker on screen and invisible to every rule about it.
                val value = rest[2].toDoubleOrNull()?.let { TrackerValue.Number(it) }
                    ?: when (rest[2]) {
                        "true", "yes" -> TrackerValue.Flag(true)
                        "false", "no" -> TrackerValue.Flag(false)
                        else -> TrackerValue.Text(rest[2])
                    }
                documents.putTracker(id, rest[1], value)
                printCheck(store, documents, id)
                0
            }

            "clear" -> {
                require(rest.size == 2) { "usage: sheet <dir> clear <document-id> <key>" }
                val id = documentId(rest[0])
                documents.removeTracker(id, rest[1])
                printCheck(store, documents, id)
                0
            }

            "ready" -> {
                require(rest.size == 1) { "usage: sheet <dir> ready <document-id>" }
                val id = documentId(rest[0])
                // Clearing `draft` is the moment the sheet claims to be complete, which is
                // the moment minimum bounds start being evaluated. Printing the check right
                // after is the whole point of making it a deliberate action.
                documents.setDraft(id, draft = false)
                printCheck(store, documents, id)
                0
            }

            "accept" -> {
                require(rest.size >= 2) {
                    "usage: sheet <dir> accept <document-id> <fingerprint> [note]"
                }
                val id = documentId(rest[0])
                val ruleset = documents.get(id)?.rulesetId
                    ?: error("#$id is unbound, so it has no violations to accept")
                documents.accept(id, rest[1], ruleset, rest.drop(2).joinToString(" ").ifBlank { null })
                printCheck(store, documents, id)
                0
            }

            "export" -> {
                require(rest.size == 2) { "usage: sheet <dir> export <document-id> <file.rpgdoc>" }
                val id = documentId(rest[0])
                val target = Path.of(rest[1])
                // Round-tripped through the file being replaced, if there is one: a document
                // exported by a later build and re-imported by this one must not lose the
                // fields this build does not know about.
                val previous = runCatching { Files.readString(target) }.getOrNull()
                Files.writeString(target, DocumentTransfer.export(documents, id, previous))
                println("wrote $target")
                0
            }

            "import" -> {
                require(rest.size == 1) { "usage: sheet <dir> import <file.rpgdoc>" }
                val imported = DocumentTransfer.import(documents, Files.readString(Path.of(rest[0])))
                // Dropped rulings are named. An acceptance that quietly failed to arrive is
                // found by seeing a flag the user thought they had settled.
                imported.droppedAcceptances.forEach { System.err.println("note: $it") }
                println("imported as #${imported.documentId}")
                printCheck(store, documents, imported.documentId)
                0
            }

            "show" -> {
                require(rest.size == 1) { "usage: sheet <dir> show <document-id>" }
                val id = documentId(rest[0])
                val document = documents.get(id) ?: error("no document #$id")
                println("#$id  ${document.title}  ${document.rulesetId ?: "unbound"}" +
                    if (document.draft) "  [draft]" else "")
                documents.trackers(id).forEach { println("  ${it.key} = ${describe(it.value)}") }
                printCheck(store, documents, id)
                0
            }

            else -> error(SHEET_USAGE)
        }
    }
}

private const val SHEET_USAGE = """usage:
  sheet <dir> new <title> [--ruleset <id>]
  sheet <dir> list
  sheet <dir> show <document-id>
  sheet <dir> set <document-id> <key> <value>
  sheet <dir> clear <document-id> <key>
  sheet <dir> ready <document-id>
  sheet <dir> accept <document-id> <fingerprint> [note]
  sheet <dir> export <document-id> <file.rpgdoc>
  sheet <dir> import <file.rpgdoc>"""

private fun documentId(argument: String): Long =
    argument.toLongOrNull() ?: error("'$argument' is not a document id")

private fun describe(value: TrackerValue): String = when (value) {
    is TrackerValue.Number -> value.value.toString().removeSuffix(".0")
    is TrackerValue.Flag -> if (value.value) "yes" else "no"
    is TrackerValue.Text -> value.value
}

/**
 * Evaluates one document against the active set and prints the result.
 *
 * The active set is opened for the length of the check and closed again, the same way the
 * app opens it per question: a document validated against packs the user deactivated ten
 * minutes ago is a document validated against rules that are no longer in play.
 */
private fun printCheck(store: Store, documents: DocumentStore, documentId: Long) {
    val check = Library.openActive(store.library).use { library ->
        Rules.check(library, documents, documentId)
    }
    check.dropped.forEach { System.err.println("note: constraint ${it.constraintId} dropped — ${it.reason}") }

    if (check.unchecked) {
        // Never "no violations". A sheet bound to a game whose book is not active has been
        // checked against nothing, and saying it is clean would be a claim about rules the
        // app cannot see.
        println("not checked: no active pack supplies this document's ruleset")
        return
    }
    // "No violations" above a list of accepted ones contradicts itself. The sheet departs
    // from the book either way; what acceptance changed is whether that is a flag.
    if (check.flagged.isEmpty()) {
        println(if (check.accepted.isEmpty()) "no violations" else "no violations beyond the accepted:")
    }
    check.flagged.forEach { flagged ->
        println("!! ${flagged.violation.explanation}")
        flagged.citation?.let { println("     — ${dev.rpghelper.routing.render(it)}") }
        // The fingerprint is what `accept` takes, so it is printed where the user reads the
        // violation rather than left to be looked up somewhere the CLI does not offer.
        println("     accept with: ${flagged.violation.fingerprint}")
    }
    check.accepted.forEach {
        println("~~ ${it.violation.explanation}  [accepted${it.note?.let { n -> ": $n" } ?: ""}]")
    }
}

// ---------------------------------------------------------------------- roll

private fun roll(arguments: List<String>): Int {
    require(arguments.size == 2) { "usage: roll <pack.rpgpack> <table-id>" }
    val tableId = arguments[1].toLongOrNull() ?: error("'${arguments[1]}' is not a table id")

    Library.open(listOf(Path.of(arguments[0]))).use { library ->
        library.dropped.forEach { System.err.println("note: $it") }
        val tables = library.rollables.values.flatten()
        val table = tables.singleOrNull { it.tableId == tableId }
            ?: error(
                "no loadable table $tableId; this pack offers " +
                    tables.joinToString(", ") { "${it.tableId} (${it.label})" }.ifEmpty { "none" },
            )

        val result = Roller(SecureDiceSource()).roll(table).getOrThrow()
        println("${table.label}: ${result.expression} → ${result.dice.joinToString(" + ")}" +
            (if (result.modifier != 0) " ${if (result.modifier > 0) "+" else "−"} ${Math.abs(result.modifier)}" else "") +
            " = ${result.total}")
        // The outcome is a validated span of a verbatim table chunk, so it is quoted and
        // cited like any other quotation rather than paraphrased into the roll line.
        val citation = PackCitations(library.packs)
            .resolve(dev.rpghelper.pack.ChunkRef(library.packs.single().packUid, result.chunkId))
        // Every line, not just the first. An outcome carrying a newline had its later
        // lines printed flush left, where nothing marks them as the book's own words.
        result.row.text.lineSequence().forEach { println("  \" $it") }
        citation?.let { println("    — ${dev.rpghelper.routing.render(it)}") }
        return 0
    }
}

// ---------------------------------------------------------------------- fetch-model

private fun fetchModel(arguments: List<String>): Int {
    require(arguments.size == 2) { "usage: fetch-model <manifest.json> <dir>" }
    val manifest = ModelManifest.parse(Files.readString(Path.of(arguments[0])))
    val downloader = ModelDownloader(Path.of(arguments[1]))

    println("${manifest.displayName} (${manifest.license}) — ${manifest.totalBytes} bytes")
    var lastPercent = -1
    return when (val result = downloader.download(manifest, onProgress = { progress ->
        val percent = (progress.fetched * 100 / maxOf(1, progress.total)).toInt()
        if (percent != lastPercent) {
            lastPercent = percent
            print("\r  $percent%")
            System.out.flush()
        }
    })) {
        is DownloadResult.Complete -> {
            println("\rverified ${result.files.size} file(s) into ${arguments[1]}")
            0
        }
        is DownloadResult.Failed -> {
            System.err.println("\rdownload failed: ${result.reason}")
            1
        }
        DownloadResult.Cancelled -> {
            System.err.println("\rcancelled")
            1
        }
    }
}

// ---------------------------------------------------------------------- make-manifest

/**
 * Writes a manifest pinned to weights already on disk.
 *
 * `ModelManifest` refuses a placeholder digest at parse, on purpose: a manifest is
 * written before the artifact it describes exists, and tolerating the placeholder is how
 * a build comes to download several gigabytes of executable behaviour and verify nothing.
 * That refusal is only tenable if filling the field in is easy, and this is what makes it
 * easy — fetch the weights however you like, point this at the directory, and get a
 * manifest pinned to the exact bytes you looked at.
 *
 * The result is parsed back through `ModelManifest` before it is printed. A generator that
 * can emit something its own parser rejects is a generator that will.
 */
private fun makeManifest(arguments: List<String>): Int {
    require(arguments.size == 5) {
        "usage: make-manifest <id> <display-name> <license> <base-url> <dir>"
    }
    print(manifestJson(arguments[0], arguments[1], arguments[2], arguments[3], Path.of(arguments[4])))
    return 0
}

internal fun manifestJson(
    id: String,
    displayName: String,
    license: String,
    baseUrl: String,
    directory: Path,
): String {
    require(Files.isDirectory(directory)) { "$directory is not a directory" }

    val files = Files.list(directory).use { stream ->
        stream.filter { Files.isRegularFile(it) }
            .filter { !it.fileName.toString().startsWith(".") }
            .sorted()
            .toList()
    }
    require(files.isNotEmpty()) { "$directory holds no files to pin" }

    val entries = files.joinToString(",\n") { file ->
        val name = file.fileName.toString()
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        Files.newInputStream(file).use { input ->
            val buffer = ByteArray(1 shl 16)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        """    {
      "name": ${quote(name)},
      "url": ${quote(baseUrl.trimEnd('/') + "/" + urlSegment(name))},
      "bytes": ${Files.size(file)},
      "sha256": "$hex"
    }"""
    }

    val json = """{
  "id": ${quote(id)},
  "display_name": ${quote(displayName)},
  "license": ${quote(license)},
  "files": [
$entries
  ]
}
"""
    // Round-tripped before it is returned: every rule the parser enforces -- HTTPS, real
    // digests, a single safe leaf per file name -- is enforced on the way out too.
    ModelManifest.parse(json)
    return json
}

/**
 * A file name as one URL path segment.
 *
 * `model v2.gguf` and `weights#1.bin` are valid leaf names, and concatenated raw they
 * produce a URL that either throws in `URI.create` or asks for a different path — `#`
 * becomes a fragment, so the request goes to the wrong file and succeeds. The manifest
 * still passes its own round-trip check, so the failure surfaces only later, in
 * `fetch-model`, on someone else's machine.
 *
 * Unreserved characters per RFC 3986 pass through; everything else is percent-encoded
 * from its UTF-8 bytes. `URLEncoder` is not used: it encodes for query strings, where a
 * space becomes `+` rather than `%20`, which in a path is a literal plus sign.
 */
internal fun urlSegment(name: String): String = buildString {
    for (byte in name.toByteArray(Charsets.UTF_8)) {
        val character = byte.toInt().toChar()
        if (character.isLetterOrDigit() && byte.toInt() in 0..127 ||
            character in "-._~"
        ) {
            append(character)
        } else {
            append("%%%02X".format(byte.toInt() and 0xFF))
        }
    }
}

/** JSON string escaping. A file name holding a quote or a backslash is a valid file name. */
private fun quote(value: String): String = buildString {
    append('"')
    for (character in value) {
        when (character) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (character < ' ') append("\\u%04x".format(character.code)) else append(character)
        }
    }
    append('"')
}
