package dev.zyriel.voicejournal.ui

import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import dev.zyriel.voicejournal.R

/**
 * Progress and result of an export or import, and the password prompt for
 * an encrypted import. One of the two is shown, or nothing.
 */
@Composable
internal fun TransferDialogs(
    status: JournalViewModel.TransferStatus?,
    onDismiss: () -> Unit,
    onPassword: (Uri, CharArray) -> Unit,
) {
    status ?: return
    val uri = status.passwordFor
    if (uri != null) {
        // Keyed on the status, so a wrong password comes back to an empty field.
        var password by remember(status) { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(stringResource(R.string.import_password_title)) },
            text = {
                Column {
                    Text(
                        stringResource(
                            if (status.wrongPassword) R.string.import_password_wrong
                            else R.string.import_password_body
                        ),
                        color = if (status.wrongPassword) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(12.dp))
                    PasswordField(
                        password, { password = it }, stringResource(R.string.import_password_field),
                        isError = status.wrongPassword,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = password.isNotEmpty(),
                    onClick = { onPassword(uri, password.toCharArray()) },
                ) { Text(stringResource(R.string.import_action)) }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
        )
        return
    }
    AlertDialog(
        onDismissRequest = { if (!status.inFlight) onDismiss() },
        title = { Text(stringResource(R.string.transfer_title)) },
        text = { Text(status.message) },
        confirmButton = {
            if (!status.inFlight) TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) }
        },
    )
}
