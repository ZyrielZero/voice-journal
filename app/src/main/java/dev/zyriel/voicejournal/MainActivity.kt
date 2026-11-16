package dev.zyriel.voicejournal

import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import dev.zyriel.voicejournal.ui.JournalScreen
import dev.zyriel.voicejournal.ui.LockScreen
import dev.zyriel.voicejournal.ui.LockState
import dev.zyriel.voicejournal.ui.PrivacyPrefs
import dev.zyriel.voicejournal.ui.PrivacySettings
import dev.zyriel.voicejournal.ui.theme.ThemePrefs
import dev.zyriel.voicejournal.ui.theme.VoiceJournalTheme

/**
 * The single Activity: owns the app lock (biometric or device credential)
 * and the screenshot-blocking window flag, and shows either the lock
 * screen or the journal. Everything on the journal screen lives in
 * [dev.zyriel.voicejournal.ui].
 */
class MainActivity : FragmentActivity() {

    private var locked by mutableStateOf(false)
    private var lockMessage by mutableStateOf<String?>(null)
    private var lockEnabled by mutableStateOf(true)
    private var hideInRecents by mutableStateOf(true)
    private lateinit var prompt: BiometricPrompt
    /**
     * The user dismissed the prompt. Saved across rotation so the recreated
     * Activity waits for the Unlock button instead of prompting again;
     * reset when the app really leaves the screen.
     */
    private var userCancelled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        userCancelled = savedInstanceState?.getBoolean(KEY_USER_CANCELLED) ?: false
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
                    LockScreen(message = lockMessage, onUnlock = {
                        // A prompt is already up (or reconnecting after
                        // rotation); a second authenticate would stack.
                        if (!LockState.prompting) {
                            userCancelled = false
                            authenticate()
                        }
                    })
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
        val now = SystemClock.elapsedRealtime()
        val left = LockState.backgroundedAt
        val picker = LockState.pickerOpenedAt
        val inPicker = picker != 0L && now - picker < LockState.PICKER_GRACE_MS
        if (left != 0L && !inPicker && now - left >= RELOCK_AFTER_MS) {
            LockState.unlocked = false
        }
        LockState.backgroundedAt = 0L
        locked = shouldLock()
    }

    override fun onResume() {
        super.onResume()
        if (locked && !LockState.prompting && !userCancelled) authenticate()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_USER_CANCELLED, userCancelled)
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            LockState.backgroundedAt = SystemClock.elapsedRealtime()
            // Coming back from elsewhere asks again, cancelled or not.
            userCancelled = false
        }
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
                .setTitle(getString(R.string.lock_prompt_title))
                .setSubtitle(getString(R.string.lock_prompt_subtitle))
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
                errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                    errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON -> {
                    userCancelled = true
                    lockMessage = null
                }
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

        const val KEY_USER_CANCELLED = "lock_user_cancelled"

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
