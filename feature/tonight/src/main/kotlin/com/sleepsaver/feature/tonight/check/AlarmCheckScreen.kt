package com.sleepsaver.feature.tonight.check

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.alarm.TuningReason
import com.sleepsaver.core.ui.components.LoadingOrb
import com.sleepsaver.core.ui.components.ScreenHeader
import com.sleepsaver.core.ui.components.SecondaryButton
import com.sleepsaver.core.ui.components.SectionCard
import com.sleepsaver.core.ui.components.SleepSaverCard
import com.sleepsaver.core.ui.components.TextLinkButton
import com.sleepsaver.core.ui.components.WellnessDisclaimer
import com.sleepsaver.core.ui.components.actionLabel
import com.sleepsaver.core.ui.components.fix
import com.sleepsaver.core.ui.components.icon
import com.sleepsaver.core.ui.components.title
import com.sleepsaver.core.ui.format.clockTime
import com.sleepsaver.core.ui.format.keepTimesTogether
import com.sleepsaver.core.ui.platform.AlarmBlocker
import com.sleepsaver.core.ui.platform.AlarmCheck
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.tonight.ArmBlockerNotice
import com.sleepsaver.feature.tonight.ArmSource
import com.sleepsaver.feature.tonight.BackTopBar
import com.sleepsaver.feature.tonight.R
import com.sleepsaver.feature.tonight.TestRingMessages
import com.sleepsaver.feature.tonight.TonightSnackbarHost
import com.sleepsaver.feature.tonight.formatMonthDay
import com.sleepsaver.feature.tonight.rememberAlarmGate
import com.sleepsaver.core.ui.R as UiR

/** Every callback of Alarm check; all default to doing nothing. */
@Stable
internal class AlarmCheckActions(
    val onBack: () -> Unit = {},
    val onFix: (AlarmBlocker) -> Unit = {},
    val onTestRing: () -> Unit = {},
    val onResetTuning: () -> Unit = {},
)

@Composable
internal fun AlarmCheckScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AlarmCheckViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val gate = rememberAlarmGate()
    val snackbar = remember { SnackbarHostState() }
    TestRingMessages(viewModel.events, snackbar)
    val actions = remember(viewModel, gate, onBack) {
        AlarmCheckActions(
            onBack = onBack,
            onFix = gate::fix,
            onTestRing = { gate.arm(ArmSource.TEST_RING, viewModel::onTestRing) },
            onResetTuning = viewModel::onResetTuning,
        )
    }
    AlarmCheckContent(
        state = state,
        checks = gate.access.checks,
        actions = actions,
        modifier = modifier,
        armBlocker = gate.blocker(ArmSource.TEST_RING),
        snackbarHostState = snackbar,
    )
}

/**
 * Alarm check for a given [state] and the phone's [checks] (AlarmAccess, in severity order): one line per check
 * (passed, or what's wrong with its fix), where the phone goes, the test ring and the restless trigger.
 */
