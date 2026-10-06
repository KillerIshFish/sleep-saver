package com.sleepsaver.feature.habits

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.NotificationsOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.icon
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.timeRange
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import java.time.Instant
import java.time.ZoneId

/** A short one-line description for list rows (core:ui's description is a full sentence). */
@get:StringRes
internal val HabitType.taglineRes: Int
    get() = when (this) {
        HabitType.MORNING_LIGHT -> R.string.habits_tagline_morning_light
        HabitType.CAFFEINE_CUTOFF -> R.string.habits_tagline_caffeine
        HabitType.LARGE_MEAL_CUTOFF -> R.string.habits_tagline_large_meal
        HabitType.ALCOHOL_CUTOFF -> R.string.habits_tagline_alcohol
        HabitType.EXERCISE_CUTOFF -> R.string.habits_tagline_exercise
        HabitType.DIM_LIGHTS -> R.string.habits_tagline_dim_lights
        HabitType.EVENING_ROUTINE -> R.string.habits_tagline_evening_routine
        HabitType.BEDTIME -> R.string.habits_tagline_bedtime
        HabitType.NAP_WINDOW -> R.string.habits_tagline_nap_window
    }

/** Two or three plain sentences on why the habit helps (detail screen). */
@get:StringRes
internal val HabitType.whyRes: Int
    get() = when (this) {
        HabitType.MORNING_LIGHT -> R.string.habits_why_morning_light
        HabitType.CAFFEINE_CUTOFF -> R.string.habits_why_caffeine
        HabitType.LARGE_MEAL_CUTOFF -> R.string.habits_why_large_meal
        HabitType.ALCOHOL_CUTOFF -> R.string.habits_why_alcohol
        HabitType.EXERCISE_CUTOFF -> R.string.habits_why_exercise
        HabitType.DIM_LIGHTS -> R.string.habits_why_dim_lights
        HabitType.EVENING_ROUTINE -> R.string.habits_why_evening_routine
        HabitType.BEDTIME -> R.string.habits_why_bedtime
        HabitType.NAP_WINDOW -> R.string.habits_why_nap_window
    }

/** "2:15 PM", or "9:15 – 10:15 PM" for habits with a window; "—" without a time. */
@Composable
@ReadOnlyComposable
internal fun habitTimeText(time: Instant?, windowEnd: Instant?, zone: ZoneId?): String = when {
    time == null || zone == null -> stringResource(R.string.habits_no_time)
    windowEnd != null -> timeRange(time, windowEnd, zone)
    else -> clockTime(time, zone)
}

/** The habit's icon in a tinted circle; grey when the habit is off. */
@Composable
internal fun HabitBadge(type: HabitType, active: Boolean, modifier: Modifier = Modifier, size: Dp = 40.dp) {
    val tint = if (active) MaterialTheme.colorScheme.primary else SleepSaverTheme.colors.textSecondary
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(if (active) MaterialTheme.colorScheme.primaryContainer else SleepSaverTheme.colors.cardRaised),
        contentAlignment = Alignment.Center,
    ) {
        Icon(type.icon, contentDescription = null, tint = tint, modifier = Modifier.size(size * 0.55f))
    }
}

/** A round icon button at the top of a screen (back arrow or close). */
@Composable
internal fun TopIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier) {
        Icon(icon, contentDescription = description, tint = MaterialTheme.colorScheme.onSurface)
    }
}

/** The app's switch: tide track when on. */
@Composable
internal fun HabitSwitch(
    checked: Boolean,
    onCheckedChange: ((Boolean) -> Unit)?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        modifier = modifier,
        enabled = enabled,
        colors = SwitchDefaults.colors(
            checkedTrackColor = MaterialTheme.colorScheme.primary,
            checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
            uncheckedTrackColor = SleepSaverTheme.colors.cardRaised,
            uncheckedBorderColor = MaterialTheme.colorScheme.outline,
            uncheckedThumbColor = SleepSaverTheme.colors.textSecondary,
        ),
    )
}

/** Explains that reminders are blocked and links to the system's notification settings. */
@Composable
internal fun NotificationsOffNotice(onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    SleepSaverCard(modifier = modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(SleepSaverTheme.colors.cardRaised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Rounded.NotificationsOff,
                    contentDescription = null,
                    tint = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(R.string.habits_notifications_off_title),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    stringResource(R.string.habits_notifications_off_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                // Shifted by the button's inner padding so its text lines up with the body.
                TextLinkButton(
                    stringResource(R.string.habits_notifications_open_settings),
                    onClick = onOpenSettings,
                    modifier = Modifier.padding(top = 4.dp).offsetStart(),
                )
            }
        }
    }
}

/** Pulls a [TextLinkButton] left by its 12 dp inner padding so its label aligns with the text above it. */
internal fun Modifier.offsetStart(): Modifier = offset(x = (-12).dp)
