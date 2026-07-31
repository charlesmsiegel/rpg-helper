package dev.rpghelper.cli

import dev.rpghelper.builder.CorpusSpec
import dev.rpghelper.builder.PackBuilder
import dev.rpghelper.capabilities.Roller
import dev.rpghelper.capabilities.SecureDiceSource
import dev.rpghelper.model.DownloadResult
import dev.rpghelper.model.ModelDownloader
import dev.rpghelper.model.ModelManifest
import dev.rpghelper.pack.Packs
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
                                     retrieve, route, and print the cards
  roll <pack.rpgpack> <table-id>     roll on a validated table
  fetch-model <manifest.json> <dir>  download a model and verify it against its digests

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
            "fetch-model" -> fetchModel(rest)
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
    val paths = tail.take(separator).map { Path.of(it) }
    val question = tail.drop(separator + 1).joinToString(" ")

    Library.open(paths).use { library ->
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
        return if (answer.refused) 1 else 0
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
