package com.sleepsaver.feature.sleep

import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.testing.SampleData
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Synced 12 minutes before SampleData's "now". */
internal val sampleLastSync: Instant = SampleData.now.minus(Duration.ofMinutes(12))

/** The home screen's ready state for sample insights, built the way the ViewModel builds it. */
internal fun ready(
    insights: DailyInsights = SampleData.insights(),
    settings: AppSettings = AppSettings(onboardingDone = true),
    sync: SyncState = SyncState(lastSuccessAt = sampleLastSync),
    pendingReview: LocalDate? = null,
    source: SourceLabel = SourceLabel.Garmin,
): SleepUiState.Ready = readyState(insights, settings, sync, SampleData.habits, pendingReview, source)
