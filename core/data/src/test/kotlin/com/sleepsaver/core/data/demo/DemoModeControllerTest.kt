package com.sleepsaver.core.data.demo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.AddedAwakeEntity
import com.sleepsaver.core.data.db.ManualNapEntity
import com.sleepsaver.core.data.db.MorningFeelEntity
import com.sleepsaver.core.data.db.MorningReviewEntity
import com.sleepsaver.core.data.db.NightMarkEntity
import com.sleepsaver.core.data.db.NightOverrideEntity
import com.sleepsaver.core.data.db.NightStepLogEntity
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.db.SourceKind
import com.sleepsaver.core.data.db.TomorrowListEntity
import com.sleepsaver.core.data.db.VitalEntity
import com.sleepsaver.core.data.mapping.toEntity
import com.sleepsaver.core.data.repository.RoutineActivityStore
import com.sleepsaver.core.data.source.VitalIds
import com.sleepsaver.core.data.sync.FakeSettingsRepository
import com.sleepsaver.core.data.sync.FakeTimeProvider
import com.sleepsaver.core.data.sync.ROBOLECTRIC_SDK
import com.sleepsaver.core.data.sync.hcSession
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.VitalType
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
@Config(sdk = [ROBOLECTRIC_SDK])
class DemoModeControllerTest {
    @get:Rule val tmp = TemporaryFolder()

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
    private val journalWriter = DemoJournalWriter(db, RoutineActivityStore(db, ApplicationProvider.getApplicationContext()), demoSource, time)
    private lateinit var backupFile: File
    private lateinit var backup: DemoEditBackupFile
    private lateinit var controller: DemoModeControllerImpl

    @Before
    fun setUp() {
        backupFile = File(tmp.root, DemoEditBackupFile.FILE_NAME)
        backup = DemoEditBackupFile(backupFile)
        controller = controller(backup)
    }

    @After
    fun tearDown() = db.close()

    private fun controller(backup: DemoEditBackupFile) = DemoModeControllerImpl(
        settingsRepository = settings,
        writer = writer,
        journalWriter = journalWriter,
        database = db,
        timeProvider = time,
        backup = backup,
    )

    private suspend fun allSessions() = db.sleepSessionDao().getWithStages(Long.MIN_VALUE, Long.MAX_VALUE)

    // ------------------------------------------------------------ rows

    private val earlier = time.now.minus(Duration.ofDays(3)).toEpochMilli()

    private fun override(wakeDate: String, updatedAt: Long, awakeMinutes: Int = 30) = NightOverrideEntity(
        wakeDate = wakeDate,
        fellAsleepEpochMs = null,
        wokeUpEpochMs = updatedAt - 3_600_000,
        awakeMinutes = awakeMinutes,
        napMinutes = null,
        isManualNight = false,
        offsetSec = -14_400,
        updatedAtEpochMs = updatedAt,
    )

    private class Edits(
        val overrides: List<NightOverrideEntity>,
        val awake: List<AddedAwakeEntity>,
        val naps: List<ManualNapEntity>,
        val reviews: List<MorningReviewEntity>,
        val marks: List<NightMarkEntity> = emptyList(),
        val feel: List<MorningFeelEntity> = emptyList(),
        val stepLogs: List<NightStepLogEntity> = emptyList(),
        val tomorrowLists: List<TomorrowListEntity> = emptyList(),
    )

