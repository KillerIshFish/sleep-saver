package com.sleepsaver.feature.winddown.reset

import android.util.Log
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.NightRules
import com.sleepsaver.core.ui.components.HushControls
import com.sleepsaver.core.ui.components.HushControlsActions
import com.sleepsaver.core.ui.components.HushControlsState
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.ToggleRow
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.platform.DimScreen
import com.sleepsaver.core.ui.platform.rememberMediaVolumeHigh
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.winddown.HushChoice
import com.sleepsaver.feature.winddown.HushPicker
import com.sleepsaver.feature.winddown.LOG_TAG
import com.sleepsaver.feature.winddown.R
import com.sleepsaver.feature.winddown.WinddownSurface
import com.sleepsaver.feature.winddown.hushControlsState
import com.sleepsaver.feature.winddown.untilWindowAvailable
import com.sleepsaver.feature.winddown.tools.BreathPhase
import com.sleepsaver.feature.winddown.tools.BreathingVisual
import com.sleepsaver.feature.winddown.tools.GuidedClock
import com.sleepsaver.feature.winddown.tools.GuidedRunner
import com.sleepsaver.feature.winddown.tools.Haptics
import com.sleepsaver.feature.winddown.tools.KeepScreenOn
import com.sleepsaver.feature.winddown.tools.RunPosition
import com.sleepsaver.feature.winddown.tools.animatedProgress
import com.sleepsaver.feature.winddown.tools.slowTidePatterns
import com.sleepsaver.feature.winddown.tools.slowTideScript
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

// ---------------------------------------------------------------- State

/** Night reset's two parts: slow breathing first, then the advice and Hush. */
internal enum class ResetStage { BREATHING, TEXT }

internal data class NightResetUiState(
    val stage: ResetStage,
    /** Vibration only (the default here): no breathing visual. */
    val eyesClosed: Boolean,
    /** The breath being cued while [stage] is BREATHING. */
    val breath: RunPosition<BreathPhase>?,
    val hush: HushControlsState,
)

/** Hush can run "Until my window" only when [nextAlarm] is the morning of [wakeDate], the night under way. */
internal fun nightResetUiState(
    stage: ResetStage,
    eyesClosed: Boolean,
    breath: RunPosition<BreathPhase>?,
    nightState: NightState,
    nextAlarm: PlannedAlarm?,
    hush: HushChoice,
    wakeDate: LocalDate,
    config: SleepConfig = SleepConfig.DEFAULT,
) = NightResetUiState(
    stage = stage,
    eyesClosed = eyesClosed,
    breath = breath,
    hush = hushControlsState(hush, nightState.hush, untilWindowAvailable(nextAlarm, wakeDate), night = true, config = config),
)

/** The wake date of the night [now] belongs to ([NightRules.wakeDateOf]: 23:30 Monday and 02:00 Tuesday → Tuesday). */
private fun wakeDateAt(now: Instant, zone: ZoneId): LocalDate = NightRules.wakeDateOf(now, zone.rules.getOffset(now))

/**
 * Night reset, the can't-sleep flow: `steps.nightResetBreathing` (2 min) of Slow tide at the default pace with
 * eyes-closed haptics, then plain advice and Hush. It never shows a time.
 */
@HiltViewModel
internal class NightResetViewModel @Inject constructor(
    nightController: NightController,
    schedule: ScheduleRepository,
    time: TimeProvider,
    haptics: Haptics,
    clock: GuidedClock,
) : ViewModel() {

    private val config = SleepConfig.DEFAULT

    private val stage = MutableStateFlow(ResetStage.BREATHING)
    private val eyesClosed = MutableStateFlow(true)
    private val breath = MutableStateFlow<RunPosition<BreathPhase>?>(null)

    private val runner = GuidedRunner<BreathPhase>(
        scope = viewModelScope,
        clock = clock,
        haptics = haptics,
        onPosition = { breath.value = it },
        onFinish = {
            breath.value = null
            stage.value = ResetStage.TEXT
        },
    )

    private val nextAlarm = schedule.nextAlarm.catch {
        Log.w(LOG_TAG, "Next alarm failed", it)
        emit(null)
    }

    private val hushPicker = HushPicker(
        nightController = nightController,
        untilWindowAvailable = { uiState.value.hush.untilWindowAvailable },
        config = config,
    )

    /** Hush's controls on the advice part. */
    val hushActions: HushControlsActions get() = hushPicker.actions

    private val breathing = combine(stage, eyesClosed, breath) { stage, eyesClosed, breath -> Triple(stage, eyesClosed, breath) }

    private val wakeDate = time.minuteTicks.map { wakeDateAt(it, time.zone()) }.distinctUntilChanged()

    val uiState: StateFlow<NightResetUiState> = combine(
        breathing,
        nightController.state,
        nextAlarm,
        hushPicker.choice,
        wakeDate,
    ) { (stage, eyes, breath), night, alarm, hush, wakeDate ->
        nightResetUiState(stage, eyes, breath, night, alarm, hush, wakeDate, config)
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        nightResetUiState(
            stage.value,
            eyesClosed.value,
            null,
            nightController.state.value,
            null,
            hushPicker.choice.value,
            wakeDateAt(time.now(), time.zone()),
            config,
        ),
    )

    init {
        runner.start(slowTideScript(config.slowTidePatterns().first(), config.steps.nightResetBreathing))
    }

    fun setEyesClosed(closed: Boolean) {
        eyesClosed.value = closed
    }

    /** On to the advice before the breathing ends. */
    fun skip() {
        runner.stop()
        breath.value = null
        stage.value = ResetStage.TEXT
    }
}