@Composable
internal fun AlarmCheckContent(
    state: AlarmCheckUiState,
    checks: List<AlarmCheck>,
    actions: AlarmCheckActions,
    modifier: Modifier = Modifier,
    scrollState: ScrollState = rememberScrollState(),
    armBlocker: AlarmBlocker? = null,
    snackbarHostState: SnackbarHostState = remember { SnackbarHostState() },
) {
    val screen = Modifier.padding(horizontal = Spacing.screenHorizontal)
    val card = screen.fillMaxWidth()
    Box(
        modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(bottom = Spacing.xl),
        ) {
            BackTopBar(title = null, onBack = actions.onBack)
            ScreenHeader(stringResource(R.string.tonight_check_title), screen, subtitle = stringResource(R.string.tonight_check_intro))

            Spacer(Modifier.height(Spacing.l))
            SleepSaverCard(card) {
                checks.forEach { check -> CheckLine(check, actions.onFix) }
            }

            Spacer(Modifier.height(Spacing.cardGap))
            SectionCard(stringResource(R.string.tonight_check_placement_title), card) {
                Text(
                    stringResource(UiR.string.ss_placement),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }

            when (state) {
                AlarmCheckUiState.Loading -> Box(Modifier.fillMaxWidth().padding(Spacing.xl), contentAlignment = Alignment.Center) {
                    LoadingOrb(size = 48.dp)
                }
                is AlarmCheckUiState.Ready -> {
                    Spacer(Modifier.height(Spacing.cardGap))
                    SectionCard(stringResource(R.string.tonight_check_test_title), card) {
                        val last = state.lastTest
                        Text(
                            if (last != null) {
                                stringResource(
                                    R.string.tonight_check_last_test,
                                    clockTime(last.firstRingAt, last.offset),
                                    formatMonthDay(last.firstRingAt.atOffset(last.offset).toLocalDate()),
                                ).keepTimesTogether()
                            } else {
                                stringResource(R.string.tonight_check_no_test)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = SleepSaverTheme.colors.textSecondary,
                        )
                        armBlocker?.let { ArmBlockerNotice(it, actions.onFix) }
                        SecondaryButton(stringResource(R.string.tonight_test_ring), onClick = actions.onTestRing, modifier = Modifier.fillMaxWidth())
                    }

                    Spacer(Modifier.height(Spacing.cardGap))
                    SectionCard(stringResource(R.string.tonight_check_trigger_title), card) {
                        val tuning = state.tuning
                        val since = tuning?.let { formatMonthDay(it.changedAt.atOffset(it.offset).toLocalDate()) }
                        Text(
                            when (tuning?.reason) {
                                TuningReason.RAISED_AFTER_DEEP_RINGS -> stringResource(R.string.tonight_check_trigger_raised, since.orEmpty())
                                TuningReason.LOWERED_AFTER_LATE_RINGS -> stringResource(R.string.tonight_check_trigger_lowered, since.orEmpty())
                                TuningReason.RESET, null -> stringResource(R.string.tonight_check_trigger_standard)
                            },
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        if (state.tuned) {
                            TextLinkButton(
                                stringResource(R.string.tonight_check_trigger_reset),
                                onClick = actions.onResetTuning,
                                modifier = Modifier.offset(x = (-12).dp),
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(Spacing.xxl))
            WellnessDisclaimer(screen)
        }
        TonightSnackbarHost(snackbarHostState, Modifier.align(Alignment.BottomCenter))
    }
}

/** One check: "Notifications allowed" with a tide tick, or the problem with its fix line and button. */
@Composable
private fun CheckLine(check: AlarmCheck, onFix: (AlarmBlocker) -> Unit) {
    val blocker = check.blocker
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.s)
            .semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            if (check.passed) Icons.Rounded.CheckCircle else blocker.icon,
            contentDescription = null,
            tint = when {
                check.passed -> MaterialTheme.colorScheme.primary
                blocker.blocking -> MaterialTheme.colorScheme.tertiary
                else -> SleepSaverTheme.colors.textSecondary
            },
            modifier = Modifier.size(22.dp),
        )
        Column(Modifier.weight(1f)) {
            if (check.passed) {
                Text(stringResource(blocker.passRes), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
            } else {
                Text(blocker.title(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                Text(
                    blocker.fix(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = SleepSaverTheme.colors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp),
                )
                blocker.actionLabel()?.let { label ->
                    TextLinkButton(label, onClick = { onFix(blocker) }, modifier = Modifier.offset(x = (-12).dp))
                }
            }
        }
    }
}

/** The line shown when a check passes: "Notifications allowed", "Sound plays on this phone". */
private val AlarmBlocker.passRes: Int
    get() = when (this) {
        AlarmBlocker.NOTIFICATIONS_OFF -> R.string.tonight_check_pass_notifications
        AlarmBlocker.RING_CHANNEL_OFF -> R.string.tonight_check_pass_ring_channel
        AlarmBlocker.EXACT_ALARMS_OFF -> R.string.tonight_check_pass_exact
        AlarmBlocker.FULL_SCREEN_OFF -> R.string.tonight_check_pass_full_screen
        AlarmBlocker.BACKGROUND_RESTRICTED -> R.string.tonight_check_pass_background
        AlarmBlocker.ALARM_VOLUME_ZERO -> R.string.tonight_check_pass_volume
        AlarmBlocker.TOTAL_SILENCE -> R.string.tonight_check_pass_silence
        AlarmBlocker.BATTERY_OPTIMIZED -> R.string.tonight_check_pass_battery
        AlarmBlocker.BLUETOOTH_OUTPUT -> R.string.tonight_check_pass_bluetooth
    }
