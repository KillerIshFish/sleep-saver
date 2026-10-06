package com.sleepsaver.feature.onboarding

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.feature.onboarding.connect.ConnectActions
import com.sleepsaver.feature.onboarding.steps.ConnectStep
import com.sleepsaver.feature.onboarding.steps.HabitsStep
import com.sleepsaver.feature.onboarding.steps.ImportStep
import com.sleepsaver.feature.onboarding.steps.SleepNeedStep
import com.sleepsaver.feature.onboarding.steps.WelcomeStep
import com.sleepsaver.feature.onboarding.ui.LaunchGuard
import com.sleepsaver.feature.onboarding.ui.StepTopBar
import com.sleepsaver.feature.onboarding.ui.startFirst
import com.sleepsaver.feature.onboarding.ui.startSafely
import kotlinx.coroutines.launch
import java.time.LocalTime

/** Everything the onboarding content can trigger, so [OnboardingContent] stays stateless. */
@Stable
internal class OnboardingActions(
    val onStart: () -> Unit = {},
    val onBack: () -> Unit = {},
    val onOpenPrivacyPolicy: () -> Unit = {},
    val connect: ConnectActions = ConnectActions(),
    val onContinueFromConnect: () -> Unit = {},
    val onRetryImport: () -> Unit = {},
    val onContinueFromImport: () -> Unit = {},
    val onNeedChange: (Int) -> Unit = {},
    val onUseSuggestion: () -> Unit = {},
    val onWakeChange: (LocalTime) -> Unit = {},
    val onContinueFromSleepNeed: () -> Unit = {},
    val onToggleHabit: (HabitType) -> Unit = {},
    val onRemindChange: (Boolean) -> Unit = {},
    val onFinish: () -> Unit = {},
)

@Composable
internal fun OnboardingScreen(
    onFinished: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // One external screen at a time: a double tap must not stack two permission dialogs or two store pages.
    val launchGuard = remember { LaunchGuard() }

    val permissionLauncher = rememberLauncherForActivityResult(viewModel.permissionContract) { viewModel.onPermissionsResult() }
    // Whatever the answer, onboarding finishes: without the permission reminders simply stay silent.
    val notificationLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { viewModel.finish() }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.onResume() }

    val currentOnFinished by rememberUpdatedState(onFinished)
    LaunchedEffect(state.finished) { if (state.finished) currentOnFinished() }

    val currentState by rememberUpdatedState(state)
    val currentOnOpenPrivacyPolicy by rememberUpdatedState(onOpenPrivacyPolicy)
    val actions = remember(viewModel, context) {
        OnboardingActions(
            onStart = viewModel::start,
            onBack = { viewModel.back() },
            onOpenPrivacyPolicy = { if (launchGuard.tryAcquire()) currentOnOpenPrivacyPolicy() },
            connect = ConnectActions(
                onConnect = {
                    if (launchGuard.tryAcquire()) {
                        scope.launch {
                            val permissions = viewModel.permissionsToRequest()
                            val launched = permissions.isNotEmpty() && runCatching { permissionLauncher.launch(permissions) }.isSuccess
                            if (!launched) viewModel.onPermissionsUnavailable()
                        }
                    }
                },
                onInstall = {
                    if (launchGuard.tryAcquire() && !context.startSafely(viewModel.installOrUpdateIntent())) viewModel.checkAgain()
                },
                onOpenHealthConnect = { if (launchGuard.tryAcquire()) context.startSafely(viewModel.manageDataIntent()) },
                onOpenGarmin = { if (launchGuard.tryAcquire()) context.startFirst(viewModel.garminConnectIntents()) },
                onCheckAgain = viewModel::checkAgain,
                onUseSource = viewModel::useSource,
                onExploreDemo = viewModel::exploreDemo,
            ),
            onContinueFromConnect = viewModel::continueFromConnect,
            onRetryImport = viewModel::retryImport,
            onContinueFromImport = viewModel::continueFromImport,
            onNeedChange = viewModel::setNeed,
            onUseSuggestion = viewModel::useSuggestion,
            onWakeChange = viewModel::setWake,
            onContinueFromSleepNeed = viewModel::continueFromSleepNeed,
            onToggleHabit = viewModel::toggleHabit,
            onRemindChange = viewModel::setRemind,
            onFinish = {
                // Android 13+ asks before any notification; older versions allow them by default.
                val needsPermission = currentState.habits.wantsNotifications &&
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                when {
                    !needsPermission -> viewModel.finish()
                    // The dialog is already on its way; its answer finishes onboarding.
                    !launchGuard.tryAcquire() -> Unit
                    runCatching { notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }.isFailure -> viewModel.finish()
                    else -> Unit
                }
            },
        )
    }

    OnboardingContent(state = state, actions = actions, modifier = modifier)
}

