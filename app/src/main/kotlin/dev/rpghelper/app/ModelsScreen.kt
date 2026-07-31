package dev.rpghelper.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rpghelper.model.Availability
import dev.rpghelper.model.ModelManifest
import dev.rpghelper.model.ModelRuntimes
import dev.rpghelper.model.ShelvedModel

/**
 * The Models surface: what weights this device has, and the one network operation in the app.
 *
 * **A manifest is added, never bundled.** `ModelManifest.parse` refuses a placeholder digest,
 * so a manifest cannot exist before the artifact it pins — shipping one would mean shipping
 * either the weights or a lie. What the app can do is make the trust chain visible: a file
 * the user supplies, whose digests were computed from the bytes that will actually be
 * fetched, and a download that verifies against them before anything is called ready.
 */
@Composable
fun ModelsScreen(model: ModelsViewModel = viewModel()) {
    // The system picker, which is the only file access this app has or wants. No storage
    // permission is requested: a document the user chose is a document the app may read.
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let(model::addManifest)
    }

    ModelsScreen(
        models = model.models,
        progress = model.progress,
        busy = model.busy,
        failure = model.failure,
        onAdd = { picker.launch(arrayOf("application/json", "*/*")) },
        onDownload = model::download,
        onCancel = model::cancelDownload,
        onVerify = model::verify,
        onRemoveFiles = model::removeFiles,
        onForget = model::forget,
    )
}

@Composable
fun ModelsScreen(
    models: List<ShelvedModel>,
    progress: Pair<String, Availability.Downloading>? = null,
    busy: Boolean = false,
    failure: String? = null,
    onAdd: () -> Unit = {},
    onDownload: (ModelManifest) -> Unit = {},
    onCancel: () -> Unit = {},
    onVerify: (ModelManifest) -> Unit = {},
    onRemoveFiles: (ModelManifest) -> Unit = {},
    onForget: (ModelManifest) -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        failure?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp),
            )
        }

        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            TextButton(onClick = onAdd) { Text("Add a manifest") }
        }

        if (models.isEmpty()) {
            Text(
                "No models. The app answers rules questions out of your books without one — " +
                    "setting questions are answered as citations until a model is here. " +
                    "Add a manifest whose digests were computed from the weights it names.\n\n" +
                    // Said before the download rather than after it, because a few
                    // gigabytes is a lot to fetch to discover the format is wrong.
                    "This build runs GGUF weights with llama.cpp, on arm64 devices. The " +
                    "context window is sized from this device's memory when the model loads.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(models, key = { it.manifest.id }) { shelved ->
                ModelCard(
                    shelved = shelved,
                    progress = progress?.takeIf { it.first == shelved.manifest.id }?.second,
                    busy = busy,
                    onDownload = { onDownload(shelved.manifest) },
                    onCancel = onCancel,
                    onVerify = { onVerify(shelved.manifest) },
                    onRemoveFiles = { onRemoveFiles(shelved.manifest) },
                    onForget = { onForget(shelved.manifest) },
                )
            }
        }
    }
}

@Composable
private fun ModelCard(
    shelved: ShelvedModel,
    progress: Availability.Downloading?,
    busy: Boolean,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onVerify: () -> Unit,
    onRemoveFiles: () -> Unit,
    onForget: () -> Unit,
) {
    val manifest = shelved.manifest
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(manifest.displayName, style = MaterialTheme.typography.titleMedium)
            // The licence, on the card. These weights come with terms, and a user agreeing
            // to a download is agreeing to those.
            Text(
                "${manifest.license} · ${megabytes(manifest.totalBytes)}",
                style = MaterialTheme.typography.bodySmall,
            )

            if (progress != null) {
                Text(
                    "Downloading over the network — ${megabytes(progress.fetched)} of " +
                        "${megabytes(progress.total)}",
                    style = MaterialTheme.typography.bodySmall,
                )
                LinearProgressIndicator(
                    progress = { if (progress.total == 0L) 0f else progress.fetched.toFloat() / progress.total },
                    modifier = Modifier.fillMaxWidth().semantics {
                        contentDescription = "Downloading ${manifest.displayName}"
                    },
                )
                TextButton(onClick = onCancel) { Text("Cancel") }
                return@Column
            }

            when (shelved.availability) {
                is Availability.Ready -> {
                    Text("Downloaded", style = MaterialTheme.typography.bodySmall)
                    // One sentence, from one place. Three surfaces wording "we cannot run
                    // this yet" three ways would be three different promises.
                    if (ModelRuntimes.runtimeFor(manifest) == null) {
                        Text(
                            ModelRuntimes.unsupported(manifest),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    Row {
                        TextButton(onClick = onVerify, enabled = !busy) { Text("Verify") }
                        TextButton(onClick = onRemoveFiles, enabled = !busy) { Text("Delete files") }
                    }
                }
                is Availability.Downloading -> {
                    // Bytes on disk from a download that stopped. Resumable, and saying so
                    // is what stops a user deleting them to "start clean".
                    Text(
                        "Partly downloaded — ${megabytes(shelved.onDisk)} on disk. " +
                            "Downloading again resumes from there.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row {
                        TextButton(onClick = onDownload, enabled = !busy) { Text("Resume") }
                        TextButton(onClick = onRemoveFiles, enabled = !busy) { Text("Delete files") }
                    }
                }
                is Availability.NotDownloaded -> Row {
                    TextButton(onClick = onDownload, enabled = !busy) {
                        Text("Download ${megabytes(manifest.totalBytes)}")
                    }
                    TextButton(onClick = onForget, enabled = !busy) { Text("Forget") }
                }
                is Availability.Failed -> Row {
                    Text(
                        (shelved.availability as Availability.Failed).reason,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    TextButton(onClick = onDownload, enabled = !busy) { Text("Try again") }
                }
            }
        }
    }
}
