package com.sleepsaver.feature.onboarding

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.model.Confidence
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.testing.FakeHealthConnectGateway
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.testing.ScreenshotTest
import com.sleepsaver.core.ui.testing.captureComponent
import com.sleepsaver.core.ui.testing.captureScreen
import com.sleepsaver.core.ui.theme.Spacing
import com.sleepsaver.feature.onboarding.connect.ConnectActions
import com.sleepsaver.feature.onboarding.connect.ConnectionUiState
import com.sleepsaver.feature.onboarding.connect.SourceCard
import com.sleepsaver.feature.onboarding.connect.SourceCheck
import com.sleepsaver.feature.onboarding.steps.ConnectStep
import com.sleepsaver.feature.onboarding.steps.HabitsStep
import com.sleepsaver.feature.onboarding.steps.SleepNeedStep
import org.junit.Test
import java.time.LocalTime

class OnboardingScreenshotTest : ScreenshotTest() {

    private val actions = OnboardingActions()

    private fun screen(name: String, state: OnboardingUiState) = captureScreen(name) { OnboardingContent(state, actions) }

    private fun connect(name: String, connection: ConnectionUiState) =
        screen(name, OnboardingUiState(step = OnboardingStep.CONNECT, connection = connection))

    // ------------------------------------------------------------ 1. welcome

    @Test fun welcome() = screen("onboarding_welcome", OnboardingUiState())

    @Test fun welcomeLargeFont() = captureScreen("onboarding_welcome_large_font") {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 1.5f)) {
            OnboardingContent(OnboardingUiState(), actions)
        }
    }

    // ------------------------------------------------------------ 2. connect

    @Test fun connectChecking() = connect("onboarding_connect_checking", ConnectionUiState())

    @Test fun connectIntro() = connect("onboarding_connect_intro", Previews.needsPermission)

    @Test fun connectNotInstalled() = connect("onboarding_connect_not_installed", ConnectionUiState(status = HealthConnectStatus.NOT_INSTALLED))

    @Test fun connectUpdate() = connect("onboarding_connect_update_required", ConnectionUiState(status = HealthConnectStatus.UPDATE_REQUIRED))

    @Test fun connectNotSupported() = connect("onboarding_connect_not_supported", ConnectionUiState(status = HealthConnectStatus.NOT_SUPPORTED))

    @Test fun connectDenied() = connect("onboarding_connect_denied", Previews.needsPermission.copy(permissionAsked = true))

    @Test fun connectGarminFound() = connect("onboarding_connect_garmin_found", Previews.connected)

    @Test fun connectGarminMissing() = connect("onboarding_connect_garmin_missing", Previews.garminMissing)

    @Test fun connectGarminMissingBottom() = captureScreen("onboarding_connect_garmin_missing_bottom") {
        ConnectStep(Previews.garminMissing, busy = false, actions = ConnectActions(), onContinue = {}, scrollState = ScrollState(Int.MAX_VALUE))
    }

    @Test fun sourceCards() = captureComponent("onboarding_source_cards") {
        Column(verticalArrangement = Arrangement.spacedBy(Spacing.cardGap)) {
            SourceCard(SourceCheck.Checking, garminInstalled = true, actions = ConnectActions())
            SourceCard(SourceCheck.Sharing(SourceSelection.Single("com.fitbit.FitbitMobile")), garminInstalled = true, actions = ConnectActions())
            SourceCard(SourceCheck.Sharing(SourceSelection.All), garminInstalled = true, actions = ConnectActions())
            SourceCard(
                SourceCheck.Missing(SourceSelection.Single("com.ouraring.oura"), listOf(SleepConfig.GARMIN_CONNECT_PACKAGE)),
                garminInstalled = false,
                actions = ConnectActions(),
            )
        }
    }

    // ------------------------------------------------------------ 3. import

    private fun import(name: String, import: ImportUiState) =
        screen(name, OnboardingUiState(step = OnboardingStep.IMPORT, import = import))

    @Test fun importProgress() = import("onboarding_import_progress", ImportUiState.Importing(0.42f))

    @Test fun importFound() = import("onboarding_import_found", ImportUiState.Done(Previews.nights))

    @Test fun importEmpty() = import("onboarding_import_empty", ImportUiState.Done(NightsSummary(recent = List(14) { null })))

    @Test fun importDemo() = import("onboarding_import_demo", ImportUiState.Demo(Previews.nights))

    @Test fun importFailed() = import("onboarding_import_failed", ImportUiState.Failed(SyncError.RATE_LIMITED))

    // ------------------------------------------------------------ 4. sleep need

    @Test fun sleepNeed() = screen(
        "onboarding_sleep_need",
        OnboardingUiState(step = OnboardingStep.SLEEP_NEED, sleepNeed = Previews.sleepNeed),
    )

    @Test fun sleepNeedSuggestion24h() = captureScreen("onboarding_sleep_need_suggestion_24h") {
        CompositionLocalProvider(LocalUse24HourClock provides true) {
            OnboardingContent(
                OnboardingUiState(
                    step = OnboardingStep.SLEEP_NEED,
                    sleepNeed = Previews.sleepNeed.copy(
                        suggestion = NeedSuggestion(515, Confidence.MEDIUM, 6),
                        wake = LocalTime.of(6, 50),
                        wakeFromNights = true,
                    ),
                ),
                actions,
            )
        }
    }

    @Test fun sleepNeedSuggestionInUse() = captureScreen("onboarding_sleep_need_suggestion_in_use") {
        SleepNeedStep(
            state = Previews.sleepNeed.copy(needMinutes = 515, suggestion = NeedSuggestion(515, Confidence.HIGH, 9)),
            onNeedChange = {},
            onUseSuggestion = {},
            onWakeChange = {},
            onContinue = {},
        )
    }

    // ------------------------------------------------------------ 5. habits

    @Test fun habits() = screen("onboarding_habits", OnboardingUiState(step = OnboardingStep.HABITS, habits = Previews.habits))

    @Test fun habitsBottom() = captureScreen("onboarding_habits_bottom") {
        HabitsStep(Previews.habits, busy = false, onToggle = {}, onRemindChange = {}, onFinish = {}, scrollState = ScrollState(Int.MAX_VALUE))
    }

    @Test fun connectLargeFont() = captureScreen("onboarding_connect_garmin_missing_large_font") {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = 2f)) {
            OnboardingContent(OnboardingUiState(step = OnboardingStep.CONNECT, connection = Previews.garminMissing), actions)
        }
    }

    @Test fun habitsNoTimes() = screen(
        "onboarding_habits_no_reminders",
        OnboardingUiState(
            step = OnboardingStep.HABITS,
            habits = HabitsUiState(
                options = HabitCatalog().all.map { HabitOption(it.type, selected = it.defaultEnabled) },
                remind = false,
                zone = SampleData.zone,
            ),
        ),
    )
}

