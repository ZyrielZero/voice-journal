package dev.zyriel.voicejournal.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zyriel.voicejournal.BuildConfig
import dev.zyriel.voicejournal.R
import dev.zyriel.voicejournal.audio.AudioPlayer
import dev.zyriel.voicejournal.data.JournalEntry
import dev.zyriel.voicejournal.pipeline.RecordingController.PipelineState
import dev.zyriel.voicejournal.ui.theme.EmberPulseHigh
import dev.zyriel.voicejournal.ui.theme.EmberPulseLow
import dev.zyriel.voicejournal.ui.theme.ThemeMode
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The journal: search box, pipeline status, the entry list and the Rec button. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    themeMode: ThemeMode,
    onCycleTheme: () -> Unit,
    privacy: PrivacySettings,
    vm: JournalViewModel = viewModel(),
) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val allEntries by vm.allEntries.collectAsStateWithLifecycle()
    val pipeline by vm.pipeline.collectAsStateWithLifecycle()
    val recovering by vm.recovering.collectAsStateWithLifecycle()
    val quarantined by vm.quarantined.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val playing by vm.player.playingFile.collectAsStateWithLifecycle()
    val transferStatus by vm.transferStatus.collectAsStateWithLifecycle()
    val elapsed by vm.recorder.elapsedMs.collectAsStateWithLifecycle()

    val context = LocalContext.current
    // POST_NOTIFICATIONS is requested alongside the mic so the recording
    // notification lands in the drawer, but it is never a gate: recording
    // proceeds on the mic grant alone. A denied notification just means the
    // ongoing recording shows in the system Task Manager instead.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> if (results[Manifest.permission.RECORD_AUDIO] == true) vm.toggleRecording() }
    val recordPermissions = remember {
        if (Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    // By id, not by entry: JournalEntry is not Parcelable (it holds a
    // FloatArray), and an id survives rotation and re-resolves against the
    // current list, so an entry deleted meanwhile simply closes the dialog.
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAbout by rememberSaveable { mutableStateOf(false) }
    // Hoisted above the entry screen so the list comes back where it was.
    val listState = rememberLazyListState()

    selectedId?.let { id ->
        // Resolved against the unfiltered list, so a search re-rank cannot
        // close the entry. Null means Room has not emitted yet (after
        // process death); an entry missing from a loaded list was deleted.
        val all = allEntries ?: return@let
        val entry = all.firstOrNull { it.id == id }
        if (entry == null) {
            LaunchedEffect(id) { selectedId = null }
            return@let
        }
        // In place of the journal, not over it: one screen at a time.
        EntryScreen(
            entry = entry,
            player = vm.player,
            onClose = { selectedId = null },
            onSave = { text -> vm.updateTranscript(entry, text) },
            onDelete = { vm.delete(entry); selectedId = null },
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    if (BuildConfig.DEBUG) DebugBenchActions()
                    TextButton(onClick = onCycleTheme) {
                        Text(stringResource(when (themeMode) {
                            ThemeMode.SYSTEM -> R.string.theme_auto
                            ThemeMode.LIGHT -> R.string.theme_light
                            ThemeMode.DARK -> R.string.theme_dark
                        }))
                    }
                    OverflowMenu(vm = vm, privacy = privacy, onAbout = { showAbout = true })
                }
            )
        },
        floatingActionButton = {
            val infinite = rememberInfiniteTransition(label = "ember")
            val ember by infinite.animateColor(
                initialValue = EmberPulseLow,
                targetValue = EmberPulseHigh,
                animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
                label = "emberColor",
            )
            val isRec = pipeline is PipelineState.Recording
            FloatingActionButton(
                containerColor = if (isRec) ember else MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                onClick = {
                    when (pipeline) {
                        is PipelineState.Transcribing -> Unit
                        else -> {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) vm.toggleRecording()
                            else permissionLauncher.launch(recordPermissions)
                        }
                    }
                },
            ) {
                when (pipeline) {
                    is PipelineState.Recording -> Text(stringResource(R.string.action_stop))
                    is PipelineState.Transcribing -> CircularProgressIndicator(
                        Modifier.size(24.dp), strokeWidth = 2.dp
                    )
                    else -> Text(stringResource(R.string.action_record))
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {

            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                placeholder = { Text(stringResource(R.string.search_placeholder)) },
                singleLine = true,
            )

            when (val p = pipeline) {
                is PipelineState.Recording -> StatusLine(stringResource(R.string.status_recording, formatMs(elapsed)))
                is PipelineState.Transcribing -> StatusLine(stringResource(R.string.status_transcribing))
                is PipelineState.Failed -> Snackbar(
                    action = { TextButton(onClick = vm::dismissError) { Text(stringResource(R.string.action_dismiss)) } }
                ) { Text(p.message) }
                else -> Unit
            }
            // Independent of the pipeline: recovery and a new recording can overlap.
            if (recovering) StatusLine(stringResource(R.string.status_recovering))

            if (quarantined > 0) {
                QuarantineNotice(
                    count = quarantined,
                    busy = recovering,
                    onRetry = vm::retryQuarantined,
                    onDelete = vm::deleteQuarantined,
                )
            }

            if (entries.isEmpty() && pipeline is PipelineState.Idle && !recovering) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) stringResource(R.string.empty_journal)
                        else stringResource(R.string.empty_search, query),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(bottom = 8.dp)
                ) {
                    items(entries, key = { it.id }) { entry ->
                        EntryCard(
                            entry = entry,
                            isPlaying = playing == File(entry.audioPath),
                            onPlay = { vm.play(entry) },
                            onOpen = { selectedId = entry.id },
                        )
                    }
                }
            }
        }
    }

    if (showAbout) AboutDialog(onDismiss = { showAbout = false })

    TransferDialogs(
        status = transferStatus,
        onDismiss = vm::dismissTransferStatus,
        onPassword = { uri, password -> vm.importFrom(uri, password) },
    )
}

@Composable
private fun StatusLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(vertical = 4.dp)
    )
}

/**
 * Recordings whose transcription failed repeatedly are set aside, never
 * deleted. This is where the user finds out, and where they can try again
 * (after an update, or once the device has memory to spare) or let go of
 * the audio. Delete asks first: there is no transcript to fall back on.
 */
@Composable
private fun QuarantineNotice(count: Int, busy: Boolean, onRetry: () -> Unit, onDelete: () -> Unit) {
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
    ) {
        Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 4.dp)) {
            Text(
                pluralStringResource(R.plurals.quarantine_notice, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { confirmDelete = true }, enabled = !busy) {
                    Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = onRetry, enabled = !busy) { Text(stringResource(R.string.action_retry)) }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.quarantine_delete_title)) },
            text = { Text(pluralStringResource(R.plurals.quarantine_delete_body, count, count)) },
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
}

@Composable
private fun EntryCard(entry: JournalEntry, isPlaying: Boolean, onPlay: () -> Unit, onOpen: () -> Unit) {
    Card(
        onClick = onOpen,
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Length from the file size alone; blank when there is no recording.
                val length = remember(entry.audioPath) {
                    AudioPlayer.wavDurationMs(File(entry.audioPath).length()).takeIf { it > 0L }
                }
                Text(
                    formatDate(entry.timestampMs) + (length?.let { "  ·  " + formatMs(it) } ?: ""),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onPlay) {
                    Text(stringResource(if (isPlaying) R.string.action_stop else R.string.action_play))
                }
            }
            Text(
                entry.transcript,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
            )
        }
    }
}

internal fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

internal fun formatDate(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.getDefault()).format(Date(ms))
