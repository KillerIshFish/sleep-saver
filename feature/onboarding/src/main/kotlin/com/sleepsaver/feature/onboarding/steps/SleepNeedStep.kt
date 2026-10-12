package com.sleepsaver.feature.onboarding.steps

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.ui.components.MinuteStepper
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.TimeScrubber
import com.sleepsaver.core.ui.format.formatDuration
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.onboarding.NeedSuggestion
import com.sleepsaver.feature.onboarding.R
import com.sleepsaver.feature.onboarding.SleepNeedUiState
import com.sleepsaver.feature.onboarding.ui.StepHeading
import com.sleepsaver.feature.onboarding.ui.StepLayout
import java.time.Duration
import java.time.LocalTime

/** Step 4: sleep need (with the estimate when there is one) and the usual wake time. */
@Composable
internal fun SleepNeedStep(
    state: SleepNeedUiState,
    onNeedChange: (Int) -> Unit,
    onUseSuggestion: () -> Unit,
    onWakeChange: (LocalTime) -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    StepLayout(
        modifier = modifier,
        bottom = {
            PrimaryButton(stringResource(R.string.onboarding_continue), onClick = onContinue, modifier = Modifier.fillMaxWidth())
        },
    ) {
        StepHeading(stringResource(R.string.onboarding_need_title), stringResource(R.string.onboarding_need_body))
        Spacer(Modifier.height(Spacing.xl))
        // Labelled like the wake-time scrubber below, so the two settings read as a pair.
        Text(
            text = stringResource(R.string.onboarding_need_label),
            style = MaterialTheme.typography.labelLarge,
            color = SleepSaverTheme.colors.textSecondary,
        )
        Spacer(Modifier.height(Spacing.s))
        MinuteStepper(
            minutes = state.needMinutes,
            onMinutesChange = onNeedChange,
            step = SleepNeedUiState.NEED_STEP_MINUTES,
            min = SleepNeedUiState.MIN_NEED_MINUTES,
            max = SleepNeedUiState.MAX_NEED_MINUTES,
        )
        state.suggestion?.let { suggestion ->
            Spacer(Modifier.height(Spacing.xl))
            SuggestionCard(suggestion, inUse = suggestion.minutes == state.needMinutes, onUse = onUseSuggestion)
        }
        Spacer(Modifier.height(Spacing.xxl + Spacing.s))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.onboarding_wake_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.onboarding_wake_body),
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(top = Spacing.xs),
            )
            if (state.wakeFromNights) {
                Text(
                    text = stringResource(R.string.onboarding_wake_from_nights),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = Spacing.s),
                )
            }
        }
        Spacer(Modifier.height(Spacing.l))
        val dayStart = state.date.atStartOfDay(state.zone)
        TimeScrubber(
            value = state.date.atTime(state.wake).atZone(state.zone).toInstant(),
            onValueChange = { onWakeChange(it.atZone(state.zone).toLocalTime()) },
            zone = state.zone,
            min = dayStart.toInstant(),
            max = dayStart.plusDays(1).minusMinutes(5).toInstant(),
            label = stringResource(R.string.onboarding_wake_label),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SuggestionCard(suggestion: NeedSuggestion, inUse: Boolean, onUse: () -> Unit) {
    val confidence = stringResource(
        when (suggestion.confidence) {
            Confidence.LOW -> R.string.onboarding_confidence_low
            Confidence.MEDIUM -> R.string.onboarding_confidence_medium
            Confidence.HIGH -> R.string.onboarding_confidence_high
        },
    )
    SleepSaverCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(22.dp))
            }
            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 14.dp, end = Spacing.s),
            ) {
                Text(
                    text = stringResource(
                        R.string.onboarding_need_suggestion,
                        formatDuration(Duration.ofMinutes(suggestion.minutes.toLong())),
                        confidence,
                    ),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = pluralStringResource(
                        R.plurals.onboarding_need_suggestion_nights,
                        suggestion.freeNights,
                        suggestion.freeNights,
                        suggestion.wakeDelayMinutes,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            if (inUse) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = Spacing.m)) {
                    Icon(Icons.Rounded.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
                    Text(
                        text = stringResource(R.string.onboarding_need_using_it),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(start = Spacing.xs),
                    )
                }
            } else {
                TextLinkButton(stringResource(R.string.onboarding_need_use_it), onClick = onUse)
            }
        }
    }
}
