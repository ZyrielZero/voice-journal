package dev.zyriel.voicejournal

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.animation.animateColor
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.material3.Button
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zyriel.voicejournal.data.JournalEntry
import dev.zyriel.voicejournal.pipeline.RecordingController.PipelineState
import dev.zyriel.voicejournal.ui.AboutDialog
import dev.zyriel.voicejournal.ui.JournalViewModel
import dev.zyriel.voicejournal.ui.theme.EmberPulseLow
import dev.zyriel.voicejournal.ui.theme.EmberPulseHigh
import dev.zyriel.voicejournal.ui.theme.ThemeMode
import dev.zyriel.voicejournal.ui.theme.ThemePrefs
import dev.zyriel.voicejournal.ui.theme.VoiceJournalTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Privacy settings, stored beside the theme in the same "settings" prefs.
 * Both default on: transcripts are private by default, and the user opts
 * out rather than in.
 */
object PrivacyPrefs {
    private const val PREFS = "settings"
    private const val KEY_HIDE = "hide_in_recents"
    private const val KEY_LOCK = "app_lock"
    private fun prefs(c: Context) = c.getSharedPreferences(PREFS, 0)
    fun hideInRecents(c: Context) = prefs(c).getBoolean(KEY_HIDE, true)
    fun setHideInRecents(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_HIDE, on).apply()
    fun lockEnabled(c: Context) = prefs(c).getBoolean(KEY_LOCK, true)
    fun setLockEnabled(c: Context, on: Boolean) = prefs(c).edit().putBoolean(KEY_LOCK, on).apply()
}

/**
 * Process-wide lock state. A new process starts locked, so a cold start
 * always asks; rotation and other Activity recreations keep the unlock.
 */
private object LockState {
    @Volatile var unlocked = false
    /** elapsedRealtime when the app left the screen, 0 while visible. */
    @Volatile var backgroundedAt = 0L
    /** True while a prompt is up, so a recreated Activity does not stack a second one. */
    @Volatile var prompting = false
}

class MainActivity : FragmentActivity() {

    private var locked by mutableStateOf(false)
    private var lockMessage by mutableStateOf<String?>(null)
    private var lockEnabled by mutableStateOf(true)
    private var hideInRecents by mutableStateOf(true)
    private lateinit var prompt: BiometricPrompt

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideInRecents = PrivacyPrefs.hideInRecents(this)
        lockEnabled = PrivacyPrefs.lockEnabled(this)
        applySecureFlag(hideInRecents)
        // Created in onCreate, as BiometricPrompt requires, so a prompt
        // that survives rotation reconnects to this Activity's callback.
        prompt = BiometricPrompt(this, ContextCompat.getMainExecutor(this), lockCallback)
        locked = shouldLock()

        setContent {
            var themeMode by remember { mutableStateOf(ThemePrefs.load(this)) }
            // Keeps the journal's saved UI state (an open edit, its draft)
            // while the lock screen replaces it, so relocking loses nothing.
            val saveable = rememberSaveableStateHolder()
            VoiceJournalTheme(themeMode) {
                if (locked) {
                    LockScreen(message = lockMessage, onUnlock = ::authenticate)
                } else saveable.SaveableStateProvider("journal") {
                    JournalScreen(
                        themeMode = themeMode,
                        onCycleTheme = {
                            themeMode = themeMode.next()
                            ThemePrefs.save(this, themeMode)
                        },
                        privacy = PrivacySettings(
                            lockEnabled = lockEnabled,
                            lockAvailable = canAuthenticate(),
                            hideInRecents = hideInRecents,
                            onToggleLock = ::toggleLock,
                            onToggleHide = ::toggleHide,
                        ),
                    )
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        val left = LockState.backgroundedAt
        if (left != 0L && SystemClock.elapsedRealtime() - left >= RELOCK_AFTER_MS) {
            LockState.unlocked = false
        }
        LockState.backgroundedAt = 0L
        locked = shouldLock()
    }

    override fun onResume() {
        super.onResume()
        if (locked && !LockState.prompting) authenticate()
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) LockState.backgroundedAt = SystemClock.elapsedRealtime()
    }

    /**
     * Locks only when the user wants it and the device can actually check
     * them. With no screen lock enrolled (or no hardware) the gate is
     * skipped entirely: the app must never lock its owner out of their
     * own journal.
     */
    private fun shouldLock(): Boolean =
        lockEnabled && !LockState.unlocked && canAuthenticate()

    private fun canAuthenticate(): Boolean =
        BiometricManager.from(this).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

    private fun authenticate() {
        if (!shouldLock()) { unlock(); return }
        lockMessage = null
        LockState.prompting = true
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock Voice Journal")
                .setSubtitle("Your entries stay hidden until you confirm it's you.")
                .setAllowedAuthenticators(AUTHENTICATORS)
                .setConfirmationRequired(false)
                .build()
        )
    }

