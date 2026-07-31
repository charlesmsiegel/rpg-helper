package dev.rpghelper.app

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.rpghelper.session.Flagged
import dev.rpghelper.session.Passage
import dev.rpghelper.session.RuleCheck
import dev.rpghelper.session.SheetState
import dev.rpghelper.session.Validation
import dev.rpghelper.state.Tracker
import dev.rpghelper.state.TrackerValue
import dev.rpghelper.state.Violation
import dev.rpghelper.routing.render

/**
 * Documents, grouped by campaign, with *Unfiled* for those without one.
 *
 * The validation state is on every row because **the three checked states have different
 * remedies** (`06-ui-spec.md` §2.3), and *partly validated* exists so the label cannot
 * overclaim: a malformed constraint row is dropped rather than rejecting the pack, and
 * without this state a sheet would read as validated while a rule it should have been
 * checked against never ran.
 */
@Composable
fun DocumentsScreen(model: DocumentsViewModel = viewModel()) {
    val open = model.open
    if (open != null) {
        SheetScreen(
            sheet = open,
            passage = model.passage,
            busy = model.busy,
            failure = model.failure,
            onBack = model::close,
            onPutTracker = { key, value -> model.putTracker(open.document.documentId, key, value) },
            onRemoveTracker = { model.removeTracker(open.document.documentId, it) },
            onSetDraft = { model.setDraft(open.document.documentId, it) },
            onAccept = { violation, note -> model.accept(open.document.documentId, violation, note) },
            onUnaccept = { model.unaccept(open.document.documentId, it) },
            onShowPassage = model::showPassage,
            onDismissPassage = model::dismissPassage,
        )
        return
    }

    DocumentList(
        sheets = model.sheets,
        failure = model.failure,
        onOpen = model::openSheet,
        onCreate = model::create,
    )
}

@Composable
fun DocumentList(
    sheets: List<SheetState>,
    failure: String? = null,
    onOpen: (Long) -> Unit = {},
    onCreate: (String, String?, String?) -> Unit = { _, _, _ -> },
) {
    var creating by remember { mutableStateOf(false) }

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
            TextButton(onClick = { creating = true }) { Text("New document") }
        }

        if (sheets.isEmpty()) {
            Text(
                "No documents yet. A document holds trackers, and binding one to a ruleset " +
                    "is what makes the books check it.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(16.dp),
            )
        }

        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // Grouped by campaign, `Unfiled` last -- a heading per group rather than a
            // sort, because "which game is this" is the question a shelf of characters is
            // actually browsed by.
            val groups = sheets.groupBy { it.campaign }
            val named: List<String?> = groups.keys.filterNotNull().sorted()
            val ordered = named + if (groups.containsKey(null)) listOf(null) else emptyList()
            ordered.forEach { campaign ->
                val inGroup = groups[campaign].orEmpty()
                if (inGroup.isEmpty()) return@forEach
                item(key = "campaign-${campaign ?: "unfiled"}") {
                    Text(
                        campaign ?: "Unfiled",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp, start = 4.dp),
                    )
                }
                items(inGroup, key = { it.documentId }) { sheet ->
                    SheetRow(sheet, onOpen = { onOpen(sheet.documentId) })
                }
            }
        }
    }

    if (creating) {
        NewDocumentDialog(
            onDismiss = { creating = false },
            onCreate = { title, campaign, ruleset ->
                creating = false
                onCreate(title, campaign, ruleset)
            },
        )
    }
}

