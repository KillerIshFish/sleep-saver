package com.sleepsaver.core.data.demo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.AddedAwakeEntity
import com.sleepsaver.core.data.db.ManualNapEntity
import com.sleepsaver.core.data.db.MorningReviewEntity
import com.sleepsaver.core.data.db.NightOverrideEntity
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.db.SourceKind
import com.sleepsaver.core.data.db.VitalEntity
import com.sleepsaver.core.data.source.VitalIds
import com.sleepsaver.core.data.sync.FakeSettingsRepository
import com.sleepsaver.core.data.sync.FakeTimeProvider
import com.sleepsaver.core.data.sync.ROBOLECTRIC_SDK
import com.sleepsaver.core.data.sync.hcSession
import com.sleepsaver.core.data.sync.toSessionWithStages
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.VitalType
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DemoModeControllerTest {
    private val zone = ZoneId.of("America/New_York")
    private val today = LocalDate.of(2026, 10, 6)
    private val time = FakeTimeProvider(today.atTime(12, 0).atZone(zone).toInstant(), zone)
    private val db = Room
        .inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), SleepSaverDatabase::class.java)
        .allowMainThreadQueries()
        .build()
    private val settings = FakeSettingsRepository()
    private val demoSource = FakeSleepDataSource(time)
    private val writer = DemoDataWriter(demoSource, db.sleepSessionDao(), db.vitalDao(), time)
    private val controller = DemoModeControllerImpl(
        settingsRepository = settings,
        writer = writer,
        overrideDao = db.nightOverrideDao(),
        addedAwakeDao = db.addedAwakeDao(),
        manualNapDao = db.manualNapDao(),
        reviewDao = db.morningReviewDao(),
        timeProvider = time,
    )

    @After
    fun tearDown() = db.close()

    private suspend fun allSessions() = db.sleepSessionDao().getWithStages(Long.MIN_VALUE, Long.MAX_VALUE)

    @Test
    fun enable_setsTheFlagAndLoadsDemoNights() = runTest {
        controller.setDemoMode(true)

        val current = settings.current()
        assertThat(current.demoMode).isTrue()
        assertThat(current.demoStartedAt).isEqualTo(time.now)
        val expected = demoSource.generate()
        val stored = allSessions()
        assertThat(stored.map { it.session.id }).containsExactlyElementsIn(expected.sessions.map { it.id })
        assertThat(stored.map { it.session.sourceKind }.toSet()).containsExactly(SourceKind.DEMO.name)
        assertThat(stored.map { it.session.originPackage }.toSet()).containsExactly(SleepConfig.DEMO_ORIGIN)
        assertThat(stored.sumOf { it.stages.size }).isEqualTo(expected.sessions.sumOf { it.stages.size })
        val vitals = db.vitalDao().getAll()
        assertThat(vitals).hasSize(expected.vitals.size)
        assertThat(vitals.map { it.sourceKind }.toSet()).containsExactly(SourceKind.DEMO.name)
    }

    @Test
    fun enableAgain_keepsTheOriginalStartTime() = runTest {
        controller.setDemoMode(true)
        val startedAt = time.now
        time.now = time.now.plus(Duration.ofHours(2))

        controller.setDemoMode(true)

        assertThat(settings.current().demoStartedAt).isEqualTo(startedAt)
        assertThat(db.sleepSessionDao().count(SourceKind.DEMO.name)).isEqualTo(demoSource.generate().sessions.size)
    }

    @Test
    fun disable_removesDemoRowsAndEditsMadeDuringDemoMode() = runTest {
        val before = time.now.minus(Duration.ofDays(3)).toEpochMilli()
        val real = hcSession("hc-1", zone, today.minusDays(4).atTime(23, 0), today.minusDays(3).atTime(7, 0))
        db.sleepSessionDao().replace(listOf(real.toSessionWithStages(SourceKind.HEALTH_CONNECT, zone)))
        db.vitalDao().upsert(listOf(VitalEntity("rhr-1", "RESTING_HR", before, null, 55.0, SleepConfig.GARMIN_CONNECT_PACKAGE, SourceKind.HEALTH_CONNECT.name)))
        db.nightOverrideDao().upsert(override("2026-10-03", before))
        db.manualNapDao().insert(ManualNapEntity(startEpochMs = before, endEpochMs = before + 1_800_000, offsetSec = -14_400, createdAtEpochMs = before))
        db.addedAwakeDao().insert(AddedAwakeEntity(wakeDate = "2026-10-03", startEpochMs = before, endEpochMs = before + 600_000, createdAtEpochMs = before))
        db.morningReviewDao().upsert(MorningReviewEntity("2026-10-03", "CONFIRMED", before, 60, 50))

        controller.setDemoMode(true)
        time.now = time.now.plus(Duration.ofHours(1))
        val during = time.now.toEpochMilli()
        db.nightOverrideDao().upsert(override("2026-10-05", during))
        db.manualNapDao().insert(ManualNapEntity(startEpochMs = during, endEpochMs = during + 1_800_000, offsetSec = -14_400, createdAtEpochMs = during))
        db.addedAwakeDao().insert(AddedAwakeEntity(wakeDate = "2026-10-05", startEpochMs = during, endEpochMs = during + 600_000, createdAtEpochMs = during))
        db.morningReviewDao().upsert(MorningReviewEntity("2026-10-06", "ADJUSTED", during, 120, 90))

        controller.setDemoMode(false)

        assertThat(settings.current().demoMode).isFalse()
        assertThat(settings.current().demoStartedAt).isNull()
        assertThat(allSessions().map { it.session.id }).containsExactly(real.id)
        assertThat(allSessions().single().stages).hasSize(2)
        assertThat(db.vitalDao().getAll().map { it.id }).containsExactly("rhr-1")
        assertThat(db.nightOverrideDao().getAll().map { it.wakeDate }).containsExactly("2026-10-03")
        assertThat(db.manualNapDao().getAll().map { it.createdAtEpochMs }).containsExactly(before)
        assertThat(db.addedAwakeDao().getAll().map { it.wakeDate }).containsExactly("2026-10-03")
        assertThat(db.morningReviewDao().get("2026-10-03")).isNotNull()
        assertThat(db.morningReviewDao().get("2026-10-06")).isNull()
    }

    @Test
    fun demoRoundTrip_keepsRealVitalsOfTheSameDays() = runTest {
        val yesterday = today.minusDays(1)
        val realSteps = VitalEntity(
            id = VitalIds.steps(yesterday),
            type = VitalType.STEPS_DAY.name,
            timeEpochMs = yesterday.atStartOfDay(zone).toInstant().toEpochMilli(),
            offsetSec = -14_400,
            value = 8_765.0,
            originPackage = SleepConfig.GARMIN_CONNECT_PACKAGE,
            sourceKind = SourceKind.HEALTH_CONNECT.name,
        )
        db.vitalDao().upsert(listOf(realSteps))

        controller.setDemoMode(true)
        controller.setDemoMode(false)

        assertThat(db.vitalDao().getAll()).containsExactly(realSteps)
    }

    @Test
    fun extendToNow_rollsDemoDataForward() = runTest {
        controller.setDemoMode(true)
        assertThat(writer.extendToNow()).isEqualTo(0)

        time.now = time.now.plus(Duration.ofDays(1))
        assertThat(writer.extendToNow()).isAtLeast(1)

        val latestEnd = db.sleepSessionDao().latestEnd(SourceKind.DEMO.name)!!
        assertThat(java.time.Instant.ofEpochMilli(latestEnd).atZone(zone).toLocalDate()).isEqualTo(today.plusDays(1))
        assertThat(db.vitalDao().getAll().map { it.id }).contains("demo:steps:$today")
        assertThat(writer.extendToNow()).isEqualTo(0)
    }

    @Test
    fun controllerExtendToNow_onlyWhileDemoModeIsOn() = runTest {
        assertThat(controller.extendToNow()).isEqualTo(0)
        assertThat(db.sleepSessionDao().count(SourceKind.DEMO.name)).isEqualTo(0)

        controller.setDemoMode(true)
        time.now = time.now.plus(Duration.ofDays(1))
        assertThat(controller.extendToNow()).isAtLeast(1)

        controller.setDemoMode(false)
        time.now = time.now.plus(Duration.ofDays(1))
        assertThat(controller.extendToNow()).isEqualTo(0)
        assertThat(db.sleepSessionDao().count(SourceKind.DEMO.name)).isEqualTo(0)
    }

    private fun override(wakeDate: String, updatedAt: Long) = NightOverrideEntity(
        wakeDate = wakeDate,
        fellAsleepEpochMs = null,
        wokeUpEpochMs = null,
        awakeMinutes = 30,
        napMinutes = null,
        isManualNight = false,
        offsetSec = -14_400,
        updatedAtEpochMs = updatedAt,
    )
}