    /**
     * Real edits and journal rows on 2026-10-03 … 06 (the same dates the demo nights use). The journal rows are ones
     * the demo journal never writes (Unwell, a feel from the ringing screen, activity 999, a Tomorrow list).
     */
    private suspend fun insertRealEdits(): Edits {
        db.nightOverrideDao().upsert(override("2026-10-03", earlier, awakeMinutes = 15))
        db.nightOverrideDao().upsert(override("2026-10-05", earlier, awakeMinutes = 45))
        db.addedAwakeDao().insert(AddedAwakeEntity(wakeDate = "2026-10-05", startEpochMs = earlier, endEpochMs = earlier + 600_000, createdAtEpochMs = earlier))
        db.manualNapDao().insert(ManualNapEntity(startEpochMs = earlier, endEpochMs = earlier + 1_800_000, offsetSec = -14_400, createdAtEpochMs = earlier))
        db.morningReviewDao().upsert(MorningReviewEntity("2026-10-03", "CONFIRMED", earlier, 60, 50))
        db.morningReviewDao().upsert(MorningReviewEntity("2026-10-06", "DISMISSED", earlier, null, null))
        db.nightMarkDao().insert(NightMarkEntity("2026-10-05", "UNWELL", earlier, -14_400))
        db.morningFeelDao().upsert(MorningFeelEntity("2026-10-06", 2, earlier, -14_400, "RING"))
        db.nightStepLogDao().upsert(NightStepLogEntity("2026-10-04", 999, null, earlier, -14_400))
        db.tomorrowListDao().upsert(TomorrowListEntity("2026-10-05", "call Sam", earlier, -14_400))
        return currentEdits()
    }

    private suspend fun currentEdits() = Edits(
        overrides = db.nightOverrideDao().getAll(),
        awake = db.addedAwakeDao().getAll(),
        naps = db.manualNapDao().getAll(),
        reviews = db.morningReviewDao().getAll(),
        marks = db.nightMarkDao().getAll(),
        feel = db.morningFeelDao().getAll(),
        stepLogs = db.nightStepLogDao().getAll(),
        tomorrowLists = db.tomorrowListDao().getAll(),
    )

    private suspend fun assertEdits(expected: Edits) {
        assertThat(db.nightOverrideDao().getAll()).containsExactlyElementsIn(expected.overrides)
        assertThat(db.addedAwakeDao().getAll()).containsExactlyElementsIn(expected.awake)
        assertThat(db.manualNapDao().getAll()).containsExactlyElementsIn(expected.naps)
        assertThat(db.morningReviewDao().getAll()).containsExactlyElementsIn(expected.reviews)
        assertThat(db.nightMarkDao().getAll()).containsExactlyElementsIn(expected.marks)
        assertThat(db.morningFeelDao().getAll()).containsExactlyElementsIn(expected.feel)
        assertThat(db.nightStepLogDao().getAll()).containsExactlyElementsIn(expected.stepLogs)
        assertThat(db.tomorrowListDao().getAll()).containsExactlyElementsIn(expected.tomorrowLists)
    }

    /** No real edit left in the tables: the four edit tables are empty, the journal holds only demo rows. */
    private suspend fun assertNoEdits() {
        assertThat(db.nightOverrideDao().getAll()).isEmpty()
        assertThat(db.addedAwakeDao().getAll()).isEmpty()
        assertThat(db.manualNapDao().getAll()).isEmpty()
        assertThat(db.morningReviewDao().getAll()).isEmpty()
        assertThat(db.nightMarkDao().getAll().map { it.mark }).doesNotContain("UNWELL")
        assertThat(db.morningFeelDao().getAll().map { it.source }).doesNotContain("RING")
        assertThat(db.nightStepLogDao().getAll().map { it.activityId }).doesNotContain(999L)
        assertThat(db.tomorrowListDao().getAll()).isEmpty()
    }

    /** What a user does while trying the demo: edits on the same dates as the real edits. */
    private suspend fun editDuringDemo() {
        time.now = time.now.plus(Duration.ofHours(1))
        val during = time.now.toEpochMilli()
        db.nightOverrideDao().upsert(override("2026-10-05", during, awakeMinutes = 5))
        db.nightOverrideDao().upsert(override("2026-10-04", during))
        db.manualNapDao().insert(ManualNapEntity(startEpochMs = during, endEpochMs = during + 1_800_000, offsetSec = -14_400, createdAtEpochMs = during))
        db.addedAwakeDao().insert(AddedAwakeEntity(wakeDate = "2026-10-05", startEpochMs = during, endEpochMs = during + 600_000, createdAtEpochMs = during))
        db.morningReviewDao().upsert(MorningReviewEntity("2026-10-06", "ADJUSTED", during, 120, 90))
        db.nightMarkDao().insert(NightMarkEntity("2026-10-05", "STRESS", during, -14_400))
        db.morningFeelDao().upsert(MorningFeelEntity("2026-10-05", 5, during, -14_400, "RING"))
        db.tomorrowListDao().upsert(TomorrowListEntity("2026-10-06", "demo list", during, -14_400))
    }

