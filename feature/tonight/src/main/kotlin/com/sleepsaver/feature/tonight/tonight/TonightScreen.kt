package com.sleepsaver.feature.tonight.tonight

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.components.HushControlsActions
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.ScreenHeader
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.keepTimesTogether
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.platform.rememberMediaVolumeHigh
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.tonight.R
import com.sleepsaver.feature.tonight.TonightPhase
import com.sleepsaver.feature.tonight.TonightSnackbarHost
import com.sleepsaver.feature.tonight.navigation.TonightNavigation
import com.sleepsaver.feature.tonight.night.NightActions
import com.sleepsaver.feature.tonight.night.NightContent
import com.sleepsaver.feature.tonight.oneoff.OneOffSheet
import com.sleepsaver.feature.tonight.rememberAlarmGate
import kotlinx.coroutines.withTimeoutOrNull
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate

/** Every callback the Tonight tab can fire. All default to doing nothing, so tests pass only what they check. */
@Stable
internal class TonightActions(
    val onOpenSettings: () -> Unit = {},
    val onOpenNightSteps: () -> Unit = {},
    /** "Pick steps": the every-evening routine. */
    val onOpenRoutineEditor: () -> Unit = {},
    val onOpenDayPlan: (DayOfWeek, DayPart) -> Unit = { _, _ -> },
    val onOpenWeekPlan: () -> Unit = {},
    val onOpenAlarmCheck: () -> Unit = {},
    val onOpenMorningReview: (LocalDate) -> Unit = {},
    val onDayOff: (LocalDate) -> Unit = {},
    /** "Just this once" and "Set one for Tue": the one-off sheet for that date. */
    val onOneOff: (LocalDate) -> Unit = {},
    val onTurnBackOn: (LocalDate) -> Unit = {},
    val onResumeAlarms: () -> Unit = {},
    /** Drop anchor: the "Settling in" sheet. */
    val onDropAnchor: () -> Unit = {},
    val onMark: (Mark, Boolean) -> Unit = { _, _ -> },
    val hush: HushControlsActions = HushControlsActions(),
    val onCatnapLength: (Duration) -> Unit = {},
    val onStartCatnap: () -> Unit = {},
    val onCancelCatnap: () -> Unit = {},
    val onFixBlocker: (AlarmBlocker) -> Unit = {},
    val night: NightActions = NightActions(),
)

/** A sheet open over the Tonight tab. */
@Immutable
internal sealed interface TonightSheet {
    data object Anchor : TonightSheet

    data class OneOff(val date: LocalDate) : TonightSheet
}

/** Saves the open sheet as "anchor" or the one-off's ISO date. */
private val TonightSheetSaver = Saver<TonightSheet?, String>(
    save = { sheet ->
        when (sheet) {
            TonightSheet.Anchor -> "anchor"
            is TonightSheet.OneOff -> sheet.date.toString()
            null -> ""
        }
    },
    restore = { saved ->
        when (saved) {
            "anchor" -> TonightSheet.Anchor
            "" -> null
            else -> runCatching { TonightSheet.OneOff(LocalDate.parse(saved)) }.getOrNull()
        }
    },
)

@Composable
internal fun TonightScreen(
    navigation: TonightNavigation,
    modifier: Modifier = Modifier,
    viewModel: TonightViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val oneOffContext by viewModel.oneOffContext.collectAsStateWithLifecycle()
    val gate = rememberAlarmGate()
    val config = SleepConfig.DEFAULT
    val mediaVolumeHigh = rememberMediaVolumeHigh(config.hush.mediaVolumeWarning)
    var sheet by rememberSaveable(stateSaver = TonightSheetSaver) { mutableStateOf<TonightSheet?>(null) }
    val snackbar = remember { SnackbarHostState() }
    val nav by rememberUpdatedState(navigation)

    val dropped = stringResource(R.string.tonight_anchor_dropped)
    val undo = stringResource(R.string.tonight_anchor_undo)
    val failed = stringResource(R.string.tonight_anchor_failed)
    val refused = stringResource(R.string.tonight_ring_refused)
    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is TonightEvent.AnchorDropped -> {
                    // Undo only within the undo window; the snackbar goes away with it.
                    val result = withTimeoutOrNull(config.anchor.undoWindow.toMillis()) {
                        snackbar.showSnackbar(dropped, actionLabel = undo, duration = SnackbarDuration.Indefinite)
                    }
                    if (result == SnackbarResult.ActionPerformed) viewModel.onUndoAnchor(event.anchorId)
                }
                TonightEvent.AnchorFailed -> snackbar.showSnackbar(failed)
                TonightEvent.RingRefused -> snackbar.showSnackbar(refused)
            }
        }
    }

    val actions = remember(viewModel, gate) {
        TonightActions(
            onOpenSettings = { nav.onOpenSettings() },
            onOpenNightSteps = { nav.onOpenNightSteps() },
            onOpenRoutineEditor = { nav.onOpenRoutineEditor(null) },
            onOpenDayPlan = { day, part -> nav.onOpenDayPlan(day, part) },
            onOpenWeekPlan = { nav.onOpenWeekPlan() },
            onOpenAlarmCheck = { nav.onOpenAlarmCheck() },
            onOpenMorningReview = { nav.onOpenMorningReview(it) },
            onDayOff = viewModel::onDayOff,
            onOneOff = { sheet = TonightSheet.OneOff(it) },
            onTurnBackOn = viewModel::onClearOverride,
            onResumeAlarms = viewModel::onResumeAlarms,
            onDropAnchor = { sheet = TonightSheet.Anchor },
            onMark = viewModel::onMark,
            hush = HushControlsActions(
                onColor = viewModel::onHushColor,
                onTimer = viewModel::onHushTimer,
                onVolume = viewModel::onHushVolume,
                onPlay = viewModel::onHushPlay,
                onStop = viewModel::onHushStop,
            ),
            onCatnapLength = viewModel::onCatnapLength,
            onStartCatnap = { gate.arm(viewModel::onStartCatnap) },
            onCancelCatnap = viewModel::onCancelCatnap,
            onFixBlocker = gate::fix,
            night = NightActions(
                onResumeLog = viewModel::onResumeLog,
                hush = HushControlsActions(
                    onColor = viewModel::onHushColor,
                    onTimer = viewModel::onHushTimer,
                    onVolume = viewModel::onHushVolume,
                    onPlay = viewModel::onHushPlay,
                    onStop = viewModel::onHushStop,
                ),
                onCantSleep = { nav.onOpenNightReset() },
                onEndNight = viewModel::onEndNight,
            ),
        )
    }

    val shown = (state as? TonightUiState.Ready)?.let { it.copy(hush = it.hush.copy(mediaVolumeHigh = mediaVolumeHigh)) } ?: state
    TonightContent(
        state = shown,
        actions = actions,
        modifier = modifier,
        alarmBlockers = gate.access.blockers,
        armBlocker = gate.blocker,
        snackbarHostState = snackbar,
    )

    when (val open = sheet) {
        TonightSheet.Anchor -> AnchorSheet(onDrop = viewModel::onDropAnchor, onDismiss = { sheet = null })
        is TonightSheet.OneOff -> oneOffContext?.let { context ->
            OneOffSheet(
                context = context,
                date = open.date,
                chooseDate = false,
                gate = gate,
                onDayOff = viewModel::onDayOff,
                onJustOnce = viewModel::onJustOnce,
                onPause = viewModel::onPause,
                onDismiss = { sheet = null },
            )
        }
        null -> Unit
    }
}

