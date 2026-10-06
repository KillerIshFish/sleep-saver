package com.sleepsaver.feature.onboarding

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.healthconnect.HealthConnectStatus
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.testing.FakeHealthConnectGateway
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.feature.onboarding.connect.ConnectStage
import com.sleepsaver.feature.onboarding.connect.ConnectionUiState
import com.sleepsaver.feature.onboarding.connect.Grant
import com.sleepsaver.feature.onboarding.connect.SourceCheck
import com.sleepsaver.feature.onboarding.connect.background
import com.sleepsaver.feature.onboarding.connect.heartData
import com.sleepsaver.feature.onboarding.connect.history
import com.sleepsaver.feature.onboarding.connect.sourceCheck
import com.sleepsaver.feature.onboarding.setup.SyncAgo
import com.sleepsaver.feature.onboarding.setup.syncAgo
import com.sleepsaver.feature.onboarding.ui.appLabel
import com.sleepsaver.feature.onboarding.ui.fallbackLabel
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

/** Plain JVM tests of the pure pieces: stage, grants, source check, wake median, summaries, "ago", labels. */
class OnboardingLogicTest {

    private val garmin = SleepConfig.GARMIN_CONNECT_PACKAGE
    private val fitbit = "com.fitbit.FitbitMobile"
    private val oura = "com.ouraring.oura"

    // ------------------------------------------------------------ stage

    @Test
    fun `stage follows status, then permissions`() {
        assertThat(ConnectionUiState().stage).isEqualTo(ConnectStage.CHECKING)
        assertThat(ConnectionUiState(status = HealthConnectStatus.AVAILABLE).stage).isEqualTo(ConnectStage.CHECKING)
        val none = FakeHealthConnectGateway.NONE_GRANTED
        assertThat(ConnectionUiState(status = HealthConnectStatus.AVAILABLE, permissions = none).stage).isEqualTo(ConnectStage.NEEDS_PERMISSION)
        assertThat(ConnectionUiState(status = HealthConnectStatus.AVAILABLE, permissions = none, permissionAsked = true).stage)
            .isEqualTo(ConnectStage.DENIED)
        assertThat(ConnectionUiState(status = HealthConnectStatus.AVAILABLE, permissions = FakeHealthConnectGateway.ALL_GRANTED).stage)
            .isEqualTo(ConnectStage.CONNECTED)
        assertThat(ConnectionUiState(status = HealthConnectStatus.NOT_SUPPORTED, permissionAsked = true).stage).isEqualTo(ConnectStage.NOT_SUPPORTED)
    }

    @Test
    fun `optional grants distinguish refused from unavailable`() {
        val all = FakeHealthConnectGateway.ALL_GRANTED
        assertThat(all.history).isEqualTo(Grant.GRANTED)
        assertThat(all.background).isEqualTo(Grant.GRANTED)
        assertThat(all.heartData).isEqualTo(Grant.NOT_GRANTED)
        assertThat(all.copy(optionalGranted = setOf("hr")).heartData).isEqualTo(Grant.GRANTED)
        assertThat(all.copy(historyGranted = false).history).isEqualTo(Grant.NOT_GRANTED)
        assertThat(all.copy(historyFeatureAvailable = false).history).isEqualTo(Grant.UNAVAILABLE)
        assertThat(all.copy(backgroundGranted = false).background).isEqualTo(Grant.NOT_GRANTED)
        assertThat(all.copy(backgroundFeatureAvailable = false).background).isEqualTo(Grant.UNAVAILABLE)
    }

    // ------------------------------------------------------------ source check

    @Test
    fun `Garmin selected and sharing`() {
        assertThat(sourceCheck(listOf(fitbit, garmin), SourceSelection.Single(garmin)))
            .isEqualTo(SourceCheck.Sharing(SourceSelection.Single(garmin)))
    }

    @Test
    fun `Garmin missing puts the alternatives in order`() {
        assertThat(sourceCheck(listOf(oura, fitbit, fitbit), SourceSelection.Single(garmin)))
            .isEqualTo(SourceCheck.Missing(SourceSelection.Single(garmin), listOf(fitbit, oura)))
    }

    @Test
    fun `another app selected but silent suggests Garmin first`() {
        assertThat(sourceCheck(listOf(oura, garmin), SourceSelection.Single(fitbit)))
            .isEqualTo(SourceCheck.Missing(SourceSelection.Single(fitbit), listOf(garmin, oura)))
    }

