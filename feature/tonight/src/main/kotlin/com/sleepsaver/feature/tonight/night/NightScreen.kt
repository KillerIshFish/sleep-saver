package com.sleepsaver.feature.tonight.night

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.ui.components.HushControlsActions
import com.sleepsaver.core.ui.platform.DimScreen
import com.sleepsaver.core.ui.platform.rememberMediaVolumeHigh
import com.sleepsaver.feature.tonight.navigation.NightNavigation
import com.sleepsaver.feature.tonight.tonight.TonightUiState
import com.sleepsaver.feature.tonight.tonight.TonightViewModel

/**
 * The Night screen ([com.sleepsaver.feature.tonight.navigation.NightRoute]): the night content full screen, from a
 * notification, a cold start into the night, or Drop anchor elsewhere. Back goes to the Tonight tab; the screen
 * leaves by itself once the night is over (no anchor and not night mode). It follows the system brightness and never
 * keeps the screen on.
 */
@Composable
internal fun NightScreen(
    navigation: NightNavigation,
    modifier: Modifier = Modifier,
    viewModel: TonightViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val mediaVolumeHigh = rememberMediaVolumeHigh(SleepConfig.DEFAULT.hush.mediaVolumeWarning)
    val nav by rememberUpdatedState(navigation)
    DimScreen(null)
    BackHandler { nav.onBackToTonight() }

    val ready = state as? TonightUiState.Ready
    val over = ready != null && ready.night == null
    LaunchedEffect(over) { if (over) nav.onNightOver() }

    val actions = remember(viewModel) {
        NightActions(
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
        )
    }
    Box(modifier.fillMaxSize().safeDrawingPadding()) {
        val night = ready?.night
        if (ready != null && night != null) {
            NightContent(night = night, hush = ready.hush.copy(mediaVolumeHigh = mediaVolumeHigh), actions = actions)
        }
    }
}
