package com.sleepsaver.feature.winddown.steps

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.nightsteps.GuidedTool
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.ui.components.HushControlsActions
import com.sleepsaver.core.ui.components.ScreenHeader
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.keepTimesTogether
import com.sleepsaver.core.ui.platform.rememberMediaVolumeHigh
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.winddown.LoadingBody
import com.sleepsaver.feature.winddown.R
import com.sleepsaver.feature.winddown.UnavailableBody
import com.sleepsaver.feature.winddown.WinddownSurface
import java.time.DayOfWeek

/** What Night steps can do; every action defaults to doing nothing. */
@Stable
internal class NightStepsActions(
    val onBack: () -> Unit = {},
    val onToggleStep: (activityId: Long, done: Boolean) -> Unit = { _, _ -> },
    /** A step's "Start": its guided tool (Hush opens the inline sheet instead). */
    val onStartTool: (GuidedTool) -> Unit = {},
    /** "Change tonight's steps": the editor for tonight's weekday. */
    val onChangeSteps: () -> Unit = {},
    /** "Pick steps" when there are none. */
    val onPickSteps: () -> Unit = {},
    /** [label] = the technique's title, resolved in composition. */
    val onAddSuggestion: (Technique, String) -> Unit = { _, _ -> },
    val onOpenLibrary: () -> Unit = {},
    val onToggleMark: (Mark) -> Unit = {},
    val onDropAnchor: (wholeNightLog: Boolean) -> Unit = {},
    /** "Week plan" when Night steps are off this evening. */
    val onOpenWeekPlan: () -> Unit = {},
    val onOpenTonight: () -> Unit = {},
    val hush: HushControlsActions = HushControlsActions(),
)

/** The bottom sheets Night steps opens. */
internal enum class NightStepsSheet { ANCHOR, HUSH }

@Composable
internal fun NightStepsScreen(
    onBack: () -> Unit,
    /** null = the every-evening list. */
    onOpenEditor: (DayOfWeek?) -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenTool: (GuidedTool) -> Unit,
    onOpenDayPlan: (DayOfWeek, DayPart) -> Unit,
    onOpenTonight: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: NightStepsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val failed = stringResource(R.string.winddown_anchor_failed)
    val openTonight by rememberUpdatedState(onOpenTonight)
    LaunchedEffect(viewModel) {
        viewModel.eventFlow.collect { event ->
            when (event) {
                NightStepsEvent.ANCHOR_DROPPED -> openTonight()
                NightStepsEvent.ANCHOR_FAILED -> snackbar.showSnackbar(failed)
            }
        }
    }
    val ready = state as? NightStepsUiState.Ready
    NightStepsContent(
        state = state,
        actions = NightStepsActions(
            onBack = onBack,
            onToggleStep = viewModel::setDone,
            onStartTool = onOpenTool,
            onChangeSteps = { ready?.let { onOpenEditor(it.day) } },
            onPickSteps = { ready?.let { onOpenEditor(if (it.ownList) it.day else null) } },
            onAddSuggestion = viewModel::addTechnique,
            onOpenLibrary = onOpenLibrary,
            onToggleMark = viewModel::toggleMark,
            onDropAnchor = viewModel::dropAnchor,
            onOpenWeekPlan = { ready?.let { onOpenDayPlan(it.day, DayPart.EVENING) } },
            onOpenTonight = onOpenTonight,
            hush = viewModel.hushActions,
        ),
        modifier = modifier,
        snackbarHostState = snackbar,
        mediaVolumeHigh = rememberMediaVolumeHigh(SleepConfig.DEFAULT.hush.mediaVolumeWarning),
    )
}

/**
 * Tonight's Night steps: the window and bedtime, "2 of 4", the steps by start time, suggestions, the evenings strip,
 * the Night steps patterns, tonight's marks and Drop anchor. At night (§5.0) no time of day is shown. [sheet] opens
 * with a sheet already showing (screenshots).
 */