    private fun unlock() {
        LockState.unlocked = true
        locked = false
        lockMessage = null
    }

    private val lockCallback = object : BiometricPrompt.AuthenticationCallback() {
        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
            LockState.prompting = false
            unlock()
        }

        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
            LockState.prompting = false
            when {
                // The screen lock was removed or the hardware went away
                // since the check: fail open rather than strand the user.
                !canAuthenticate() -> unlock()
                // System cancel (the app left the screen): ask again on return.
                errorCode == BiometricPrompt.ERROR_CANCELED -> Unit
                errorCode == BiometricPrompt.ERROR_USER_CANCELED -> lockMessage = null
                else -> lockMessage = errString.toString()
            }
        }
        // onAuthenticationFailed is one rejected attempt; the prompt stays up.
    }

    private fun toggleLock() {
        lockEnabled = !lockEnabled
        PrivacyPrefs.setLockEnabled(this, lockEnabled)
        // Turning it on from inside the app means the user is present.
        if (lockEnabled) LockState.unlocked = true
    }

    private fun toggleHide() {
        hideInRecents = !hideInRecents
        PrivacyPrefs.setHideInRecents(this, hideInRecents)
        applySecureFlag(hideInRecents)
    }

    /** Blanks recents thumbnails and blocks screenshots and screen shares. */
    private fun applySecureFlag(on: Boolean) {
        if (on) window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }

    private companion object {
        /** Away this long and the journal locks again on return. */
        const val RELOCK_AFTER_MS = 5 * 60 * 1000L

        /**
         * Strong biometrics or the device PIN, pattern or password. API 28
         * and 29 reject BIOMETRIC_STRONG combined with DEVICE_CREDENTIAL,
         * so those (and older) releases accept any enrolled biometric or
         * the credential.
         */
        val AUTHENTICATORS =
            if (Build.VERSION.SDK_INT >= 30) BIOMETRIC_STRONG or DEVICE_CREDENTIAL
            else BIOMETRIC_WEAK or DEVICE_CREDENTIAL
    }
}

/** The privacy toggles shown in the overflow menu. */
data class PrivacySettings(
    val lockEnabled: Boolean,
    /** False when the device has no screen lock, so the lock cannot engage. */
    val lockAvailable: Boolean,
    val hideInRecents: Boolean,
    val onToggleLock: () -> Unit,
    val onToggleHide: () -> Unit,
)

