package dev.zyriel.voicejournal.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.role
import kotlinx.coroutines.delay
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.zyriel.voicejournal.R
import dev.zyriel.voicejournal.audio.AudioPlayer
import dev.zyriel.voicejournal.data.JournalEntry
import dev.zyriel.voicejournal.pipeline.RecordingController.PipelineState
import dev.zyriel.voicejournal.ui.theme.ThemeMode
import java.io.File
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.StateFlow

/** The screens of the app. One is shown at a time; the journal is home. */
private enum class Page { JOURNAL, ENTRY, SETTINGS, LICENSE, NOTICES }

/**
 * The app behind the lock: the journal page, and in its place an entry,
 * the settings or a license text. Dialogs that can outlast a screen
 * (transfer progress, the import password) are shown over whichever one
 * is up.
 */
@Composable
fun JournalScreen(
    themeMode: ThemeMode,
    onSetTheme: (ThemeMode) -> Unit,
    privacy: PrivacySettings,
    vm: JournalViewModel = viewModel(),
) {
    val allEntries by vm.allEntries.collectAsStateWithLifecycle()
    val pipeline by vm.pipeline.collectAsStateWithLifecycle()
    val transferStatus by vm.transferStatus.collectAsStateWithLifecycle()

    var page by rememberSaveable { mutableStateOf(Page.JOURNAL) }
    // By id, not by entry: JournalEntry is not Parcelable (it holds a
    // FloatArray), and an id survives rotation and re-resolves against the
    // current list, so an entry deleted meanwhile simply closes the screen.
    var selectedId by rememberSaveable { mutableStateOf<Long?>(null) }
    // Hoisted above the pages so the list comes back where it was.
    val listState = rememberLazyListState()

    AnimatedContent(
        targetState = page,
        transitionSpec = {
            // Deeper pages come in from the right and leave the same way.
            val forward = targetState.ordinal > initialState.ordinal
            val shift = { full: Int -> (if (forward) full else -full) / 10 }
            (fadeIn(tween(220)) + slideInHorizontally(tween(220)) { shift(it) }) togetherWith
                (fadeOut(tween(120)) + slideOutHorizontally(tween(220)) { -shift(it) })
        },
        label = "page",
    ) { shown ->
        when (shown) {
            Page.JOURNAL -> JournalPage(
                vm = vm,
                listState = listState,
                onOpen = { selectedId = it; page = Page.ENTRY },
                onSettings = { page = Page.SETTINGS },
            )
            Page.ENTRY -> {
                // Resolved against the unfiltered list, so a search re-rank
                // cannot close the entry. A null list means Room has not
                // emitted yet (after process death); an entry missing from
                // a loaded list was deleted. The last one seen is kept so
                // the page still has something to show while it fades out.
                val current = allEntries?.firstOrNull { it.id == selectedId }
                var last by remember { mutableStateOf(current) }
                if (current != null) last = current
                if (allEntries != null && current == null && page == Page.ENTRY) {
                    LaunchedEffect(Unit) { page = Page.JOURNAL }
                }
                last?.let { entry ->
                    EntryScreen(
                        entry = entry,
                        player = vm.player,
                        // Playing into an open microphone would record the playback.
                        canPlay = pipeline !is PipelineState.Recording,
                        onClose = { page = Page.JOURNAL },
                        onSave = { text -> vm.updateTranscript(entry, text) },
                        onDelete = { vm.delete(entry); page = Page.JOURNAL },
                    )
                }
            }
            Page.SETTINGS -> SettingsScreen(
                themeMode = themeMode,
                onSetTheme = onSetTheme,
                privacy = privacy,
                vm = vm,
                onBack = { page = Page.JOURNAL },
                onLicense = { page = Page.LICENSE },
                onNotices = { page = Page.NOTICES },
            )
            Page.LICENSE -> LicenseScreen(
                title = stringResource(R.string.about_license_title),
                paths = LICENSE_TEXTS,
                onBack = { page = Page.SETTINGS },
            )
            Page.NOTICES -> LicenseScreen(
                title = stringResource(R.string.about_notices),
                paths = THIRD_PARTY_TEXTS,
                onBack = { page = Page.SETTINGS },
            )
        }
    }

    TransferDialogs(
        status = transferStatus,
        onDismiss = vm::dismissTransferStatus,
        onPassword = { uri, password -> vm.importFrom(uri, password) },
    )
}

