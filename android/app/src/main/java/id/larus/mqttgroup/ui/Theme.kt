package id.larus.mqttgroup.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Deep blue for anything you can press, cyan held back for the things that mean "now" — someone
 * is live, a message is unread, the connection is up. That only works as a signal because
 * nothing else on the screen is that colour.
 *
 * The app follows the phone: white in light mode, black in dark mode, and the system's own
 * colours where it offers them.
 */
private val Blue = Color(0xFF2563EB)
private val BlueBright = Color(0xFF3B82F6)
private val BlueDeep = Color(0xFF1E3A8A)

// The accent needs a different weight per theme: #22D3EE is barely visible on white, and the
// darker cyan disappears into a black ground. Same hue, two brightnesses.
private val Cyan = Color(0xFF22D3EE)
private val CyanDeep = Color(0xFF0891B2)

private val Black = Color(0xFF08090C)
private val Raised = Color(0xFF12141A)
private val Raised2 = Color(0xFF1B1E26)
private val Raised3 = Color(0xFF262A34)
private val DarkLine = Color(0xFF2C313B)
private val DarkText = Color(0xFFE6E9EF)
private val DarkTextMuted = Color(0xFF98A0AE)

private val Slate50 = Color(0xFFF8FAFC)
private val Slate100 = Color(0xFFF1F5F9)
private val Slate200 = Color(0xFFE2E8F0)
private val Slate300 = Color(0xFFCBD5E1)
private val Slate500 = Color(0xFF64748B)
private val Slate900 = Color(0xFF0F172A)

/**
 * The accent, kept outside the colour scheme on purpose.
 *
 * Material You replaces a scheme wholesale with colours derived from the wallpaper, so an
 * accent living inside it is the first thing to disappear on a phone with dynamic colour on.
 * Holding it here means "live", "unread" and "connected" look the same on every device, while
 * the surfaces around them still adapt.
 */
val LocalAccent = staticCompositionLocalOf { Cyan }

/** Legible on the accent: near-black on the bright cyan, white on the deep one. */
val LocalOnAccent = staticCompositionLocalOf { Black }

private val LightColors = lightColorScheme(
    primary = Blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = BlueDeep,
    secondary = Color(0xFF4F46E5),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE0E7FF),
    onSecondaryContainer = Color(0xFF312E81),
    tertiary = Color(0xFF0E7490),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFCFFAFE),
    onTertiaryContainer = Color(0xFF164E63),
    background = Slate50,
    onBackground = Slate900,
    surface = Color.White,
    onSurface = Slate900,
    surfaceVariant = Slate100,
    onSurfaceVariant = Slate500,
    surfaceContainer = Color.White,
    surfaceContainerHigh = Slate100,
    surfaceContainerHighest = Slate200,
    outline = Slate300,
    outlineVariant = Slate200,
    error = Color(0xFFDC2626),
    onError = Color.White,
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D),
)

private val DarkColors = darkColorScheme(
    primary = BlueBright,
    onPrimary = Color(0xFF06152E),
    primaryContainer = BlueDeep,
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF818CF8),
    onSecondary = Color(0xFF1E1B4B),
    secondaryContainer = Color(0xFF312E81),
    onSecondaryContainer = Color(0xFFE0E7FF),
    tertiary = Cyan,
    onTertiary = Black,
    tertiaryContainer = Color(0xFF0E3D47),
    onTertiaryContainer = Color(0xFFCFFAFE),
    background = Black,
    onBackground = DarkText,
    surface = Black,
    onSurface = DarkText,
    surfaceVariant = Raised,
    onSurfaceVariant = DarkTextMuted,
    surfaceContainer = Raised,
    surfaceContainerHigh = Raised2,
    surfaceContainerHighest = Raised3,
    outline = DarkLine,
    outlineVariant = Raised2,
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFEE2E2),
)

/**
 * Tighter tracking and heavier headings than the Material default, which is tuned for a much
 * larger app than this one. Everything else is inherited.
 */
private val AppTypography = Typography().run {
    copy(
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.3).sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.SemiBold),
    )
}

@Composable
fun FamilyGroupTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(
        LocalAccent provides if (darkTheme) Cyan else CyanDeep,
        LocalOnAccent provides if (darkTheme) Black else Color.White,
    ) {
        MaterialTheme(colorScheme = colors, typography = AppTypography, content = content)
    }
}
