package com.sleepsaver.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.AddedAwakeEntity
import com.sleepsaver.core.data.db.ManualNapEntity
import com.sleepsaver.core.data.db.NightOverrideEntity
import com.sleepsaver.core.data.db.SessionWithStages
import com.sleepsaver.core.data.db.SleepSessionEntity
import com.sleepsaver.core.data.db.SourceKind
import com.sleepsaver.core.data.db.VitalEntity
import com.sleepsaver.core.data.export.NightsCsv
import com.sleepsaver.core.data.testing.FakeHabitsRepository
import com.sleepsaver.core.data.testing.FakeSettingsRepository
import com.sleepsaver.core.data.testing.FakeTimeProvider
import com.sleepsaver.core.data.testing.GARMIN
import com.sleepsaver.core.data.testing.assumeNightAssemblyImplemented
import com.sleepsaver.core.data.testing.inMemoryDatabase
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.VitalType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class SleepRepositoryImplTest {

    private val db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
    private val settings = FakeSettingsRepository()
    private val time = FakeTimeProvider(now = instant("2026-10-06T12:00:00Z"))
    private val date = LocalDate.of(2026, 10, 6)

    @After
    fun tearDown() = db.close()

    private fun TestScope.repository() =
        SleepRepositoryImpl(db, settings, time, backgroundScope, StandardTestDispatcher(testScheduler))

    private suspend fun insertSession(
        id: String,
        start: Instant,
        end: Instant,
        origin: String = GARMIN,
        kind: SourceKind = SourceKind.HEALTH_CONNECT,
    ) {
        val entity = SleepSessionEntity(id, origin, start.toEpochMilli(), end.toEpochMilli(), 0, 0, "UTC", null, end.toEpochMilli(), kind.name)
        db.sleepSessionDao().replace(listOf(SessionWithStages(entity, emptyList())))
    }

    @Test
    fun `manual night is an override flagged manual with the given offset`() = runTest {
        val fellAsleep = instant("2026-10-05T21:30:00Z")
        val wokeUp = instant("2026-10-06T05:15:00Z")

        repository().addManualNight(date, fellAsleep, wokeUp, ZoneOffset.ofHours(2))

        assertThat(db.nightOverrideDao().get("2026-10-06")).isEqualTo(
            NightOverrideEntity(
                wakeDate = "2026-10-06",
                fellAsleepEpochMs = fellAsleep.toEpochMilli(),
                wokeUpEpochMs = wokeUp.toEpochMilli(),
                awakeMinutes = null,
                napMinutes = null,
                isManualNight = true,
                offsetSec = 7200,
                updatedAtEpochMs = time.now.toEpochMilli(),
            ),
        )
    }

    @Test
    fun `manual night must end after it starts`() = runTest {
        val error = runCatching {
            repository().addManualNight(date, instant("2026-10-06T07:00:00Z"), instant("2026-10-06T06:00:00Z"), ZoneOffset.UTC)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(db.nightOverrideDao().getAll()).isEmpty()
    }

    @Test
    fun `saving an override stamps it with the current time`() = runTest {
        val override = NightOverride(wakeDate = date, awakeMinutes = 25, offset = ZoneOffset.UTC, updatedAt = Instant.EPOCH)

        repository().saveOverride(override)

        val stored = db.nightOverrideDao().get("2026-10-06")!!
        assertThat(stored.awakeMinutes).isEqualTo(25)
        assertThat(stored.isManualNight).isFalse()
        assertThat(stored.updatedAtEpochMs).isEqualTo(time.now.toEpochMilli())
    }

    @Test
    fun `revert removes that night's override and added awake only`() = runTest {
        val repository = repository()
        repository.saveOverride(NightOverride(wakeDate = date, napMinutes = 10, offset = ZoneOffset.UTC, updatedAt = Instant.EPOCH))
        repository.saveOverride(NightOverride(wakeDate = date.minusDays(1), napMinutes = 10, offset = ZoneOffset.UTC, updatedAt = Instant.EPOCH))
        repository.addAwake(date, instant("2026-10-06T02:00:00Z"), instant("2026-10-06T02:20:00Z"))
        val otherAwake = repository.addAwake(date.minusDays(1), instant("2026-10-05T02:00:00Z"), instant("2026-10-05T02:20:00Z"))
        val nap = repository.addNap(instant("2026-10-06T13:00:00Z"), instant("2026-10-06T13:20:00Z"), ZoneOffset.UTC)

        repository.revert(date)

        assertThat(db.nightOverrideDao().getAll().map { it.wakeDate }).containsExactly("2026-10-05")
        assertThat(db.addedAwakeDao().getAll().map { it.id }).containsExactly(otherAwake)
        assertThat(db.manualNapDao().getAll().map { it.id }).containsExactly(nap)
    }

    @Test
    fun `naps and added awake are stored with createdAt and can be deleted`() = runTest {
        val repository = repository()
        val napStart = instant("2026-10-06T13:00:00Z")
        val napEnd = instant("2026-10-06T13:25:00Z")

        val napId = repository.addNap(napStart, napEnd, ZoneOffset.ofHours(-7))
        val awakeId = repository.addAwake(date, instant("2026-10-06T03:00:00Z"), instant("2026-10-06T03:30:00Z"))

        assertThat(db.manualNapDao().getAll()).containsExactly(
            ManualNapEntity(napId, napStart.toEpochMilli(), napEnd.toEpochMilli(), -7 * 3600, time.now.toEpochMilli()),
        )
        assertThat(db.addedAwakeDao().getAll()).containsExactly(
            AddedAwakeEntity(
                awakeId,
                "2026-10-06",
                instant("2026-10-06T03:00:00Z").toEpochMilli(),
                instant("2026-10-06T03:30:00Z").toEpochMilli(),
                time.now.toEpochMilli(),
            ),
        )

        repository.deleteNap(napId)
        repository.deleteAwake(awakeId)
        assertThat(db.manualNapDao().getAll()).isEmpty()
        assertThat(db.addedAwakeDao().getAll()).isEmpty()
    }

    @Test
    fun `added awake time keeps an awake-minutes edit in step`() = runTest {
        val repository = repository()
        repository.saveOverride(NightOverride(date, awakeMinutes = 20, offset = ZoneOffset.UTC, updatedAt = time.now))

        val awakeId = repository.addAwake(date, instant("2026-10-06T03:00:00Z"), instant("2026-10-06T03:15:00Z"))
        assertThat(db.nightOverrideDao().get(date.toString())!!.awakeMinutes).isEqualTo(35)

        repository.deleteAwake(awakeId)
        assertThat(db.nightOverrideDao().get(date.toString())!!.awakeMinutes).isEqualTo(20)
    }

    @Test
    fun `added awake time leaves an edit without awake minutes alone`() = runTest {
        val repository = repository()
        repository.saveOverride(NightOverride(date, napMinutes = 10, offset = ZoneOffset.UTC, updatedAt = time.now))

        repository.addAwake(date, instant("2026-10-06T03:00:00Z"), instant("2026-10-06T03:15:00Z"))

        assertThat(db.nightOverrideDao().get(date.toString())!!.awakeMinutes).isNull()
    }

    @Test
    fun `nap must end after it starts`() = runTest {
        val error = runCatching {
            repository().addNap(instant("2026-10-06T13:00:00Z"), instant("2026-10-06T13:00:00Z"), ZoneOffset.UTC)
        }.exceptionOrNull()

        assertThat(error).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `origins list Health Connect packages only`() = runTest {
        insertSession("a", instant("2026-10-05T23:00:00Z"), instant("2026-10-06T07:00:00Z"))
        insertSession("b", instant("2026-10-04T23:00:00Z"), instant("2026-10-05T07:00:00Z"), origin = "com.example.ring")
        insertSession("demo:1", instant("2026-10-03T23:00:00Z"), instant("2026-10-04T07:00:00Z"), SleepConfig.DEMO_ORIGIN, SourceKind.DEMO)

        assertThat(repository().origins.first()).containsExactly("com.example.ring", GARMIN).inOrder()
    }

    @Test
    fun `vitals follow demo mode and the time range`() = runTest {
        db.vitalDao().upsert(
            listOf(
                VitalEntity("hc-rhr", "RESTING_HR", instant("2026-10-06T07:00:00Z").toEpochMilli(), null, 52.0, GARMIN, "HEALTH_CONNECT"),
                VitalEntity("hc-old", "RESTING_HR", instant("2026-09-01T07:00:00Z").toEpochMilli(), null, 55.0, GARMIN, "HEALTH_CONNECT"),
                VitalEntity("hc-unknown-type", "SPO2", instant("2026-10-06T07:00:00Z").toEpochMilli(), null, 97.0, GARMIN, "HEALTH_CONNECT"),
                VitalEntity("demo-hrv", "HRV_RMSSD", instant("2026-10-06T07:00:00Z").toEpochMilli(), null, 61.0, SleepConfig.DEMO_ORIGIN, "DEMO"),
            ),
        )
        val repository = repository()
        val from = instant("2026-10-01T00:00:00Z")
        val to = instant("2026-10-07T00:00:00Z")

        val real = repository.vitals(from, to).first()
        settings.state.update { it.copy(demoMode = true) }
        val demo = repository.vitals(from, to).first { list -> list.any { it.id == "demo-hrv" } }

        assertThat(real.map { it.id }).containsExactly("hc-rhr")
        assertThat(real.single().type).isEqualTo(VitalType.RESTING_HR)
        assertThat(demo.map { it.id }).containsExactly("demo-hrv")
    }

    @Test
    fun `a failing night assembly still answers, so insights and export never wait forever`() = runTest {
        insertSession("g", instant("2026-10-05T23:00:00Z"), instant("2026-10-06T07:00:00Z"))
        // A row the mapping can't read: the very first assembly throws.
        db.nightOverrideDao().upsert(NightOverrideEntity("not-a-date", null, null, 10, null, false, 0, 0))
        val repository = repository()

        assertThat(repository.nights.first()).isEmpty()
        assertThat(repository.rawNight(date).first()).isNotNull()

        val out = ByteArrayOutputStream()
        ExportRepositoryImpl(db, repository, settings, time, StandardTestDispatcher(testScheduler)).exportCsv(out)
        assertThat(out.toString(Charsets.UTF_8.name())).isEqualTo(buildString { NightsCsv.write(emptyList(), this) })

        val insights = InsightsRepositoryImpl(repository, settings, FakeHabitsRepository(), time, backgroundScope, StandardTestDispatcher(testScheduler))
        assertThat(insights.state.first { it != InsightsState.Loading }).isEqualTo(InsightsState.NoNights)

        // Once the row is fixed the nights come back.
        db.nightOverrideDao().delete("not-a-date")
        assertThat(repository.nights.first { it.isNotEmpty() }.map { it.wakeDate }).containsExactly(date)
    }

    // ------------------------------------------------ Needs the core:domain engine (skipped until it is merged)

    @Test
    fun `nights come from the selected source with edits applied`() = runTest {
        assumeNightAssemblyImplemented()
        settings.state.update { it.copy(sleep = it.sleep.copy(source = SourceSelection.Single(GARMIN))) }
        insertSession("g", instant("2026-10-05T23:00:00Z"), instant("2026-10-06T07:00:00Z"))
        insertSession("o", instant("2026-10-04T23:00:00Z"), instant("2026-10-05T07:00:00Z"), origin = "com.example.ring")
        val repository = repository()

        val nights = repository.nights.first { it.isNotEmpty() }
        assertThat(nights.map { it.wakeDate }).containsExactly(date)
        assertThat(nights.single().isPresent).isTrue()
        assertThat(nights.single().mainSleep).isEqualTo(Duration.ofHours(8))

        val earlierWake = instant("2026-10-06T06:00:00Z")
        repository.saveOverride(NightOverride(wakeDate = date, wokeUp = earlierWake, offset = ZoneOffset.UTC, updatedAt = Instant.EPOCH))

        val edited = repository.night(date).first { it?.isEdited == true }!!
        assertThat(edited.wokeUp).isEqualTo(earlierWake)
        val raw = repository.rawNight(date).first()!!
        assertThat(raw.isEdited).isFalse()
        assertThat(raw.wokeUp).isEqualTo(instant("2026-10-06T07:00:00Z"))
    }

    @Test
    fun `demo mode shows only demo nights`() = runTest {
        assumeNightAssemblyImplemented()
        insertSession("g", instant("2026-10-05T23:00:00Z"), instant("2026-10-06T07:00:00Z"))
        insertSession("demo:1", instant("2026-10-04T23:00:00Z"), instant("2026-10-05T07:00:00Z"), SleepConfig.DEMO_ORIGIN, SourceKind.DEMO)
        settings.state.update { it.copy(demoMode = true) }

        val nights = repository().nights.first { it.isNotEmpty() }

        assertThat(nights.map { it.wakeDate }).containsExactly(date.minusDays(1))
    }

    @Test
    fun `leftover demo rows never mix into real nights, even with all sources`() = runTest {
        assumeNightAssemblyImplemented()
        settings.state.update { it.copy(sleep = it.sleep.copy(source = SourceSelection.All)) }
        insertSession("g", instant("2026-10-05T23:00:00Z"), instant("2026-10-06T07:00:00Z"))
        insertSession("demo:1", instant("2026-10-04T23:00:00Z"), instant("2026-10-05T07:00:00Z"), SleepConfig.DEMO_ORIGIN, SourceKind.DEMO)

        val nights = repository().nights.first { it.isNotEmpty() }

        assertThat(nights.map { it.wakeDate }).containsExactly(date)
    }

    @Test
    fun `revert brings back exactly the raw night`() = runTest {
        assumeNightAssemblyImplemented()
        insertSession("g", instant("2026-10-05T23:00:00Z"), instant("2026-10-06T07:00:00Z"))
        val repository = repository()
        val raw = repository.rawNight(date).first()!!

        repository.saveOverride(
            NightOverride(wakeDate = date, wokeUp = instant("2026-10-06T06:00:00Z"), offset = ZoneOffset.UTC, updatedAt = Instant.EPOCH),
        )
        repository.addAwake(date, instant("2026-10-06T02:00:00Z"), instant("2026-10-06T02:30:00Z"))
        val edited = repository.night(date).first { it?.isEdited == true && it.interruptions.isNotEmpty() }!!
        assertThat(edited.mainSleep).isEqualTo(Duration.ofMinutes(6 * 60 + 30))

        repository.revert(date)

        assertThat(repository.night(date).first { it?.isEdited == false && it.interruptions.isEmpty() }).isEqualTo(raw)
    }

    @Test
    fun `manual night shows up as a present night`() = runTest {
        assumeNightAssemblyImplemented()
        val repository = repository()

        repository.addManualNight(date, instant("2026-10-05T22:00:00Z"), instant("2026-10-06T06:00:00Z"), ZoneOffset.UTC)

        val night = repository.night(date).first { it != null }!!
        assertThat(night.isPresent).isTrue()
        assertThat(night.isManual).isTrue()
    }
}
