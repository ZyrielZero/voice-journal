package dev.zyriel.voicejournal

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
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
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zyriel.voicejournal.data.JournalEntry
import dev.zyriel.voicejournal.pipeline.RecordingController.PipelineState
import dev.zyriel.voicejournal.ui.JournalViewModel
import dev.zyriel.voicejournal.ui.theme.EmberPulseLow
import dev.zyriel.voicejournal.ui.theme.EmberPulseHigh
import dev.zyriel.voicejournal.ui.theme.ThemeMode
import dev.zyriel.voicejournal.ui.theme.ThemePrefs
import dev.zyriel.voicejournal.ui.theme.VoiceJournalTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var themeMode by remember { mutableStateOf(ThemePrefs.load(this)) }
            VoiceJournalTheme(themeMode) {
                JournalScreen(
                    themeMode = themeMode,
                    onCycleTheme = {
                        themeMode = themeMode.next()
                        ThemePrefs.save(this, themeMode)
                    }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(themeMode: ThemeMode, onCycleTheme: () -> Unit, vm: JournalViewModel = viewModel()) {
    val entries by vm.entries.collectAsState()
    val pipeline by vm.pipeline.collectAsState()
    val query by vm.query.collectAsState()
    val playing by vm.player.playingFile.collectAsState()
    val transferStatus by vm.transferStatus.collectAsState()
    val elapsed by vm.recorder.elapsedMs.collectAsState()

    val context = LocalContext.current
    // POST_NOTIFICATIONS is requested alongside the mic so the recording
    // notification lands in the drawer, but it is never a gate: recording
    // proceeds on the mic grant alone. A denied notification just means the
    // ongoing recording shows in the system Task Manager instead.
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results -> if (results[Manifest.permission.RECORD_AUDIO] == true) vm.toggleRecording() }
    val recordPermissions = remember {
        if (android.os.Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    var selected by remember { mutableStateOf<JournalEntry?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Journal", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    if (dev.zyriel.voicejournal.BuildConfig.DEBUG) {
                        var benchStatus by remember { mutableStateOf<String?>(null) }
                        val ctx = LocalContext.current
                        TextButton(onClick = {
                            if (benchStatus == null) {
                                benchStatus = "starting"
                                Thread {
                                    val f = dev.zyriel.voicejournal.bench.BenchmarkSuite(ctx)
                                        .runAll { s -> benchStatus = s }
                                    benchStatus = "Saved: ${f.name} (adb pull ${f.absolutePath})"
                                }.start()
                            }
                        }) { Text("Bench") }
                        benchStatus?.let {
                            AlertDialog(
                                onDismissRequest = { if (it.startsWith("Saved")) benchStatus = null },
                                title = { Text("Benchmark") },
                                text = { Text(it) },
                                confirmButton = {
                                    if (it.startsWith("Saved")) TextButton(onClick = { benchStatus = null }) { Text("Close") }
                                },
                            )
                        }
                    }
                    TextButton(onClick = onCycleTheme) {
                        Text(when (themeMode) {
                            ThemeMode.SYSTEM -> "Auto"
                            ThemeMode.LIGHT -> "Light"
                            ThemeMode.DARK -> "Dark"
                        })
                    }
                    Box {
                        var menuOpen by remember { mutableStateOf(false) }
                        val exportLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.CreateDocument("application/zip")
                        ) { uri -> uri?.let(vm::exportTo) }
                        val importLauncher = rememberLauncherForActivityResult(
                            ActivityResultContracts.OpenDocument()
                        ) { uri -> uri?.let(vm::importFrom) }

                        TextButton(onClick = { menuOpen = true }) { Text("More") }
                        DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                            DropdownMenuItem(
                                text = { Text("Export journal") },
                                onClick = {
                                    menuOpen = false
                                    val stamp = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
                                    exportLauncher.launch("voice-journal-$stamp.zip")
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Import journal") },
                                onClick = {
                                    menuOpen = false
                                    importLauncher.launch(arrayOf("application/zip"))
                                },
                            )
                        }
                    }
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
            }) {
                when (pipeline) {
                    is PipelineState.Recording -> Text("Stop")
                    is PipelineState.Transcribing -> CircularProgressIndicator(
                        Modifier.size(24.dp), strokeWidth = 2.dp
                    )
                    else -> Text("Rec")
                }
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().padding(horizontal = 16.dp)) {

            OutlinedTextField(
                value = query,
                onValueChange = vm::setQuery,
                modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                placeholder = { Text("Search entries") },
                singleLine = true,
            )

            when (val p = pipeline) {
                is PipelineState.Recording -> StatusLine("Recording  ${formatMs(elapsed)}")
                is PipelineState.Transcribing -> StatusLine("Transcribing on-device...")
                is PipelineState.Failed -> Snackbar(
                    action = { TextButton(onClick = vm::dismissError) { Text("Dismiss") } }
                ) { Text(p.message) }
                else -> Unit
            }

            if (entries.isEmpty() && pipeline is PipelineState.Idle) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        if (query.isBlank()) "Quiet in here. Record your first entry."
                        else "Nothing matches \"$query\".",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxSize().padding(bottom = 8.dp)
                ) {
                    items(entries) { entry ->
                        EntryCard(
                            entry = entry,
                            isPlaying = playing == File(entry.audioPath),
                            onPlay = { vm.play(entry) },
                            onOpen = { selected = entry },
                        )
                    }
                }
            }
        }
    }

    transferStatus?.let { status ->
        val inFlight = status.endsWith("...")
        AlertDialog(
            onDismissRequest = { if (!inFlight) vm.dismissTransferStatus() },
            title = { Text("Journal transfer") },
            text = { Text(status) },
            confirmButton = {
                if (!inFlight) TextButton(onClick = vm::dismissTransferStatus) { Text("Close") }
            },
        )
    }

    selected?.let { entry ->
        EntryDialog(
            entry = entry,
            onDismiss = { selected = null },
            onSave = { text -> vm.updateTranscript(entry, text); selected = null },
            onDelete = { vm.delete(entry); selected = null },
        )
    }
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
                Text(
                    formatDate(entry.timestampMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = onPlay) { Text(if (isPlaying) "Stop" else "Play") }
            }
            Text(
                entry.transcript,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
            )
        }
    }
}

@Composable
private fun EntryDialog(
    entry: JournalEntry,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var text by remember { mutableStateOf(entry.transcript) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(formatDate(entry.timestampMs)) },
        text = {
            Column {
                OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                Text(
                    "Editing re-queues this entry for embedding once semantic search is active.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        }
    )
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.US).format(Date(ms))