@Composable
internal fun NightStepsContent(
    state: NightStepsUiState,
    actions: NightStepsActions,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
    mediaVolumeHigh: Boolean = false,
    sheet: NightStepsSheet? = null,
) {
    var openSheet by rememberSaveable { mutableStateOf(sheet) }
    Box(modifier.fillMaxSize()) {
        WinddownSurface(onBack = actions.onBack) {
            when (state) {
                NightStepsUiState.Loading -> LoadingBody()
                NightStepsUiState.Unavailable -> UnavailableBody(actions.onBack)
                is NightStepsUiState.Ready -> NightStepsBody(
                    state = state,
                    actions = actions,
                    scrollState = scrollState,
                    onOpenSheet = { openSheet = it },
                )
            }
        }
        SnackbarHost(
            snackbarHostState,
            Modifier
                .align(Alignment.BottomCenter)
                .windowInsetsPadding(WindowInsets.safeDrawing),
        )
    }

    val ready = state as? NightStepsUiState.Ready
    val shownSheet = openSheet
    if (ready != null && shownSheet != null) {
        ModalBottomSheet(
            onDismissRequest = { openSheet = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        ) {
            when (shownSheet) {
                NightStepsSheet.ANCHOR -> AnchorSheetContent(
                    night = ready.night,
                    onDrop = { log ->
                        openSheet = null
                        actions.onDropAnchor(log)
                    },
                )
                NightStepsSheet.HUSH -> HushSheetContent(
                    state = ready.hush.copy(mediaVolumeHigh = mediaVolumeHigh),
                    actions = actions.hush,
                )
            }
        }
    }
}

@Composable
private fun NightStepsBody(
    state: NightStepsUiState.Ready,
    actions: NightStepsActions,
    scrollState: ScrollState,
    onOpenSheet: (NightStepsSheet) -> Unit,
) {
    val session = state.session
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = Spacing.screenHorizontal),
    ) {
        val subtitle = if (state.night) {
            stringResource(R.string.winddown_subtitle_night)
        } else {
            stringResource(
                R.string.winddown_subtitle,
                clockTime(session.windowStart, state.zone),
                clockTime(session.bedtime, state.zone),
            ).keepTimesTogether()
        }
        ScreenHeader(title = stringResource(R.string.winddown_title), subtitle = subtitle, modifier = Modifier.padding(top = Spacing.xs))
        if (session.fromSettingsOnly) {
            Text(
                stringResource(R.string.winddown_settings_only),
                style = MaterialTheme.typography.bodyMedium,
                color = SleepSaverTheme.colors.textSecondary,
                modifier = Modifier.padding(top = Spacing.s),
            )
        }

        val cardModifier = Modifier.padding(top = Spacing.cardGap)
        when {
            !session.enabled -> StepsOffCard(state.day, actions.onOpenWeekPlan, Modifier.padding(top = Spacing.xl))
            session.steps.isEmpty() -> NoStepsCard(state.night, actions.onPickSteps, Modifier.padding(top = Spacing.xl))
            else -> {
                StepsCard(
                    state = state,
                    onToggle = actions.onToggleStep,
                    onStart = { tool -> if (tool == GuidedTool.HUSH) onOpenSheet(NightStepsSheet.HUSH) else actions.onStartTool(tool) },
                    onChangeSteps = actions.onChangeSteps,
                    modifier = Modifier.padding(top = Spacing.xl),
                )
                if (state.suggestions.isNotEmpty()) {
                    SuggestionsCard(state.suggestions, actions.onAddSuggestion, actions.onOpenLibrary, cardModifier)
                } else {
                    TextLinkButton(stringResource(R.string.winddown_see_library), onClick = actions.onOpenLibrary, modifier = Modifier.padding(top = Spacing.xs))
                }
            }
        }
        TrackingCard(state.adherence, state.streakShowFrom, cardModifier)
        CompareCard(state.compare, state.compareStatus, cardModifier)
        MarksCard(state.marks, actions.onToggleMark, cardModifier)
        AnchorCard(
            anchored = state.anchored,
            night = state.night,
            onDropAnchor = { onOpenSheet(NightStepsSheet.ANCHOR) },
            onOpenTonight = actions.onOpenTonight,
            modifier = cardModifier,
        )
        WellnessDisclaimer(Modifier.padding(top = Spacing.xxl))
        Spacer(Modifier.height(Spacing.xl))
        Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.safeDrawing))
    }
}
