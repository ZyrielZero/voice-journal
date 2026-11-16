package dev.zyriel.voicejournal.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zyriel.voicejournal.R
import dev.zyriel.voicejournal.data.JournalEntry

/**
 * Edit, save or delete one entry. Both destructive exits ask first: Delete
 * confirms, and dismissing with unsaved changes (back, outside tap, Cancel)
 * offers to discard. The draft and both prompts survive rotation.
 */
@Composable
internal fun EntryDialog(
    entry: JournalEntry,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var text by rememberSaveable(entry.id) { mutableStateOf(entry.transcript) }
    var confirmDelete by rememberSaveable(entry.id) { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable(entry.id) { mutableStateOf(false) }
    val dirty = text != entry.transcript
    val requestClose = { if (dirty) confirmDiscard = true else onDismiss() }

    AlertDialog(
        onDismissRequest = requestClose,
        title = { Text(formatDate(entry.timestampMs)) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.entry_save_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text) }, enabled = dirty) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = requestClose) { Text(stringResource(R.string.action_cancel)) }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.entry_delete_title)) },
            text = { Text(stringResource(R.string.entry_delete_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_keep)) }
            },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.entry_discard_title)) },
            text = { Text(stringResource(R.string.entry_discard_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDismiss() }) {
                    Text(stringResource(R.string.entry_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.entry_keep_editing)) }
            },
        )
    }
}
