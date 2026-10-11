package com.sleepsaver.feature.tonight

import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.testing.FakeInsightsRepository
import com.sleepsaver.core.testing.FakeJournalRepository
import com.sleepsaver.core.testing.FakeNightController
import com.sleepsaver.core.testing.FakeNightLogRepository
import com.sleepsaver.core.testing.FakeNightStepsRepository
import com.sleepsaver.core.testing.FakePatternsRepository
import com.sleepsaver.core.testing.FakeScheduleRepository
import com.sleepsaver.core.testing.FakeSettingsRepository
import com.sleepsaver.core.testing.FakeTimeProvider
import com.sleepsaver.core.testing.PlanSamples
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalTime

/**
 * Every repository and hook the Tonight ViewModels read, as core:testing fakes in one coherent evening
 * ([TonightSamples]): the week plan, its planned alarms after [now], tonight's Night steps, SampleData's nights and a
 * fixed 23:00 bedtime. All clocks follow [time].
 */
internal class TonightFakes(
    now: Instant = TonightSamples.at(14, 0),
    week: WeekPlan = TonightSamples.week,
    oneOffs: OneOffs = OneOffs(),
    insights: InsightsState? = null,
    session: NightStepsSession? = null,
) {
    val time = FakeTimeProvider(now, TonightSamples.zone)
    val insights = FakeInsightsRepository(insights ?: TonightSamples.insights(now, week, oneOffs))
    val schedule = FakeScheduleRepository(
        week = week,
        oneOffs = oneOffs,
        usualWake = DayOfWeek.entries.associateWith { if (it.value >= 6) LocalTime.of(8, 10) else LocalTime.of(6, 45) },
        upcoming = TonightSamples.alarms(now, week, oneOffs),
    )
    val nightSteps = FakeNightStepsRepository(
        tonight = session ?: PlanSamples.nightStepsSession(nightOf = TonightSamples.today),
        activities = PlanSamples.activities(),
        adherence = PlanSamples.adherence(TonightSamples.today),
        clock = { time.now() },
    )
    val journal = FakeJournalRepository(clock = { time.now() }, zone = TonightSamples.zone)
    val nightLog = FakeNightLogRepository(clock = { time.now() })
    val patterns = FakePatternsRepository(
        keel = PlanSamples.keelReady(),
        planCheck = PlanSamples.planCheck(warn = false),
        debtPreviewInputs = TonightSamples.debtInputs(week),
    )
    val nightController = FakeNightController(clock = { time.now() }, zone = TonightSamples.zone)
    val settings = FakeSettingsRepository(AppSettings(sleep = TonightSamples.sleepSettings.copy(week = week, oneOffs = oneOffs)))
    val endedNights = EndedNights()

    /** The night clock the Week plan and Alarm check read, from these fakes. */
    fun clockSource(): NightClockSource =
        NightClockSource(insights, schedule, nightSteps, nightLog, patterns, nightController, time, endedNights)
}
