package com.sleepsaver.feature.winddown.tools

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.winddown.R
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject

// ---------------------------------------------------------------- Body scan

/** Body scan (`GuidedTool.BODY_SCAN`): ten areas, feet to whole body, a pulse at each; it can pause. */
@HiltViewModel
internal class BodyScanViewModel @Inject constructor(
    haptics: Haptics,
    clock: GuidedClock,
    completion: ToolCompletion,
) : GuidedToolViewModel<Int>(Technique.BODY_SCAN, haptics, clock, completion) {
    fun start() = begin(bodyScanScript())
}

@Stable
internal class BodyScanActions(
    val onClose: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
)

@Composable
internal fun BodyScanScreen(onClose: () -> Unit, modifier: Modifier = Modifier, viewModel: BodyScanViewModel = hiltViewModel()) {
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    BodyScanContent(
        stage = stage,
        actions = BodyScanActions(onClose, viewModel::start, viewModel::pause, viewModel::resume),
        modifier = modifier,
    )
}

/** The prompt for area [area] (0..9): "Notice your feet. Let them be heavy." … "Face and scalp. Then your whole body, resting." */
@StringRes
internal fun bodyScanPrompt(area: Int): Int = when (area) {
    0 -> R.string.winddown_scan_1
    1 -> R.string.winddown_scan_2
    2 -> R.string.winddown_scan_3
    3 -> R.string.winddown_scan_4
    4 -> R.string.winddown_scan_5
    5 -> R.string.winddown_scan_6
    6 -> R.string.winddown_scan_7
    7 -> R.string.winddown_scan_8
    8 -> R.string.winddown_scan_9
    else -> R.string.winddown_scan_10
}

@Composable
internal fun BodyScanContent(stage: ToolStage<Int>, actions: BodyScanActions, modifier: Modifier = Modifier) {
    val running = stage is ToolStage.Running
    // A paused scan gives no cues, so it lets the screen time out: someone who drifts off mid-pause isn't lit all night.
    val cueing = (stage as? ToolStage.Running)?.position?.paused == false
    ToolFrame(onClose = actions.onClose, dim = if (running) ToolDim.AFTER_DELAY else ToolDim.OFF, modifier = modifier, keepScreenOn = cueing) {
        when (stage) {
            ToolStage.Setup -> ToolScrollBody {
                ToolHeader(Technique.BODY_SCAN)
                // The order is shown once, here; the run itself only shows the current area.
                Text(
                    stringResource(R.string.winddown_scan_order),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = Spacing.xl),
                )
                Text(
                    stringResource(R.string.winddown_scan_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = Spacing.s),
                )
                StartButton(actions.onStart)
            }
            is ToolStage.Running -> RunningBody(
                cue = stringResource(bodyScanPrompt(stage.position.cue)),
                counter = stringResource(R.string.winddown_tool_step, stage.position.index + 1, stage.position.count),
                onEnd = actions.onClose,
                pause = PauseControl(stage.position.paused, actions.onPause, actions.onResume),
            )
            is ToolStage.Finished -> ToolFinished(stage.checkedOff, onDone = actions.onClose)
        }
    }
}

// ---------------------------------------------------------------- Muscle release

/** Muscle release (`GuidedTool.MUSCLE_RELEASE`): twelve groups, tense 5 s, let go 15 s, a tick at each switch. */
@HiltViewModel
internal class MuscleReleaseViewModel @Inject constructor(
    haptics: Haptics,
    clock: GuidedClock,
    completion: ToolCompletion,
) : GuidedToolViewModel<MuscleCue>(Technique.MUSCLE_RELEASE, haptics, clock, completion) {
    fun start() = begin(muscleReleaseScript())
}

@Stable
internal class MuscleReleaseActions(
    val onClose: () -> Unit = {},
    val onStart: () -> Unit = {},
)