/**
 * Home. A journal page rather than a list of cards: the wordmark, the
 * search field, then the entries set as paragraphs under the day they
 * were spoken, with the record button floating over the foot of the page.
 * Brass is kept for that button; nothing else on the page competes with it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun JournalPage(
    vm: JournalViewModel,
    listState: LazyListState,
    onOpen: (Long) -> Unit,
    onSettings: () -> Unit,
) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val pipeline by vm.pipeline.collectAsStateWithLifecycle()
    val recovering by vm.recovering.collectAsStateWithLifecycle()
    val quarantined by vm.quarantined.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
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

    val focus = LocalFocusManager.current
    val typing = WindowInsets.isImeVisible
    // The field gives up its focus (and its cursor) when the keyboard goes
    // or the page is scrolled, so it does not sit there looking active.
    LaunchedEffect(typing) { if (!typing) focus.clearFocus() }
    LaunchedEffect(listState.isScrollInProgress) { if (listState.isScrollInProgress) focus.clearFocus() }
    // Back leaves a search before it leaves the app.
    BackHandler(enabled = query.isNotEmpty()) { vm.setQuery("") }

    Scaffold { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    Modifier.fillMaxWidth().padding(start = PAGE_MARGIN, end = 8.dp, top = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        stringResource(R.string.app_name),
                        style = MaterialTheme.typography.headlineMedium,
                        modifier = Modifier.weight(1f).semantics { heading() },
                    )
                    IconButton(onClick = onSettings) {
                        Icon(
                            AppIcons.Settings,
                            contentDescription = stringResource(R.string.action_settings),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                SearchField(
                    query = query,
                    onQueryChange = vm::setQuery,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )

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

                val searching = query.isNotBlank()
                if (entries.isEmpty() && pipeline is PipelineState.Idle && !recovering) {
                    EmptyPage(
                        title = if (searching) stringResource(R.string.empty_search, query.trim())
                        else stringResource(R.string.empty_journal),
                        hint = stringResource(
                            if (searching) R.string.empty_search_hint else R.string.empty_journal_hint
                        ),
                    )
                } else {
                    EntryList(entries = entries, searching = searching, listState = listState, onOpen = onOpen)
                }
            }

            Column(Modifier.align(Alignment.BottomCenter)) {
                (pipeline as? PipelineState.Failed)?.let { failed ->
                    Snackbar(
                        modifier = Modifier
                            .padding(horizontal = 16.dp)
                            .semantics { liveRegion = LiveRegionMode.Assertive },
                        action = {
                            TextButton(onClick = vm::dismissError) { Text(stringResource(R.string.action_dismiss)) }
                        },
                    ) { Text(failed.message) }
                }
                // Out of the way while the keyboard is up for a search, but
                // never while there is a recording to stop.
                AnimatedVisibility(
                    visible = !typing || pipeline is PipelineState.Recording || pipeline is PipelineState.Transcribing,
                    enter = fadeIn(),
                    exit = fadeOut(),
                ) {
                    RecordFooter(
                        pipeline = pipeline,
                        elapsedMs = elapsed,
                        level = vm.recorder.level,
                        onToggle = {
                            val granted = ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                            if (granted) vm.toggleRecording()
                            else permissionLauncher.launch(recordPermissions)
                        },
                        onDiscard = vm::discardRecording,
                    )
                }
            }
        }
    }
}

/** Left and right margin of the journal page's text. */
private val PAGE_MARGIN = 20.dp

/** Room the list leaves at its end so the last entry scrolls clear of the floating footer. */
private val FOOTER_CLEARANCE = 128.dp

/**
 * A quiet filled pill, not an outlined box: it should read as a place to
 * type without competing with the record button for attention.
 */
@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit, modifier: Modifier = Modifier) {
    val focus = LocalFocusManager.current
    val container = MaterialTheme.colorScheme.surfaceContainerHigh
    val sans = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Default)
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier.fillMaxWidth(),
        textStyle = sans,
        placeholder = { Text(stringResource(R.string.search_placeholder), style = sans) },
        leadingIcon = { Icon(AppIcons.Search, contentDescription = null) },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(AppIcons.Close, contentDescription = stringResource(R.string.search_clear))
                }
            }
        },
        singleLine = true,
        shape = CircleShape,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        // Results arrive as you type; the key only puts the keyboard away.
        keyboardActions = KeyboardActions(onSearch = { focus.clearFocus() }),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = container,
            unfocusedContainerColor = container,
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
            focusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            focusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unfocusedTrailingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    )
}