    // ------------------------------------------------------------ on / off

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
        assertThat(vitals.all { it.offsetSec == zone.rules.getOffset(java.time.Instant.ofEpochMilli(it.timeEpochMs)).totalSeconds }).isTrue()
    }

    @Test
    fun enable_setsTheRealEditsAsideAndEmptiesTheTables() = runTest {
        val real = insertRealEdits()

        controller.setDemoMode(true)

        assertNoEdits()
        assertThat(backupFile.isFile).isTrue()
        assertThat(File(tmp.root, "${DemoEditBackupFile.FILE_NAME}.tmp").exists()).isFalse()
        val saved = backup.read()!!
        assertThat(saved.overrides.map { it.toEntity() }).containsExactlyElementsIn(real.overrides)
        assertThat(saved.addedAwake.map { it.toEntity() }).containsExactlyElementsIn(real.awake)
        assertThat(saved.manualNaps.map { it.toEntity() }).containsExactlyElementsIn(real.naps)
        assertThat(saved.reviews.map { it.toEntity() }).containsExactlyElementsIn(real.reviews)
        assertThat(saved.version).isEqualTo(2)
        assertThat(saved.nightMarks.map { it.toEntity() }).containsExactlyElementsIn(real.marks)
        assertThat(saved.morningFeel.map { it.toEntity() }).containsExactlyElementsIn(real.feel)
        assertThat(saved.nightStepLogs.map { it.toEntity() }).containsExactlyElementsIn(real.stepLogs)
        assertThat(saved.tomorrowLists.map { it.toEntity() }).containsExactlyElementsIn(real.tomorrowLists)
    }

    @Test
    fun enable_writesADemoJournalForTheDemoNights_andDisableRemovesIt() = runTest {
        val real = insertRealEdits()

        controller.setDemoMode(true)

        val wakeDates = demoSource.generate().sessions.map { it.end.atZone(zone).toLocalDate() }.toSet()
        val marks = db.nightMarkDao().getAll()
        val feel = db.morningFeelDao().getAll()
        val stepLogs = db.nightStepLogDao().getAll()
        // Marks on about 20 % of the nights, and two marks with enough nights on each side for the statistics.
        val markedNights = marks.map { it.wakeDate }.toSet()
        assertThat(markedNights.size).isIn(9..16)
        assertThat(marks.groupBy { it.mark }.values.count { it.size >= 6 }).isAtLeast(2)
        assertThat(marks.all { LocalDate.parse(it.wakeDate) in wakeDates }).isTrue()
        // Morning feel on about 70 % of the nights, Night steps on about 60 % of the evenings (each one done).
        assertThat(feel.size).isIn(30..54)
        assertThat(feel.all { it.level in 1..5 && LocalDate.parse(it.wakeDate) in wakeDates }).isTrue()
        val evenings = stepLogs.groupBy { it.nightOf }
        assertThat(evenings.size).isIn(25..47)
        assertThat(evenings.values.all { logs -> logs.map { it.activityId }.toSet().size >= 2 }).isTrue()
        assertThat(db.tomorrowListDao().getAll()).isEmpty()

        // The same nights always get the same rows.
        controller.setDemoMode(false)
        assertEdits(real)
        controller.setDemoMode(true)
        assertThat(db.nightMarkDao().getAll()).containsExactlyElementsIn(marks)
        assertThat(db.morningFeelDao().getAll()).containsExactlyElementsIn(feel)
        assertThat(db.nightStepLogDao().getAll()).containsExactlyElementsIn(stepLogs)

        controller.setDemoMode(false)
        assertEdits(real)
    }

    @Test
    fun demoEditsOnTheSameNights_neverReachTheRealEdits() = runTest {
        val realSession = hcSession("hc-1", zone, today.minusDays(4).atTime(23, 0), today.minusDays(3).atTime(7, 0))
        db.sleepSessionDao().replace(listOf(realSession.toEntity(SourceKind.HEALTH_CONNECT, zone)))
        db.vitalDao().upsert(listOf(VitalEntity("rhr-1", "RESTING_HR", earlier, null, 55.0, SleepConfig.GARMIN_CONNECT_PACKAGE, SourceKind.HEALTH_CONNECT.name)))
        val real = insertRealEdits()

        controller.setDemoMode(true)
        editDuringDemo()
        controller.setDemoMode(false)

        assertThat(settings.current().demoMode).isFalse()
        assertThat(settings.current().demoStartedAt).isNull()
        assertThat(allSessions().map { it.session.id }).containsExactly(realSession.id)
        assertThat(allSessions().single().stages).hasSize(2)
        assertThat(db.vitalDao().getAll().map { it.id }).containsExactly("rhr-1")
        // Exactly as before, ids and timestamps included, although the demo edited 2026-10-05 and 2026-10-06 too.
        assertEdits(real)
        assertThat(backupFile.exists()).isFalse()
    }

    @Test
    fun enableTwice_keepsTheDemoEditsAndTheSavedRealEdits() = runTest {
        val real = insertRealEdits()
        controller.setDemoMode(true)
        val startedAt = settings.current().demoStartedAt
        editDuringDemo()
        val demoEdits = currentEdits()

        controller.setDemoMode(true)

        assertThat(settings.current().demoStartedAt).isEqualTo(startedAt)
        assertEdits(demoEdits)
        assertThat(db.sleepSessionDao().count(SourceKind.DEMO.name)).isEqualTo(demoSource.generate().sessions.size)

        controller.setDemoMode(false)
        assertEdits(real)
    }

    @Test
    fun disable_whenAlreadyOffOrTwice_leavesTheRealEditsAlone() = runTest {
        val real = insertRealEdits()

        controller.setDemoMode(false)
        assertEdits(real)

        controller.setDemoMode(true)
        controller.setDemoMode(false)
        controller.setDemoMode(false)

        assertEdits(real)
        assertThat(settings.current().demoMode).isFalse()
        assertThat(backupFile.exists()).isFalse()
    }

    // ------------------------------------------------------------ interrupted switches

    @Test
    fun enable_keepsABackupLeftByAnInterruptedSwitch() = runTest {
        // A switch that saved the real edits and emptied the tables, then died before setting the flag.
        val real = insertRealEdits()
        backup.write(
            DemoEditBackup.of(real.overrides, real.awake, real.naps, real.reviews, real.marks, real.feel, real.stepLogs, real.tomorrowLists),
        )
        db.nightOverrideDao().deleteAll()
        db.addedAwakeDao().deleteAll()
        db.manualNapDao().deleteAll()
        db.morningReviewDao().deleteAll()
        db.nightMarkDao().deleteAll()
        db.morningFeelDao().deleteAll()
        db.nightStepLogDao().deleteAll()
        db.tomorrowListDao().deleteAll()
        val savedBytes = backupFile.readBytes()

        controller.setDemoMode(true)

        assertThat(backupFile.readBytes()).isEqualTo(savedBytes)
        assertThat(settings.current().demoMode).isTrue()

        controller.setDemoMode(false)
        assertEdits(real)
        assertThat(backupFile.exists()).isFalse()
    }

    @Test
    fun enable_finishesASwitchThatDiedBeforeMovingTheEdits() = runTest {
        // The flag was set, then the app died: the real edits are still in the tables and there is no backup.
        val real = insertRealEdits()
        settings.setDemoModeFlag(true, time.now)

        controller.setDemoMode(true)

        assertNoEdits()
        assertThat(backupFile.isFile).isTrue()
        controller.setDemoMode(false)
        assertEdits(real)
    }

    @Test
    fun disable_withoutABackup_keepsTheTablesAsTheyAre() = runTest {
        // A switch off that restored the edits and deleted the backup, then died before clearing the flag.
        val real = insertRealEdits()
        settings.setDemoModeFlag(true, time.now)
        writer.writeAll()

        controller.setDemoMode(false)

        assertEdits(real)
        assertThat(settings.current().demoMode).isFalse()
        assertThat(db.sleepSessionDao().count(SourceKind.DEMO.name)).isEqualTo(0)
    }

    @Test
    fun enable_whenTheBackupCannotBeWritten_staysInRealModeWithTheEditsInPlace() = runTest {
        val real = insertRealEdits()
        val notADirectory = tmp.newFile("blocker")
        val failing = controller(DemoEditBackupFile(File(notADirectory, DemoEditBackupFile.FILE_NAME)))

        failing.setDemoMode(true)

        assertThat(settings.current().demoMode).isFalse()
        assertThat(settings.current().demoStartedAt).isNull()
        assertEdits(real)
        assertThat(db.sleepSessionDao().count(SourceKind.DEMO.name)).isEqualTo(0)
    }

    @Test
    fun enable_whenEmptyingTheTablesFails_rollsBackAndLeavesNoBackup() = runTest {
        val real = insertRealEdits()
        // The backup is written, then the transaction fails while emptying the tables.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER block_review_delete BEFORE DELETE ON morning_reviews BEGIN SELECT RAISE(ABORT, 'blocked'); END",
        )

        controller.setDemoMode(true)

        assertThat(settings.current().demoMode).isFalse()
        assertThat(settings.current().demoStartedAt).isNull()
        assertEdits(real)
        // A backup left behind would be restored over newer real edits after the next demo.
        assertThat(backupFile.exists()).isFalse()
        assertThat(db.sleepSessionDao().count(SourceKind.DEMO.name)).isEqualTo(0)

        db.openHelper.writableDatabase.execSQL("DROP TRIGGER block_review_delete")
        controller.setDemoMode(true)
        assertNoEdits()
        controller.setDemoMode(false)
        assertEdits(real)
    }

    @Test
    fun extendToNow_finishesASwitchThatDiedBeforeMovingTheEdits() = runTest {
        // The flag was set, then the app died; the next sync (not another tap) runs into it.
        val real = insertRealEdits()
        settings.setDemoModeFlag(true, time.now)

        assertThat(controller.extendToNow()).isEqualTo(demoSource.generate().sessions.size)

        assertNoEdits()
        assertThat(backupFile.isFile).isTrue()
        editDuringDemo()
        controller.setDemoMode(false)
        assertEdits(real)
    }

    @Test
    fun extendToNow_leavesTheTablesAloneOnceTheEditsAreAside() = runTest {
        val real = insertRealEdits()
        controller.setDemoMode(true)
        editDuringDemo()
        val demoEdits = currentEdits()

        time.now = time.now.plus(Duration.ofDays(1))
        controller.extendToNow()

        assertEdits(demoEdits)
        controller.setDemoMode(false)
        assertEdits(real)
    }

    @Test
    fun aSwitchRunsToTheEndEvenIfItsCallerIsCancelled() = runTest {
        val real = insertRealEdits()

        val job = launch(start = CoroutineStart.UNDISPATCHED) { controller.setDemoMode(true) }
        job.cancel()
        job.join()

        assertThat(settings.current().demoMode).isTrue()
        assertNoEdits()
        assertThat(backupFile.isFile).isTrue()
        controller.setDemoMode(false)
        assertEdits(real)
    }

    @Test
    fun disable_withADamagedBackup_dropsTheDemoEditsAndLeavesDemoMode() = runTest {
        insertRealEdits()
        controller.setDemoMode(true)
        editDuringDemo()
        backupFile.writeText("{ not json")

        controller.setDemoMode(false)

        assertNoEdits()
        assertThat(settings.current().demoMode).isFalse()
        assertThat(backupFile.exists()).isFalse()
    }

    // ------------------------------------------------------------ demo rows

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
}
