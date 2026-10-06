package com.sleepsaver.core.ui.components

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BlurOn
import androidx.compose.material.icons.rounded.SelfImprovement
import androidx.compose.material.icons.rounded.WbCloudy
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.ui.R
import com.sleepsaver.core.ui.theme.SleepSaverTheme

val PhaseType.icon: ImageVector
    get() = when (this) {
        PhaseType.GROGGINESS -> Icons.Rounded.BlurOn
        PhaseType.MORNING_PEAK -> Icons.Rounded.WbSunny
        PhaseType.AFTERNOON_DIP -> Icons.Rounded.WbCloudy
        PhaseType.EVENING_PEAK -> Icons.Rounded.WbTwilight
        PhaseType.WIND_DOWN -> Icons.Rounded.SelfImprovement
        PhaseType.MELATONIN_WINDOW -> Icons.Rounded.Bedtime
    }

@get:StringRes
val PhaseType.titleRes: Int
    get() = when (this) {
        PhaseType.GROGGINESS -> R.string.ss_phase_grogginess
        PhaseType.MORNING_PEAK -> R.string.ss_phase_morning_peak
        PhaseType.AFTERNOON_DIP -> R.string.ss_phase_afternoon_dip
        PhaseType.EVENING_PEAK -> R.string.ss_phase_evening_peak
        PhaseType.WIND_DOWN -> R.string.ss_phase_wind_down
        PhaseType.MELATONIN_WINDOW -> R.string.ss_phase_melatonin_window
    }

/** One plain-language line about what the phase feels like. */
@get:StringRes
val PhaseType.descriptionRes: Int
    get() = when (this) {
        PhaseType.GROGGINESS -> R.string.ss_phase_grogginess_body
        PhaseType.MORNING_PEAK -> R.string.ss_phase_morning_peak_body
        PhaseType.AFTERNOON_DIP -> R.string.ss_phase_afternoon_dip_body
        PhaseType.EVENING_PEAK -> R.string.ss_phase_evening_peak_body
        PhaseType.WIND_DOWN -> R.string.ss_phase_wind_down_body
        PhaseType.MELATONIN_WINDOW -> R.string.ss_phase_melatonin_window_body
    }

@Composable
@ReadOnlyComposable
fun PhaseType.title(): String = stringResource(titleRes)

/** Peaks use the accent, the night phases the melatonin tint, the rest stay grey. */
val PhaseType.tint: Color
    @Composable @ReadOnlyComposable
    get() = when (this) {
        PhaseType.MORNING_PEAK, PhaseType.EVENING_PEAK -> MaterialTheme.colorScheme.primary
        PhaseType.WIND_DOWN, PhaseType.MELATONIN_WINDOW -> SleepSaverTheme.colors.melatonin
        PhaseType.GROGGINESS, PhaseType.AFTERNOON_DIP -> SleepSaverTheme.colors.textSecondary
    }