/**
 * The whole flow for [state]: progress bar, then the current step, sliding forwards and back. Back (the arrow
 * and the system gesture) goes one step back, and is ignored while a step is still sliding in, so a double tap
 * can't skip a step.
 */
@Composable
internal fun OnboardingContent(
    state: OnboardingUiState,
    actions: OnboardingActions,
    modifier: Modifier = Modifier,
) {
    val transition = updateTransition(state.step, label = "onboardingStep")
    val onBack: () -> Unit = remember(transition, actions) {
        { if (transition.currentState == transition.targetState) actions.onBack() }
    }
    // On the first step the system handles back (it leaves the app).
    BackHandler(enabled = state.step != OnboardingStep.WELCOME, onBack = onBack)

    Surface(modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                Modifier
                    .widthIn(max = MaxContentWidth)
                    .fillMaxSize(),
            ) {
                AnimatedVisibility(
                    visible = state.step != OnboardingStep.WELCOME,
                    enter = fadeIn() + expandVertically(),
                    exit = fadeOut() + shrinkVertically(),
                ) {
                    StepTopBar(
                        current = state.step.ordinal,
                        total = OnboardingStep.entries.size,
                        onBack = onBack.takeIf { !state.busy },
                    )
                }
                transition.AnimatedContent(
                    transitionSpec = {
                        val forward = targetState.ordinal > initialState.ordinal
                        val direction = if (forward) 1 else -1
                        (slideInHorizontally(tween(TransitionMillis)) { it / 5 * direction } + fadeIn(tween(TransitionMillis)))
                            .togetherWith(slideOutHorizontally(tween(TransitionMillis)) { -it / 5 * direction } + fadeOut(tween(TransitionMillis / 2)))
                    },
                ) { step ->
                    Step(step, state, actions)
                }
            }
        }
    }
}

@Composable
private fun Step(step: OnboardingStep, state: OnboardingUiState, actions: OnboardingActions) {
    when (step) {
        OnboardingStep.WELCOME -> WelcomeStep(onStart = actions.onStart, onOpenPrivacyPolicy = actions.onOpenPrivacyPolicy)
        OnboardingStep.CONNECT -> ConnectStep(
            connection = state.connection,
            busy = state.busy,
            actions = actions.connect,
            onContinue = actions.onContinueFromConnect,
        )
        OnboardingStep.IMPORT -> ImportStep(
            import = state.import,
            busy = state.busy,
            onRetry = actions.onRetryImport,
            onContinue = actions.onContinueFromImport,
        )
        OnboardingStep.SLEEP_NEED -> SleepNeedStep(
            state = state.sleepNeed,
            onNeedChange = actions.onNeedChange,
            onUseSuggestion = actions.onUseSuggestion,
            onWakeChange = actions.onWakeChange,
            onContinue = actions.onContinueFromSleepNeed,
        )
        OnboardingStep.HABITS -> HabitsStep(
            state = state.habits,
            busy = state.busy,
            onToggle = actions.onToggleHabit,
            onRemindChange = actions.onRemindChange,
            onFinish = actions.onFinish,
        )
    }
}

private val MaxContentWidth = 560.dp
private const val TransitionMillis = 350
