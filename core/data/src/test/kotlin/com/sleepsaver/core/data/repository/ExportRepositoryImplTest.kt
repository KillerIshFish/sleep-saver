package com.sleepsaver.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sleepsaver.core.data.db.AddedAwakeEntity
import com.sleepsaver.core.data.db.AlarmEventEntity
import com.sleepsaver.core.data.db.AlarmTuningEntity
import com.sleepsaver.core.data.db.AnchorEntity
import com.sleepsaver.core.data.db.DayPlanEntity
import com.sleepsaver.core.data.db.DayRoutineItemEntity
import com.sleepsaver.core.data.db.HabitEntity
import com.sleepsaver.core.data.db.HushPlayEntity
import com.sleepsaver.core.data.db.ManualNapEntity
import com.sleepsaver.core.data.db.MorningFeelEntity
import com.sleepsaver.core.data.db.MorningReviewEntity
import com.sleepsaver.core.data.db.MovementEpochEntity
import com.sleepsaver.core.data.db.MovementLogEntity
import com.sleepsaver.core.data.db.NightMarkEntity
import com.sleepsaver.core.data.db.NightOverrideEntity
import com.sleepsaver.core.data.db.NightStepLogEntity
import com.sleepsaver.core.data.db.PlanOverrideEntity
import com.sleepsaver.core.data.db.PlanPauseEntity
import com.sleepsaver.core.data.db.RoutineActivityEntity
import com.sleepsaver.core.data.db.SessionWithStages
import com.sleepsaver.core.data.db.SleepSessionEntity
import com.sleepsaver.core.data.db.SleepStageEntity
import com.sleepsaver.core.data.db.TomorrowListEntity
import com.sleepsaver.core.data.db.VitalEntity
import com.sleepsaver.core.data.export.NightsCsv
import com.sleepsaver.core.data.testing.FakeSettingsRepository
import com.sleepsaver.core.data.testing.FakeSleepRepository
import com.sleepsaver.core.data.testing.FakeTimeProvider
import com.sleepsaver.core.data.testing.GARMIN
import com.sleepsaver.core.data.testing.inMemoryDatabase
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.data.testing.testNight
import com.sleepsaver.core.domain.model.SourceSelection
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ExportRepositoryImplTest {

    private val db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
    private val sleep = FakeSleepRepository()
    private val settings = FakeSettingsRepository()
    private val time = FakeTimeProvider(now = instant("2026-10-06T12:00:00Z"))

    @After
    fun tearDown() = db.close()

    private fun TestScope.repository() = ExportRepositoryImpl(db, sleep, settings, time, StandardTestDispatcher(testScheduler))

    @Test
    fun `csv export writes the nights table to the stream without closing it`() = runTest {
        val nights = listOf(testNight(LocalDate.of(2026, 10, 5)), testNight(LocalDate.of(2026, 10, 6), wokeUp = "06:15"))
        sleep.state.value = nights
        val out = TrackingOutputStream()

        repository().exportCsv(out)

        assertThat(out.toString(Charsets.UTF_8.name())).isEqualTo(buildString { NightsCsv.write(nights, this) })
        assertThat(out.closed).isFalse()
    }

    @Test
    fun `csv export adds Morning feel, the stored marks and the anchor latency per night`() = runTest {
        val night = testNight(LocalDate.of(2026, 10, 6)) // asleep at 23:00 UTC
        sleep.state.value = listOf(testNight(LocalDate.of(2026, 10, 5)), night)
        db.morningFeelDao().upsert(MorningFeelEntity("2026-10-06", 4, 0, 0, "REVIEW"))
        db.nightMarkDao().insert(NightMarkEntity("2026-10-06", "LATE_MEAL", 0, 0))
        db.nightMarkDao().insert(NightMarkEntity("2026-10-06", "ALCOHOL", 0, 0))
        // Dropped 22:44 → asleep 16 min later, shown as "about 15 min".
        db.anchorDao().insert(AnchorEntity(atEpochMs = instant("2026-10-05T22:44:00Z").toEpochMilli(), offsetSec = 0, wakeDate = "2026-10-06", source = "TONIGHT", wholeNightLog = false))
        val out = ByteArrayOutputStream()

        repository().exportCsv(out)

        val rows = out.toString(Charsets.UTF_8.name()).trimEnd().split("\r\n").map { it.split(',') }
        assertThat(rows.first().takeLast(3)).containsExactly("morning_feel", "marks", "anchor_latency_min").inOrder()
        assertThat(rows[1].takeLast(3)).containsExactly("", "", "").inOrder()
        assertThat(rows[2].takeLast(3)).containsExactly("4", "alcohol;late_meal", "15").inOrder()
    }

    @Test
    fun `json export contains every table and the settings`() = runTest {
        settings.state.update { it.copy(sleep = it.sleep.copy(source = SourceSelection.All)) }
        val start = instant("2026-10-05T23:00:00Z").toEpochMilli()
        val end = instant("2026-10-06T07:00:00Z").toEpochMilli()
        db.sleepSessionDao().replace(
            listOf(
                SessionWithStages(
                    SleepSessionEntity("hc-1", GARMIN, start, end, null, -14_400, "America/New_York", null, end, "HEALTH_CONNECT"),
                    listOf(SleepStageEntity("hc-1", start, end, 5)),
                ),
            ),
        )
        db.nightOverrideDao().upsert(NightOverrideEntity("2026-10-06", null, end, 10, null, false, -14_400, end))
        db.manualNapDao().insert(ManualNapEntity(startEpochMs = 1, endEpochMs = 2, offsetSec = 0, createdAtEpochMs = 3))
        db.addedAwakeDao().insert(AddedAwakeEntity(wakeDate = "2026-10-06", startEpochMs = 4, endEpochMs = 5, createdAtEpochMs = 6))
        db.vitalDao().upsert(listOf(VitalEntity("v1", "RESTING_HR", end, null, 52.0, GARMIN, "HEALTH_CONNECT")))
        db.habitDao().upsert(HabitEntity("BEDTIME", true, true, 0, 0))
        val activityId = db.routineActivityDao().insert(
            RoutineActivityEntity(label = "Reading", emoji = "r", isCustom = false, selected = true, sortOrder = 0, techniqueKey = "READ_ON_PAPER", leadMin = 50, durationMin = 20),
        )
        db.morningReviewDao().upsert(MorningReviewEntity("2026-10-06", "CONFIRMED", end, 120, 100))
        insertOneRowPerV2Table(activityId, end)
        val out = ByteArrayOutputStream()

        repository().exportJson(out)

        val root = Json.parseToJsonElement(out.toString(Charsets.UTF_8.name())).jsonObject
        assertThat(root["schemaVersion"]!!.jsonPrimitive.int).isEqualTo(2)
        assertThat(root["exportedAt"]!!.jsonPrimitive.content).isEqualTo("2026-10-06T12:00:00Z")
        assertThat(root["settings"]!!.jsonObject["source"]!!.jsonPrimitive.content).isEqualTo("*all*")
        assertThat(root["settings"]!!.jsonObject["sleepNeedMin"]!!.jsonPrimitive.int).isEqualTo(480)
        assertThat(root["settings"]!!.jsonObject["typicalWake"]!!.jsonPrimitive.content).isEqualTo("07:00")
        val keys = listOf(
            "rawSessions", "overrides", "manualNaps", "addedAwake", "vitals", "habits", "routineActivities", "reviews",
            "dayPlans", "dayRoutineItems", "planOverrides", "planPauses", "nightStepLogs", "tomorrowLists", "nightMarks",
            "morningFeel", "anchors", "movementLogs", "alarmEvents", "alarmTuning", "hushPlays",
        )
        for (key in keys) {
            assertWithMessage(key).that((root[key] as JsonArray).size).isEqualTo(1)
        }
        val activity = root["routineActivities"]!!.jsonArray.single().jsonObject
        assertThat(activity["techniqueKey"]!!.jsonPrimitive.content).isEqualTo("READ_ON_PAPER")
        assertThat(activity["leadMin"]!!.jsonPrimitive.int).isEqualTo(50)
        val day = root["dayPlans"]!!.jsonArray.single().jsonObject
        assertThat(day["wakeTime"]!!.jsonPrimitive.content).isEqualTo("06:45")
        assertThat(day["bedtime"]).isEqualTo(JsonNull)
        val event = root["alarmEvents"]!!.jsonArray.single().jsonObject
        assertThat(event["snoozeTimes"]!!.jsonArray.map { it.jsonPrimitive.content }).containsExactly("2026-10-06T07:00:00Z")
        // Epochs stay out of the export.
        assertThat(root["movementLogs"]!!.jsonArray.single().jsonObject.keys).doesNotContain("epochs")
        val session = root["rawSessions"]!!.jsonArray.single().jsonObject
        assertThat(session["start"]!!.jsonPrimitive.content).isEqualTo("2026-10-05T23:00:00Z")
        assertThat(session["startOffset"]).isEqualTo(JsonNull)
        assertThat(session["endOffset"]!!.jsonPrimitive.content).isEqualTo("-04:00")
        val stage = session["stages"]!!.jsonArray.single().jsonObject
        assertThat(stage["type"]!!.jsonPrimitive.content).isEqualTo("DEEP")
        assertThat((root["reviews"]!!.jsonArray.single() as JsonObject)["debtAfterMin"]!!.jsonPrimitive.int).isEqualTo(100)
    }

    private suspend fun insertOneRowPerV2Table(activityId: Long, at: Long) {
        db.dayPlanDao().upsert(DayPlanEntity(1, 405, "SURFACING", 30, "STANDARD", "SOFT_CHIMES", 80, "TAPERING", 9, 3, 10, null, true, 60, true))
        db.dayRoutineItemDao().replaceForDay(1, listOf(DayRoutineItemEntity(1, activityId, 0)))
        db.planOverrideDao().upsert(PlanOverrideEntity("2026-10-08", "DAY_OFF", null, null, at, 0))
        db.planPauseDao().upsert(PlanPauseEntity(fromDate = "2026-12-20", untilDate = "2026-12-31", createdAtEpochMs = at, offsetSec = 0))
        db.nightStepLogDao().upsert(NightStepLogEntity("2026-10-05", activityId, "READ_ON_PAPER", at, 0))
        db.tomorrowListDao().upsert(TomorrowListEntity("2026-10-05", "call Sam", at, 0))
        db.nightMarkDao().insert(NightMarkEntity("2026-10-06", "ALCOHOL", at, 0))
        db.morningFeelDao().upsert(MorningFeelEntity("2026-10-06", 3, at, 0, "RING"))
        val anchorId = db.anchorDao().insert(AnchorEntity(atEpochMs = at, offsetSec = 0, wakeDate = "2026-10-06", source = "TONIGHT", wholeNightLog = true))
        val logId = db.movementLogDao().insert(MovementLogEntity(startEpochMs = at, endEpochMs = null, offsetSec = 0, mode = "WHOLE_NIGHT", wakeDate = "2026-10-06", anchorId = anchorId, occurrenceId = null))
        db.movementLogDao().insertEpochs(listOf(MovementEpochEntity(logId, at, 12.5)))
        db.alarmEventDao().upsert(
            AlarmEventEntity(
                kind = "PLANNED", occurrenceId = "2026-10-06T06:45", wakeDate = "2026-10-06", targetEpochMs = at, windowStartEpochMs = at,
                mode = "SURFACING", wakeStyle = "STANDARD", firstRingEpochMs = at, reason = "RESTLESS", maxZ = 3.1,
                snoozeTimes = "$at", stoppedAtEpochMs = at, outcome = "STOPPED", sensingStartEpochMs = at, offsetSec = 0,
            ),
        )
        db.alarmTuningDao().upsert(AlarmTuningEntity(zThreshold = 2.75, reason = "RAISED_AFTER_DEEP_RINGS", changedAtEpochMs = at, offsetSec = 0))
        db.hushPlayDao().insert(HushPlayEntity(startEpochMs = at, endEpochMs = null, offsetSec = 0, color = "PINK", wakeDate = "2026-10-06"))
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }
}
