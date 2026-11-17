package dev.zyriel.voicejournal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.zyriel.voicejournal.R
import dev.zyriel.voicejournal.audio.AudioPlayer
import dev.zyriel.voicejournal.data.JournalEntry
import java.io.File

/**
 * One entry, full screen: its recording with a seek bar, and its transcript
 * to read, select or edit. Both destructive exits ask first: Delete
 * confirms, and leaving with unsaved changes (Back, Cancel) offers to
 * discard. The draft and both prompts survive rotation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun EntryScreen(
    entry: JournalEntry,
    player: AudioPlayer,
    onClose: () -> Unit,
    onSave: (String) -> Unit,
    onDelete: () -> Unit,
) {
    var editing by rememberSaveable(entry.id) { mutableStateOf(false) }
    var text by rememberSaveable(entry.id) { mutableStateOf(entry.transcript) }
    var confirmDelete by rememberSaveable(entry.id) { mutableStateOf(false) }
    var confirmDiscard by rememberSaveable(entry.id) { mutableStateOf(false) }
    val dirty = editing && text != entry.transcript

    val stopEditing = { editing = false; text = entry.transcript }
    // Back and Cancel leave edit mode first; only a second Back leaves the entry.
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
                title = { Text(formatDate(entry.timestampMs), style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    TextButton(onClick = goBack) {
                        Text(stringResource(if (editing) R.string.action_cancel else R.string.action_back))
                    }
                },
                actions = {
                    if (editing) {
                        TextButton(onClick = { onSave(text); editing = false }, enabled = dirty) {
                            Text(stringResource(R.string.action_save))
                        }
                    } else {
                        TextButton(onClick = { editing = true }) { Text(stringResource(R.string.action_edit)) }
                        TextButton(onClick = { confirmDelete = true }) {
                            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
                        }
                    }
                },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
            val audio = remember(entry.audioPath) { File(entry.audioPath).takeIf { it.isFile } }
            if (audio != null) PlaybackBar(audio, player)

            if (editing) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(top = 8.dp),
                    textStyle = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    stringResource(R.string.entry_save_hint),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            } else if (entry.transcript.isBlank()) {
                Text(
                    stringResource(R.string.entry_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 16.dp),
                )
            } else {
                SelectionContainer(Modifier.weight(1f)) {
                    Text(
                        entry.transcript,
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                            .padding(top = 16.dp, bottom = 24.dp),
                    )
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
 * Play or pause, a seek bar with position and length, and the speed. The
 * bar works before anything is playing: its length comes from the file,
 * and dragging it starts playback from there.
 */
@Composable
private fun PlaybackBar(audio: File, player: AudioPlayer) {
    val playback by player.playback.collectAsStateWithLifecycle()
    val mine = playback?.takeIf { it.file == audio }
    val durationMs = mine?.durationMs?.takeIf { it > 0L }
        ?: remember(audio) { AudioPlayer.wavDurationMs(audio.length()) }
    val playing = mine != null && !mine.paused
    // While the thumb is being dragged it follows the finger, not the player.
    var dragMs by remember(audio) { mutableStateOf<Float?>(null) }
    val positionMs = dragMs ?: (mine?.positionMs ?: 0L).toFloat()
    val speed = mine?.speed ?: playback?.speed ?: 1f

    Column(Modifier.fillMaxWidth()) {
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
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = {
                when {
                    mine == null -> player.play(audio)
                    mine.paused -> player.resume()
                    else -> player.pause()
                }
            }) { Text(stringResource(if (playing) R.string.action_pause else R.string.action_play)) }
            Text(
                stringResource(R.string.playback_position, formatMs(positionMs.toLong()), formatMs(durationMs)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { player.setSpeed(AudioPlayer.nextSpeed(speed)) }) {
                Text(stringResource(R.string.playback_speed, formatSpeed(speed)))
            }
        }
    }
}

/** "1", "1.25", "0.75": a speed without a trailing ".0". */
internal fun formatSpeed(speed: Float): String =
    if (speed == speed.toInt().toFloat()) speed.toInt().toString() else speed.toString()
