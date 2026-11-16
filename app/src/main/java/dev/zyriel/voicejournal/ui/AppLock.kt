package dev.zyriel.voicejournal.ui

import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import dev.zyriel.voicejournal.R

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
    fun setHideInRecents(c: Context, on: Boolean) = prefs(c).edit { putBoolean(KEY_HIDE, on) }
    fun lockEnabled(c: Context) = prefs(c).getBoolean(KEY_LOCK, true)
    fun setLockEnabled(c: Context, on: Boolean) = prefs(c).edit { putBoolean(KEY_LOCK, on) }
}

/**
 * Process-wide lock state. A new process starts locked, so a cold start
 * always asks; rotation and other Activity recreations keep the unlock.
 *
 * The lock is a screen gate: it keeps someone holding the unlocked phone
 * out of the journal. It does not encrypt anything; the data's protection
 * at rest is Android's app sandbox and the device's own storage encryption.
 */
internal object LockState {
    @Volatile var unlocked = false
    /** elapsedRealtime when the app left the screen, 0 while visible. */
    @Volatile var backgroundedAt = 0L
    /** True while a prompt is up, so a recreated Activity does not stack a second one. */
    @Volatile var prompting = false
    /**
     * True while a system file picker the app launched (import or export)
     * covers the journal. Leaving for it is not leaving the app, so it
     * must not trip the relock timer, up to [PICKER_GRACE_MS] so a picker
     * left open is not an unlock that never expires. elapsedRealtime at
     * launch, 0 when none is open; cleared by the picker's result.
     */
    @Volatile var pickerOpenedAt = 0L

    /** Launches an app-owned picker without the relock firing behind it. */
    fun launchPicker(launch: () -> Unit) {
        pickerOpenedAt = SystemClock.elapsedRealtime()
        try {
            launch()
        } catch (e: android.content.ActivityNotFoundException) {
            pickerOpenedAt = 0L
            throw e
        }
    }

    /** Longest a picker can hold off the relock. */
    const val PICKER_GRACE_MS = 30 * 60 * 1000L
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
internal fun LockScreen(message: String?, onUnlock: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).padding(32.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(stringResource(R.string.lock_title), style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                message ?: stringResource(R.string.lock_body),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(24.dp))
            Button(onClick = onUnlock) { Text(stringResource(R.string.lock_unlock)) }
        }
    }
}