@Composable
internal fun MuscleReleaseScreen(onClose: () -> Unit, modifier: Modifier = Modifier, viewModel: MuscleReleaseViewModel = hiltViewModel()) {
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    MuscleReleaseContent(stage, MuscleReleaseActions(onClose, viewModel::start), modifier)
}

@StringRes
internal fun MuscleGroup.nameRes(): Int = when (this) {
    MuscleGroup.HANDS -> R.string.winddown_muscle_hands
    MuscleGroup.FOREARMS -> R.string.winddown_muscle_forearms
    MuscleGroup.UPPER_ARMS -> R.string.winddown_muscle_upper_arms
    MuscleGroup.SHOULDERS -> R.string.winddown_muscle_shoulders
    MuscleGroup.FACE -> R.string.winddown_muscle_face
    MuscleGroup.NECK -> R.string.winddown_muscle_neck
    MuscleGroup.CHEST -> R.string.winddown_muscle_chest
    MuscleGroup.STOMACH -> R.string.winddown_muscle_stomach
    MuscleGroup.BACK -> R.string.winddown_muscle_back
    MuscleGroup.THIGHS -> R.string.winddown_muscle_thighs
    MuscleGroup.CALVES -> R.string.winddown_muscle_calves
    MuscleGroup.FEET -> R.string.winddown_muscle_feet
}

@Composable
internal fun MuscleReleaseContent(stage: ToolStage<MuscleCue>, actions: MuscleReleaseActions, modifier: Modifier = Modifier) {
    val running = stage is ToolStage.Running
    ToolFrame(onClose = actions.onClose, dim = if (running) ToolDim.AFTER_DELAY else ToolDim.OFF, modifier = modifier, keepScreenOn = running) {
        when (stage) {
            ToolStage.Setup -> ToolScrollBody {
                ToolHeader(Technique.MUSCLE_RELEASE)
                Text(
                    stringResource(R.string.winddown_muscle_intro),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.padding(top = Spacing.xl),
                )
                StartButton(actions.onStart)
            }
            is ToolStage.Running -> {
                val cue = stage.position.cue
                RunningBody(
                    cue = if (cue.tense) {
                        stringResource(R.string.winddown_muscle_tense, stringResource(cue.group.nameRes()))
                    } else {
                        stringResource(R.string.winddown_muscle_release)
                    },
                    counter = stringResource(R.string.winddown_tool_step, cue.group.ordinal + 1, MuscleGroup.entries.size),
                    onEnd = actions.onClose,
                )
            }
            is ToolStage.Finished -> ToolFinished(stage.checkedOff, onDone = actions.onClose)
        }
    }
}

// ---------------------------------------------------------------- Shared pieces

@Composable
private fun StartButton(onStart: () -> Unit) {
    PrimaryButton(
        stringResource(R.string.winddown_tool_start),
        onClick = onStart,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = Spacing.xl),
    )
}

/** Pause / Resume for a run that can hold (Body scan). */
internal class PauseControl(val paused: Boolean, val onPause: () -> Unit, val onResume: () -> Unit)

/** A running text tool: the [cue] in the middle, "3 of 10" under it, then Pause/Resume and End at the bottom. */
@Composable
private fun RunningBody(cue: String, counter: String, onEnd: () -> Unit, pause: PauseControl? = null) {
    Column(
        Modifier
            .fillMaxSize()
            .padding(horizontal = Spacing.screenHorizontal),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.weight(1f))
        CueText(cue)
        Text(
            counter,
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(top = Spacing.m),
        )
        Spacer(Modifier.weight(1f))
        if (pause != null) {
            SecondaryButton(
                text = stringResource(if (pause.paused) R.string.winddown_tool_resume else R.string.winddown_tool_pause),
                onClick = if (pause.paused) pause.onResume else pause.onPause,
                icon = if (pause.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        TextLinkButton(
            stringResource(R.string.winddown_tool_end),
            onClick = onEnd,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(top = Spacing.s),
        )
        WellnessDisclaimer(Modifier.padding(top = Spacing.s, bottom = Spacing.l))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
    }
}
