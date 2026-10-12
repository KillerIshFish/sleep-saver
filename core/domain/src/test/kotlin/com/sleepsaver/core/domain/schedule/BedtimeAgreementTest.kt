package com.sleepsaver.core.domain.schedule

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.insights.InsightsEngine
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.DailyInsights
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.testing.Fx
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import org.junit.jupiter.api.Test

/**
 * One bedtime per evening (docs/PLAN.md §3.5): the energy schedule's B, the Bedtime habit (Energy's "Bedtime" label,
 * Sleep home's "Next", the reminder), the wind-down where Night steps start, and [ScheduleTimes.bedtime] (Tonight, the
 * week plan, Night steps without nights, the alarm's planned bedtime) agree under every bedtime rule.
 *
 * Regression: the Bedtime habit sat at the melatonin window's start, so Energy read "Bedtime 10:32 PM" while Tonight,
 * Night steps and the Day plan read "bedtime 11:02 PM" for the same Sunday evening.
 */
class BedtimeAgreementTest {

    private val engine = InsightsEngine()
    private val defaultHabits = HabitCatalog().defaultSettings()
    private val times = ScheduleTimes()
    private val zone: ZoneId = ZoneId.of("America/New_York")

    /** Sunday 2026-10-11, the evening before a Monday. */
    private val sunday = LocalDate.of(2026, 10, 11)

    /** Three weeks asleep 23:17 → 06:50: FROM_DATA learns B = 23:17 − 15 min = 23:02. */
    private val nights: List<Night> = (0L..20L).map { Fx.nightInZone(sunday.minusDays(it), "23:17", "06:50", zone) }

    private fun at(date: LocalDate, time: String): Instant = ZonedDateTime.of(date, LocalTime.parse(time), zone).toInstant()

    private fun insights(
        settings: UserSleepSettings,
        now: Instant = at(sunday, "20:37"),
        habitSettings: List<HabitSettings> = defaultHabits,
    ): DailyInsights = engine.compute(nights, settings, habitSettings, now, zone)

    /** Every place an evening's bedtime is read from, for the evening [insights] shows. */
    private fun assertOneBedtime(insights: DailyInsights, settings: UserSleepSettings): Instant {
        val schedule = insights.schedule
        val bedtime = schedule.targetBedtime
        val evening = settings.week.resolve(schedule.date, settings.oneOffs).evening

        assertThat(times.bedtime(schedule.date, zone, nights, settings.sleepNeed, settings.timing).first).isEqualTo(bedtime)
        assertThat(insights.habits.single { it.type == HabitType.BEDTIME }.time).isEqualTo(bedtime)
        // Night steps run from the wind-down's start to B; the melatonin window is centred on B.
        assertThat(schedule.phase(PhaseType.WIND_DOWN)!!.start).isEqualTo(times.windDownStart(bedtime, evening))
        val melatonin = schedule.phase(PhaseType.MELATONIN_WINDOW)!!
        assertThat(Duration.between(melatonin.start, bedtime)).isEqualTo(Duration.between(bedtime, melatonin.end))
        return bedtime
    }

    @Test
    fun `the Bedtime habit is the bedtime every screen shows, not the melatonin window's start`() {
        val settings = UserSleepSettings()

        val insights = insights(settings)

        assertThat(assertOneBedtime(insights, settings)).isEqualTo(at(sunday, "23:02"))
        assertThat(insights.habits.single { it.type == HabitType.BEDTIME }.windowEnd).isEqualTo(at(sunday, "23:32"))
        assertThat(insights.schedule.phase(PhaseType.MELATONIN_WINDOW)!!.start).isEqualTo(at(sunday, "22:32"))
    }

    @Test
    fun `every bedtime rule gives one bedtime`() {
        val mondayAt0530 = Fx.weekPlan(DayOfWeek.MONDAY to LocalTime.of(5, 30))
        val sundaySetBedtime = WeekPlan.DEFAULT.let { plan ->
            plan.with(plan.day(DayOfWeek.SUNDAY).let { it.copy(evening = it.evening.copy(bedtime = LocalTime.of(0, 15))) })
        }
        listOf(
            UserSleepSettings(),
            UserSleepSettings(personalLatency = Duration.ofMinutes(28)),
            UserSleepSettings(bedtimeMode = BedtimeMode.SLEEP_NEED, sleepNeed = Duration.ofMinutes(8 * 60 + 20)),
            UserSleepSettings(bedtimeMode = BedtimeMode.SLEEP_NEED, week = mondayAt0530, personalLatency = Duration.ofMinutes(20)),
            UserSleepSettings(bedtimeMode = BedtimeMode.FIXED, bedtime = LocalTime.of(22, 40)),
            UserSleepSettings(week = sundaySetBedtime),
        ).forEach { settings -> assertOneBedtime(insights(settings), settings) }
    }

    @Test
    fun `a stored Bedtime habit offset can't bring a second bedtime back`() {
        // Older builds let Bedtime shift ±60 min; Energy's "Bedtime", Sleep home's "Next" and the reminder would then
        // have read B ± that under the same name while Tonight, Night steps and the alarm read B.
        listOf(Duration.ofMinutes(-30), Duration.ofMinutes(45)).forEach { stored ->
            val habits = defaultHabits.map { if (it.type == HabitType.BEDTIME) it.copy(offset = stored) else it }
            listOf(UserSleepSettings(), UserSleepSettings(bedtimeMode = BedtimeMode.FIXED, bedtime = LocalTime.of(22, 40))).forEach { settings ->
                val insights = insights(settings, habitSettings = habits)

                assertOneBedtime(insights, settings)
                // Before B, the next habit up is Bedtime at B itself.
                val next = insights(settings, now = insights.schedule.targetBedtime - Duration.ofMinutes(5), habitSettings = habits).nextHabit
                assertThat(next?.type).isEqualTo(HabitType.BEDTIME)
                assertThat(next?.time).isEqualTo(insights.schedule.targetBedtime)
            }
        }
    }

    @Test
    fun `after midnight the evening still shown keeps its one bedtime`() {
        val settings = UserSleepSettings(week = WeekPlan.DEFAULT.let { plan ->
            plan.with(plan.day(DayOfWeek.SUNDAY).let { it.copy(evening = it.evening.copy(bedtime = LocalTime.of(0, 45))) })
        })

        val insights = insights(settings, now = at(sunday.plusDays(1), "00:20"))

        assertThat(insights.schedule.date).isEqualTo(sunday)
        assertThat(assertOneBedtime(insights, settings)).isEqualTo(at(sunday.plusDays(1), "00:45"))
    }
}
