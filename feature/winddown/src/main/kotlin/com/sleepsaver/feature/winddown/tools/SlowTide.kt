package com.sleepsaver.feature.winddown.tools

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SelectableChip
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.ToggleRow
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.winddown.R
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import java.time.Duration
import javax.inject.Inject
import kotlin.math.PI
import kotlin.math.cos

// ---------------------------------------------------------------- State

/** Slow tide's choices on the start screen and where the run is. */
internal data class SlowTideUiState(
    val patterns: List<BreathPattern>,
    val pattern: BreathPattern,
    val lengths: List<Duration>,
    val length: Duration,
    /** Vibration only: the screen stays near-black, one tick to breathe in, two to breathe out. */
    val eyesClosed: Boolean,
    val stage: ToolStage<BreathPhase>,
)

/** The start-screen choices: `steps.slowTidePatterns` (4 in · 6 out first) and `steps.slowTideDefaultLength`. */
internal data class SlowTideSetup(
    val pattern: BreathPattern,
    val length: Duration,
    val eyesClosed: Boolean = false,
)

internal fun slowTideUiState(setup: SlowTideSetup, stage: ToolStage<BreathPhase>, config: SleepConfig = SleepConfig.DEFAULT) = SlowTideUiState(
    patterns = config.slowTidePatterns(),
    pattern = setup.pattern,
    lengths = config.steps.slowTideLengths,
    length = setup.length,
    eyesClosed = setup.eyesClosed,
    stage = stage,
)

/** Slow tide breathing (`GuidedTool.SLOW_TIDE`): about six slow breaths a minute with haptic cues. */
@HiltViewModel
internal class SlowTideViewModel @Inject constructor(
    haptics: Haptics,
    clock: GuidedClock,
    completion: ToolCompletion,
) : GuidedToolViewModel<BreathPhase>(Technique.SLOW_TIDE, haptics, clock, completion) {

    private val config = SleepConfig.DEFAULT

    private val setup = MutableStateFlow(
        SlowTideSetup(pattern = config.slowTidePatterns().first(), length = config.steps.slowTideDefaultLength),
    )

    val uiState: StateFlow<SlowTideUiState> = combine(setup, stage) { setup, stage -> slowTideUiState(setup, stage, config) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, slowTideUiState(setup.value, stage.value, config))

    fun setPattern(pattern: BreathPattern) = setup.update { it.copy(pattern = pattern) }

    fun setLength(length: Duration) = setup.update { it.copy(length = length) }

    fun setEyesClosed(eyesClosed: Boolean) = setup.update { it.copy(eyesClosed = eyesClosed) }

    fun start() {
        val choice = setup.value
        begin(slowTideScript(choice.pattern, choice.length))
    }
}

// ---------------------------------------------------------------- Screen

@Stable
internal class SlowTideActions(
    val onClose: () -> Unit = {},
    val onPattern: (BreathPattern) -> Unit = {},
    val onLength: (Duration) -> Unit = {},
    val onEyesClosed: (Boolean) -> Unit = {},
    val onStart: () -> Unit = {},
)

@Composable
internal fun SlowTideScreen(onClose: () -> Unit, modifier: Modifier = Modifier, viewModel: SlowTideViewModel = hiltViewModel()) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    SlowTideContent(
        state = state,
        actions = SlowTideActions(
            onClose = onClose,
            onPattern = viewModel::setPattern,
            onLength = viewModel::setLength,
            onEyesClosed = viewModel::setEyesClosed,
            onStart = viewModel::start,
        ),
        modifier = modifier,
    )
}

/** [breathProgress] fixes the breathing visual at one point of the breath (screenshots); null animates it. */
@Composable
internal fun SlowTideContent(
    state: SlowTideUiState,
    actions: SlowTideActions,
    modifier: Modifier = Modifier,
    breathProgress: Float? = null,
) {
    val stage = state.stage
    val dim = when {
        stage !is ToolStage.Running -> ToolDim.OFF
        state.eyesClosed -> ToolDim.NOW
        else -> ToolDim.AFTER_DELAY
    }
    ToolFrame(onClose = actions.onClose, dim = dim, modifier = modifier, keepScreenOn = stage is ToolStage.Running) {
        when (stage) {
            ToolStage.Setup -> SlowTideSetupBody(state, actions)
            is ToolStage.Running -> if (state.eyesClosed) {
                EyesClosedBody(onEnd = actions.onClose)
            } else {
                BreathingBody(state.pattern, stage.position, breathProgress, onEnd = actions.onClose)
            }
            is ToolStage.Finished -> ToolFinished(stage.checkedOff, onDone = actions.onClose)
        }
    }
}

