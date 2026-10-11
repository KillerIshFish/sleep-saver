package com.sleepsaver.core.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified

/** The system font scale from which choice rows stack vertically instead of sitting side by side. */
internal const val StackedFontScale = 1.5f

/** Whether the system font scale is at least [StackedFontScale]. */
@Composable
@ReadOnlyComposable
internal fun stackForLargeFont(): Boolean = LocalDensity.current.fontScale >= StackedFontScale

/**
 * This style, growing with the system font size only up to [maxScale]: for text inside fixed-size shapes
 * (a day circle) that would otherwise spill out at 200 %.
 */
@Composable
@ReadOnlyComposable
internal fun TextStyle.maxFontScale(maxScale: Float): TextStyle {
    val factor = maxScale / LocalDensity.current.fontScale
    return if (factor >= 1f) {
        this
    } else {
        copy(fontSize = fontSize * factor, lineHeight = if (lineHeight.isSpecified) lineHeight * factor else lineHeight)
    }
}
