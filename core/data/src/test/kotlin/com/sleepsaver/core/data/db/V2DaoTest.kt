package com.sleepsaver.core.data.db

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.testing.inMemoryDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/** The v2 DAOs (week plan, journal, night log). */
@RunWith(AndroidJUnit4::class)
class V2DaoTest {

    private val db = inMemoryDatabase(ApplicationProvider.getApplicationContext())

    @After
    fun tearDown() = db.close()

    private fun activity(label: String, sortOrder: Int, technique: String? = null) =
        RoutineActivityEntity(label = label, emoji = "e", isCustom = technique == null, selected = false, sortOrder = sortOrder, techniqueKey = technique)

    private fun dayPlan(day: Int, wake: Int? = 405) = DayPlanEntity(
        dayOfWeek = day, wakeMinOfDay = wake, alarmMode = "SURFACING", windowMin = 30, wakeStyle = "STANDARD",
        tone = "SOFT_CHIMES", volumePct = 80, snoozeStyle = "TAPERING", snoozeFixedMin = 9, snoozeFixedCount = 3,
        overrunMin = 10, bedtimeMinOfDay = null, nightStepsOn = true, windDownMin = 60, ownRoutine = false,
    )

    private fun event(
        kind: String = "PLANNED",
        wakeDate: String = "2026-10-06",
        firstRing: Long = 1_000,
        outcome: String? = null,
        occurrenceId: String? = "${wakeDate}T06:45",
        id: Long = 0,
    ) = AlarmEventEntity(
        id = id, kind = kind, occurrenceId = occurrenceId, wakeDate = wakeDate, targetEpochMs = 2_000,
        windowStartEpochMs = 200, mode = "SURFACING", wakeStyle = "STANDARD", firstRingEpochMs = firstRing,
        reason = "RESTLESS", maxZ = 3.0, snoozeTimes = "", stoppedAtEpochMs = null, outcome = outcome,
        sensingStartEpochMs = null, offsetSec = 0,
    )

    // ---------------------------------------------------------------- Routine activities

    @Test
    fun `routine activities gain timing, lookup by technique and the last sort order`() = runTest {
        val dao = db.routineActivityDao()
        assertThat(dao.maxSortOrder()).isNull()
        dao.insertAll(listOf(activity("a", 0, "SLOW_TIDE"), activity("b", 4), activity("c", 2, "SLOW_TIDE")))
        val slowTide = dao.findByTechnique("SLOW_TIDE")!!

        dao.setTiming(slowTide.id, leadMin = 25, durationMin = 8)

        assertThat(slowTide.label).isEqualTo("a")
        assertThat(dao.findByTechnique("BODY_SCAN")).isNull()
        assertThat(dao.maxSortOrder()).isEqualTo(4)
        val updated = dao.getAll().first { it.id == slowTide.id }
        assertThat(updated.leadMin to updated.durationMin).isEqualTo(25 to 8)
    }

    // ---------------------------------------------------------------- Week plan

    @Test
    fun `day plans upsert by weekday`() = runTest {
        val dao = db.dayPlanDao()
        dao.upsertAll((1..7).map { dayPlan(it) })

        dao.upsert(dayPlan(3, wake = 420))

        assertThat(dao.getAll().map { it.dayOfWeek }).containsExactly(1, 2, 3, 4, 5, 6, 7).inOrder()
        assertThat(dao.get(3)?.wakeMinOfDay).isEqualTo(420)
        assertThat(dao.observeAll().first()).hasSize(7)
    }

    @Test
    fun `a day's routine is replaced alone and follows deleted activities`() = runTest {
        val activities = db.routineActivityDao()
        activities.insertAll(listOf(activity("a", 0), activity("b", 1), activity("c", 2)))
        val (a, b, c) = activities.getAll().map { it.id }
        val dao = db.dayRoutineItemDao()
        dao.replaceForDay(1, listOf(DayRoutineItemEntity(1, a, 0), DayRoutineItemEntity(1, b, 1)))
        dao.replaceForDay(2, listOf(DayRoutineItemEntity(2, c, 0)))

        dao.replaceForDay(1, listOf(DayRoutineItemEntity(1, c, 0), DayRoutineItemEntity(1, a, 1)))
        activities.delete(a)

        assertThat(dao.getForDay(1).map { it.activityId }).containsExactly(c)
        assertThat(dao.getForDay(2).map { it.activityId }).containsExactly(c)
        assertThat(dao.existingActivityIds(listOf(a, b, c, 99))).containsExactly(b, c)
        dao.replaceForDay(2, emptyList())
        assertThat(dao.observeAll().first().map { it.dayOfWeek }).containsExactly(1)
    }

