package com.sleepsaver.feature.sleep

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.ScreenHeader
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import java.time.LocalDate

/** Every callback the home screen can fire. Defaults do nothing, so tests pass only what they check. */
@Stable
class SleepActions(
    val onOpenSettings: () -> Unit = {},
    val onOpenNight: (LocalDate) -> Unit = {},
    val onOpenReview: (LocalDate) -> Unit = {},
    val onAddNap: () -> Unit = {},
    val onAddMissingNight: (LocalDate) -> Unit = {},
    val onOpenHabits: () -> Unit = {},
    val onOpenEnergy: () -> Unit = {},
    val onOpenHealthConnectSetup: () -> Unit = {},
    /** Pull to refresh. */
    val onRefresh: () -> Unit = {},
    /** "Sync now" in the waiting banner and the no-data state: may open Garmin Connect first. */
    val onSyncNowViaGarmin: () -> Unit = {},
    /** "Sync now" in the data-source sheet: a plain Health Connect sync. */
    val onSyncNow: () -> Unit = {},
    val onInstallHealthConnect: () -> Unit = {},
    val onTryDemo: () -> Unit = {},
    val onUseSuggestedNeed: () -> Unit = {},
    val onDismissNeedSuggestion: () -> Unit = {},
    val onReminderChange: (HabitType, Boolean) -> Unit = { _, _ -> },
)

@Composable
internal fun SleepScreen(
    onOpenNight: (LocalDate) -> Unit,
    onOpenReview: (LocalDate) -> Unit,
    onAddNap: () -> Unit,
    onAddMissingNight: (LocalDate) -> Unit,
    onOpenHabits: () -> Unit,
    onOpenEnergy: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenHealthConnectSetup: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SleepViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    // Health Connect may have been installed or permissions granted while we were away; a pending
    // "Sync now" (sent to Garmin Connect) syncs here too.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    // The navigation callbacks are already guarded against double taps (see sleepScreen); always call the latest.
    val openSettings by rememberUpdatedState(onOpenSettings)
    val openNight by rememberUpdatedState(onOpenNight)
    val openReview by rememberUpdatedState(onOpenReview)
    val addNap by rememberUpdatedState(onAddNap)
    val addMissingNight by rememberUpdatedState(onAddMissingNight)
    val openHabits by rememberUpdatedState(onOpenHabits)
    val openEnergy by rememberUpdatedState(onOpenEnergy)
    val openHealthConnectSetup by rememberUpdatedState(onOpenHealthConnectSetup)

    val actions = remember(viewModel, context, lifecycle) {
        /** Leaving the app: a second tap while the first launch is under way would open it twice. */
        fun ifResumed(block: () -> Unit) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) block()
        }
        SleepActions(
            onOpenSettings = { openSettings() },
            onOpenNight = { openNight(it) },
            onOpenReview = { openReview(it) },
            onAddNap = { addNap() },
            onAddMissingNight = { addMissingNight(it) },
            onOpenHabits = { openHabits() },
            onOpenEnergy = { openEnergy() },
            onOpenHealthConnectSetup = { openHealthConnectSetup() },
            onRefresh = viewModel::onRefresh,
            onSyncNowViaGarmin = {
                ifResumed {
                    val intent = viewModel.syncNowIntent()
                    if (intent != null && !context.tryStartActivity(intent)) viewModel.onExternalLaunchFailed()
                }
            },
            onSyncNow = viewModel::syncNow,
            onInstallHealthConnect = {
                ifResumed {
                    // No Play Store: the setup screen explains the other ways to get it.
                    if (!context.tryStartActivity(viewModel.installHealthConnectIntent())) openHealthConnectSetup()
                }
            },
            onTryDemo = viewModel::onTryDemo,
            onUseSuggestedNeed = viewModel::onUseSuggestedNeed,
            onDismissNeedSuggestion = viewModel::onDismissNeedSuggestion,
            onReminderChange = viewModel::onReminderChange,
        )
    }
    SleepContent(state = state, actions = actions, modifier = modifier)
}

/**
 * The home screen for a given [state]. Stateless apart from the data-source sheet's visibility, so it can be
 * screenshot-tested without Hilt. Pass a [scrollState] to render it scrolled.
 */
@Composable
internal fun SleepContent(
    state: SleepUiState,
    actions: SleepActions,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
) {
    var sourceSheetOpen by rememberSaveable { mutableStateOf(false) }
    val refreshing = when (state) {
        is SleepUiState.Ready -> state.isRefreshing
        is SleepUiState.Empty -> state.isRefreshing
        SleepUiState.Loading -> false
    }
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = actions.onRefresh,
        state = pullState,
        modifier = modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
        indicator = {
            PullToRefreshDefaults.Indicator(
                state = pullState,
                isRefreshing = refreshing,
                modifier = Modifier.align(Alignment.TopCenter),
                containerColor = SleepSaverTheme.colors.cardRaised,
                color = MaterialTheme.colorScheme.primary,
            )
        },
    ) {
        BoxWithConstraints(Modifier.fillMaxSize()) {
            val viewport = maxHeight
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(scrollState)
                    // At least a screen tall, so short states can centre themselves and the disclaimer sits at the bottom.
                    .heightIn(min = viewport)
                    .padding(top = Spacing.l, bottom = Spacing.xl),
            ) {
                ScreenHeader(
                    title = stringResource(R.string.sleep_title),
                    subtitle = when (state) {
                        is SleepUiState.Ready -> formatHeaderDate(state.today)
                        is SleepUiState.Empty -> formatHeaderDate(state.today)
                        SleepUiState.Loading -> null
                    },
                    // The icon button's own 12 dp padding brings its glyph to the screen margin.
                    modifier = Modifier.padding(start = Spacing.screenHorizontal, end = Spacing.s),
                ) {
                    IconButton(onClick = actions.onOpenSettings) {
                        Icon(
                            imageVector = Icons.Rounded.Settings,
                            contentDescription = stringResource(R.string.sleep_settings),
                            tint = SleepSaverTheme.colors.textSecondary,
                        )
                    }
                }
                when (state) {
                    SleepUiState.Loading -> LoadingBody()
                    is SleepUiState.Empty -> EmptyBody(state, actions)
                    is SleepUiState.Ready -> ReadyBody(state, actions, onOpenSource = { sourceSheetOpen = true })
                }
            }
        }
    }
    if (sourceSheetOpen && state is SleepUiState.Ready) {
        SourceSheet(state = state, actions = actions, onDismiss = { sourceSheetOpen = false })
    }
}

@Composable
private fun ColumnScope.LoadingBody() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .weight(1f),
        contentAlignment = Alignment.Center,
    ) {
        LoadingOrb(label = stringResource(R.string.sleep_loading))
    }
    Spacer(Modifier.heightIn(min = Spacing.xxxl))
}

private fun Context.tryStartActivity(intent: Intent): Boolean = try {
    startActivity(intent)
    true
} catch (_: ActivityNotFoundException) {
    false
} catch (_: SecurityException) {
    false
}
