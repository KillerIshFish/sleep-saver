package com.sleepsaver.feature.review

import com.sleepsaver.core.data.hooks.MorningReviewNotifier
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.ManualNap
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.night.NightAssembler
import com.sleepsaver.core.testing.SampleData
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

class FakeTimeProvider(
    var now: Instant = SampleData.now,
    private val zone: ZoneId = SampleData.zone,
) : TimeProvider {
    override fun now(): Instant = now
    override fun zone(): ZoneId = zone
    override val minuteTicks: Flow<Instant> get() = flowOf(now)
}

class FakeMorningReviewNotifier : MorningReviewNotifier {
    var cancels = 0
    val arrived = mutableListOf<LocalDate>()
    override fun onLastNightArrived(wakeDate: LocalDate) {
        arrived += wakeDate
    }
    override fun scheduleFallbackReminder(at: Instant) = Unit
    override fun cancel() {
        cancels++
    }
}

/** One simple night in New York (EDT, GMT−4): 23:00 → 07:00 with 30 min awake at 02:00. */
object TestNight {
    val zone: ZoneId = ZoneId.of("America/New_York")
    val offset: ZoneOffset = ZoneOffset.ofHours(-4)
    val wakeDate: LocalDate = LocalDate.of(2026, 10, 6)
    val now: Instant = at(wakeDate, 10, 20)

    fun at(date: LocalDate, hour: Int, minute: Int = 0): Instant = date.atTime(hour, minute).atZone(zone).toInstant()

    val start: Instant = at(wakeDate.minusDays(1), 23)
    val end: Instant = at(wakeDate, 7)

    val session = RawSleepSession(
        id = "s1",
        origin = SleepConfig.GARMIN_CONNECT_PACKAGE,
        start = start,
        end = end,
        startOffset = offset,
        endOffset = offset,
        stages = listOf(
            SleepStage(start, at(wakeDate, 2), SleepStageType.LIGHT),
            SleepStage(at(wakeDate, 2), at(wakeDate, 2, 30), SleepStageType.AWAKE),
            SleepStage(at(wakeDate, 2, 30), end, SleepStageType.DEEP),
        ),
    )

    fun nights(
        sessions: List<RawSleepSession> = listOf(session),
        overrides: List<NightOverride> = emptyList(),
        manualNaps: List<ManualNap> = emptyList(),
    ): List<Night> = NightAssembler().assemble(
        sessions,
        SourceSelection.Single(SleepConfig.GARMIN_CONNECT_PACKAGE),
        overrides = overrides,
        manualNaps = manualNaps,
    )

    fun night(overrides: List<NightOverride> = emptyList()): Night = nights(overrides = overrides).single { it.wakeDate == wakeDate }
}