/**
 * The Tonight tab for a given [state], stateless so it can be screenshot-tested without Hilt. [alarmBlockers] are the
 * phone's failing alarm checks (for the "Your alarm might not ring" notice) and [armBlocker] what stopped the last
 * attempt to start something that rings. In the NIGHT phase only the no-clock night shows. Pass a [scrollState] to
 * render it scrolled.
 */
@Composable
internal fun TonightContent(
    state: TonightUiState,
    actions: TonightActions,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    alarmBlockers: List<AlarmBlocker> = emptyList(),
    armBlocker: AlarmBlocker? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    Box(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        when (state) {
            TonightUiState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingOrb(label = stringResource(R.string.tonight_loading))
            }
            TonightUiState.Unavailable -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                EmptyState(
                    icon = Icons.Rounded.ErrorOutline,
                    title = stringResource(R.string.tonight_unavailable_title),
                    body = stringResource(R.string.tonight_unavailable_body),
                    modifier = Modifier.padding(horizontal = Spacing.screenHorizontal),
                )
            }
            is TonightUiState.Ready -> {
                val night = state.night
                if (state.phase == TonightPhase.NIGHT && night != null) {
                    NightContent(night = night, hush = state.hush, actions = actions.night, scrollState = scrollState)
                } else {
                    ReadyTonight(state, actions, scrollState, alarmBlockers, armBlocker)
                }
            }
        }
        TonightSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

@Composable
private fun ReadyTonight(
    state: TonightUiState.Ready,
    actions: TonightActions,
    scrollState: ScrollState,
    alarmBlockers: List<AlarmBlocker>,
    armBlocker: AlarmBlocker?,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val viewport = maxHeight
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                // At least a screen tall, so the disclaimer sits at the bottom of short phases.
                .heightIn(min = viewport)
                .padding(top = Spacing.l, bottom = Spacing.xl),
        ) {
            ScreenHeader(
                title = stringResource(R.string.tonight_title),
                subtitle = headerSubtitle(state),
                // The icon button's own 12 dp padding brings its glyph to the screen margin.
                modifier = Modifier.padding(start = Spacing.screenHorizontal, end = Spacing.s),
            ) {
                IconButton(onClick = actions.onOpenSettings) {
                    Icon(
                        Icons.Rounded.Settings,
                        contentDescription = stringResource(R.string.tonight_settings),
                        tint = SleepSaverTheme.colors.textSecondary,
                    )
                }
            }
            TonightSections(state, actions, alarmBlockers, armBlocker)
        }
    }
}

/** The header's grey line: "Good morning", "Night steps from 9:45 PM", "Bedtime 11:15 PM", or the no-nights note. */
@Composable
private fun headerSubtitle(state: TonightUiState.Ready): String? {
    val steps = state.steps
    return when {
        state.noNights -> stringResource(R.string.tonight_subtitle_no_nights)
        state.phase == TonightPhase.MORNING -> stringResource(R.string.tonight_subtitle_morning)
        steps == null -> null
        state.phase == TonightPhase.DAY && steps.enabled ->
            stringResource(R.string.tonight_subtitle_day, clockTime(steps.windowStart, state.zone))
        else -> stringResource(R.string.tonight_subtitle_evening, clockTime(steps.bedtime, state.zone))
    }?.keepTimesTogether()
}

/** Space above every section. */
internal val SectionGap = Spacing.cardGap

@Composable
internal fun Gap() = Spacer(Modifier.height(SectionGap))
