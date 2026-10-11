package com.sleepsaver.feature.tonight

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.format.LocalUse24HourClock

/** [content] at a system font scale of 2 (the largest common setting). */
@Composable
internal fun LargeFont(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f), content = content)
}

/** [content] with the 24-hour clock. */
@Composable
internal fun Clock24(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalUse24HourClock provides true, content = content)
}

/**
 * A bottom sheet's [sheet] content over a dimmed [screen], drawn the way ModalBottomSheet shows it (its container
 * colour, top corners and drag handle), without the sheet's open animation.
 */
@Composable
internal fun SheetOver(screen: @Composable () -> Unit, sheet: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        screen()
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.32f)),
        )
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                BottomSheetDefaults.DragHandle()
                sheet()
            }
        }
    }
}
