package com.sleepsaver.feature.progress.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.HealthAndSafety
import androidx.compose.material.icons.rounded.Insights
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.ui.components.EmptyState
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.PrimaryButton
import com.sleepsaver.core.ui.components.ScreenHeader
import com.sleepsaver.core.ui.components.SegmentedControl
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.format.formatWindowHeader
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.progress.NoNightsReason
import com.sleepsaver.feature.progress.ProgressTab
import com.sleepsaver.feature.progress.ProgressUiState
import com.sleepsaver.feature.progress.ProgressViewModel
import com.sleepsaver.feature.progress.ProgressWindow
import com.sleepsaver.feature.progress.R
import java.time.LocalDate

@Composable
internal fun ProgressScreen(
    onOpenNight: (LocalDate) -> Unit,
    onAddMissingNight: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ProgressViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ProgressContent(
        state = state,
        onPreviousWindow = viewModel::showPreviousWindow,
        onNextWindow = viewModel::showNextWindow,
        onLatestWindow = viewModel::showLatestWindow,
        onOpenNight = onOpenNight,
        onAddMissingNight = onAddMissingNight,
        onOpenSettings = onOpenSettings,
        modifier = modifier,
    )
}

/** The whole Progress tab for one [state]. Stateless apart from the selected tab (saved across recreation). */
@Composable
internal fun ProgressContent(
    state: ProgressUiState,
    onPreviousWindow: () -> Unit,
    onNextWindow: () -> Unit,
    onLatestWindow: () -> Unit,
    onOpenNight: (LocalDate) -> Unit,
    onAddMissingNight: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    initialTab: ProgressTab = ProgressTab.SLEEP_TIMES,
    listState: LazyListState = rememberLazyListState(),
) {
    var tabIndex by rememberSaveable { mutableIntStateOf(initialTab.ordinal) }
    Box(
        modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        when (state) {
            ProgressUiState.Loading -> Column(Modifier.fillMaxSize().padding(ScreenPadding)) {
                ProgressHeader(window = null, onPreviousWindow, onNextWindow, onOpenSettings)
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    LoadingOrb(label = stringResource(R.string.progress_loading))
                }
            }

            is ProgressUiState.NoNights -> Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(ScreenPadding),
            ) {
                ProgressHeader(window = null, onPreviousWindow, onNextWindow, onOpenSettings)
                Spacer(Modifier.height(Spacing.xxxl))
                NoNightsState(state, onAddMissingNight, onOpenSettings)
                Spacer(Modifier.height(Spacing.xxl))
                WellnessDisclaimer()
            }

            is ProgressUiState.Ready -> {
                val tab = ProgressTab.entries[tabIndex]
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = ScreenPadding,
                ) {
                    item(key = "header") {
                        ProgressHeader(state.window, onPreviousWindow, onNextWindow, onOpenSettings)
                    }
                    item(key = "tabs") {
                        SegmentedControl(
                            options = listOf(
                                stringResource(R.string.progress_tab_sleep_times),
                                stringResource(R.string.progress_tab_sleep_debt),
                                stringResource(R.string.progress_tab_sleep_quality),
                            ),
                            selectedIndex = tabIndex,
                            onSelectedChange = { tabIndex = it },
                            modifier = Modifier.padding(top = Spacing.xl, bottom = Spacing.l),
                        )
                    }
                    if (!state.hasNightsInWindow) {
                        item(key = "window-empty") {
                            EmptyWindowCard(
                                onAddNight = { onAddMissingNight(state.window.to) },
                                onLatestWindow = onLatestWindow,
                                showLatest = state.window.canGoForward,
                                modifier = Modifier.padding(bottom = Spacing.cardGap),
                            )
                        }
                    }
                    when (tab) {
                        ProgressTab.SLEEP_TIMES -> sleepTimesItems(state, onOpenNight, onAddMissingNight)
                        ProgressTab.SLEEP_DEBT -> sleepDebtItems(state)
                        ProgressTab.SLEEP_QUALITY -> sleepQualityItems(state, onOpenNight, onOpenSettings)
                    }
                    item(key = "disclaimer") {
                        WellnessDisclaimer(Modifier.padding(top = Spacing.xl))
                    }
                }
            }
        }
    }
}

private val ScreenPadding = PaddingValues(
    start = Spacing.screenHorizontal,
    end = Spacing.screenHorizontal,
    top = Spacing.l,
    bottom = Spacing.xl,
)