@Composable
private fun LockScreen(message: String?, onUnlock: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Voice Journal is locked", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                message ?: "Recording and transcription keep running while locked.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) { Text("Unlock") }
        }
    }
}

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
        if (android.os.Build.VERSION.SDK_INT >= 33)
            arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)
        else arrayOf(Manifest.permission.RECORD_AUDIO)
    }

    // By id, not by entry: JournalEntry is not Parcelable (it holds a
    // FloatArray), and an id survives rotation and re-resolves against the
    // current list, so an entry deleted meanwhile simply closes the dialog.
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    var showAbout by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voice Journal", style = MaterialTheme.typography.headlineMedium) },
                actions = {
                    if (dev.zyriel.voicejournal.BuildConfig.DEBUG) {
                        // Debug-only suites. They run on Default with the
                        // application context (never the Activity) and post
                        // progress back to Main, so a rotation mid-run leaks
                        // nothing and no Compose state is written off-thread.
                        // try/catch, not crash visibility: an uncaught
                        // exception would kill the process, which reads as a
                        // hang behind a progress dialog.
                        val appCtx = LocalContext.current.applicationContext
                        val benchScope = rememberCoroutineScope()
                        var benchStatus by remember { mutableStateOf<String?>(null) }
                        TextButton(onClick = {
                            if (benchStatus == null) {
                                benchStatus = "starting"
                                benchScope.launch {
                                    benchStatus = withContext(Dispatchers.Default) {
                                        try {
                                            val f = dev.zyriel.voicejournal.bench.BenchmarkSuite(appCtx)
                                                .runAll { s -> benchScope.launch { benchStatus = s } }
                                            "Saved: ${f.name} (adb pull ${f.absolutePath})"
                                        } catch (e: Exception) {
                                            "FAILED: ${e.message}"
                                        }
                                    }
                                }
                            }
                        }) { Text("Bench") }
                        benchStatus?.let {
                            val done = it.startsWith("Saved") || it.startsWith("FAILED")
                            AlertDialog(
                                onDismissRequest = { if (done) benchStatus = null },
                                title = { Text("Benchmark") },
                                text = { Text(it) },
                                confirmButton = {
                                    if (done) TextButton(onClick = { benchStatus = null }) { Text("Close") }
                                },
                            )
                        }
                        var accStatus by remember { mutableStateOf<String?>(null) }
                        TextButton(onClick = {
                            if (accStatus == null) {
                                accStatus = "starting"
                                benchScope.launch {
                                    accStatus = withContext(Dispatchers.Default) {
                                        try {
                                            val f = dev.zyriel.voicejournal.bench.AccuracySuite(appCtx)
                                                .runAll { s -> benchScope.launch { accStatus = s } }
                                            "Saved: ${f.name} (adb pull ${f.absolutePath})"
                                        } catch (e: Exception) {
                                            "FAILED: ${e.message}"
                                        }
                                    }
                                }
                            }
                        }) { Text("Acc") }
                        accStatus?.let {
                            val done = it.startsWith("Saved") || it.startsWith("FAILED")
                            AlertDialog(
                                onDismissRequest = { if (done) accStatus = null },
                                title = { Text("Accuracy") },
                                text = { Text(it) },
                                confirmButton = {
                                    if (done) TextButton(onClick = { accStatus = null }) { Text("Close") }
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
                            DropdownMenuItem(
                                text = {
                                    Text(when {
                                        !privacy.lockEnabled -> "App lock: off"
                                        privacy.lockAvailable -> "App lock: on"
                                        else -> "App lock: on (needs a screen lock)"
                                    })
                                },
                                onClick = { menuOpen = false; privacy.onToggleLock() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (privacy.hideInRecents) "Hide in recents: on" else "Hide in recents: off") },
                                onClick = { menuOpen = false; privacy.onToggleHide() },
                            )
                            DropdownMenuItem(
                                text = { Text("About") },
                                onClick = { menuOpen = false; showAbout = true },
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

    transferStatus?.let { status ->
        AlertDialog(
            onDismissRequest = { if (!status.inFlight) vm.dismissTransferStatus() },
            title = { Text("Journal transfer") },
            text = { Text(status.message) },
            confirmButton = {
                if (!status.inFlight) TextButton(onClick = vm::dismissTransferStatus) { Text("Close") }
            },
        )
    }

    selectedId?.let { id ->
        // Resolved against the unfiltered list, so a search re-rank cannot
        // close the dialog. Null means Room has not emitted yet (after
        // process death); an entry missing from a loaded list was deleted.
        val all = allEntries ?: return@let
        val entry = all.firstOrNull { it.id == id }
        if (entry == null) {
            LaunchedEffect(id) { selectedId = null }
            return@let
        }
        EntryDialog(
            entry = entry,
            onDismiss = { selectedId = null },
            onSave = { text -> vm.updateTranscript(entry, text); selectedId = null },
            onDelete = { vm.delete(entry); selectedId = null },
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

/**
 * Edit, save or delete one entry. Both destructive exits ask first: Delete
 * confirms, and dismissing with unsaved changes (back, outside tap, Cancel)
 * offers to discard. The draft and both prompts survive rotation.
 */
@Composable
private fun EntryDialog(
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
                    "Saving updates this entry's search index too.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }, enabled = dirty) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = { confirmDelete = true }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
                TextButton(onClick = requestClose) { Text("Cancel") }
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("The transcript and its recording are removed from this device. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = { confirmDelete = false; onDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep") } },
        )
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard your changes?") },
            text = { Text("Your edits to this transcript have not been saved.") },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDismiss() }) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
}

private fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

private fun formatDate(ms: Long): String =
    SimpleDateFormat("MMM d, yyyy  h:mm a", Locale.US).format(Date(ms))