    @Test
    fun `all sources need at least one app, and the demo origin doesn't count`() {
        assertThat(sourceCheck(listOf(fitbit), SourceSelection.All)).isEqualTo(SourceCheck.Sharing(SourceSelection.All))
        assertThat(sourceCheck(listOf(SleepConfig.DEMO_ORIGIN, ""), SourceSelection.All))
            .isEqualTo(SourceCheck.Missing(SourceSelection.All, emptyList()))
    }

    // ------------------------------------------------------------ nights

    private fun night(wakeDate: LocalDate, wake: LocalTime, offset: ZoneOffset = ZoneOffset.ofHours(-4), slept: Long = 450): Night {
        val wokeUp = wakeDate.atTime(wake).toInstant(offset)
        return SampleData.nights().first().copy(
            wakeDate = wakeDate,
            wokeUp = wokeUp,
            wokeUpOffset = offset,
            fellAsleep = wokeUp.minus(Duration.ofMinutes(slept + 20)),
            totalSleep = Duration.ofMinutes(slept),
        )
    }

    @Test
    fun `median wake uses the recorded offset and snaps to 5 minutes`() {
        val day = LocalDate.of(2026, 10, 1)
        val nights = listOf(
            night(day, LocalTime.of(6, 41)),
            night(day.plusDays(1), LocalTime.of(6, 52)),
            night(day.plusDays(2), LocalTime.of(9, 30)),
        )
        assertThat(medianWake(nights)).isEqualTo(LocalTime.of(6, 50))
    }

    @Test
    fun `median wake of an even count averages the middle two`() {
        val day = LocalDate.of(2026, 10, 1)
        val nights = listOf(night(day, LocalTime.of(6, 0)), night(day.plusDays(1), LocalTime.of(7, 0)))
        assertThat(medianWake(nights)).isEqualTo(LocalTime.of(6, 30))
    }

    @Test
    fun `median wake looks only at the latest nights`() {
        val day = LocalDate.of(2026, 9, 1)
        val old = (0 until 20).map { night(day.plusDays(it.toLong()), LocalTime.of(5, 0)) }
        val recent = (20 until 34).map { night(day.plusDays(it.toLong()), LocalTime.of(8, 0)) }
        assertThat(medianWake(old + recent)).isEqualTo(LocalTime.of(8, 0))
        assertThat(medianWake(emptyList())).isNull()
    }

    @Test
    fun `summary counts present nights and lays out the last two weeks`() {
        val today = LocalDate.of(2026, 10, 6)
        val nights = listOf(
            night(today, LocalTime.of(7, 0), slept = 420),
            night(today.minusDays(2), LocalTime.of(7, 0), slept = 480),
            night(today.minusDays(30), LocalTime.of(7, 0)),
        )
        val summary = summarize(nights, today)
        assertThat(summary.count).isEqualTo(3)
        assertThat(summary.first).isEqualTo(today.minusDays(30))
        assertThat(summary.last).isEqualTo(today)
        assertThat(summary.recent).hasSize(14)
        assertThat(summary.recent[13]).isEqualTo(Duration.ofMinutes(420))
        assertThat(summary.recent[12]).isNull()
        assertThat(summary.recent[11]).isEqualTo(Duration.ofMinutes(480))
    }

    // ------------------------------------------------------------ last sync

    @Test
    fun `last sync reads as just now, minutes, hours, then a date`() {
        val now = Instant.parse("2026-10-06T14:00:00Z")
        val zone = ZoneOffset.UTC
        assertThat(syncAgo(now.minusSeconds(20), now, zone)).isEqualTo(SyncAgo.JustNow)
        assertThat(syncAgo(now.plusSeconds(20), now, zone)).isEqualTo(SyncAgo.JustNow)
        assertThat(syncAgo(now.minusSeconds(12 * 60), now, zone)).isEqualTo(SyncAgo.Minutes(12))
        assertThat(syncAgo(now.minusSeconds(3 * 3600 + 10), now, zone)).isEqualTo(SyncAgo.Hours(3))
        val old = now.minus(Duration.ofDays(2))
        assertThat(syncAgo(old, now, zone)).isEqualTo(SyncAgo.On(old, LocalDate.of(2026, 10, 6)))
    }

    // ------------------------------------------------------------ app labels

    @Test
    fun `app labels prefer known names, then fall back to the package`() {
        assertThat(appLabel(garmin, null)).isEqualTo("Garmin Connect")
        assertThat(appLabel(fitbit, null)).isEqualTo("Fitbit")
        assertThat(appLabel("com.example.sleepcoach", null)).isEqualTo("Sleepcoach")
        assertThat(fallbackLabel("com.acme.android.app")).isEqualTo("Acme")
        assertThat(fallbackLabel("com")).isEqualTo("Com")
    }
}
