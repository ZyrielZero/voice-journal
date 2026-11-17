package dev.zyriel.voicejournal.ui

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.zyriel.voicejournal.BuildConfig
import dev.zyriel.voicejournal.R
import dev.zyriel.voicejournal.ui.theme.ThemeMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** File extension of a password-protected export; a plain one is a .zip. */
private const val ENCRYPTED_EXTENSION = "vjbackup"

/** The GPL itself. */
internal val LICENSE_TEXTS = listOf("licenses/LICENSE")

/** Notices first, then the full license texts they reference that are not the GPL. */
internal val THIRD_PARTY_TEXTS = listOf(
    "licenses/THIRD_PARTY_NOTICES.md",
    "licenses/LICENSE-whisper.cpp.txt",
    "licenses/OFL-Literata.txt",
)

private val MARGIN = 20.dp

/**
 * Everything that is not the journal itself: the theme, the two privacy
 * switches, export and import, and About. Owns the system file pickers
 * for both transfer directions.
 *
 * About carries what the GPL calls the Appropriate Legal Notices (version,
 * copyright line, the license and no-warranty notice) and leads to the
 * full license and third-party texts.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(
    themeMode: ThemeMode,
    onSetTheme: (ThemeMode) -> Unit,
    privacy: PrivacySettings,
    vm: JournalViewModel,
    onBack: () -> Unit,
    onLicense: () -> Unit,
    onNotices: () -> Unit,
) {
    BackHandler(onBack = onBack)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            Section(stringResource(R.string.settings_appearance))
            val modes = listOf(
                ThemeMode.SYSTEM to R.string.theme_auto,
                ThemeMode.LIGHT to R.string.theme_light,
                ThemeMode.DARK to R.string.theme_dark,
            )
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = MARGIN, vertical = 8.dp)) {
                modes.forEachIndexed { i, (mode, label) ->
                    SegmentedButton(
                        selected = themeMode == mode,
                        onClick = { onSetTheme(mode) },
                        shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    ) { Text(stringResource(label)) }
                }
            }

            Section(stringResource(R.string.settings_privacy))
            Note(stringResource(R.string.settings_privacy_note))
            SwitchRow(
                title = stringResource(R.string.settings_lock),
                body = stringResource(
                    if (privacy.lockEnabled && !privacy.lockAvailable) R.string.settings_lock_unavailable
                    else R.string.settings_lock_body
                ),
                checked = privacy.lockEnabled,
                onToggle = privacy.onToggleLock,
            )
            SwitchRow(
                title = stringResource(R.string.settings_hide),
                body = stringResource(R.string.settings_hide_body),
                checked = privacy.hideInRecents,
                onToggle = privacy.onToggleHide,
            )

            Section(stringResource(R.string.settings_journal))
            LinkRow(
                title = stringResource(R.string.menu_export),
                body = stringResource(R.string.settings_export_body),
                onClick = { exportDialog = true },
            )
            LinkRow(
                title = stringResource(R.string.menu_import),
                body = stringResource(R.string.settings_import_body),
                onClick = {
                    // Any type: an encrypted export has no registered MIME
                    // type, and providers disagree on what a .zip is. The
                    // importer identifies the file by its content.
                    LockState.launchPicker { importLauncher.launch(arrayOf("*/*")) }
                },
            )

            Section(stringResource(R.string.settings_about))
            Column(Modifier.padding(horizontal = MARGIN, vertical = 8.dp)) {
                Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleLarge)
                Text(
                    stringResource(R.string.about_version, BuildConfig.VERSION_NAME, BuildConfig.GIT_SHA),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(12.dp))
                Fine(stringResource(R.string.about_copyright))
                Spacer(Modifier.height(4.dp))
                Fine(stringResource(R.string.about_license_notice))
                Spacer(Modifier.height(4.dp))
                Fine(stringResource(R.string.about_source))
            }
            LinkRow(title = stringResource(R.string.about_license), onClick = onLicense)
            LinkRow(title = stringResource(R.string.about_notices), onClick = onNotices)

            // Strings stay inline: this never ships in a release.
            if (BuildConfig.DEBUG) {
                Section("Debug")
                Row(Modifier.padding(horizontal = 8.dp)) { DebugBenchActions() }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun Section(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(start = MARGIN, end = MARGIN, top = 24.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

/** A sentence that explains the section it opens. */
@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = MARGIN, vertical = 4.dp),
    )
}

/** Small print: the legal notices. */
@Composable
private fun Fine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun RowText(title: String, body: String?, modifier: Modifier = Modifier) {
    Column(modifier) {
        // Sans, like every other control; the serif is for reading.
        Text(title, style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Default))
        if (body != null) {
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

/** The whole row is the switch: one target, one announcement. */
@Composable
private fun SwitchRow(title: String, body: String, checked: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .toggleable(value = checked, role = Role.Switch, onValueChange = { onToggle() })
            .padding(horizontal = MARGIN, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, body, Modifier.weight(1f))
        Spacer(Modifier.width(16.dp))
        Switch(checked = checked, onCheckedChange = null)
    }
}

/** A row that leads somewhere: a dialog, a picker or another page. */
@Composable
private fun LinkRow(title: String, body: String? = null, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = MARGIN, vertical = if (body == null) 16.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RowText(title, body, Modifier.weight(1f))
        Icon(AppIcons.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * A license text as a page of its own. The texts come from assets that
 * the build copies out of the repo-root originals, so this can never
 * drift from what the repo actually says.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LicenseScreen(title: String, paths: List<String>, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val loading = stringResource(R.string.about_loading)
    val body by produceState(loading, paths) {
        value = paths.map { readLicenseAsset(context, it) }.joinToString("\n\n")
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(AppIcons.ArrowBack, stringResource(R.string.action_back)) }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState())) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Text(
                body,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = MARGIN, vertical = 16.dp),
            )
        }
    }
}

private suspend fun readLicenseAsset(context: Context, path: String): String =
    withContext(Dispatchers.IO) {
        runCatching {
            context.assets.open(path).use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrElse { "Could not load $path: ${it.message}" }
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
    val sans = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Default)
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        textStyle = sans,
        singleLine = true,
        isError = isError,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
}
