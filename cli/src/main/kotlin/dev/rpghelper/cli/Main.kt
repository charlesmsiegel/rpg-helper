package dev.rpghelper.cli

import dev.rpghelper.builder.CorpusSpec
import dev.rpghelper.builder.PackBuilder
import dev.rpghelper.capabilities.Roller
import dev.rpghelper.capabilities.SecureDiceSource
import dev.rpghelper.model.DownloadResult
import dev.rpghelper.model.ModelDownloader
import dev.rpghelper.model.ModelManifest
import dev.rpghelper.pack.Packs
import dev.rpghelper.state.InstallResult
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
  ask [--why] --library <dir> -- <question>
                                     retrieve, route, and print the cards

  install <dir> <pack.rpgpack> [--replace]   install into a library
  packs <dir>                                list what is installed
  activate <dir> <install-id>                make a pack live
  deactivate <dir> <install-id>              take it out of the active set
  uninstall <dir> <install-id>               forget it, and unlink when unread
  roll <pack.rpgpack> <table-id>     roll on a validated table
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

    // The builder's output goes through the app's own gate, always. Emitting a pack the
    // app would refuse is the one bug a builder must not be able to ship, and the only way
    // to know it cannot is to run the real validator over the real output.
    val report = Packs.validateFile(target, BUNDLED.bundled)
    println(if (report.isValid) "$target activates" else "$target would NOT activate:\n$report")
    return if (report.isValid) 0 else 1
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
    val why = arguments.firstOrNull() == "--why"
    val tail = if (why) arguments.drop(1) else arguments
    val separator = tail.indexOf("--")
    require(separator > 0 && separator < tail.size - 1) {
        "usage: ask [--why] <pack.rpgpack>... -- <question>"
    }
    val head = tail.take(separator)
    val question = tail.drop(separator + 1).joinToString(" ")

    // Either an explicit list of files or a library's active set. The second is the app's
    // own path -- priority as `installed_packs` records it, deactivated packs absent
    // because the user said so rather than because they were not typed.
    val store = if (head.firstOrNull() == "--library") {
        require(head.size == 2) { "usage: ask [--why] --library <dir> -- <question>" }
        Store(Path.of(head[1]))
    } else {
        null
    }

    val opened = store?.let { Library.openActive(it.library) }
        ?: Library.open(head.map { Path.of(it) })

    opened.use { library ->
        library.dropped.forEach { System.err.println("note: $it") }

        // One embedding per distinct contract, because a pack built two years ago and one
        // built today can name different embedders and both must keep working.
        val vectors = BUNDLED.embedPerContract(question, library.active.distinctContracts)
        val retrieved = Pipeline.retrieve(question, library.active, vectors, GATES)

        val router = Router(
            PackCitations(library.packs),
            PackDerivations(library.packs),
            LoadedRollables(library.rollableChunks),
        )
        // No generator: this tool ships no weights, and passing null is how the app says
        // "not downloaded" rather than a special command-line path.
        val answer = router.route(
            retrieved,
            generator = null,
            activePacks = library.packs.map { it.packUid },
        )
        print(renderAnswer(answer, diagnostics = why))
        store?.close()
        return if (answer.refused) 1 else 0
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
                println("  inactive until you activate it")
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

private fun setActive(arguments: List<String>, active: Boolean): Int {
    require(arguments.size == 2) { "usage: ${if (active) "activate" else "deactivate"} <dir> <install-id>" }
    val installId = arguments[1].toLongOrNull() ?: error("'${arguments[1]}' is not an install id")
    Store(Path.of(arguments[0])).use { store ->
        require(store.library.installed().any { it.installId == installId }) {
            "no pack #$installId is installed"
        }
        store.library.setActive(installId, active)
        println("#$installId is now ${if (active) "active" else "inactive"}")
        return 0
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
        println("  \" ${result.row.text}")
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