/**
 * The entries. Browsing, they are grouped under the day they were spoken,
 * newest first, and each shows only its time and length. Searching, they
 * come in order of closeness instead, so each carries its full date and a
 * line above says how they are ordered.
 */
@Composable
private fun EntryList(
    entries: List<JournalEntry>,
    searching: Boolean,
    listState: LazyListState,
    onOpen: (Long) -> Unit,
) {
    val zone = remember { ZoneId.systemDefault() }
    val today = LocalDate.now(zone)
    val days = remember(entries, zone) { entries.groupBy { dayOf(it.timestampMs, zone) }.toList() }

    LazyColumn(
        state = listState,
        // The footer floats over the list; this lets the last entry scroll clear of it.
        contentPadding = PaddingValues(bottom = FOOTER_CLEARANCE),
        modifier = Modifier.fillMaxSize(),
    ) {
        if (searching) {
            item(key = "results") {
                Text(
                    pluralStringResource(R.plurals.search_results, entries.size, entries.size),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = PAGE_MARGIN, vertical = 8.dp),
                )
            }
            itemsIndexed(entries, key = { _, e -> e.id }) { i, entry ->
                if (i > 0) EntryRule()
                EntryRow(entry, meta = entryMeta(entry, withDate = true), onOpen = { onOpen(entry.id) })
            }
        } else {
            for ((day, ofDay) in days) {
                stickyHeader(key = "day-$day") { DayHeader(dayLabel(day, today)) }
                itemsIndexed(ofDay, key = { _, e -> e.id }) { i, entry ->
                    if (i > 0) EntryRule()
                    EntryRow(entry, meta = entryMeta(entry, withDate = false), onOpen = { onOpen(entry.id) })
                }
            }
        }
    }
}

/** The day a group of entries belongs to. Opaque, so entries pass under it as it sticks. */
@Composable
private fun DayHeader(label: String) {
    Text(
        label,
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.onBackground,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .padding(start = PAGE_MARGIN, end = PAGE_MARGIN, top = 16.dp, bottom = 4.dp)
            .semantics { heading() },
    )
}

@Composable
private fun EntryRule() {
    HorizontalDivider(
        Modifier.padding(horizontal = PAGE_MARGIN),
        color = MaterialTheme.colorScheme.outlineVariant,
    )
}