    @Test
    fun `overrides read from a date and prune before one`() = runTest {
        val dao = db.planOverrideDao()
        for (date in listOf("2026-09-20", "2026-10-03", "2026-10-07")) {
            dao.upsert(PlanOverrideEntity(date, "DAY_OFF", null, null, 0, 0))
        }
        dao.upsert(PlanOverrideEntity("2026-10-07", "JUST_ONCE", 330, "FIXED", 1, 0))

        assertThat(dao.getFrom("2026-10-03").map { it.date }).containsExactly("2026-10-03", "2026-10-07").inOrder()
        assertThat(dao.observeFrom("2026-10-07").first().single().kind).isEqualTo("JUST_ONCE")

        dao.deleteBefore("2026-09-29")
        dao.delete("2026-10-07")
        assertThat(dao.getAll().map { it.date }).containsExactly("2026-10-03")
    }

    @Test
    fun `the pause is a single row`() = runTest {
        val dao = db.planPauseDao()
        dao.upsert(PlanPauseEntity(fromDate = "2026-10-10", untilDate = "2026-10-20", createdAtEpochMs = 0, offsetSec = 0))
        dao.upsert(PlanPauseEntity(fromDate = "2026-10-12", untilDate = "2026-10-25", createdAtEpochMs = 1, offsetSec = 0))

        assertThat(dao.getAll()).hasSize(1)
        assertThat(dao.observe().first()?.untilDate).isEqualTo("2026-10-25")
        dao.clear()
        assertThat(dao.get()).isNull()
    }

    // ---------------------------------------------------------------- Journal

    @Test
    fun `step logs by evening, with the first evening ever`() = runTest {
        val dao = db.nightStepLogDao()
        assertThat(dao.firstNightOf()).isNull()
        dao.upsert(NightStepLogEntity("2026-10-05", 1, "SLOW_TIDE", 100, 0))
        dao.upsert(NightStepLogEntity("2026-10-04", 2, null, 50, 0))
        dao.upsert(NightStepLogEntity("2026-10-05", 2, null, 120, 0))

        dao.delete("2026-10-05", 2)

        assertThat(dao.firstNightOf()).isEqualTo("2026-10-04")
        assertThat(dao.observeFor("2026-10-05").first().map { it.activityId }).containsExactly(1L)
        assertThat(dao.observeFrom("2026-10-05").first()).hasSize(1)
        assertThat(dao.observeAll().first()).hasSize(2)
    }

    @Test
    fun `marks keep the first row and report each mark's first date`() = runTest {
        val dao = db.nightMarkDao()
        dao.insert(NightMarkEntity("2026-10-05", "ALCOHOL", 100, 0))
        dao.insert(NightMarkEntity("2026-10-05", "ALCOHOL", 999, 0))
        dao.insert(NightMarkEntity("2026-10-03", "ALCOHOL", 50, 0))
        dao.insert(NightMarkEntity("2026-10-04", "STRESS", 60, 0))

        assertThat(dao.observe("2026-10-05").first().single().createdAtEpochMs).isEqualTo(100)
        assertThat(dao.firstDates()).containsExactly(MarkFirstDate("ALCOHOL", "2026-10-03"), MarkFirstDate("STRESS", "2026-10-04"))

        dao.delete("2026-10-05", "ALCOHOL")
        assertThat(dao.observeAll().first().map { it.wakeDate }).containsExactly("2026-10-03", "2026-10-04").inOrder()
    }

    @Test
    fun `feel, tomorrow lists and prompts`() = runTest {
        db.morningFeelDao().upsert(MorningFeelEntity("2026-10-06", 2, 100, 0, "RING"))
        db.morningFeelDao().upsert(MorningFeelEntity("2026-10-06", 4, 200, 0, "REVIEW"))
        assertThat(db.morningFeelDao().observe("2026-10-06").first()?.level).isEqualTo(4)
        db.morningFeelDao().delete("2026-10-06")
        assertThat(db.morningFeelDao().observeAll().first()).isEmpty()

        db.tomorrowListDao().upsert(TomorrowListEntity("2026-10-05", "call Sam", 100, 0))
        assertThat(db.tomorrowListDao().observe("2026-10-05").first()?.text).isEqualTo("call Sam")
        db.tomorrowListDao().delete("2026-10-05")
        assertThat(db.tomorrowListDao().observe("2026-10-05").first()).isNull()

        db.promptLogDao().insert(PromptLogEntity("2026-10-06", "ODD_NIGHT", 100, 0))
        db.promptLogDao().insert(PromptLogEntity("2026-10-06", "ODD_NIGHT", 200, 0))
        assertThat(db.promptLogDao().observe("2026-10-06", "ODD_NIGHT").first()?.shownAtEpochMs).isEqualTo(100)
        assertThat(db.promptLogDao().observe("2026-10-07", "ODD_NIGHT").first()).isNull()
    }

