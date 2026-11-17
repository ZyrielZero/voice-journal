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
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import dev.zyriel.voicejournal.R

enum class ThemeMode { SYSTEM, LIGHT, DARK }

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
 *   ivy          -> secondary accents only
 * Every role Material reads is set here. A role left out falls back to
 * the baseline purple scheme, which is how menus, dialogs and the slider
 * track once came out lavender. Set a new role here before relying on it.
 */
private val EmberBrass = Color(0xFFD99A4E)
private val EmberDeep = Color(0xFF8F5A1F)
private val IvyDark = Color(0xFF8C9678)
private val IvyLight = Color(0xFF556247)


private val DarkColors: ColorScheme = darkColorScheme(
    primary = EmberBrass,
    onPrimary = Color(0xFF2C1C05),
    primaryContainer = Color(0xFF4A3416),
    onPrimaryContainer = Color(0xFFF1DEBB),
    inversePrimary = Color(0xFF7A5224),
    secondary = IvyDark,
    onSecondary = Color(0xFF1C2114),
    secondaryContainer = Color(0xFF3A3020),
    onSecondaryContainer = Color(0xFFE6DABF),
    tertiary = IvyDark,
    onTertiary = Color(0xFF1C2114),
    tertiaryContainer = Color(0xFF2C331F),
    onTertiaryContainer = Color(0xFFD5DDC2),
    background = Color(0xFF171008),
    onBackground = Color(0xFFEBDFC9),
    surface = Color(0xFF171008),
    onSurface = Color(0xFFEBDFC9),
    surfaceVariant = Color(0xFF2A1F14),
    onSurfaceVariant = Color(0xFFBBA98D),
    surfaceTint = EmberBrass,
    inverseSurface = Color(0xFFEBDFC9),
    inverseOnSurface = Color(0xFF2A2013),
    outline = Color(0xFF7A6A52),
    outlineVariant = Color(0xFF3A2E20),
    error = Color(0xFFC96F58),
    onError = Color(0xFF2B0E06),
    errorContainer = Color(0xFF4A1F14),
    onErrorContainer = Color(0xFFF3D2C6),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFF171008),
    surfaceBright = Color(0xFF3A2C1E),
    surfaceContainerLowest = Color(0xFF120C06),
    surfaceContainerLow = Color(0xFF1E160D),
    surfaceContainer = Color(0xFF241A10),
    surfaceContainerHigh = Color(0xFF2E2216),
    surfaceContainerHighest = Color(0xFF382A1C),
)

/* The same room, morning. Aged paper and oak ink, tanner than default cream. */
private val LightColors: ColorScheme = lightColorScheme(
    primary = Color(0xFF7A5224),
    onPrimary = Color(0xFFFFF6E3),
    primaryContainer = Color(0xFFEBD6A8),
    onPrimaryContainer = Color(0xFF33210A),
    inversePrimary = EmberBrass,
    secondary = IvyLight,
    onSecondary = Color(0xFFF2F0E4),
    secondaryContainer = Color(0xFFE0D3B4),
    onSecondaryContainer = Color(0xFF33291A),
    tertiary = IvyLight,
    onTertiary = Color(0xFFF2F0E4),
    tertiaryContainer = Color(0xFFD6DCC4),
    onTertiaryContainer = Color(0xFF1F2716),
    background = Color(0xFFF1E7D2),
    onBackground = Color(0xFF2A2013),
    surface = Color(0xFFF1E7D2),
    onSurface = Color(0xFF2A2013),
    surfaceVariant = Color(0xFFE7D9BC),
    onSurfaceVariant = Color(0xFF6C5D46),
    surfaceTint = Color(0xFF7A5224),
    inverseSurface = Color(0xFF33291A),
    inverseOnSurface = Color(0xFFF1E7D2),
    outline = Color(0xFF8E7B5C),
    outlineVariant = Color(0xFFD2C3A3),
    error = Color(0xFF9E4A38),
    onError = Color(0xFFFFF3EC),
    errorContainer = Color(0xFFF2D2C4),
    onErrorContainer = Color(0xFF4A1A0E),
    scrim = Color(0xFF000000),
    surfaceDim = Color(0xFFDECFAE),
    surfaceBright = Color(0xFFF8F0DF),
    surfaceContainerLowest = Color(0xFFF8F0DF),
    surfaceContainerLow = Color(0xFFEDE2CA),
    surfaceContainer = Color(0xFFE9DCC1),
    surfaceContainerHigh = Color(0xFFE4D6B8),
    surfaceContainerHighest = Color(0xFFDECFAE),
)

/*
 * Literata carries titles and transcripts; sans stays on controls.
 * literata.ttf is a variable font (wght 200-900). A single Font entry at
 * Normal made Compose fake-bold SemiBold and render Medium as Regular, so
 * each weight the typography uses gets its own entry pinned to the real
 * wght axis value. Add an entry here before using a new weight.
 */
@OptIn(ExperimentalTextApi::class)
private fun literataAt(weight: FontWeight) = Font(
    R.font.literata,
    weight = weight,
    variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)),
)

val Literata = FontFamily(
    literataAt(FontWeight.Normal),
    literataAt(FontWeight.Medium),
    literataAt(FontWeight.SemiBold),
)

private val ReadingTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = Literata, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 34.sp,
    ),
    // Dialog titles and the date at the head of an entry.
    headlineSmall = TextStyle(
        fontFamily = Literata, fontWeight = FontWeight.Medium,
        fontSize = 22.sp, lineHeight = 30.sp,
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

/** Whether [this] mode shows the dark scheme right now. */
@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun VoiceJournalTheme(mode: ThemeMode, content: @Composable () -> Unit) {
    val dark = mode.isDark()
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        typography = ReadingTypography,
        content = content,
    )
}