/** One entry on the page: when it was said, how long it runs, and how it begins. */
@Composable
private fun EntryRow(entry: JournalEntry, meta: String, onOpen: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClickLabel = stringResource(R.string.entry_open), onClick = onOpen)
            .padding(horizontal = PAGE_MARGIN, vertical = 14.dp),
    ) {
        Text(
            meta,
            style = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))
        if (entry.transcript.isBlank()) {
            Text(
                stringResource(R.string.entry_no_speech),
                style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            Text(
                entry.transcript,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** "10:43 AM · 0:19", with the date in front while searching; no length when there is no recording. */
@Composable
private fun entryMeta(entry: JournalEntry, withDate: Boolean): String {
    val context = LocalContext.current
    val (at, length) = remember(entry.timestampMs, entry.audioPath, withDate) {
        val time = DateFormat.getTimeFormat(context).format(Date(entry.timestampMs))
        val at = if (withDate) formatDay(entry.timestampMs, "yMMMd") + "  ·  " + time else time
        // Length from the file size alone; blank when there is no recording.
        at to AudioPlayer.wavDurationMs(File(entry.audioPath).length()).takeIf { it > 0L }
    }
    // stringResource, not context.getString: it follows a language change.
    return if (length == null) at else stringResource(R.string.entry_meta, at, formatMs(length))
}

/** "Today", "Yesterday", "Tuesday, November 17", and the year once it is not this one. */
@Composable
private fun dayLabel(day: LocalDate, today: LocalDate): String = when {
    day == today -> stringResource(R.string.day_today)
    day == today.minusDays(1) -> stringResource(R.string.day_yesterday)
    else -> remember(day, today.year) {
        val ms = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        formatDay(ms, if (day.year == today.year) "EEEEMMMMd" else "EEEEMMMMdy")
    }
}

@Composable
private fun EmptyPage(title: String, hint: String) {
    Column(
        Modifier.fillMaxSize().padding(start = 40.dp, end = 40.dp, bottom = FOOTER_CLEARANCE),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(title, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

/** What the footer is doing; its three faces. */
private enum class FooterMode { IDLE, RECORDING, TRANSCRIBING }

/**
 * The footer: the one action of the app, and the only brass on the page.
 * It is a capsule the width of the page, the search field's twin at the
 * other end and the same shape as the player on an entry. Its outline
 * never changes; only what is in it does. Idle, a brass microphone and an
 * invitation, and the whole capsule starts a recording. Recording: stop,
 * the running time, a trail of the input level, and Discard. Transcribing,
 * it goes quiet and says so: there is nothing to start or stop until the
 * entry is written. Every state keeps its mark in the same slot on the
 * left and starts its text at the same place, so nothing jumps.
 *
 * It has no bar. The page ends in a short fade above it and a plain band
 * behind it, so text slides under a clean edge instead of showing through.
 */
@Composable
private fun RecordFooter(
    pipeline: PipelineState,
    elapsedMs: Long,
    level: StateFlow<Float>,
    onToggle: () -> Unit,
    onDiscard: () -> Unit,
) {
    val mode = when (pipeline) {
        is PipelineState.Recording -> FooterMode.RECORDING
        is PipelineState.Transcribing -> FooterMode.TRANSCRIBING
        else -> FooterMode.IDLE
    }
    val haptics = LocalHapticFeedback.current
    val toggle = {
        haptics.performHapticFeedback(HapticFeedbackType.Confirm)
        onToggle()
    }
    val background = MaterialTheme.colorScheme.background
    // Sans, medium: a control's label, not a field's hint.
    val controlText = MaterialTheme.typography.bodyLarge.copy(
        fontFamily = FontFamily.Default,
        fontWeight = FontWeight.Medium,
    )
    // Only while recording: the prompt closes itself if the recording ends first.
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    if (mode != FooterMode.RECORDING && confirmDiscard) confirmDiscard = false

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(FOOTER_FADE)
                .background(Brush.verticalGradient(listOf(Color.Transparent, background)))
        )
        Box(
            Modifier.fillMaxWidth().background(background).padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = mode,
                transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(120)) },
                contentAlignment = Alignment.Center,
                label = "footer",
            ) { shown ->
                when (shown) {
                    FooterMode.IDLE -> {
                        val label = stringResource(R.string.action_record)
                        RecorderCapsule(
                            onClick = toggle,
                            modifier = Modifier.semantics { contentDescription = label; role = Role.Button },
                        ) {
                            Box(
                                Modifier.size(MARK_SLOT).background(MaterialTheme.colorScheme.primary, CircleShape),
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(AppIcons.Mic, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
                            }
                            Spacer(Modifier.width(MARK_GAP))
                            Text(stringResource(R.string.action_new_entry), style = controlText)
                        }
                    }
                    FooterMode.RECORDING -> RecorderCapsule {
                        StopButton(toggle)
                        Spacer(Modifier.width(MARK_GAP))
                        Text(
                            formatMs(elapsedMs),
                            // Fixed-width digits, so the trail beside it does not twitch every second.
                            style = controlText.copy(fontFeatureSettings = "tnum"),
                        )
                        Spacer(Modifier.width(16.dp))
                        LevelTrail(level, Modifier.weight(1f).height(32.dp))
                        Spacer(Modifier.width(4.dp))
                        IconButton(onClick = { confirmDiscard = true }) {
                            Icon(
                                AppIcons.Close,
                                contentDescription = stringResource(R.string.rec_discard),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    FooterMode.TRANSCRIBING -> RecorderCapsule(
                        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
                    ) {
                        Box(
                            Modifier.size(MARK_SLOT).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            CircularProgressIndicator(
                                Modifier.size(24.dp),
                                strokeWidth = 3.dp,
                                color = MaterialTheme.colorScheme.primary,
                                trackColor = Color.Transparent,
                            )
                        }
                        Spacer(Modifier.width(MARK_GAP))
                        Text(
                            stringResource(R.string.status_transcribing),
                            style = controlText,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text(stringResource(R.string.rec_discard_title)) },
            text = { Text(stringResource(R.string.rec_discard_body)) },
            confirmButton = {
                TextButton(onClick = { confirmDiscard = false; onDiscard() }) {
                    Text(stringResource(R.string.entry_discard), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.rec_keep_recording)) }
            },
        )
    }
}

/** Height of the fade where the page meets the footer. */
private val FOOTER_FADE = 32.dp

/** The slot on the capsule's left that holds its mark in every state, and the gap before its text. */
private val MARK_SLOT = 48.dp
private val MARK_GAP = 16.dp

/** The capsule itself. Tappable as a whole when [onClick] is given. */
@Composable
private fun RecorderCapsule(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val shaped = modifier.fillMaxWidth().height(64.dp)
    val row: @Composable () -> Unit = {
        Row(
            Modifier.padding(start = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }
    val fill = MaterialTheme.colorScheme.surfaceContainerHigh
    if (onClick != null) Surface(onClick = onClick, shape = CircleShape, color = fill, modifier = shaped, content = row)
    else Surface(shape = CircleShape, color = fill, modifier = shaped, content = row)
}

/**
 * Stop, in brass, with a halo that breathes around it: the sign that the
 * microphone is live. The halo fades rather than the button changing
 * color, so the brass and the mark on it stay readable in both themes.
 */
@Composable
private fun StopButton(onStop: () -> Unit) {
    val label = stringResource(R.string.action_stop)
    val brass = MaterialTheme.colorScheme.primary
    val breath by rememberInfiniteTransition(label = "live").animateFloat(
        initialValue = 0.1f,
        targetValue = 0.4f,
        animationSpec = infiniteRepeatable(tween(1400), RepeatMode.Reverse),
        label = "halo",
    )
    // A little smaller than its slot, which leaves the ring room inside the capsule.
    Box(Modifier.size(MARK_SLOT), contentAlignment = Alignment.Center) {
        Surface(
            onClick = onStop,
            shape = CircleShape,
            color = brass,
            modifier = Modifier
                .size(42.dp)
                // A fixed radius, not one taken from the layout size: a
                // clickable surface is padded out to the minimum touch target.
                .drawBehind { drawCircle(brass.copy(alpha = breath), radius = 25.dp.toPx()) }
                .semantics { contentDescription = label; role = Role.Button },
        ) {
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(15.dp).background(MaterialTheme.colorScheme.onPrimary, RoundedCornerShape(4.dp)))
            }
        }
    }
}

/**
 * The input level as a trail of bars, newest on the right, moving left as
 * you speak: proof at a glance that the microphone hears you, and a little
 * of what was said a moment ago. Sampled here, not in the screen, so
 * several updates a second redraw only this canvas.
 */
@Composable
private fun LevelTrail(level: StateFlow<Float>, modifier: Modifier = Modifier) {
    val samples = remember { mutableStateListOf<Float>() }
    LaunchedEffect(level) {
        while (true) {
            samples.add(level.value.coerceIn(0f, 1f))
            if (samples.size > TRAIL_SAMPLES) samples.removeAt(0)
            delay(TRAIL_SAMPLE_MS)
        }
    }
    val past = MaterialTheme.colorScheme.onSurfaceVariant
    val now = MaterialTheme.colorScheme.primary
    Canvas(modifier.clearAndSetSemantics { }) {
        val bar = 3.dp.toPx()
        val pitch = 6.dp.toPx()
        val fits = (size.width / pitch).toInt()
        val shown = samples.takeLast(fits)
        shown.forEachIndexed { i, v ->
            val age = shown.lastIndex - i
            val x = size.width - bar / 2 - age * pitch
            val h = bar + (size.height - bar) * v
            drawLine(
                // One quiet tone for what was said; brass only for now. The
                // oldest few fade out at the left edge.
                color = if (age == 0) now else past.copy(alpha = 0.5f * ((fits - age) / 6f).coerceIn(0f, 1f)),
                start = Offset(x, (size.height - h) / 2 + bar / 2),
                end = Offset(x, (size.height + h) / 2 - bar / 2),
                strokeWidth = bar,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** How much of the recent past the level trail keeps, and how often it samples. */
private const val TRAIL_SAMPLES = 80
private const val TRAIL_SAMPLE_MS = 90L

@Composable
private fun StatusLine(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .padding(horizontal = PAGE_MARGIN, vertical = 4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
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
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 14.dp, bottom = 4.dp)) {
            Text(
                pluralStringResource(R.plurals.quarantine_notice, count, count),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(end = 8.dp),
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

internal fun formatMs(ms: Long): String {
    val s = ms / 1000
    return "%d:%02d".format(s / 60, s % 60)
}

/** The calendar day [ms] falls on in [zone]. */
internal fun dayOf(ms: Long, zone: ZoneId): LocalDate = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

/**
 * [ms] as a date in the user's locale. [skeleton] names the fields wanted
 * ("EEEEMMMMd"); the locale decides their order and punctuation.
 */
internal fun formatDay(ms: Long, skeleton: String): String {
    val locale = Locale.getDefault()
    return SimpleDateFormat(DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(Date(ms))
}
