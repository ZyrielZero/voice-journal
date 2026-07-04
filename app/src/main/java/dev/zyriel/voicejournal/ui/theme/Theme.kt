package dev.zyriel.voicejournal.ui.theme

import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.zyriel.voicejournal.R

enum class ThemeMode { SYSTEM, LIGHT, DARK;
    fun next(): ThemeMode = entries[(ordinal + 1) % entries.size]
}

object ThemePrefs {
    private const val PREFS = "settings"
    private const val KEY = "theme_mode"
    fun load(context: Context): ThemeMode =
        runCatching { ThemeMode.valueOf(context.getSharedPreferences(PREFS, 0).getString(KEY, null) ?: "") }
            .getOrDefault(ThemeMode.SYSTEM)
    fun save(context: Context, mode: ThemeMode) {
        context.getSharedPreferences(PREFS, 0).edit().putString(KEY, mode.name).apply()
    }
}

/*
 * "Reading room after midnight."
 * Dark is the primary experience: warm-cast charred umber, never blue-black.
 * Each color has one job:
 *   ember brass  -> the single action (record) and active states
 *   worn leather -> entry cards
 *   aged paper   -> text
 *   ivy          -> secondary accents only (theme toggle, links)
 */
private val EmberBrass = Color(0xFFD99A4E)
private val EmberDeep = Color(0xFF8F5A1F)
private val IvyDark = Color(0xFF8C9678)
private val IvyLight = Color(0xFF556247)

val EmberPulseLow = EmberDeep
val EmberPulseHigh = Color(0xFFE8B36A)

private val DarkColors: ColorScheme = darkColorScheme(
    primary = EmberBrass,
    onPrimary = Color(0xFF2C1C05),
    primaryContainer = Color(0xFF4A3416),
    onPrimaryContainer = Color(0xFFF1DEBB),
    secondary = IvyDark,
    onSecondary = Color(0xFF1C2114),
    background = Color(0xFF171008),
    onBackground = Color(0xFFEBDFC9),
    surface = Color(0xFF171008),
    onSurface = Color(0xFFEBDFC9),
    surfaceVariant = Color(0xFF2A1F14),
    onSurfaceVariant = Color(0xFFBBA98D),
    outline = Color(0xFF5C4E3A),
    error = Color(0xFFC96F58),
    onError = Color(0xFF2B0E06),
)

/* The same room, morning. Aged paper and oak ink, tanner than default cream. */
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF7A5224),
    onPrimary = Color(0xFFFFF6E3),
    primaryContainer = Color(0xFFEBD6A8),
    onPrimaryContainer = Color(0xFF33210A),
    secondary = IvyLight,
    onSecondary = Color(0xFFF2F0E4),
    background = Color(0xFFF1E7D2),
    onBackground = Color(0xFF2A2013),
    surface = Color(0xFFF1E7D2),
    onSurface = Color(0xFF2A2013),
    surfaceVariant = Color(0xFFE7D9BC),
    onSurfaceVariant = Color(0xFF6C5D46),
    outline = Color(0xFFA08D6E),
    error = Color(0xFF9E4A38),
    onError = Color(0xFFFFF3EC),
)

/* Literata carries titles and transcripts; sans stays on controls. */
val Literata = FontFamily(Font(R.font.literata))

private val ReadingTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = Literata, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = Literata, fontWeight = FontWeight.Medium,
        fontSize = 20.sp, lineHeight = 28.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = Literata, fontWeight = FontWeight.Normal,
        fontSize = 17.sp, lineHeight = 26.sp, letterSpacing = 0.1.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = Literata, fontWeight = FontWeight.Normal,
        fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.1.sp,
    ),
)

@Composable
fun VoiceJournalTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = when (mode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = ReadingTypography,
        content = content,
    )
}
