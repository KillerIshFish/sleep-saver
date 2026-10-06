package com.sleepsaver.feature.review.edit

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.sleepsaver.core.ui.components.MinuteStepper
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TimeScrubber
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.review.R
import com.sleepsaver.feature.review.common.DataSourceCard
import com.sleepsaver.feature.review.common.InlineNote
import java.time.Duration
import java.time.Instant

/** Everything the editor form reports back. */
class NightEditorCallbacks(
    val onFellAsleepChange: (Instant) -> Unit,
    val onWokeUpChange: (Instant) -> Unit,
    val onAwakeChange: (Int) -> Unit,
    val onNapsChange: (Int) -> Unit,
    val onHowWeMeasure: () -> Unit,
) {
    companion object {
        val Noop = NightEditorCallbacks({}, {}, {}, {}, {})
    }
}

/**
 * The night editor: live total, fell asleep / woke up scrubbers, awake and nap steppers, the data source.
 * Lays itself out as a column; the caller scrolls it.
 */
@Composable
internal fun NightEditorForm(
    state: NightEditorState,
    callbacks: NightEditorCallbacks,
    modifier: Modifier = Modifier,
) {
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
        LiveTotal(state)
        SleepSaverCard(Modifier.fillMaxWidth()) {
            TimeScrubber(
                value = state.fellAsleep,
                onValueChange = callbacks.onFellAsleepChange,
                zone = state.fellAsleepZone,
                min = state.fellAsleepMin,
                max = state.fellAsleepMax,
                label = stringResource(R.string.review_row_fell_asleep),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        SleepSaverCard(Modifier.fillMaxWidth()) {
            TimeScrubber(
                value = state.wokeUp,
                onValueChange = callbacks.onWokeUpChange,
                zone = state.wokeUpZone,
                min = state.wokeUpMin,
                max = state.wokeUpMax,
                label = stringResource(R.string.review_row_woke_up),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (state.error != null) ErrorNote(state.error)
        StepperCard(
            title = stringResource(R.string.review_row_awake),
            caption = stringResource(R.string.review_awake_caption),
            minutes = state.awakeMinutes,
            max = state.awakeMax,
            onChange = callbacks.onAwakeChange,
        )
        StepperCard(
            title = stringResource(R.string.review_row_naps),
            caption = stringResource(R.string.review_naps_caption),
            minutes = state.napMinutes,
            max = state.napMax,
            onChange = callbacks.onNapsChange,
        )
        DataSourceCard(source = state.source, onHowWeMeasure = callbacks.onHowWeMeasure)
    }
}

@Composable
private fun LiveTotal(state: NightEditorState) {
    val valid = state.error == null
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.s)
            .semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = stringResource(R.string.review_total_sleep_time),
            style = MaterialTheme.typography.labelLarge,
            color = SleepSaverTheme.colors.textSecondary,
        )
        Text(
            text = if (valid) formatDuration(state.totalSleep) else "–",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
        )
        if (valid) {
            Text(
                text = stringResource(
                    R.string.review_in_bed_awake,
                    formatDuration(state.timeInBed),
                    formatDuration(Duration.ofMinutes(state.awakeMinutes.toLong())),
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(top = Spacing.xs),
            )
        }
    }
}

@Composable
private fun StepperCard(
    title: String,
    caption: String,
    minutes: Int,
    max: Int,
    onChange: (Int) -> Unit,
) {
    SleepSaverCard(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            MinuteStepper(
                minutes = minutes,
                onMinutesChange = onChange,
                step = NightEditorState.STEP,
                min = 0,
                max = max,
            )
        }
        Text(
            text = caption,
            style = MaterialTheme.typography.bodySmall,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(top = Spacing.s),
        )
    }
}

@Composable
private fun ErrorNote(error: EditError) {
    InlineNote(
        text = stringResource(
            when (error) {
                EditError.WAKE_BEFORE_SLEEP -> R.string.review_error_order
                EditError.TOO_LONG -> R.string.review_error_too_long
                EditError.IN_FUTURE -> R.string.review_error_future
            },
        ),
    )
}
