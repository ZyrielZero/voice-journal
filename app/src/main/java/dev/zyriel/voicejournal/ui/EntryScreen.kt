package dev.zyriel.voicejournal.ui

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zyriel.voicejournal.R
import dev.zyriel.voicejournal.audio.AudioPlayer
import dev.zyriel.voicejournal.data.JournalEntry
import java.io.File
import java.util.Date

/**
 * One entry as a page: the day it was spoken, its recording, and the
 * transcript to read, select or edit. Editing happens on the page itself,
 * in the same type and place as the reading text, so nothing jumps. Both
 * destructive exits ask first: Delete confirms, and leaving with unsaved
 * changes (Back, Close) offers to discard. The draft and both prompts
 * survive rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryScreen(
    entry: JournalEntry,
    player: AudioPlayer,
    canPlay: Boolean,
    onClose: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by rememberSaveable(entry.id) { mutableStateOf(false) }
    var draft by rememberSaveable(entry.id, stateSaver = TextFieldValue.Saver) {
        mutableStateOf(TextFieldValue(entry.transcript))
    }
    var confirmDelete by rememberSaveable(entry.id) { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable(entry.id) { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val dirty = editing && draft.text != entry.transcript

    val startEditing = {
        // The cursor starts at the end, where a correction usually continues.
        draft = TextFieldValue(entry.transcript, TextRange(entry.transcript.length))
        editing = true
    }
    val stopEditing = { editing = false }
    // Back and Close leave edit mode first; only a second Back leaves the entry.
    val goBack = {
        when {
            dirty -> confirmDiscard = true
            editing -> stopEditing()
            else -> onClose()
        }
    }
    BackHandler(onBack = goBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { if (editing) Text(stringResource(R.string.entry_editing)) },
                navigationIcon = {
                    IconButton(onClick = goBack) {
                        if (editing) Icon(AppIcons.Close, stringResource(R.string.action_cancel))
                        else Icon(AppIcons.ArrowBack, stringResource(R.string.action_back))
                    }
                },
                actions = {
                    if (editing) {
                        FilledTonalButton(
                            onClick = { onSave(draft.text); editing = false },
                            enabled = dirty,
                            modifier = Modifier.padding(end = 8.dp),
                        ) { Text(stringResource(R.string.action_save)) }
                    } else {
                        IconButton(onClick = startEditing) {
                            Icon(AppIcons.Edit, stringResource(R.string.action_edit))
                        }
                        // Delete is rare and final; it waits behind the menu.
                        Box {
                            IconButton(onClick = { menuOpen = true }) {
                                Icon(AppIcons.MoreVert, stringResource(R.string.action_more))
                            }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.action_delete)) },
                                    leadingIcon = { Icon(AppIcons.Delete, contentDescription = null) },
                                    onClick = { menuOpen = false; confirmDelete = true },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .consumeWindowInsets(pad)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(start = 20.dp, end = 20.dp, bottom = 32.dp)
        ) {
            val context = LocalContext.current
            Text(
                remember(entry.timestampMs) { formatDay(entry.timestampMs, "EEEEMMMMdy") },
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                remember(entry.timestampMs) { DateFormat.getTimeFormat(context).format(Date(entry.timestampMs)) },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )

            val audio = remember(entry.audioPath) { File(entry.audioPath).takeIf { it.isFile } }
            if (audio != null) {
                Spacer(Modifier.height(20.dp))
                PlaybackBar(audio, player, enabled = canPlay)
            }
            Spacer(Modifier.height(24.dp))

            val reading = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onBackground)
            if (editing) {
                val focus = remember { FocusRequester() }
                LaunchedEffect(Unit) { focus.requestFocus() }
                BasicTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    textStyle = reading,
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Text(
                    stringResource(R.string.entry_save_hint),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 20.dp),
                )
            } else if (entry.transcript.isBlank()) {
                Text(
                    stringResource(R.string.entry_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                SelectionContainer {
                    Text(entry.transcript, style = reading, modifier = Modifier.fillMaxWidth())
                }
            }
        }
    }

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
                TextButton(onClick = { confirmDiscard = false; stopEditing() }) {
                    Text(stringResource(R.string.entry_discard))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDiscard = false }) { Text(stringResource(R.string.entry_keep_editing)) }
            },
        )
    }
}

/**
 * The recording: a play or pause button, a seek bar with position and
 * length under it, and the speed. The bar works before anything is
 * playing: its length comes from the file, and dragging it starts playback
 * from there. Disabled as a whole while the microphone is recording.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlaybackBar(audio: File, player: AudioPlayer, enabled: Boolean) {
    val playback by player.playback.collectAsStateWithLifecycle()
    val mine = playback?.takeIf { it.file == audio }
    val durationMs = mine?.durationMs?.takeIf { it > 0L }
        ?: remember(audio) { AudioPlayer.wavDurationMs(audio.length()) }
    val playing = mine != null && !mine.paused
    // While the thumb is being dragged it follows the finger, not the player.
    var dragMs by remember(audio) { mutableStateOf<Float?>(null) }
    val positionMs = dragMs ?: (mine?.positionMs ?: 0L).toFloat()
    val speed = mine?.speed ?: playback?.speed ?: 1f
    val position = formatMs(positionMs.toLong())
    val length = formatMs(durationMs)
    val spoken = stringResource(R.string.playback_position_spoken, position, length)
    val colors = MaterialTheme.colorScheme
    val meta = MaterialTheme.typography.labelMedium.copy(fontFeatureSettings = "tnum")

    Surface(color = colors.surfaceContainer, shape = RoundedCornerShape(24.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FilledIconButton(
                onClick = {
                    when {
                        mine == null -> player.play(audio)
                        mine.paused -> player.resume()
                        else -> player.pause()
                    }
                },
                enabled = enabled,
                modifier = Modifier.size(48.dp),
            ) {
                if (playing) Icon(AppIcons.Pause, stringResource(R.string.action_pause))
                else Icon(AppIcons.Play, stringResource(R.string.action_play))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                val sliderColors = SliderDefaults.colors(
                    thumbColor = colors.primary,
                    activeTrackColor = colors.primary,
                    inactiveTrackColor = colors.outlineVariant,
                    disabledThumbColor = colors.outline,
                    disabledActiveTrackColor = colors.outline,
                    disabledInactiveTrackColor = colors.outlineVariant,
                )
                Slider(
                    value = positionMs.coerceIn(0f, durationMs.toFloat()),
                    onValueChange = { dragMs = it },
                    onValueChangeFinished = {
                        dragMs?.let { target ->
                            if (mine != null) player.seekTo(target.toLong()) else player.play(audio, target.toLong())
                        }
                        dragMs = null
                    },
                    valueRange = 0f..durationMs.toFloat().coerceAtLeast(1f),
                    enabled = enabled,
                    colors = sliderColors,
                    // A round thumb on a thin line: at 0:00 the stock bar
                    // thumb reads as a text cursor.
                    thumb = {
                        Box(
                            Modifier
                                .size(16.dp)
                                .background(if (enabled) colors.primary else colors.outline, CircleShape)
                        )
                    },
                    track = { state ->
                        SliderDefaults.Track(
                            sliderState = state,
                            colors = sliderColors,
                            enabled = enabled,
                            modifier = Modifier.height(4.dp),
                            drawStopIndicator = null,
                            thumbTrackGapSize = 0.dp,
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(32.dp)
                        .semantics { stateDescription = spoken },
                )
                Row(Modifier.fillMaxWidth()) {
                    Text(position, style = meta, color = colors.onSurfaceVariant)
                    Spacer(Modifier.weight(1f))
                    Text(length, style = meta, color = colors.onSurfaceVariant)
                }
            }
            val speedLabel = stringResource(R.string.playback_speed_label)
            TextButton(
                onClick = { player.setSpeed(AudioPlayer.nextSpeed(speed)) },
                modifier = Modifier.semantics { contentDescription = speedLabel },
            ) { Text(stringResource(R.string.playback_speed, formatSpeed(speed))) }
        }
    }
}

/** "1", "1.25", "0.75": a speed without a trailing ".0". */
internal fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
