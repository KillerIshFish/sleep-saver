package com.sleepsaver.feature.energy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudSync
import androidx.compose.material.icons.rounded.EditCalendar
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material3.BottomSheetScaffold
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetScaffoldState
import androidx.compose.material3.rememberStandardBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.ScreenHeader
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.components.label
import com.sleepsaver.core.ui.format.timeRange
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.energy.timeline.EnergyTimeline
import com.sleepsaver.feature.energy.timeline.TimelineBlock
import com.sleepsaver.feature.energy.timeline.TimelineBlockKind
import com.sleepsaver.feature.energy.timeline.TomorrowWakeIcon
import com.sleepsaver.feature.energy.timeline.awakeStartFor
import com.sleepsaver.feature.energy.timeline.buildTimelineLayout
import com.sleepsaver.feature.energy.timeline.rememberTimelineSpec
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
internal fun EnergyScreen(
    onEditNight: (LocalDate) -> Unit,
    onAddHabit: () -> Unit,
    onOpenHabit: (HabitType) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: EnergyViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    EnergyContent(
        state = state,
        onEditNight = onEditNight,
        onAddAwake = viewModel::addAwake,
        onAddHabit = onAddHabit,
        onOpenHabit = onOpenHabit,
        onToggleReminder = viewModel::setReminder,
        onOpenSettings = onOpenSettings,
        onSync = viewModel::syncNow,
        modifier = modifier,
    )
}

/**
 * The Energy tab without its ViewModel. [focusTime] is what the timeline scrolls to on open (about a third of
 * the way down the screen); null means now. [initialSheetValue] lets previews show the habits sheet open.
 */
@Composable
internal fun EnergyContent(
    state: EnergyUiState,
    onEditNight: (LocalDate) -> Unit,
    onAddAwake: (LocalDate, Instant, Instant) -> Unit,
    onAddHabit: () -> Unit,
    onOpenHabit: (HabitType) -> Unit,
    onToggleReminder: (HabitType, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    onSync: () -> Unit = {},
    focusTime: Instant? = null,
    initialSheetValue: SheetValue = SheetValue.PartiallyExpanded,
) {
    val insetsModifier = modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.background)
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
    when (state) {
        EnergyUiState.Loading -> Column(insetsModifier) {
            EnergyHeader(subtitle = null, onOpenSettings = onOpenSettings)
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                LoadingOrb(label = stringResource(R.string.energy_loading), modifier = Modifier.padding(bottom = 96.dp))
            }
        }
        is EnergyUiState.NoNights -> Column(insetsModifier) {
            EnergyHeader(subtitle = null, onOpenSettings = onOpenSettings)
            NoNightsContent(reason = state.reason, onOpenSettings = onOpenSettings)
        }
        is EnergyUiState.Ready -> ReadyContent(
            state = state,
            onEditNight = onEditNight,
            onAddAwake = onAddAwake,
            onAddHabit = onAddHabit,
            onOpenHabit = onOpenHabit,
            onToggleReminder = onToggleReminder,
            onOpenSettings = onOpenSettings,
            onSync = onSync,
            focusTime = focusTime,
            initialSheetValue = initialSheetValue,
            modifier = insetsModifier,
        )
    }
}

@Composable
private fun EnergyHeader(subtitle: String?, onOpenSettings: () -> Unit, modifier: Modifier = Modifier) {
    ScreenHeader(
        title = stringResource(R.string.energy_title),
        subtitle = subtitle,
        modifier = modifier.padding(start = Spacing.screenHorizontal, end = Spacing.s, top = Spacing.l, bottom = Spacing.s),
    ) {
        IconButton(onClick = onOpenSettings) {
            Icon(
                imageVector = Icons.Rounded.Settings,
                contentDescription = stringResource(R.string.energy_settings),
                tint = SleepSaverTheme.colors.textSecondary,
            )
        }
    }
}

