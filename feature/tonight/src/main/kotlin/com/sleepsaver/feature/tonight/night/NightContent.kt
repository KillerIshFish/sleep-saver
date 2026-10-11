package com.sleepsaver.feature.tonight.night

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Anchor
import androidx.compose.material.icons.rounded.Sensors
import androidx.compose.material.icons.rounded.SensorsOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.ui.components.HushControls
import com.sleepsaver.core.ui.components.HushControlsActions
import com.sleepsaver.core.ui.components.HushControlsState
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.keepTimesTogether
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.tonight.R
import java.time.LocalTime

/** The morning alarm as the night shows it: its latest time, never a countdown. */
@Immutable
internal sealed interface NightAlarm {
    /** Surfacing: "Alarm set · never later than 6:30 AM". */
    data class NeverLater(val time: LocalTime) : NightAlarm

    /** At wake time: "Alarm set · 6:30 AM". */
    data class At(val time: LocalTime) : NightAlarm

    /** "No alarm in the morning". */
    data object None : NightAlarm
}

/** The opt-in whole-night movement log: running, or stopped unexpectedly (offer Resume). */
internal enum class MovementLog { LOGGING, STOPPED }

/** What the no-clock night shows: the morning [alarm] and the movement [log] (null = not logging tonight). */
@Immutable
internal data class NightModel(val alarm: NightAlarm, val log: MovementLog?)

/** Every callback of the night content; all default to doing nothing. */
@Stable
internal class NightActions(
    val onResumeLog: () -> Unit = {},
    val hush: HushControlsActions = HushControlsActions(),
    val onCantSleep: () -> Unit = {},
    /** "End night" in the "I'm up" confirmation. */
    val onEndNight: () -> Unit = {},
)

/**
 * The night, shared by the Tonight tab (NIGHT) and the Night screen: the alarm line ("Alarm set · never later than
 * 6:30 AM"), the movement log's status with Resume, Hush (night controls), "Can't sleep?" and "I'm up" (confirmed).
 * Dim and grey, no tide buttons, and never the current time or a countdown (SPEC_V2 §0.2).
 */
@Composable
internal fun NightContent(
    night: NightModel,
    hush: HushControlsState,
    actions: NightActions,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    val secondary = SleepSaverTheme.colors.textSecondary
    var confirmEnd by rememberSaveable { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xl),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Spacing.xl))
        Box(
            modifier = Modifier
                .size(72.dp)
                .background(SleepSaverTheme.colors.card, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Rounded.Anchor, contentDescription = null, tint = secondary, modifier = Modifier.size(32.dp))
        }
        Spacer(Modifier.height(Spacing.l))
        Text(
            text = stringResource(R.string.tonight_night_title),
            style = MaterialTheme.typography.headlineSmall,
            color = secondary,
            modifier = Modifier.semantics { heading() },
        )
        Spacer(Modifier.height(Spacing.s))
        Text(
            text = night.alarm.line(),
            style = MaterialTheme.typography.bodyLarge,
            color = secondary,
            textAlign = TextAlign.Center,
        )
        night.log?.let { log ->
            Spacer(Modifier.height(Spacing.m))
            LogStatus(log, onResume = actions.onResumeLog)
        }

        Spacer(Modifier.height(Spacing.xl))
        SleepSaverCard(Modifier.fillMaxWidth()) {
            HushControls(state = hush.copy(night = true), actions = actions.hush)
        }

        Spacer(Modifier.height(Spacing.xl))
        TextLinkButton(stringResource(R.string.tonight_night_cant_sleep), onClick = actions.onCantSleep, color = secondary)
        Spacer(Modifier.height(Spacing.s))
        SecondaryButton(
            text = stringResource(R.string.tonight_night_up),
            onClick = { confirmEnd = true },
            modifier = Modifier.fillMaxWidth(),
        )
    }
    if (confirmEnd) {
        AlertDialog(
            onDismissRequest = { confirmEnd = false },
            title = { Text(stringResource(R.string.tonight_night_end_title)) },
            text = { Text(stringResource(R.string.tonight_night_end_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmEnd = false
                    actions.onEndNight()
                }) { Text(stringResource(R.string.tonight_night_end_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmEnd = false }) {
                    Text(stringResource(R.string.tonight_night_end_cancel), color = secondary)
                }
            },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        )
    }
}

@Composable
private fun LogStatus(log: MovementLog, onResume: () -> Unit) {
    val secondary = SleepSaverTheme.colors.textSecondary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
        Icon(
            imageVector = if (log == MovementLog.LOGGING) Icons.Rounded.Sensors else Icons.Rounded.SensorsOff,
            contentDescription = null,
            tint = SleepSaverTheme.colors.textTertiary,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = stringResource(
                if (log == MovementLog.LOGGING) R.string.tonight_night_logging else R.string.tonight_night_log_stopped,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = secondary,
        )
        if (log == MovementLog.STOPPED) {
            TextLinkButton(stringResource(R.string.tonight_night_resume), onClick = onResume, color = secondary)
        }
    }
}

/** "Alarm set · never later than 6:30 AM", "Alarm set · 6:30 AM" or "No alarm in the morning". */
@Composable
internal fun NightAlarm.line(): String = when (this) {
    is NightAlarm.NeverLater -> stringResource(R.string.tonight_night_never_later, clockTime(time)).keepTimesTogether()
    is NightAlarm.At -> stringResource(R.string.tonight_night_at, clockTime(time)).keepTimesTogether()
    NightAlarm.None -> stringResource(R.string.tonight_night_no_alarm)
}
