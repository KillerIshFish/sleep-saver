package com.sleepsaver.feature.sleep

import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.SampleData
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Synced 12 minutes before SampleData's "now". */
internal val sampleLastSync: Instant = SampleData.now.minus(Duration.ofMinutes(12))

/** Tomorrow's 6:45 AM Surfacing alarm (window from 6:15) and tonight's Night steps from 9:30 PM (bedtime 11 PM). */
internal val sampleAlarms: List<PlannedAlarm> = listOf(PlanSamples.plannedAlarm())
internal val sampleSteps: NightStepsSession = PlanSamples.nightStepsSession()

/** The home screen's ready state for sample insights, built the way the ViewModel builds it. */
internal fun ready(
    insights: DailyInsights = SampleData.insights(),
    settings: AppSettings = AppSettings(onboardingDone = true),
    sync: SyncState = SyncState(lastSuccessAt = sampleLastSync),
    pendingReview: LocalDate? = null,
    source: SourceLabel = SourceLabel.Garmin,
    alarms: List<PlannedAlarm> = sampleAlarms,
    steps: NightStepsSession? = sampleSteps,
    anchoredAt: Instant? = null,
    morningStop: Instant? = null,
): SleepUiState.Ready = readyState(
    insights, settings, sync, SampleData.habits, pendingReview, source,
    alarms = alarms,
    steps = steps,
    anchoredAt = anchoredAt,
    morningStop = morningStop,
)
