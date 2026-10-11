package com.sleepsaver.feature.tonight

import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.DebtPreviewInputs
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.domain.alarm.AlarmPlanner
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.feature.tonight.tonight.TonightInputs
import com.sleepsaver.feature.tonight.tonight.TonightLocal
import com.sleepsaver.feature.tonight.tonight.TonightUiState
import com.sleepsaver.feature.tonight.tonight.tonightUiState
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * One coherent evening for the Tonight tests: SampleData's nights, PlanSamples' week plan (Mon–Fri Surfacing at
 * 6:45, window 30) and a fixed 23:00 bedtime, so the schedule's wind-down (21:30) and the Night steps session agree.
 * Today is Tue 2026-10-06 in New York.
 */
internal object TonightSamples {
    val config: SleepConfig = SleepConfig.DEFAULT
    val zone: ZoneId get() = SampleData.zone
    val today: LocalDate get() = SampleData.today
    val week: WeekPlan = PlanSamples.weekPlan()
    val sleepSettings = UserSleepSettings(week = week, bedtimeMode = BedtimeMode.FIXED, bedtime = LocalTime.of(23, 0))
    val nights: List<Night> by lazy { SampleData.nights() }

    fun at(hour: Int, minute: Int = 0, date: LocalDate = today): Instant =
        ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant()

    fun insights(now: Instant, week: WeekPlan = this.week, oneOffs: OneOffs = OneOffs()): InsightsState.Ready =
        InsightsState.Ready(
            SampleData.insights(now = now, settings = sleepSettings.copy(week = week, oneOffs = oneOffs), nights = nights),
        )

    fun debtInputs(week: WeekPlan = this.week, nights: List<Night> = this.nights): DebtPreviewInputs =
        DebtPreviewInputs(nights, sleepSettings.copy(week = week), zone)

    /** The planned alarms after [now], as ScheduleRepository.upcomingAlarms would list them. */
    fun alarms(now: Instant, week: WeekPlan = this.week, oneOffs: OneOffs = OneOffs()): List<PlannedAlarm> =
        AlarmPlanner(config).upcoming(week, oneOffs, now, zone).map { occurrence ->
            PlanSamples.plannedAlarm(
                date = occurrence.date,
                time = occurrence.localTime,
                mode = occurrence.settings.mode,
                window = occurrence.settings.window,
            )
        }

    fun local(
        endedAt: Instant? = null,
        hushColor: HushColor = HushColor.PINK,
    ): TonightLocal = TonightLocal(
        hushColor = hushColor,
        hushTimer = config.hush.defaultTimer,
        hushVolume = config.hush.defaultVolume,
        catnapLength = config.napIdealLength,
        endedAt = endedAt,
    )

    fun inputs(
        now: Instant,
        night: NightState = NightState(),
        week: WeekPlan = this.week,
        oneOffs: OneOffs = OneOffs(),
        insights: InsightsState? = null,
        session: NightStepsSession? = null,
        settings: AppSettings = AppSettings(sleep = sleepSettings),
        marks: Map<LocalDate, Set<Mark>> = emptyMap(),
        lastNightList: TomorrowList? = null,
        local: TonightLocal = local(),
        alarms: List<PlannedAlarm> = alarms(now, week, oneOffs),
    ): TonightInputs {
        val state = insights ?: insights(now, week, oneOffs)
        val evening = (state as? InsightsState.Ready)?.insights?.schedule?.date ?: today
        return TonightInputs(
            insights = state,
            debtInputs = debtInputs(week),
            settings = settings,
            night = night,
            now = now,
            zone = zone,
            week = week,
            oneOffs = oneOffs,
            alarms = alarms,
            session = session ?: PlanSamples.nightStepsSession(nightOf = evening),
            adherence = PlanSamples.adherence(evening),
            marks = marks,
            lastNightList = lastNightList,
            todayEvent = null,
            local = local,
        )
    }

    fun ready(inputs: TonightInputs): TonightUiState.Ready = tonightUiState(inputs, config) as TonightUiState.Ready

    /** A writer whose app scope and IO dispatcher run eagerly on the test scheduler. */
    fun TestScope.writer(): TonightWriter {
        val eager = UnconfinedTestDispatcher(testScheduler)
        return TonightWriter(backgroundScope + eager, eager)
    }
}