// ---------------------------------------------------------------- Screen

@Stable
internal class NightResetActions(
    val onClose: () -> Unit = {},
    val onEyesClosed: (Boolean) -> Unit = {},
    val onSkip: () -> Unit = {},
    val hush: HushControlsActions = HushControlsActions(),
)

@Composable
internal fun NightResetScreen(onClose: () -> Unit, modifier: Modifier = Modifier, viewModel: NightResetViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    NightResetContent(
        state = state,
        actions = NightResetActions(onClose, viewModel::setEyesClosed, viewModel::skip, viewModel.hushActions),
        modifier = modifier,
        mediaVolumeHigh = rememberMediaVolumeHigh(SleepConfig.DEFAULT.hush.mediaVolumeWarning),
    )
}

/**
 * Night reset, always dim and with no clock: the breathing part ("Let's slow down first. Follow the gentle pulses."),
 * then three short pieces of advice, Hush, "Back to night" and the see-a-doctor footer. [breathProgress] fixes the
 * breathing visual (screenshots).
 */
@Composable
internal fun NightResetContent(
    state: NightResetUiState,
    actions: NightResetActions,
    modifier: Modifier = Modifier,
    mediaVolumeHigh: Boolean = false,
    breathProgress: Float? = null,
    scrollState: ScrollState = rememberScrollState(),
) {
    DimScreen(SleepConfig.DEFAULT.steps.dimBrightness)
    // The breathing cues are haptics only by default; they stop if the screen times out.
    if (state.stage == ResetStage.BREATHING) KeepScreenOn()
    WinddownSurface(
        onBack = actions.onClose,
        modifier = modifier,
        backIcon = Icons.Rounded.Close,
        backDescription = stringResource(R.string.winddown_close),
    ) {
        when (state.stage) {
            ResetStage.BREATHING -> BreathingPart(state, actions, breathProgress)
            ResetStage.TEXT -> AdvicePart(state.hush.copy(mediaVolumeHigh = mediaVolumeHigh), actions, scrollState)
        }
    }
}

@Composable
private fun BreathingPart(state: NightResetUiState, actions: NightResetActions, breathProgress: Float?) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        ResetTitle()
        Text(
            stringResource(R.string.winddown_reset_breathing),
            style = MaterialTheme.typography.bodyLarge,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.s),
        )
        ToggleRow(
            title = stringResource(R.string.winddown_tide_eyes_closed),
            checked = state.eyesClosed,
            onCheckedChange = actions.onEyesClosed,
            modifier = Modifier.padding(top = Spacing.m),
        )
        Spacer(Modifier.weight(1f))
        val breath = state.breath
        if (!state.eyesClosed && breath != null) {
            BreathingVisual(breath.cue, breathProgress ?: animatedProgress(breath))
            Text(
                stringResource(if (breath.cue == BreathPhase.IN) R.string.winddown_tide_in else R.string.winddown_tide_out),
                style = MaterialTheme.typography.headlineSmall,
                color = SleepSaverTheme.colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .padding(top = Spacing.xl)
                    .semantics { liveRegion = LiveRegionMode.Polite },
            )
        }
        Spacer(Modifier.weight(1f))
        TextLinkButton(stringResource(R.string.winddown_reset_skip), onClick = actions.onSkip, color = SleepSaverTheme.colors.textSecondary)
        WellnessDisclaimer(Modifier.padding(top = Spacing.s, bottom = Spacing.l))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
    }
}

@Composable
private fun AdvicePart(hush: HushControlsState, actions: NightResetActions, scrollState: ScrollState) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.screenHorizontal),
    ) {
        ResetTitle()
        listOf(R.string.winddown_reset_get_up, R.string.winddown_reset_wake_time, R.string.winddown_reset_normal).forEach { line ->
            Text(
                stringResource(line),
                style = MaterialTheme.typography.bodyLarge,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(top = Spacing.l),
            )
        }
        SleepSaverCard(
            Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xl),
        ) {
            Text(
                stringResource(com.sleepsaver.core.ui.R.string.ss_hush_title),
                style = MaterialTheme.typography.titleMedium,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier
                    .padding(bottom = Spacing.m)
                    .semantics { heading() },
            )
            HushControls(hush.copy(night = true), actions.hush)
        }
        SecondaryButton(
            stringResource(R.string.winddown_reset_back),
            onClick = actions.onClose,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xl),
        )
        Text(
            stringResource(R.string.winddown_reset_footer),
            style = MaterialTheme.typography.bodySmall,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(top = Spacing.xxl),
        )
        WellnessDisclaimer(Modifier.padding(top = Spacing.l))
        Spacer(Modifier.height(Spacing.xl))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
    }
}

@Composable
private fun ResetTitle() {
    Text(
        stringResource(R.string.winddown_reset_title),
        style = MaterialTheme.typography.headlineMedium,
        color = SleepSaverTheme.colors.textSecondary,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xs)
            .semantics { heading() },
    )
}
