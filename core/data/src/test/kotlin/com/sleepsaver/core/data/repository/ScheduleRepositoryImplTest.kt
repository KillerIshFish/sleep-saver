package com.sleepsaver.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.AlarmEventEntity
import com.sleepsaver.core.data.db.AnchorEntity
import com.sleepsaver.core.data.db.PlanOverrideEntity
import com.sleepsaver.core.data.db.PlanPauseEntity
import com.sleepsaver.core.data.testing.FakeSettingsRepository
import com.sleepsaver.core.data.testing.FakeSleepRepository
import com.sleepsaver.core.data.testing.FakeTimeProvider
import com.sleepsaver.core.data.testing.inMemoryDatabase
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.data.testing.latencySource
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.WakeTimeMode
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.domain.schedule.OverrideKind
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.domain.schedule.WeekPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime

@RunWith(AndroidJUnit4::class)
class ScheduleRepositoryImplTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = inMemoryDatabase(context)
    private val config = SleepConfig.DEFAULT

    /** Tuesday 2026-10-06, 12:00 UTC (the device zone). */
    private val today = LocalDate.of(2026, 10, 6)
    private val time = FakeTimeProvider(now = instant("2026-10-06T12:00:00Z"))
    private val sleep = FakeSleepRepository()
    private val settings = FakeSettingsRepository()

    @After
    fun tearDown() = db.close()

    private fun TestScope.repository(
        settingsRepository: SettingsRepository = settings,
        sleepRepository: SleepRepository = sleep,
    ) = ScheduleRepositoryImpl(
        db,
        sleepRepository,
        settingsRepository,
        time,
        latencySource(sleepRepository, db, time),
        backgroundScope,
        StandardTestDispatcher(testScheduler),
    )

    private suspend fun ScheduleRepositoryImpl.weekdayAlarmsAt(time: LocalTime) = applyPreset(WeekPreset.WORK_WEEK, time)

    // ------------------------------------------------------------ week plan

    @Test
    fun `the week plan is seeded with the seven default days on first read`() = runTest {
        val repository = repository()

        assertThat(repository.weekPlan.first()).isEqualTo(WeekPlan.DEFAULT)
        assertThat(db.dayPlanDao().getAll().map { it.dayOfWeek }).containsExactly(1, 2, 3, 4, 5, 6, 7).inOrder()
    }

    @Test
    fun `seeding adds only the missing days`() = runTest {
        val repository = repository()
        repository.updateDay(DayOfWeek.FRIDAY) { it.copy(evening = it.evening.copy(bedtime = LocalTime.of(0, 30))) }

        val week = repository.weekPlan.first()

        assertThat(week.day(DayOfWeek.FRIDAY).evening.bedtime).isEqualTo(LocalTime.of(0, 30))
        assertThat(db.dayPlanDao().getAll()).hasSize(7)
    }

    @Test
    fun `turning an alarm on without a time stores the usual wake rounded to five minutes`() = runTest {
        settings.state.update { it.copy(sleep = it.sleep.copy(wakeTimeMode = WakeTimeMode.FIXED, typicalWake = LocalTime.of(6, 52))) }
        val repository = repository()

        repository.updateDay(DayOfWeek.MONDAY) { it.copy(morning = it.morning.copy(alarm = it.morning.alarm.copy(mode = AlarmMode.SURFACING))) }

        val monday = repository.weekPlan.first().day(DayOfWeek.MONDAY)
        assertThat(monday.morning.wakeTime).isEqualTo(LocalTime.of(6, 50))
        assertThat(monday.morning.alarm.mode).isEqualTo(AlarmMode.SURFACING)
    }

    @Test
    fun `updateDay clamps every range and never touches the day's routine`() = runTest {
        val repository = repository()

        repository.updateDay(DayOfWeek.WEDNESDAY) { day ->
            val alarm = day.morning.alarm
            day.copy(
                morning = day.morning.copy(
                    wakeTime = LocalTime.of(6, 0),
                    alarm = alarm.copy(
                        mode = AlarmMode.FIXED,
                        window = Duration.ofMinutes(90),
                        volume = 0.01f,
                        snooze = alarm.snooze.copy(fixedLength = Duration.ofMinutes(45), fixedCount = 9, overrun = Duration.ofMinutes(60)),
                    ),
                ),
                evening = day.evening.copy(windDownLength = Duration.ofMinutes(10), routine = listOf(1L, 2L)),
            )
        }

        val wednesday = repository.weekPlan.first().day(DayOfWeek.WEDNESDAY)
        val alarm = wednesday.morning.alarm
        assertThat(alarm.window).isEqualTo(config.alarm.windowMax)
        assertThat(alarm.volume).isWithin(0.001f).of(config.alarm.minVolume)
        assertThat(alarm.snooze.fixedLength).isEqualTo(config.alarm.fixedSnoozeMax)
        assertThat(alarm.snooze.fixedCount).isEqualTo(config.alarm.fixedSnoozeCountMax)
        assertThat(alarm.snooze.overrun).isEqualTo(config.alarm.overrunMax)
        assertThat(wednesday.evening.windDownLength).isEqualTo(config.week.windDownMinLength)
        assertThat(wednesday.evening.routine).isNull()
        assertThat(db.dayRoutineItemDao().getAll()).isEmpty()
    }

    @Test
    fun `a day's own routine keeps known activities in order and drops unknown ones`() = runTest {
        RoutineActivityStore(db, context).seedIfEmpty()
        val ids = db.routineActivityDao().getAll().map { it.id }
        val repository = repository()

        repository.setDayRoutine(DayOfWeek.WEDNESDAY, listOf(ids[2], 999L, ids[0], ids[2]))
        assertThat(repository.weekPlan.first().day(DayOfWeek.WEDNESDAY).evening.routine).containsExactly(ids[2], ids[0]).inOrder()

        repository.setDayRoutine(DayOfWeek.WEDNESDAY, null)
        assertThat(repository.weekPlan.first().day(DayOfWeek.WEDNESDAY).evening.routine).isNull()
        assertThat(db.dayRoutineItemDao().getForDay(DayOfWeek.WEDNESDAY.value)).isEmpty()
    }

    @Test
    fun `copying the evening copies its routine items, never onto the source day`() = runTest {
        RoutineActivityStore(db, context).seedIfEmpty()
        val ids = db.routineActivityDao().getAll().map { it.id }
        val repository = repository()
        repository.updateDay(DayOfWeek.MONDAY) { it.copy(evening = it.evening.copy(bedtime = LocalTime.of(22, 30), nightStepsOn = false)) }
        repository.setDayRoutine(DayOfWeek.MONDAY, listOf(ids[1]))
        val before = repository.weekPlan.first()

        repository.copyDay(DayOfWeek.MONDAY, setOf(DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.THURSDAY), setOf(DayPart.EVENING))

        val week = repository.weekPlan.first()
        assertThat(week.day(DayOfWeek.TUESDAY).evening).isEqualTo(before.day(DayOfWeek.MONDAY).evening)
        assertThat(week.day(DayOfWeek.THURSDAY).evening.routine).containsExactly(ids[1])
        assertThat(week.day(DayOfWeek.TUESDAY).morning).isEqualTo(before.day(DayOfWeek.TUESDAY).morning)
        assertThat(week.day(DayOfWeek.MONDAY)).isEqualTo(before.day(DayOfWeek.MONDAY))
        assertThat(week.day(DayOfWeek.WEDNESDAY)).isEqualTo(before.day(DayOfWeek.WEDNESDAY))
    }

    @Test
    fun `the work-week preset sets weekday alarms and frees the weekend`() = runTest {
        val repository = repository()

        repository.weekdayAlarmsAt(LocalTime.of(6, 32))

        val week = repository.weekPlan.first()
        for (day in listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)) {
            assertThat(week.day(day).morning.wakeTime).isEqualTo(LocalTime.of(6, 30))
            assertThat(week.day(day).morning.alarm.mode).isEqualTo(config.alarm.defaultMode)
        }
        assertThat(week.day(DayOfWeek.SATURDAY).morning.alarm.mode).isEqualTo(AlarmMode.OFF)
        assertThat(week.day(DayOfWeek.SUNDAY).morning.wakeTime).isNull()
    }

    // ------------------------------------------------------------ one-offs

    @Test
    fun `one-offs keep three days of history, prune older rows on write and drop an expired pause`() = runTest {
        val dao = db.planOverrideDao()
        dao.upsert(PlanOverrideEntity("2026-09-20", "DAY_OFF", null, null, 0, 0))
        dao.upsert(PlanOverrideEntity("2026-10-02", "DAY_OFF", null, null, 0, 0))
        dao.upsert(PlanOverrideEntity("2026-10-04", "DAY_OFF", null, null, 0, 0))
        db.planPauseDao().upsert(PlanPauseEntity(fromDate = "2026-09-01", untilDate = "2026-10-05", createdAtEpochMs = 0, offsetSec = 0))
        val repository = repository()

        val oneOffs = repository.oneOffs.first()
        assertThat(oneOffs.overrides.map { it.date }).containsExactly(LocalDate.of(2026, 10, 4))
        assertThat(oneOffs.pause).isNull()

        val once = DateOverride(LocalDate.of(2026, 10, 10), OverrideKind.JUST_ONCE, LocalTime.of(9, 0), AlarmMode.FIXED)
        repository.setOverride(once)
        assertThat(dao.getAll().map { it.date }).containsExactly("2026-10-02", "2026-10-04", "2026-10-10").inOrder()
        assertThat(repository.oneOffs.first { it.overrides.size == 2 }.overrides.last()).isEqualTo(once)

        repository.clearOverride(LocalDate.of(2026, 10, 10))
        assertThat(dao.getAll().map { it.date }).doesNotContain("2026-10-10")
    }

    @Test
    fun `a pause is stored until resumed and can't reach past sixty days`() = runTest {
        val repository = repository()
        val pause = PlanPause(today, today.plusDays(4))

        repository.setPause(pause)
        assertThat(repository.oneOffs.first { it.pause != null }.pause).isEqualTo(pause)

        val tooLong = runCatching { repository.setPause(PlanPause(today, today.plusDays(config.week.pauseMaxDays + 1))) }
        assertThat(tooLong.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)

        repository.setPause(null)
        assertThat(repository.oneOffs.first { it.pause == null }.pause).isNull()
    }

    // ------------------------------------------------------------ usual wake

    @Test
    fun `usual wake is Settings' time for every weekday, and falls back to it when nothing can be read`() = runTest {
        settings.state.update { it.copy(sleep = it.sleep.copy(wakeTimeMode = WakeTimeMode.FIXED, typicalWake = LocalTime.of(6, 15))) }
        assertThat(repository().usualWake.first()).isEqualTo(DayOfWeek.values().associateWith { LocalTime.of(6, 15) })

        val working: SettingsRepository = settings
        val brokenSettings = object : SettingsRepository by working {
            override val settings: Flow<AppSettings> = flow { throw IOException("store unreadable") }
        }
        val workingSleep: SleepRepository = sleep
        val brokenSleep = object : SleepRepository by workingSleep {
            override val nights: Flow<List<Night>> = flow { throw IllegalStateException("database closed") }
        }

        val fallback = repository(settingsRepository = brokenSettings, sleepRepository = brokenSleep).usualWake.first()

        assertThat(fallback).isEqualTo(DayOfWeek.values().associateWith { AppSettings().sleep.typicalWake })
    }

    // ------------------------------------------------------------ alarm plan

    @Test
    fun `the alarm plan arms the next alarm and mirrors the two after it`() = runTest {
        val repository = repository()
        repository.weekdayAlarmsAt(LocalTime.of(6, 30))

        val plan = repository.computeAlarmPlan(time.now, exclude = emptySet())

        val next = plan.next!!
        assertThat(next.occurrence.id).isEqualTo("2026-10-07T06:30")
        assertThat(next.occurrence.target).isEqualTo(instant("2026-10-07T06:30:00Z"))
        assertThat(plan.following.map { it.occurrence.id }).containsExactly("2026-10-08T06:30", "2026-10-09T06:30").inOrder()
        // Still planned: yesterday through the two-week horizon, whatever has rung.
        assertThat(plan.plannedIds).containsAtLeast("2026-10-05T06:30", "2026-10-06T06:30", "2026-10-13T06:30", "2026-10-20T06:30")
        assertThat(plan.computedAt).isEqualTo(time.now)
        // No nights: Settings' 23:00 bedtime, 15 min to fall asleep, 7 h 15 min of opportunity → the plain 30-min window.
        assertThat(next.plannedBedtime).isEqualTo(instant("2026-10-06T23:00:00Z"))
        assertThat(next.latency).isEqualTo(config.sleepLatency)
        assertThat(next.need).isEqualTo(settings.state.value.sleep.sleepNeed)
        assertThat(next.debtStatus).isNull()
        assertThat(next.window.debtAware).isFalse()
        assertThat(next.window.start).isEqualTo(instant("2026-10-07T06:00:00Z"))
    }

    @Test
    fun `handled occurrences and the ones the scheduler excludes are never planned again`() = runTest {
        val repository = repository()
        repository.weekdayAlarmsAt(LocalTime.of(6, 30))
        // Wednesday's alarm already rang early and was stopped.
        db.alarmEventDao().upsert(plannedEvent("2026-10-07", "2026-10-07T06:30", outcome = "STOPPED"))

        val plan = repository.computeAlarmPlan(time.now, exclude = setOf("2026-10-08T06:30"))

        assertThat(plan.next!!.occurrence.id).isEqualTo("2026-10-09T06:30")
        assertThat(plan.following.map { it.occurrence.id }).doesNotContain("2026-10-07T06:30")
        assertThat(plan.plannedIds).containsAtLeast("2026-10-07T06:30", "2026-10-08T06:30")
        assertThat(repository.upcomingAlarms.first { it.isNotEmpty() }.map { it.occurrence.id }).doesNotContain("2026-10-07T06:30")
    }

    @Test
    fun `a morning whose alarm was stopped doesn't ring again at a new time, only at one set just for it`() = runTest {
        // Wednesday 06:40: the 06:30 alarm rang early and was stopped, then Wednesday moves to 07:15 (for next week).
        time.now = instant("2026-10-07T06:40:00Z")
        time.ticks.value = time.now
        val repository = repository()
        repository.weekdayAlarmsAt(LocalTime.of(6, 30))
        db.alarmEventDao().upsert(plannedEvent("2026-10-07", "2026-10-07T06:30", outcome = "STOPPED"))
        repository.updateDay(DayOfWeek.WEDNESDAY) { it.copy(morning = it.morning.copy(wakeTime = LocalTime.of(7, 15))) }

        val plan = repository.computeAlarmPlan(time.now, exclude = setOf("2026-10-07T06:30"))

        assertThat(plan.next!!.occurrence.id).isEqualTo("2026-10-08T06:30")
        val upcoming = repository.upcomingAlarms.first { it.isNotEmpty() }.map { it.occurrence.id }
        assertThat(upcoming.first()).isEqualTo("2026-10-08T06:30")
        assertThat(upcoming).contains("2026-10-14T07:15")

        // A just-once alarm the user sets for this morning still rings.
        repository.setOverride(DateOverride(LocalDate.of(2026, 10, 7), OverrideKind.JUST_ONCE, LocalTime.of(8, 0), AlarmMode.FIXED))
        assertThat(repository.computeAlarmPlan(time.now, emptySet()).next!!.occurrence.id).isEqualTo("2026-10-07T08:00")
    }

    @Test
    fun `a weekly alarm that already rang tomorrow is armed for the week after, not dropped`() = runTest {
        // Wednesday-only 06:30. It rang on Wednesday and was stopped, then the clock went back to Tuesday 20:55.
        time.now = instant("2026-10-06T20:55:00Z")
        time.ticks.value = time.now
        val repository = repository()
        repository.updateDay(DayOfWeek.WEDNESDAY) {
            it.copy(morning = it.morning.copy(wakeTime = LocalTime.of(6, 30), alarm = it.morning.alarm.copy(mode = AlarmMode.SURFACING)))
        }
        db.alarmEventDao().upsert(plannedEvent("2026-10-07", "2026-10-07T06:30", outcome = "STOPPED"))

        val plan = repository.computeAlarmPlan(time.now, exclude = setOf("2026-10-07T06:30"))

        // Wednesday the 7th doesn't ring again; the 14th (today + 8) is armed instead of nothing.
        assertThat(plan.next!!.occurrence.id).isEqualTo("2026-10-14T06:30")
        assertThat(plan.plannedIds).contains("2026-10-14T06:30")
        assertThat(repository.nextAlarm.first { it != null }!!.occurrence.id).isEqualTo("2026-10-14T06:30")
    }

    @Test
    fun `a snooze cancelled by moving the alarm leaves the morning to the new time`() = runTest {
        // Wednesday 06:20: the 06:30 alarm rang early and was snoozed; moving Wednesday to 07:00 cancelled the snooze.
        time.now = instant("2026-10-07T06:20:00Z")
        time.ticks.value = time.now
        val repository = repository()
        repository.weekdayAlarmsAt(LocalTime.of(6, 30))
        repository.updateDay(DayOfWeek.WEDNESDAY) { it.copy(morning = it.morning.copy(wakeTime = LocalTime.of(7, 0))) }
        db.alarmEventDao().upsert(plannedEvent("2026-10-07", "2026-10-07T06:30", outcome = "CANCELLED"))

        assertThat(repository.computeAlarmPlan(time.now, emptySet()).next!!.occurrence.id).isEqualTo("2026-10-07T07:00")
        assertThat(repository.nextAlarm.first { it != null }!!.occurrence.id).isEqualTo("2026-10-07T07:00")
    }

    @Test
    fun `an event still ringing doesn't count as handled`() = runTest {
        val repository = repository()
        repository.weekdayAlarmsAt(LocalTime.of(6, 30))
        db.alarmEventDao().upsert(plannedEvent("2026-10-07", "2026-10-07T06:30", outcome = null))

        assertThat(repository.computeAlarmPlan(time.now, emptySet()).next!!.occurrence.id).isEqualTo("2026-10-07T06:30")
    }

    @Test
    fun `the alarm plan throws when storage can't be read, instead of planning nothing`() = runTest {
        val repository = repository()
        repository.weekdayAlarmsAt(LocalTime.of(6, 30))
        val working: SettingsRepository = settings
        val brokenSettings = object : SettingsRepository by working {
            override suspend fun current(): AppSettings = throw IOException("store unreadable")
        }

        val settingsFailure = runCatching { repository(settingsRepository = brokenSettings).computeAlarmPlan(time.now, emptySet()) }
        db.close()
        val databaseFailure = runCatching { repository.computeAlarmPlan(time.now, emptySet()) }

        assertThat(settingsFailure.exceptionOrNull()).isInstanceOf(IOException::class.java)
        assertThat(databaseFailure.exceptionOrNull()).isNotNull()
    }

    @Test
    fun `a late Drop anchor makes tonight's window debt-aware`() = runTest {
        val repository = repository()
        repository.weekdayAlarmsAt(LocalTime.of(6, 30))
        // Settled at 01:30 for Wednesday's 06:30 alarm: 4 h 45 min of opportunity.
        db.anchorDao().insert(
            AnchorEntity(atEpochMs = instant("2026-10-07T01:30:00Z").toEpochMilli(), offsetSec = 0, wakeDate = "2026-10-07", source = "TONIGHT", wholeNightLog = false),
        )

        val window = repository.computeAlarmPlan(time.now, emptySet()).next!!.window

        assertThat(window.debtAware).isTrue()
        assertThat(window.length).isEqualTo(config.alarm.defaultWindow + config.alarm.debtAwareWidening)
        assertThat(repository.upcomingAlarms.first { it.isNotEmpty() }.first().window).isEqualTo(window)
    }

    @Test
    fun `upcoming alarms drop an alarm once its wake time passes`() = runTest {
        val repository = repository()
        repository.weekdayAlarmsAt(LocalTime.of(6, 30))
        assertThat(repository.nextAlarm.first { it != null }!!.occurrence.id).isEqualTo("2026-10-07T06:30")
        // Wednesday through next Thursday: upcomingLimit (7) of the two-week horizon's ten.
        assertThat(repository.upcomingAlarms.first { it.isNotEmpty() }.map { it.occurrence.date.dayOfMonth })
            .containsExactly(7, 8, 9, 12, 13, 14, 15).inOrder()

        time.ticks.value = instant("2026-10-07T07:00:00Z")

        assertThat(repository.nextAlarm.first { it?.occurrence?.id != "2026-10-07T06:30" }!!.occurrence.id).isEqualTo("2026-10-08T06:30")
    }

    @Test
    fun `an unreadable anchor row reads as no anchors instead of breaking the upcoming alarms`() = runTest {
        val repository = repository()
        repository.weekdayAlarmsAt(LocalTime.of(6, 30))
        db.anchorDao().insert(AnchorEntity(atEpochMs = 0, offsetSec = 0, wakeDate = "not a date", source = "TONIGHT", wholeNightLog = false))

        val next = repository.nextAlarm.first { it != null }!!

        assertThat(next.occurrence.id).isEqualTo("2026-10-07T06:30")
        assertThat(next.window.debtAware).isFalse()
    }

    private fun plannedEvent(wakeDate: String, occurrenceId: String, outcome: String?) = AlarmEventEntity(
        kind = "PLANNED",
        occurrenceId = occurrenceId,
        wakeDate = wakeDate,
        targetEpochMs = instant("${occurrenceId}:00Z").toEpochMilli(),
        windowStartEpochMs = instant("${occurrenceId}:00Z").toEpochMilli(),
        mode = "SURFACING",
        wakeStyle = "STANDARD",
        firstRingEpochMs = instant("${occurrenceId}:00Z").toEpochMilli(),
        reason = "DEADLINE",
        maxZ = null,
        snoozeTimes = "",
        stoppedAtEpochMs = null,
        outcome = outcome,
        sensingStartEpochMs = null,
        offsetSec = 0,
    )
}
