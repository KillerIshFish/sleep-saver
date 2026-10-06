package com.sleepsaver.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sleepsaver.core.data.db.AddedAwakeEntity
import com.sleepsaver.core.data.db.HabitEntity
import com.sleepsaver.core.data.db.ManualNapEntity
import com.sleepsaver.core.data.db.MorningReviewEntity
import com.sleepsaver.core.data.db.NightOverrideEntity
import com.sleepsaver.core.data.db.RoutineActivityEntity
import com.sleepsaver.core.data.db.SessionWithStages
import com.sleepsaver.core.data.db.SleepSessionEntity
import com.sleepsaver.core.data.db.SleepStageEntity
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
        db.routineActivityDao().insert(RoutineActivityEntity(label = "Reading", emoji = "r", isCustom = false, selected = true, sortOrder = 0))
        db.morningReviewDao().upsert(MorningReviewEntity("2026-10-06", "CONFIRMED", end, 120, 100))
        val out = ByteArrayOutputStream()

        repository().exportJson(out)

        val root = Json.parseToJsonElement(out.toString(Charsets.UTF_8.name())).jsonObject
        assertThat(root["schemaVersion"]!!.jsonPrimitive.int).isEqualTo(1)
        assertThat(root["exportedAt"]!!.jsonPrimitive.content).isEqualTo("2026-10-06T12:00:00Z")
        assertThat(root["settings"]!!.jsonObject["source"]!!.jsonPrimitive.content).isEqualTo("*all*")
        assertThat(root["settings"]!!.jsonObject["sleepNeedMin"]!!.jsonPrimitive.int).isEqualTo(480)
        assertThat(root["settings"]!!.jsonObject["typicalWake"]!!.jsonPrimitive.content).isEqualTo("07:00")
        for (key in listOf("rawSessions", "overrides", "manualNaps", "addedAwake", "vitals", "habits", "routineActivities", "reviews")) {
            assertWithMessage(key).that((root[key] as JsonArray).size).isEqualTo(1)
        }
        val session = root["rawSessions"]!!.jsonArray.single().jsonObject
        assertThat(session["start"]!!.jsonPrimitive.content).isEqualTo("2026-10-05T23:00:00Z")
        assertThat(session["startOffset"]).isEqualTo(JsonNull)
        assertThat(session["endOffset"]!!.jsonPrimitive.content).isEqualTo("-04:00")
        val stage = session["stages"]!!.jsonArray.single().jsonObject
        assertThat(stage["type"]!!.jsonPrimitive.content).isEqualTo("DEEP")
        assertThat((root["reviews"]!!.jsonArray.single() as JsonObject)["debtAfterMin"]!!.jsonPrimitive.int).isEqualTo(100)
    }

    private class TrackingOutputStream : ByteArrayOutputStream() {
        var closed = false
        override fun close() {
            closed = true
            super.close()
        }
    }
}
