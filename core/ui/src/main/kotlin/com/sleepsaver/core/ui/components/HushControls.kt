package com.sleepsaver.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.ui.R
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import java.time.Duration

/**
 * What [HushControls] shows: the noise [color], the fade-out [timer] (null = "Until my window"), Hush's own [volume],
 * whether it is [playing], whether "Until my window" can be picked ([untilWindowAvailable]: an alarm is armed),
 * whether the phone's media volume is high ([mediaVolumeHigh]) and whether it sits on a [night] screen.
 */
@Immutable
data class HushControlsState(
    val color: HushColor,
    val timer: Duration?,
    val volume: Float,
    val playing: Boolean,
    val untilWindowAvailable: Boolean,
    val mediaVolumeHigh: Boolean,
    val night: Boolean = false,
)

/** What [HushControls] can do; every action defaults to doing nothing. [onTimer] gets null for "Until my window". */
@Stable
class HushControlsActions(
    val onColor: (HushColor) -> Unit = {},
    val onTimer: (Duration?) -> Unit = {},
    val onVolume: (Float) -> Unit = {},
    val onPlay: () -> Unit = {},
    val onStop: () -> Unit = {},
)

/**
 * Hush, inline wherever it's offered (Tonight, the Night screen, a Night step): White / Pink / Brown with a line on
 * how it sounds, a fade-out timer (15–90 min or "Until my window"), a volume slider from [minVolume] to full, Play or
 * Stop, the earplug tip, and "Your media volume is high" when it is. It never shows a clock time, only the chosen
 * timer ("Fades out after 45 min"), so it is safe at night; with `night = true` Play is a quiet button instead of
 * a tide one. Use it as `HushControls(state.hush.copy(night = true), hushActions)` on night screens.
 */
@Composable
fun HushControls(
    state: HushControlsState,
    actions: HushControlsActions,
    modifier: Modifier = Modifier,
    minVolume: Float = SleepConfig.DEFAULT.hush.minVolume,
    timers: List<Duration> = SleepConfig.DEFAULT.hush.timers,
) {
    val secondary = SleepSaverTheme.colors.textSecondary
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.s)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            HushColor.entries.forEach { color ->
                SelectableChip(color.title(), selected = color == state.color, onClick = { actions.onColor(color) })
            }
        }
        Text(state.color.description(), style = MaterialTheme.typography.bodyMedium, color = secondary)

        FlowRow(
            modifier = Modifier.padding(top = Spacing.s),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
        ) {
            timers.forEach { timer ->
                SelectableChip(
                    text = stringResource(R.string.ss_hush_minutes, timer.toMinutes().toInt()),
                    selected = timer == state.timer,
                    onClick = { actions.onTimer(timer) },
                )
            }
            SelectableChip(
                text = stringResource(R.string.ss_hush_until_window),
                selected = state.timer == null,
                onClick = { actions.onTimer(null) },
                enabled = state.untilWindowAvailable,
            )
        }
        Text(
            text = state.timer?.let {
                stringResource(R.string.ss_hush_fades_after, stringResource(R.string.ss_hush_minutes, it.toMinutes().toInt()))
            } ?: stringResource(R.string.ss_hush_fades_before_window),
            style = MaterialTheme.typography.bodyMedium,
            color = secondary,
        )

        val volumeLabel = stringResource(R.string.ss_hush_volume)
        Text(
            volumeLabel,
            style = MaterialTheme.typography.labelLarge,
            color = secondary,
            modifier = Modifier.padding(top = Spacing.s),
        )
        Slider(
            value = state.volume.coerceIn(minVolume, 1f),
            onValueChange = actions.onVolume,
            valueRange = minVolume..1f,
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = SleepSaverTheme.colors.cardRaised,
            ),
            modifier = Modifier.semantics { contentDescription = volumeLabel },
        )

        val buttonModifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs)
        when {
            state.playing ->
                SecondaryButton(stringResource(R.string.ss_hush_stop), onClick = actions.onStop, buttonModifier, icon = Icons.Rounded.Stop)
            state.night ->
                SecondaryButton(stringResource(R.string.ss_hush_play), onClick = actions.onPlay, buttonModifier, icon = Icons.Rounded.PlayArrow)
            else ->
                PrimaryButton(stringResource(R.string.ss_hush_play), onClick = actions.onPlay, buttonModifier, icon = Icons.Rounded.PlayArrow)
        }

        if (state.mediaVolumeHigh) {
            Row(
                modifier = Modifier.padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.spacedBy(Spacing.s),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(
                    Icons.AutoMirrored.Rounded.VolumeUp,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    stringResource(R.string.ss_hush_media_loud),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        Text(
            stringResource(R.string.ss_hush_tip),
            style = MaterialTheme.typography.bodySmall,
            color = secondary,
            modifier = Modifier.padding(top = Spacing.xs),
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0A0C10)
@Composable
private fun HushControlsPreview() = SleepSaverTheme {
    HushControls(
        state = HushControlsState(
            color = HushColor.PINK,
            timer = Duration.ofMinutes(45),
            volume = 0.25f,
            playing = false,
            untilWindowAvailable = true,
            mediaVolumeHigh = true,
        ),
        actions = HushControlsActions(),
        modifier = Modifier.padding(16.dp),
    )
}
