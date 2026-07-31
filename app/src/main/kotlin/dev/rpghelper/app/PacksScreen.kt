package dev.rpghelper.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rpghelper.session.ShelvedPack
import dev.rpghelper.state.SupersessionImpact

/**
 * The Packs surface: what is installed, what each pack corrects, and what it kept back.
 *
 * **The build report is a first-class listing, not a diagnostic panel** (`06-ui-spec.md`
 * §2.2). Everything the builder dropped or shipped unchecked was computable and appeared
 * nowhere on the device, so *"this table is quotable but not rollable"* was a thing a user
 * could only infer from a control that never appeared. It is on the card now, above the
 * fold, because a pack that admits it ships unadjudicated prose is telling the user
 * something they need before they activate it rather than after.
 */
@Composable
fun PacksScreen(model: PacksViewModel = viewModel()) {
    PacksScreen(
        packs = model.packs,
        busy = model.busy,
        failure = model.failure,
        pending = model.pending,
        onSetActive = model::setActive,
        onConfirm = model::confirm,
        onDismiss = model::dismiss,
    )
}

/**
 * The surface itself, taking state and callbacks rather than the ViewModel.
 *
 * Split so the screen can be rendered under Robolectric without an `Application`, a
 * `state.db`, and a directory of packs behind it. The alternative — testing through the
 * ViewModel — would make every assertion about layout depend on real storage, which is how
 * a UI ends up with no tests at all.
 */
@Composable
fun PacksScreen(
    packs: List<ShelvedPack>,
    busy: Boolean = false,
    failure: String? = null,
    pending: PacksViewModel.PendingActivation? = null,
    onSetActive: (Long, Boolean) -> Unit = { _, _ -> },
    onConfirm: (PacksViewModel.PendingActivation) -> Unit = {},
    onDismiss: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        failure?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(12.dp),
            )
        }

        if (packs.isEmpty()) {
            Text(
                // Not a spinner. Installing is the only way a pack arrives, so an empty
                // library is a state with an action rather than a state to wait out.
                text = if (busy) "Reading the library…" else
                    "No packs installed. Install one from a file to give the app something to quote.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(packs, key = { it.pack.installId }) { shelved ->
                PackCard(
                    shelved = shelved,
                    enabled = !busy,
                    onSetActive = { onSetActive(shelved.pack.installId, it) },
                )
            }
        }
    }

    pending?.let {
        WithdrawalDialog(it, onConfirm = { onConfirm(it) }, onDismiss = onDismiss)
    }
}

@Composable
private fun PackCard(shelved: ShelvedPack, enabled: Boolean, onSetActive: (Boolean) -> Unit) {
    val pack = shelved.pack
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(pack.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "${pack.packUid} ${pack.packVersion} · ${megabytes(pack.byteSize)}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(
                    checked = pack.active,
                    onCheckedChange = onSetActive,
                    enabled = enabled && shelved.unreadable == null,
                    // The toggle's own description names the pack. A row of switches all
                    // announced as "switch" is a screen a screen-reader user cannot use.
                    modifier = Modifier.semantics {
                        contentDescription =
                            if (pack.active) "Deactivate ${pack.title}" else "Activate ${pack.title}"
                    },
                )
            }

            shelved.unreadable?.let {
                Text(
                    "This pack cannot be read: $it",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            // Worst first, and the worst is `unchecked`: a dropped item is absent and
            // absence announces itself, while unchecked content is present and looks
            // exactly like checked content.
            if (shelved.shipsUnchecked) {
                Text(
                    "Ships content nobody adjudicated",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            shelved.buildNotes.forEach { note ->
                Text(
                    "${note.severity}: ${note.subjectKind}" +
                        (note.subjectId?.let { " $it" } ?: "") +
                        (note.detail?.let { " — $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            // Rendered from each row's own snapshot, so an errata pack whose target is not
            // installed still says what it corrects and where.
            shelved.supersessions.forEach { notice ->
                Text(
                    notice.render() + if (notice.inEffect) "" else " (not in effect: that book is not active)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * The question `PackLibrary` refuses to answer for the user.
 *
 * Supersession is unbounded — any active pack may withdraw any chunk of any other — so a
 * pack that hollows out a book already on the shelf is a decision to make rather than one
 * to inherit. The share is named **per book**, because "a third of your core rulebook" is
 * the sentence that conveys it and "a third of everything installed" is not.
 */
@Composable
private fun WithdrawalDialog(
    pending: PacksViewModel.PendingActivation,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${pending.title} would hide part of another book") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                pending.impacts.forEach { Text(describe(it)) }
                Text(
                    if (pending.stale) {
                        "The active set changed while this was on screen, so this is a fresh " +
                            "measurement. Nothing has been activated."
                    } else {
                        "At that size it is a replacement edition. Deactivating the older " +
                            "pack is usually the honest action."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Activate anyway") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

private fun describe(impact: SupersessionImpact): String =
    "%s: %d of %d passages (%.0f%%)".format(
        impact.targetTitle, impact.withdrawnChunks, impact.totalChunks, impact.fraction * 100,
    )

/** Sizes a person reads, not bytes. A 300-page rulebook is "84 MB", never "88080384". */
internal fun megabytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.0f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f kB".format(bytes / 1_000.0)
    else -> "$bytes bytes"
}