@Composable
private fun ReadyContent(
    state: EnergyUiState.Ready,
    onEditNight: (LocalDate) -> Unit,
    onAddAwake: (LocalDate, Instant, Instant) -> Unit,
    onAddHabit: () -> Unit,
    onOpenHabit: (HabitType) -> Unit,
    onToggleReminder: (HabitType, Boolean) -> Unit,
    onOpenSettings: () -> Unit,
    onSync: () -> Unit,
    focusTime: Instant?,
    initialSheetValue: SheetValue,
    modifier: Modifier,
) {
    val scaffoldState = rememberBottomSheetScaffoldState(
        bottomSheetState = rememberStandardBottomSheetState(initialValue = initialSheetValue, skipHiddenState = true),
    )
    // Survives rotation, so a half-made entry isn't lost.
    var awakeDraft by rememberSaveable(stateSaver = AwakeDraft.Saver) { mutableStateOf<AwakeDraft?>(null) }

    BottomSheetScaffold(
        sheetContent = {
            HabitsSheetContent(
                habits = state.habits,
                nextHabit = state.nextHabit,
                zone = state.zone,
                onAddHabit = onAddHabit,
                onOpenHabit = onOpenHabit,
                onToggleReminder = onToggleReminder,
            )
        },
        modifier = modifier,
        scaffoldState = scaffoldState,
        sheetPeekHeight = sheetPeekHeight(),
        sheetContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        sheetContentColor = MaterialTheme.colorScheme.onSurface,
        sheetShadowElevation = 12.dp,
        sheetDragHandle = { SheetHandle() },
        containerColor = MaterialTheme.colorScheme.background,
    ) {
        Column(Modifier.fillMaxSize()) {
            EnergyHeader(
                subtitle = stringResource(R.string.energy_subtitle, state.energyPotential, state.status.label()),
                onOpenSettings = onOpenSettings,
            )
            if (state.lastNightState == LastNightState.WAITING || state.lastNightState == LastNightState.MISSING) {
                LastNightBanner(
                    missing = state.lastNightState == LastNightState.MISSING,
                    syncing = state.syncing,
                    onAdd = { onEditNight(state.day) },
                    onSync = onSync,
                    modifier = Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.xs),
                )
            }
            TimelineScroller(
                state = state,
                focusTime = focusTime ?: state.now,
                onBlockTap = { block ->
                    when (block.kind) {
                        TimelineBlockKind.PENDING -> onEditNight(state.day)
                        else -> onEditNight(block.wakeDate)
                    }
                },
                onMainLongPress = { block, pressed ->
                    awakeDraft = AwakeDraft(
                        wakeDate = block.wakeDate,
                        blockStart = block.start,
                        blockEnd = block.end,
                        start = awakeStartFor(pressed, block.start, block.end),
                    )
                },
                onOpenHabit = onOpenHabit,
                modifier = Modifier.weight(1f),
            )
        }
    }

    awakeDraft?.let { draft ->
        AddAwakeDialog(
            draft = draft,
            zone = state.zone,
            onDismiss = { awakeDraft = null },
            onConfirm = { start, end ->
                awakeDraft = null
                onAddAwake(draft.wakeDate, start, end)
            },
        )
    }
}

@Composable
private fun LastNightBanner(
    missing: Boolean,
    syncing: Boolean,
    onAdd: () -> Unit,
    onSync: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(SleepSaverTheme.colors.card)
            .padding(start = Spacing.l, end = Spacing.xs, top = Spacing.xs, bottom = Spacing.xs)
            .heightIn(min = 48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = if (missing) Icons.Rounded.EditCalendar else Icons.Rounded.CloudSync,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(20.dp),
        )
        Spacer(Modifier.width(Spacing.m))
        Text(
            text = stringResource(if (missing) R.string.energy_missing_banner else R.string.energy_waiting_banner),
            style = MaterialTheme.typography.bodySmall,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier
                .weight(1f)
                .padding(vertical = Spacing.s),
        )
        if (missing) {
            TextLinkButton(stringResource(R.string.energy_missing_action), onClick = onAdd)
        } else {
            TextLinkButton(
                text = stringResource(if (syncing) R.string.energy_syncing else R.string.energy_sync_now),
                onClick = onSync,
                enabled = !syncing,
            )
        }
    }
}