@Composable
private fun SheetRow(sheet: SheetState, onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(sheet.title, style = MaterialTheme.typography.titleMedium)
            Text(describe(sheet), style = MaterialTheme.typography.bodySmall)
            if (sheet.violations > 0) {
                Text(
                    "${sheet.violations} violation${if (sheet.violations == 1) "" else "s"}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/**
 * The row's own sentence, and it never claims more checking than happened.
 *
 * The draft badge is here rather than decorative: minimum-bound violations are suppressed
 * while a document is a draft, and a user should know that is why the sheet looks clean.
 */
internal fun describe(sheet: SheetState): String {
    val state = when (sheet.validation) {
        Validation.VALIDATED -> "validated"
        Validation.PARTLY_VALIDATED ->
            "partly validated — ${sheet.dropped} rule${if (sheet.dropped == 1) "" else "s"} " +
                "could not be loaded"
        Validation.UNVALIDATED -> "unvalidated — its pack is not installed or not active"
        Validation.UNBOUND -> "unbound — nothing is checking it"
    }
    val accepted = if (sheet.accepted > 0) ", ${sheet.accepted} accepted" else ""
    val draft = if (sheet.draft) " · draft: minimums are not checked yet" else ""
    return state + accepted + draft
}

@Composable
private fun NewDocumentDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String?, String?) -> Unit,
) {
    var title by remember { mutableStateOf("") }
    var campaign by remember { mutableStateOf("") }
    var ruleset by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New document") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                TextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                TextField(campaign, { campaign = it }, label = { Text("Campaign") }, singleLine = true)
                TextField(ruleset, { ruleset = it }, label = { Text("Ruleset") }, singleLine = true)
                Text(
                    // Naming the trade rather than defaulting it. A document with no
                    // ruleset works fine and is checked by nothing, which is a choice the
                    // user should be making knowingly.
                    "A ruleset binds this to a game's books. Leave it empty and the trackers " +
                        "still work — nothing will check them.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(title, campaign.ifBlank { null }, ruleset.ifBlank { null }) },
                enabled = title.isNotBlank(),
            ) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------------- the document view

/**
 * One document: its trackers, and the rules against them.
 *
 * Violations render **against the tracker involved** where the constraint names one, so a
 * flag about `aptitude.might` is beside `aptitude.might` rather than in a list at the
 * bottom that the user has to correlate by reading. Trackers are what people reach for
 * mid-session, so editing one is a direct manipulation here — not a modal, not an edit mode.
 */
@Composable
fun SheetScreen(
    sheet: OpenSheet,
    passage: Passage? = null,
    busy: Boolean = false,
    failure: String? = null,
    onBack: () -> Unit = {},
    onPutTracker: (String, String) -> Unit = { _, _ -> },
    onRemoveTracker: (String) -> Unit = {},
    onSetDraft: (Boolean) -> Unit = {},
    onAccept: (Violation, String?) -> Unit = { _, _ -> },
    onUnaccept: (Violation) -> Unit = {},
    onShowPassage: (Violation) -> Unit = {},
    onDismissPassage: () -> Unit = {},
) {
    var key by remember { mutableStateOf("") }
    var value by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("Documents") }
            Text(sheet.document.title, style = MaterialTheme.typography.titleMedium)
        }

        Text(headline(sheet.check), style = MaterialTheme.typography.bodySmall)

        failure?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        TextButton(onClick = { onSetDraft(!sheet.document.draft) }, enabled = !busy) {
            Text(
                if (sheet.document.draft) "Mark complete (starts checking minimums)"
                else "Back to draft (stops checking minimums)",
            )
        }

        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(sheet.trackers, key = { it.key }) { tracker ->
                TrackerRow(
                    tracker = tracker,
                    // Only the violations that name this tracker. A `sum_range` over a
                    // whole namespace names none, and pinning it to an arbitrary member
                    // would blame one value for a total.
                    violations = sheet.check.flagged.filter { tracker.key in it.violation.explanation },
                    enabled = !busy,
                    onRemove = { onRemoveTracker(tracker.key) },
                    onAccept = { onAccept(it, null) },
                    onShowPassage = onShowPassage,
                )
            }

            // Everything the flags could not be attached to a single tracker.
            val unattached = sheet.check.flagged.filterNot { flagged ->
                sheet.trackers.any { it.key in flagged.violation.explanation }
            }
            items(unattached, key = { it.violation.fingerprint }) { flagged ->
                ViolationRow(flagged, onAccept = { onAccept(it, null) }, onShowPassage = onShowPassage)
            }

            if (sheet.check.accepted.isNotEmpty()) {
                item(key = "accepted-heading") {
                    Text(
                        "Deliberate deviations",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                items(sheet.check.accepted, key = { "accepted-" + it.violation.fingerprint }) {
                    Column(Modifier.padding(vertical = 4.dp)) {
                        Text(it.violation.explanation, style = MaterialTheme.typography.bodyMedium)
                        it.note?.let { note -> Text(note, style = MaterialTheme.typography.bodySmall) }
                        TextButton(onClick = { onUnaccept(it.violation) }) { Text("Flag it again") }
                    }
                }
            }
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            TextField(
                key, { key = it },
                label = { Text("Tracker") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            TextField(
                value, { value = it },
                label = { Text("Value") },
                singleLine = true,
                modifier = Modifier.width(120.dp),
            )
            TextButton(
                onClick = { onPutTracker(key, value); key = ""; value = "" },
                enabled = !busy && key.isNotBlank(),
            ) { Text("Set") }
        }
    }

    passage?.let { PassageDialog(it, onDismissPassage) }
}

/** Never "no violations" when nothing was checked. */
internal fun headline(check: RuleCheck): String = when {
    check.unchecked -> "Not checked: no active pack supplies this document's ruleset"
    check.clean -> "Matches the book"
    check.flagged.isEmpty() -> "No violations beyond the ones you accepted"
    else -> "${check.flagged.size} violation${if (check.flagged.size == 1) "" else "s"}"
}

@Composable
private fun TrackerRow(
    tracker: Tracker,
    violations: List<Flagged>,
    enabled: Boolean,
    onRemove: () -> Unit,
    onAccept: (Violation) -> Unit,
    onShowPassage: (Violation) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(tracker.key, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text(show(tracker.value), style = MaterialTheme.typography.bodyLarge)
            TextButton(
                onClick = onRemove,
                enabled = enabled,
                modifier = Modifier.semantics { contentDescription = "Remove ${tracker.key}" },
            ) { Text("Remove") }
        }
        violations.forEach { ViolationRow(it, onAccept, onShowPassage) }
    }
}

@Composable
private fun ViolationRow(
    flagged: Flagged,
    onAccept: (Violation) -> Unit,
    onShowPassage: (Violation) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(start = 12.dp)) {
        Text(
            flagged.violation.explanation,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.error,
        )
        Row {
            // Tap-through is the feature that makes constraints worth having: a flag is
            // useful, and a flag that opens the paragraph saying so settles the argument.
            TextButton(onClick = { onShowPassage(flagged.violation) }) { Text("Show the rule") }
            TextButton(onClick = { onAccept(flagged.violation) }) { Text("We play it this way") }
        }
    }
}

/**
 * The rule itself, under the quotation rule every other quotation in this app obeys.
 *
 * A chunk that is not verbatim-class carries no text here — the pack may have rooted its
 * constraint at builder-written prose, and rendering that as a quotation beneath a real
 * citation is the one thing the routing partition exists to prevent.
 */
@Composable
private fun PassageDialog(passage: Passage, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("The rule") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val text = passage.text
                if (text == null) {
                    Text(
                        "This rule is rooted in prose the pack's builder wrote, so it cannot " +
                            "be quoted. The citation still points at it.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                } else {
                    Row(Modifier.height(IntrinsicSize.Min)) {
                        Surface(
                            Modifier.width(3.dp).fillMaxHeight(),
                            color = MaterialTheme.colorScheme.primary,
                        ) {}
                        Text(
                            text,
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(start = 8.dp),
                        )
                    }
                }
                passage.citation?.let {
                    Text("— ${render(it)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}

private fun show(value: TrackerValue): String = when (value) {
    is TrackerValue.Number -> value.value.toString().removeSuffix(".0")
    is TrackerValue.Flag -> if (value.value) "yes" else "no"
    is TrackerValue.Text -> value.value
}
