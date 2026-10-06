package com.sleepsaver.core.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.unit.dp

private val DarkScheme = darkColorScheme(
    primary = Tide,
    onPrimary = OnTide,
    primaryContainer = TideContainer,
    onPrimaryContainer = Tide,
    secondary = Tide,
    onSecondary = OnTide,
    secondaryContainer = Surface3,
    onSecondaryContainer = TextPrimary,
    tertiary = Amber,
    onTertiary = Ink,
    background = Ink,
    onBackground = TextPrimary,
    surface = Ink,
    onSurface = TextPrimary,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextSecondary,
    surfaceContainerLowest = Ink,
    surfaceContainerLow = Surface1,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface3,
    surfaceBright = Surface3,
    surfaceDim = Ink,
    inverseSurface = TextPrimary,
    inverseOnSurface = Ink,
    outline = Outline,
    outlineVariant = OutlineSoft,
    error = Coral,
    onError = Ink,
    errorContainer = Coral.copy(alpha = 0.18f),
    onErrorContainer = Coral,
    scrim = Ink,
)

val SleepSaverShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** The app is dark-only by design (it's used at night). */
@Composable
fun SleepSaverTheme(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalSleepSaverColors provides DarkExtendedColors) {
        MaterialTheme(
            colorScheme = DarkScheme,
            typography = SleepSaverTypography,
            shapes = SleepSaverShapes,
            content = content,
        )
    }
}

object SleepSaverTheme {
    val colors: SleepSaverExtendedColors
        @Composable @ReadOnlyComposable get() = LocalSleepSaverColors.current
}