    // ---------------------------------------------------------------- Night log

    @Test
    fun `anchors by wake date and the latest`() = runTest {
        val dao = db.anchorDao()
        val first = dao.insert(AnchorEntity(atEpochMs = 100, offsetSec = 0, wakeDate = "2026-10-06", source = "TONIGHT", wholeNightLog = false))
        val second = dao.insert(AnchorEntity(atEpochMs = 300, offsetSec = 0, wakeDate = "2026-10-06", source = "NIGHT_STEPS", wholeNightLog = true))

        assertThat(dao.observeLatest().first()?.id).isEqualTo(second)
        dao.delete(second)
        assertThat(dao.observeLatest().first()?.id).isEqualTo(first)
        assertThat(dao.observeFor("2026-10-06").first()).hasSize(1)
        assertThat(dao.observeFor("2026-10-07").first()).isEmpty()
    }

    @Test
    fun `movement epochs keep the first value per slot and go with their log`() = runTest {
        val dao = db.movementLogDao()
        val id = dao.insert(MovementLogEntity(startEpochMs = 0, endEpochMs = null, offsetSec = 0, mode = "WINDOW", wakeDate = "2026-10-06", anchorId = null, occurrenceId = "x"))
        dao.insertEpochs(listOf(MovementEpochEntity(id, 30_000, 1.5), MovementEpochEntity(id, 0, 0.5)))
        dao.insertEpochs(listOf(MovementEpochEntity(id, 30_000, 9.0)))
        dao.setEnd(id, 60_000)

        assertThat(dao.observeEpochs(id).first().map { it.activity }).containsExactly(0.5, 1.5).inOrder()
        assertThat(dao.observeFor("2026-10-06").first().single().endEpochMs).isEqualTo(60_000)
        assertThat(dao.getAllLogs()).hasSize(1)

        dao.deleteAll()
        assertThat(dao.observeEpochs(id).first()).isEmpty()
    }

    @Test
    fun `alarm events upsert, planned lookups and handled ids`() = runTest {
        val dao = db.alarmEventDao()
        val planned = dao.upsert(event(firstRing = 1_000))
        dao.upsert(event(kind = "NAP", firstRing = 5_000, occurrenceId = null))
        dao.upsert(event(kind = "TEST", firstRing = 6_000, occurrenceId = null))
        dao.upsert(event(wakeDate = "2026-10-04", firstRing = 100, outcome = "STOPPED"))

        val updated = dao.upsert(event(id = planned, firstRing = 1_000, outcome = "STOPPED"))

        assertThat(planned).isGreaterThan(0L)
        assertThat(updated).isEqualTo(-1L)
        assertThat(dao.observePlannedFor("2026-10-06").first()?.outcome).isEqualTo("STOPPED")
        assertThat(dao.observeLastTest().first()?.kind).isEqualTo("TEST")
        assertThat(dao.observeRecent(2).first().map { it.kind }).containsExactly("TEST", "NAP").inOrder()
        assertThat(dao.handledIds("2026-10-05")).containsExactly("2026-10-06T06:45")
        assertThat(dao.handledIds("2026-10-01")).containsExactly("2026-10-06T06:45", "2026-10-04T06:45")
        assertThat(dao.observePlannedFrom("2026-10-05").first()).hasSize(1)
    }

    @Test
    fun `tuning is a single row and Hush plays get their end`() = runTest {
        db.alarmTuningDao().upsert(AlarmTuningEntity(zThreshold = 2.75, reason = "RAISED_AFTER_DEEP_RINGS", changedAtEpochMs = 0, offsetSec = 0))
        db.alarmTuningDao().upsert(AlarmTuningEntity(zThreshold = 3.0, reason = "RAISED_AFTER_DEEP_RINGS", changedAtEpochMs = 1, offsetSec = 0))
        assertThat(db.alarmTuningDao().getAll().single().zThreshold).isEqualTo(3.0)
        db.alarmTuningDao().clear()
        assertThat(db.alarmTuningDao().observe().first()).isNull()

        val hush = db.hushPlayDao().insert(HushPlayEntity(startEpochMs = 0, endEpochMs = null, offsetSec = 0, color = "PINK", wakeDate = "2026-10-06"))
        db.hushPlayDao().setEnd(hush, 2_700_000)
        assertThat(db.hushPlayDao().observeAll().first().single().endEpochMs).isEqualTo(2_700_000)
    }
}
