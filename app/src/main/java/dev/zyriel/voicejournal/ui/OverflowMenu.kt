package dev.zyriel.voicejournal.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.zyriel.voicejournal.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** File extension of a password-protected export; a plain one is a .zip. */
private const val ENCRYPTED_EXTENSION = "vjbackup"

/**
 * The "More" menu: export, import, the two privacy toggles and About.
 * Owns the system file pickers for both transfer directions.
 */
@Composable
internal fun OverflowMenu(vm: JournalViewModel, privacy: PrivacySettings, onAbout: () -> Unit) {
    Box {
        var menuOpen by remember { mutableStateOf(false) }
        var exportDialog by rememberSaveable { mutableStateOf(false) }

        val exportPlain = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/zip")
        ) { uri ->
            LockState.pickerOpenedAt = 0L
            uri?.let { vm.exportTo(it, encrypted = false) }
        }
        // A separate launcher, so that the result itself says an encrypted
        // export was asked for, even if the process was restarted behind
        // the picker and the password is gone (the ViewModel then refuses).
        val exportEncrypted = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream")
        ) { uri ->
            LockState.pickerOpenedAt = 0L
            if (uri != null) vm.exportTo(uri, encrypted = true) else vm.setExportPassword(null)
        }
        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri ->
            LockState.pickerOpenedAt = 0L
            uri?.let { vm.importFrom(it) }
        }

        if (exportDialog) {
            ExportDialog(
                onDismiss = { exportDialog = false },
                onExport = { password ->
                    exportDialog = false
                    val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                    if (password == null) {
                        vm.setExportPassword(null)
                        LockState.launchPicker { exportPlain.launch("voice-journal-$stamp.zip") }
                    } else {
                        vm.setExportPassword(password)
                        LockState.launchPicker { exportEncrypted.launch("voice-journal-$stamp.$ENCRYPTED_EXTENSION") }
                    }
                },
            )
        }

        TextButton(onClick = { menuOpen = true }) { Text(stringResource(R.string.action_more)) }
        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_export)) },
                onClick = { menuOpen = false; exportDialog = true },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_import)) },
                onClick = {
                    menuOpen = false
                    // Any type: an encrypted export has no registered MIME
                    // type, and providers disagree on what a .zip is. The
                    // importer identifies the file by its content.
                    LockState.launchPicker { importLauncher.launch(arrayOf("*/*")) }
                },
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(when {
                        !privacy.lockEnabled -> R.string.menu_lock_off
                        privacy.lockAvailable -> R.string.menu_lock_on
                        else -> R.string.menu_lock_unavailable
                    }))
                },
                onClick = { menuOpen = false; privacy.onToggleLock() },
            )
            DropdownMenuItem(
                text = {
                    Text(stringResource(if (privacy.hideInRecents) R.string.menu_hide_on else R.string.menu_hide_off))
                },
                onClick = { menuOpen = false; privacy.onToggleHide() },
            )
            DropdownMenuItem(
                text = { Text(stringResource(R.string.menu_about)) },
                onClick = { menuOpen = false; onAbout() },
            )
        }
    }
}

/**
 * Asks how to export: with a password (encrypted) or without. The fields
 * are deliberately not saved across rotation or process death; a password
 * does not belong in a saved-instance bundle, so a rotation clears them.
 * [onExport] receives the password, or null for an unencrypted export.
 */
@Composable
private fun ExportDialog(onDismiss: () -> Unit, onExport: (CharArray?) -> Unit) {
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val wantsPassword = password.isNotEmpty()
    val mismatch = wantsPassword && confirm.isNotEmpty() && confirm != password
    val ready = !wantsPassword || confirm == password

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export_title)) },
        text = {
            Column {
                Text(stringResource(R.string.export_body))
                Spacer(Modifier.height(12.dp))
                PasswordField(password, { password = it }, stringResource(R.string.export_password))
                if (wantsPassword) {
                    Spacer(Modifier.height(8.dp))
                    PasswordField(
                        confirm, { confirm = it }, stringResource(R.string.export_password_confirm),
                        isError = mismatch,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(
                            if (mismatch) R.string.export_password_mismatch else R.string.export_password_warning
                        ),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (mismatch) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = ready,
                onClick = { onExport(if (wantsPassword) password.toCharArray() else null) },
            ) {
                Text(stringResource(
                    if (wantsPassword) R.string.export_action_encrypted else R.string.export_action_plain
                ))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
internal fun PasswordField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    isError: Boolean = false,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