/** Sample states shared by the screenshot and UI tests. */
internal object Previews {
    val needsPermission = ConnectionUiState(
        status = HealthConnectStatus.AVAILABLE,
        permissions = FakeHealthConnectGateway.NONE_GRANTED,
    )

    val connected = ConnectionUiState(
        status = HealthConnectStatus.AVAILABLE,
        permissions = FakeHealthConnectGateway.ALL_GRANTED.copy(optionalGranted = setOf("android.permission.health.READ_HEART_RATE")),
        source = SourceCheck.Sharing(SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE)),
    )

    val garminMissing = ConnectionUiState(
        status = HealthConnectStatus.AVAILABLE,
        permissions = FakeHealthConnectGateway.ALL_GRANTED.copy(
            historyFeatureAvailable = false,
            historyGranted = false,
            backgroundGranted = false,
        ),
        source = SourceCheck.Missing(
            SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE),
            listOf("com.fitbit.FitbitMobile", "com.sec.android.app.shealth"),
        ),
    )

    val nights: NightsSummary get() = summarize(SampleData.nights(count = 42), SampleData.today)

    val sleepNeed = SleepNeedUiState(
        needMinutes = 480,
        wake = LocalTime.of(7, 0),
        date = SampleData.today,
        zone = SampleData.zone,
    )

    val habits: HabitsUiState
        get() {
            val insights = SampleData.insights()
            val times = insights.habits.associate { it.type to it.time }
            return HabitsUiState(
                options = HabitCatalog().all.map { HabitOption(it.type, selected = it.defaultEnabled, time = times[it.type]) },
                remind = true,
                zone = insights.zone,
            )
        }
}
