package com.sleepsaver.feature.tonight.ringing

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Stable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.sleepsaver.core.data.hooks.Ringing
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.ui.components.MorningFeelScale
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.WarmGlow
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.keepTimesTogether
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.tonight.R
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import com.sleepsaver.core.ui.R as UiR

/** Every callback of the ringing screen; all default to doing nothing. */
@Stable
internal class RingingActions(
    val onSnooze: () -> Unit = {},
    val onStop: () -> Unit = {},
    val onFeel: (MorningFeel) -> Unit = {},
    val onDone: () -> Unit = {},
    val onOpenReview: (LocalDate) -> Unit = {},
)

/**
 * The ringing screen: dark, with the warm glow behind. While sounding, the time (showing it is fine: it's morning),
 * "Good morning" / "Catnap over" / "Test ring", why it rang ("A restless moment · 18 min before 6:30 AM", "Your wake
 * time", …) and Snooze ("Snooze until 6:40 AM", only while one is left) and Stop, pinned to the bottom. After Stop: a
 * planned alarm asks "How do you feel?" and suggests bright light; a catnap or test ring just says Done.
 */
@Composable
internal fun RingingContent(state: RingingUiState, actions: RingingActions, modifier: Modifier = Modifier) {
    val sounding = state is RingingUiState.Sounding
    // While it rings, Back does nothing (Stop or Snooze end it) and the screen stays on.
    BackHandler(enabled = sounding) {}
    if (sounding) KeepScreenOn()
    Box(modifier.fillMaxSize()) {
        WarmGlow(Modifier.matchParentSize())
        when (state) {
            is RingingUiState.Sounding -> Sounding(state, actions)
            is RingingUiState.AfterStop -> AfterStop(state, actions)
            RingingUiState.Gone -> Unit
        }
    }
}

@Composable
private fun Sounding(state: RingingUiState.Sounding, actions: RingingActions) {
    val ringing = state.ringing
    val secondary = SleepSaverTheme.colors.textSecondary
    Screen(
        top = {
            BasicText(
                text = clockTime(state.now, ringing.zone),
                style = MaterialTheme.typography.displayLarge.copy(color = MaterialTheme.colorScheme.onBackground, textAlign = TextAlign.Center),
                maxLines = 1,
                autoSize = TextAutoSize.StepBased(minFontSize = 40.sp, maxFontSize = MaterialTheme.typography.displayLarge.fontSize),
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(Spacing.l))
            Title(ringing.kind.title())
            Spacer(Modifier.height(Spacing.s))
            Text(
                ringing.reasonLine().keepTimesTogether(),
                style = MaterialTheme.typography.bodyLarge,
                color = secondary,
                textAlign = TextAlign.Center,
            )
        },
        bottom = {
            val until = ringing.snooze.until
            if (until != null) {
                SecondaryButton(
                    stringResource(R.string.tonight_ring_snooze, clockTime(until, ringing.zone)).keepTimesTogether(),
                    onClick = actions.onSnooze,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (ringing.snooze.lastOne) Caption(stringResource(R.string.tonight_ring_last_snooze))
                Spacer(Modifier.height(Spacing.m))
            } else if (ringing.kind == RingKind.PLANNED) {
                Caption(stringResource(R.string.tonight_ring_last_ring))
                Spacer(Modifier.height(Spacing.s))
            }
            PrimaryButton(stringResource(R.string.tonight_ring_stop), onClick = actions.onStop, modifier = Modifier.fillMaxWidth())
        },
    )
}

@Composable
private fun AfterStop(state: RingingUiState.AfterStop, actions: RingingActions) {
    val secondary = SleepSaverTheme.colors.textSecondary
    val wakeDate = state.wakeDate
    Screen(
        top = {
            Title(state.kind.title())
            when (state.kind) {
                RingKind.PLANNED -> {
                    Spacer(Modifier.height(Spacing.xl))
                    Text(
                        stringResource(UiR.string.ss_feel_question),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                    )
                    Spacer(Modifier.height(Spacing.m))
                    MorningFeelScale(value = state.feel, onValueChange = actions.onFeel)
                    Spacer(Modifier.height(Spacing.xl))
                    Text(stringResource(R.string.tonight_after_light), style = MaterialTheme.typography.bodyLarge, color = secondary, textAlign = TextAlign.Center)
                }
                RingKind.TEST -> {
                    Spacer(Modifier.height(Spacing.s))
                    Text(stringResource(R.string.tonight_after_test), style = MaterialTheme.typography.bodyLarge, color = secondary, textAlign = TextAlign.Center)
                }
                RingKind.NAP -> Unit
            }
        },
        bottom = {
            PrimaryButton(stringResource(R.string.tonight_after_done), onClick = actions.onDone, modifier = Modifier.fillMaxWidth())
            if (state.kind == RingKind.PLANNED && state.unlocked && wakeDate != null) {
                Spacer(Modifier.height(Spacing.m))
                SecondaryButton(
                    stringResource(R.string.tonight_after_review),
                    onClick = { actions.onOpenReview(wakeDate) },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
    )
}

/** [top] centred in the space above [bottom], which stays pinned to the bottom (never inside the scroll). */
@Composable
private fun Screen(top: @Composable ColumnScope.() -> Unit, bottom: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = Spacing.xl, vertical = Spacing.l),
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            content = top,
        )
        Spacer(Modifier.height(Spacing.l))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, content = bottom)
    }
}

@Composable
private fun Title(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.headlineMedium,
        color = MaterialTheme.colorScheme.onBackground,
        textAlign = TextAlign.Center,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = SleepSaverTheme.colors.textSecondary,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = Spacing.s),
    )
}

/** Keeps the screen on while this is in composition (only while ringing). */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}

/** "Good morning" / "Catnap over" / "Test ring". */
@Composable
private fun RingKind.title(): String = stringResource(
    when (this) {
        RingKind.PLANNED -> R.string.tonight_ring_title_planned
        RingKind.NAP -> R.string.tonight_ring_title_catnap
        RingKind.TEST -> R.string.tonight_ring_title_test
    },
)

/** Why it rang, never claiming a sleep stage: "A restless moment · 18 min before 6:30 AM", "Your wake time", … */
@Composable
private fun Ringing.reasonLine(): String {
    val wake = target?.let { clockTime(it, zone) }
    return when (reason) {
        RingReason.RESTLESS -> {
            // Whole clock minutes, so it agrees with the times shown.
            val minutes = target?.let { ChronoUnit.MINUTES.between(since.truncatedTo(ChronoUnit.MINUTES), it.truncatedTo(ChronoUnit.MINUTES)) }
                ?: earlyBy.toMinutes()
            stringResource(R.string.tonight_ring_restless, stringResource(R.string.tonight_minutes, minutes.toInt()), wake.orEmpty())
        }
        // NEVER_RANG never sounds; read it as the wake time.
        RingReason.DEADLINE, RingReason.PLACEMENT_FALLBACK, RingReason.SENSING_UNAVAILABLE, RingReason.FIXED_TIME, RingReason.NEVER_RANG ->
            stringResource(R.string.tonight_ring_wake_time)
        RingReason.SNOOZE_END -> stringResource(R.string.tonight_ring_snooze_over)
        RingReason.CATNAP_END -> stringResource(R.string.tonight_ring_catnap)
        RingReason.TEST -> stringResource(R.string.tonight_ring_test)
        RingReason.CATCH_UP -> if (wake != null) stringResource(R.string.tonight_ring_catch_up, wake) else stringResource(R.string.tonight_ring_wake_time)
        RingReason.BACKUP -> stringResource(R.string.tonight_ring_backup)
    }
}