@Composable
private fun SlowTideSetupBody(state: SlowTideUiState, actions: SlowTideActions) {
    ToolScrollBody {
        ToolHeader(Technique.SLOW_TIDE)
        ChoiceLabel(stringResource(R.string.winddown_tide_pace), Modifier.padding(top = Spacing.xl))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            state.patterns.forEach { pattern ->
                SelectableChip(
                    text = patternLabel(pattern),
                    selected = pattern == state.pattern,
                    onClick = { actions.onPattern(pattern) },
                )
            }
        }
        ChoiceLabel(stringResource(R.string.winddown_tide_length), Modifier.padding(top = Spacing.l))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(Spacing.s)) {
            state.lengths.forEach { length ->
                SelectableChip(
                    text = stringResource(R.string.winddown_minutes, length.toMinutes().toInt()),
                    selected = length == state.length,
                    onClick = { actions.onLength(length) },
                )
            }
        }
        ToggleRow(
            title = stringResource(R.string.winddown_tide_eyes_closed),
            checked = state.eyesClosed,
            onCheckedChange = actions.onEyesClosed,
            supporting = stringResource(R.string.winddown_tide_eyes_closed_body),
            modifier = Modifier.padding(top = Spacing.l),
        )
        PrimaryButton(
            stringResource(R.string.winddown_tool_start),
            onClick = actions.onStart,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = Spacing.xl),
        )
    }
}

@Composable
internal fun ChoiceLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = SleepSaverTheme.colors.textSecondary,
        modifier = modifier.padding(bottom = Spacing.xs),
    )
}

/** "4 in · 6 out". */
@Composable
internal fun patternLabel(pattern: BreathPattern): String =
    stringResource(R.string.winddown_tide_pattern, pattern.breatheIn.seconds.toInt(), pattern.breatheOut.seconds.toInt())

/** The orb that swells with each in-breath and settles with each out-breath, with the spoken cue under it. */
@Composable
private fun BreathingBody(pattern: BreathPattern, position: RunPosition<BreathPhase>, fixedProgress: Float?, onEnd: () -> Unit) {
    val progress = fixedProgress ?: animatedProgress(position)
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        BreathingVisual(position.cue, progress)
        CueText(
            stringResource(if (position.cue == BreathPhase.IN) R.string.winddown_tide_in else R.string.winddown_tide_out),
            Modifier.padding(top = Spacing.xxl),
        )
        Text(
            patternLabel(pattern),
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(top = Spacing.s),
        )
        Spacer(Modifier.weight(1f))
        TextLinkButton(stringResource(R.string.winddown_tool_end), onClick = onEnd, color = SleepSaverTheme.colors.textSecondary)
        WellnessDisclaimer(Modifier.padding(top = Spacing.s, bottom = Spacing.l))
    }
}

/** Eyes closed: a near-black screen; the ticks carry the breath. */
@Composable
internal fun EyesClosedBody(onEnd: () -> Unit, modifier: Modifier = Modifier, text: String = stringResource(R.string.winddown_tide_eyes_closed_running)) {
    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        Text(
            text,
            style = MaterialTheme.typography.bodyLarge,
            color = SleepSaverTheme.colors.textSecondary,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.weight(1f))
        TextLinkButton(stringResource(R.string.winddown_tool_end), onClick = onEnd, color = SleepSaverTheme.colors.textSecondary)
        WellnessDisclaimer(Modifier.padding(top = Spacing.s, bottom = Spacing.l))
    }
}

/** 0 → 1 across the current half-breath, restarted at each segment. */
@Composable
internal fun animatedProgress(position: RunPosition<BreathPhase>): Float {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(position.index) {
        progress.snapTo(0f)
        progress.animateTo(1f, tween(position.length.toMillis().toInt(), easing = LinearEasing))
    }
    return progress.value
}

/**
 * Stateless breathing orb: during [BreathPhase.IN] it grows from small to full as [progress] goes 0 → 1, during
 * [BreathPhase.OUT] it shrinks back, eased so the turn of each breath is soft. A faint ring marks the full size.
 * Decorative: the cue text next to it carries the meaning for screen readers.
 */
@Composable
internal fun BreathingVisual(phase: BreathPhase, progress: Float, modifier: Modifier = Modifier, size: Dp = 260.dp) {
    val tide = MaterialTheme.colorScheme.primary
    val ring = SleepSaverTheme.colors.textTertiary
    Canvas(
        modifier
            .size(size)
            .clearAndSetSemantics {},
    ) {
        val eased = (1f - cos(PI.toFloat() * progress.coerceIn(0f, 1f))) / 2f
        val fill = if (phase == BreathPhase.IN) eased else 1f - eased
        val outer = this.size.minDimension / 2f
        val radius = outer * (MIN_SHARE + (MAX_SHARE - MIN_SHARE) * fill)
        drawCircle(ring.copy(alpha = 0.4f), radius = outer * RING_SHARE, style = Stroke(width = 1.5.dp.toPx()))
        drawCircle(
            Brush.radialGradient(listOf(tide.copy(alpha = 0.18f), Color.Transparent), center = center, radius = radius * 1.3f),
            radius = radius * 1.3f,
        )
        drawCircle(
            Brush.radialGradient(listOf(tide.copy(alpha = 0.5f), tide.copy(alpha = 0.16f)), center = center, radius = radius),
            radius = radius,
        )
        drawCircle(tide.copy(alpha = 0.75f), radius = radius, style = Stroke(width = 2.dp.toPx()))
    }
}

private const val MIN_SHARE = 0.36f
private const val MAX_SHARE = 0.74f
private const val RING_SHARE = 0.76f