/** Nothing to draw yet: say why and offer the fix (data source and Health Connect live in Settings). */
@Composable
private fun NoNightsContent(reason: EmptyReason, onOpenSettings: () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.xl, vertical = Spacing.xxl),
        contentAlignment = Alignment.Center,
    ) {
        if (reason == EmptyReason.IMPORTING) {
            LoadingOrb(label = stringResource(R.string.energy_importing), modifier = Modifier.padding(bottom = 96.dp))
        } else {
            val (icon, title, body) = when (reason) {
                EmptyReason.HEALTH_CONNECT_MISSING ->
                    Triple(Icons.Rounded.HealthAndSafety, R.string.energy_hc_missing_title, R.string.energy_hc_missing_body)
                EmptyReason.HEALTH_CONNECT_UPDATE ->
                    Triple(Icons.Rounded.SystemUpdate, R.string.energy_hc_update_title, R.string.energy_hc_update_body)
                EmptyReason.PERMISSION_DENIED ->
                    Triple(Icons.Rounded.Lock, R.string.energy_permission_title, R.string.energy_permission_body)
                EmptyReason.NO_DATA, EmptyReason.IMPORTING ->
                    Triple(Icons.Rounded.Insights, R.string.energy_empty_title, R.string.energy_empty_body)
            }
            EmptyState(
                icon = icon,
                title = stringResource(title),
                body = stringResource(body),
                primaryActionLabel = stringResource(R.string.energy_empty_action),
                onPrimaryAction = onOpenSettings,
            )
        }
    }
}

/** The scrolling timeline. Opens with [focusTime] about a third of the way down. */
@Composable
private fun TimelineScroller(
    state: EnergyUiState.Ready,
    focusTime: Instant,
    onBlockTap: (TimelineBlock) -> Unit,
    onMainLongPress: (TimelineBlock, Instant) -> Unit,
    onOpenHabit: (HabitType) -> Unit,
    modifier: Modifier = Modifier,
) {
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val spec = rememberTimelineSpec(maxWidth)
        val layout = remember(state, spec) { buildTimelineLayout(state, spec) }
        val viewport = constraints.maxHeight
        val initialScroll = remember {
            (layout.geometry.yOf(focusTime) - viewport / 3f).roundToInt().coerceAtLeast(0)
        }
        val scrollState = rememberScrollState(initialScroll)
        // When the range's start moves (last night arrives in place of the placeholder), keep the same moment
        // in view instead of letting the content jump under the user.
        val rangeStart = layout.geometry.range.start
        var shownStart by remember { mutableStateOf(rangeStart) }
        LaunchedEffect(rangeStart) {
            val shiftPx = Duration.between(rangeStart, shownStart).toMillis() / MillisPerHour * layout.spec.pxPerHour
            shownStart = rangeStart
            if (shiftPx != 0f) scrollState.scrollBy(shiftPx)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState),
        ) {
            EnergyTimeline(
                layout = layout,
                state = state,
                onBlockTap = onBlockTap,
                onMainLongPress = onMainLongPress,
                onOpenHabit = onOpenHabit,
            )
            TomorrowWakeLine(state)
            WellnessDisclaimer(Modifier.padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.l))
            Spacer(Modifier.height(sheetPeekHeight() + Spacing.l))
        }
        // Content slides softly under the header instead of being cut off.
        val fade = with(LocalDensity.current) { 14.dp.toPx() }
        Box(
            Modifier
                .fillMaxWidth()
                .height(14.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.background, Color.Transparent),
                        startY = 0f,
                        endY = fade,
                    ),
                ),
        )
    }
}

@Composable
private fun TomorrowWakeLine(state: EnergyUiState.Ready) {
    val zone = state.schedule.tomorrowWakeZone
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.screenHorizontal, vertical = Spacing.s),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(TomorrowWakeIcon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(Spacing.s))
        Text(
            text = stringResource(R.string.energy_tomorrow_wake, timeRange(zone.start, zone.end, state.zone)),
            style = MaterialTheme.typography.labelMedium,
            color = SleepSaverTheme.colors.textSecondary,
        )
    }
}

private const val MillisPerHour = 3_600_000f