@Composable
private fun ProgressHeader(
    window: ProgressWindow?,
    onPreviousWindow: () -> Unit,
    onNextWindow: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val colors = SleepSaverTheme.colors
    ScreenHeader(
        title = stringResource(R.string.progress_title),
        subtitle = window?.let { formatWindowHeader(it.from, it.to) },
    ) {
        if (window != null) {
            Row(
                Modifier
                    .clip(CircleShape)
                    .background(colors.card),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PagerButton(
                    enabled = window.canGoBack,
                    onClick = onPreviousWindow,
                    description = stringResource(R.string.progress_previous_window),
                    forward = false,
                )
                PagerButton(
                    enabled = window.canGoForward,
                    onClick = onNextWindow,
                    description = stringResource(R.string.progress_next_window),
                    forward = true,
                )
            }
        }
        IconButton(onClick = onOpenSettings, modifier = Modifier.padding(start = 4.dp)) {
            Icon(Icons.Rounded.Settings, contentDescription = stringResource(R.string.progress_settings), tint = colors.textSecondary)
        }
    }
}

@Composable
private fun PagerButton(enabled: Boolean, onClick: () -> Unit, description: String, forward: Boolean) {
    val colors = SleepSaverTheme.colors
    IconButton(
        onClick = onClick,
        enabled = enabled,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.onSurface,
            disabledContentColor = colors.textTertiary.copy(alpha = 0.5f),
        ),
    ) {
        Icon(
            imageVector = if (forward) Icons.AutoMirrored.Rounded.KeyboardArrowRight else Icons.AutoMirrored.Rounded.KeyboardArrowLeft,
            contentDescription = description,
            modifier = Modifier.size(26.dp),
        )
    }
}

/**
 * The whole tab when no night has come in. When the last sync said why (Health Connect missing, sleep access off),
 * the fix comes first: Settings holds the data-source and Health Connect setup. Otherwise adding a night leads.
 */
@Composable
private fun NoNightsState(
    state: ProgressUiState.NoNights,
    onAddMissingNight: (LocalDate) -> Unit,
    onOpenSettings: () -> Unit,
) {
    val addNight = stringResource(R.string.progress_add_night)
    val openSettings = stringResource(R.string.progress_open_settings)
    val addToday = { onAddMissingNight(state.today) }
    when (state.reason) {
        NoNightsReason.NO_DATA -> EmptyState(
            icon = Icons.Rounded.Insights,
            title = stringResource(R.string.progress_empty_title),
            body = stringResource(R.string.progress_empty_body),
            primaryActionLabel = addNight,
            onPrimaryAction = addToday,
            secondaryActionLabel = openSettings,
            onSecondaryAction = onOpenSettings,
        )

        NoNightsReason.HEALTH_CONNECT_UNAVAILABLE -> EmptyState(
            icon = Icons.Rounded.HealthAndSafety,
            title = stringResource(R.string.progress_empty_hc_title),
            body = stringResource(R.string.progress_empty_hc_body),
            primaryActionLabel = openSettings,
            onPrimaryAction = onOpenSettings,
            secondaryActionLabel = addNight,
            onSecondaryAction = addToday,
        )

        NoNightsReason.PERMISSION_DENIED -> EmptyState(
            icon = Icons.Rounded.Lock,
            title = stringResource(R.string.progress_empty_permission_title),
            body = stringResource(R.string.progress_empty_permission_body),
            primaryActionLabel = openSettings,
            onPrimaryAction = onOpenSettings,
            secondaryActionLabel = addNight,
            onSecondaryAction = addToday,
        )
    }
}

@Composable
private fun EmptyWindowCard(
    onAddNight: () -> Unit,
    onLatestWindow: () -> Unit,
    showLatest: Boolean,
    modifier: Modifier = Modifier,
) {
    SleepSaverCard(modifier.fillMaxWidth()) {
        Text(
            text = stringResource(R.string.progress_window_empty_title),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = stringResource(R.string.progress_window_empty_body),
            style = MaterialTheme.typography.bodyMedium,
            color = SleepSaverTheme.colors.textSecondary,
            modifier = Modifier.padding(top = 6.dp),
        )
        Row(
            modifier = Modifier.padding(top = Spacing.l),
            horizontalArrangement = Arrangement.spacedBy(Spacing.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            PrimaryButton(stringResource(R.string.progress_add_night), onClick = onAddNight)
            if (showLatest) TextLinkButton(stringResource(R.string.progress_go_latest), onClick = onLatestWindow)
        }
    }
}
