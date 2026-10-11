package com.sleepsaver.core.data.mapping

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.DayPlanEntity
import com.sleepsaver.core.data.db.DayRoutineItemEntity
import com.sleepsaver.core.data.db.PlanOverrideEntity
import com.sleepsaver.core.data.db.PlanPauseEntity
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.AlarmSettings
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.DayPlan
import com.sleepsaver.core.domain.schedule.EveningPlan
import com.sleepsaver.core.domain.schedule.MorningPlan
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.SnoozeSettings
import com.sleepsaver.core.domain.schedule.SnoozeStyle
import com.sleepsaver.core.domain.schedule.WakeStyle
import com.sleepsaver.core.domain.schedule.WeekPlan
import org.junit.Test
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

class PlanMappersTest {

    private val config = SleepConfig.DEFAULT

    private val tuesday = DayPlan(
        day = DayOfWeek.TUESDAY,
        morning = MorningPlan(
            wakeTime = LocalTime.of(6, 45),
            alarm = AlarmSettings(
                mode = AlarmMode.SURFACING,
                window = Duration.ofMinutes(25),
                style = WakeStyle.QUIET_BED,
                tone = AlarmTone.WARM_RISE,
                volume = 0.6f,
                snooze = SnoozeSettings(SnoozeStyle.FIXED, Duration.ofMinutes(6), 2, Duration.ofMinutes(15)),
            ),
        ),
        evening = EveningPlan(bedtime = LocalTime.of(0, 30), nightStepsOn = false, windDownLength = Duration.ofMinutes(90), routine = listOf(4L, 2L)),
    )

    @Test
    fun `a day plan round-trips, its own routine via the item rows`() {
        val entity = tuesday.toEntity()

        assertThat(entity).isEqualTo(
            DayPlanEntity(2, 405, "SURFACING", 25, "QUIET_BED", "WARM_RISE", 60, "FIXED", 6, 2, 15, 30, false, 90, true),
        )
        assertThat(tuesday.routineItems()).containsExactly(DayRoutineItemEntity(2, 4, 0), DayRoutineItemEntity(2, 2, 1)).inOrder()
        assertThat(entity.toDomain(routineIds = listOf(4L, 2L))).isEqualTo(tuesday)
    }

    @Test
    fun `the default week round-trips`() {
        for (day in WeekPlan.DEFAULT.days) {
            assertThat(day.toEntity().toDomain(routineIds = null)).isEqualTo(day)
        }
    }

    @Test
    fun `without its own routine the evening uses the every-evening list`() {
        val entity = tuesday.toEntity().copy(ownRoutine = false)

        assertThat(entity.toDomain(routineIds = listOf(4L)).evening.routine).isNull()
        assertThat(tuesday.toEntity().toDomain(routineIds = null).evening.routine).isEmpty()
    }

    @Test
    fun `ranges are clamped and unknown names fall back`() {
        val entity = DayPlanEntity(
            dayOfWeek = 7, wakeMinOfDay = 1_445, alarmMode = "LOUDEST", windowMin = 90, wakeStyle = "LOUD", tone = "BEEP",
            volumePct = 3, snoozeStyle = "RANDOM", snoozeFixedMin = 60, snoozeFixedCount = 0, overrunMin = -5,
            bedtimeMinOfDay = null, nightStepsOn = true, windDownMin = 10, ownRoutine = false,
        )

        val plan = entity.toDomain(routineIds = null)

        val alarm = plan.morning.alarm
        assertThat(plan.day).isEqualTo(DayOfWeek.SUNDAY)
        assertThat(plan.morning.wakeTime).isEqualTo(LocalTime.of(0, 5))
        assertThat(alarm.mode).isEqualTo(AlarmMode.OFF)
        assertThat(alarm.style).isEqualTo(WakeStyle.STANDARD)
        assertThat(alarm.tone).isEqualTo(AlarmTone.SOFT_CHIMES)
        assertThat(alarm.snooze.style).isEqualTo(SnoozeStyle.TAPERING)
        assertThat(alarm.window).isEqualTo(config.alarm.windowMax)
        assertThat(alarm.volume).isEqualTo(config.alarm.minVolume)
        assertThat(alarm.snooze.fixedLength).isEqualTo(config.alarm.fixedSnoozeMax)
        assertThat(alarm.snooze.fixedCount).isEqualTo(config.alarm.fixedSnoozeCountMin)
        assertThat(alarm.snooze.overrun).isEqualTo(Duration.ZERO)
        assertThat(plan.evening.windDownLength).isEqualTo(config.week.windDownMinLength)
    }

    @Test
    fun `volume is stored as a clamped percent`() {
        val loud = tuesday.copy(morning = tuesday.morning.copy(alarm = tuesday.morning.alarm.copy(volume = 1.4f)))
        val quiet = tuesday.copy(morning = tuesday.morning.copy(alarm = tuesday.morning.alarm.copy(volume = 0f)))

        assertThat(loud.toEntity().volumePct).isEqualTo(100)
        assertThat(quiet.toEntity().volumePct).isEqualTo(10)
    }

    @Test
    fun `a repeated routine id is stored once`() {
        val plan = tuesday.copy(evening = tuesday.evening.copy(routine = listOf(3L, 3L, 1L)))

        assertThat(plan.routineItems().map { it.activityId to it.position }).containsExactly(3L to 0, 1L to 1).inOrder()
    }

    @Test
    fun `overrides round-trip and unreadable ones are dropped`() {
        val at = instant("2026-10-06T08:00:00Z")
        val offset = ZoneOffset.ofHours(-4)
        val dayOff = DateOverride(LocalDate.of(2026, 10, 9), OverrideKind.DAY_OFF)
        val justOnce = DateOverride(LocalDate.of(2026, 10, 10), OverrideKind.JUST_ONCE, LocalTime.of(5, 30), AlarmMode.FIXED)

        assertThat(dayOff.toEntity(at, offset)).isEqualTo(PlanOverrideEntity("2026-10-09", "DAY_OFF", null, null, at.toEpochMilli(), -14_400))
        assertThat(dayOff.toEntity(at, offset).toDomainOrNull()).isEqualTo(dayOff)
        assertThat(justOnce.toEntity(at, offset).toDomainOrNull()).isEqualTo(justOnce)

        assertThat(PlanOverrideEntity("2026-10-10", "NEVER", null, null, 0, 0).toDomainOrNull()).isNull()
        assertThat(PlanOverrideEntity("2026-10-10", "JUST_ONCE", null, "FIXED", 0, 0).toDomainOrNull()).isNull()
        assertThat(PlanOverrideEntity("2026-10-10", "JUST_ONCE", 330, "LOUD", 0, 0).toDomainOrNull()?.mode).isEqualTo(AlarmMode.OFF)
    }

    @Test
    fun `the pause round-trips and a backwards one is dropped`() {
        val pause = PlanPause(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 20))
        val entity = pause.toEntity(instant("2026-10-06T08:00:00Z"), ZoneOffset.UTC)

        assertThat(entity.id).isEqualTo(1)
        assertThat(entity.toDomainOrNull()).isEqualTo(pause)
        assertThat(PlanPauseEntity(fromDate = "2026-10-20", untilDate = "2026-10-10", createdAtEpochMs = 0, offsetSec = 0).toDomainOrNull()).isNull()
    }
}
