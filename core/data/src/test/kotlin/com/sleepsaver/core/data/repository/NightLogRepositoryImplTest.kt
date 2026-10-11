package com.sleepsaver.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.testing.FakeSettingsRepository
import com.sleepsaver.core.data.testing.FakeSleepRepository
import com.sleepsaver.core.data.testing.FakeTimeProvider
import com.sleepsaver.core.data.testing.inMemoryDatabase
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.data.testing.testNight
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.LandingStage
import com.sleepsaver.core.domain.alarm.MovementEpoch
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.RingReason
import com.sleepsaver.core.domain.alarm.TuningReason
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.WakeStyle
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class NightLogRepositoryImplTest {

    private val db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
    private val config = SleepConfig.DEFAULT

    /** Tuesday 2026-10-06, 02:00 UTC (the device zone): the night before Tuesday's wake. */
    private val today = LocalDate.of(2026, 10, 6)
    private val time = FakeTimeProvider(now = instant("2026-10-06T02:00:00Z"))
    private val sleep = FakeSleepRepository()
    private val settings = FakeSettingsRepository()

    @After
    fun tearDown() = db.close()

    private fun TestScope.repository() = NightLogRepositoryImpl(db, sleep, settings, time, StandardTestDispatcher(testScheduler))

    private fun event(
        wakeDate: LocalDate,
        firstRingAt: Instant = wakeDate.atTime(6, 20).toInstant(ZoneOffset.UTC),
        reason: RingReason = RingReason.RESTLESS,
        outcome: RingOutcome? = RingOutcome.STOPPED,
        kind: RingKind = RingKind.PLANNED,
    ) = AlarmEvent(
        kind = kind,
        occurrenceId = if (kind == RingKind.PLANNED) "${wakeDate}T06:30" else null,
        wakeDate = wakeDate,
        target = wakeDate.atTime(6, 30).toInstant(ZoneOffset.UTC),
        windowStart = wakeDate.atTime(6, 0).toInstant(ZoneOffset.UTC),
        mode = AlarmMode.SURFACING,
        style = WakeStyle.STANDARD,
        firstRingAt = firstRingAt,
        reason = reason,
        outcome = outcome,
        offset = ZoneOffset.UTC,
    )

    /** A night the watch scored as deep sleep from start to end. */
    private fun deepNight(wakeDate: LocalDate): Night {
        val night = testNight(wakeDate)
        val main = night.main!!
        return night.copy(main = main.copy(stages = listOf(SleepStage(main.start, main.end, SleepStageType.DEEP))))
    }

    // ------------------------------------------------------------ anchors

    @Test
    fun `an anchor belongs to the wake date of its moment`() = runTest {
        val repository = repository()

        val evening = repository.addAnchor(instant("2026-10-05T23:10:00Z"), ZoneOffset.UTC, AnchorSource.TONIGHT, wholeNightLog = true)
        repository.addAnchor(instant("2026-10-06T01:00:00Z"), ZoneOffset.UTC, AnchorSource.NIGHT_SCREEN, wholeNightLog = false)

        val anchors = repository.anchors(today).first()
        assertThat(anchors.map { it.id }).contains(evening)
        assertThat(anchors).hasSize(2)
        assertThat(anchors.first().wholeNightLog).isTrue()
        assertThat(repository.allAnchors.first()).hasSize(2)

        repository.deleteAnchor(evening)
        assertThat(repository.anchors(today).first().map { it.source }).containsExactly(AnchorSource.NIGHT_SCREEN)
    }

    @Test
    fun `the active anchor lasts until the planned alarm ends or the night runs out`() = runTest {
        val repository = repository()
        val id = repository.addAnchor(instant("2026-10-05T23:10:00Z"), ZoneOffset.UTC, AnchorSource.TONIGHT, wholeNightLog = false)

        assertThat(repository.activeAnchor.first { it != null }!!.id).isEqualTo(id)

        // Ringing: still the night.
        val ringing = repository.recordAlarmEvent(event(today, outcome = null))
        assertThat(repository.activeAnchor.first()!!.id).isEqualTo(id)

        // Stopped: the night is over.
        repository.recordAlarmEvent(event(today, outcome = RingOutcome.STOPPED).copy(id = ringing))
        assertThat(repository.activeAnchor.first { it == null }).isNull()
    }

    @Test
    fun `the active anchor ends by itself after the longest night`() = runTest {
        val repository = repository()
        repository.addAnchor(instant("2026-10-05T23:10:00Z"), ZoneOffset.UTC, AnchorSource.TONIGHT, wholeNightLog = false)
        assertThat(repository.activeAnchor.first { it != null }).isNotNull()

        time.ticks.value = instant("2026-10-05T23:10:00Z").plus(config.anchor.nightMaxLength)

        assertThat(repository.activeAnchor.first { it == null }).isNull()
    }

    // ------------------------------------------------------------ movement

    @Test
    fun `movement keeps the non-missing epochs of each log`() = runTest {
        val repository = repository()
        val start = instant("2026-10-06T05:45:00Z")
        val logId = repository.startMovementLog(start, ZoneOffset.UTC, "WINDOW", today, anchorId = null, occurrenceId = "2026-10-06T06:30")

        repository.addEpochs(
            logId,
            listOf(
                MovementEpoch(start, 4.0),
                MovementEpoch(start.plusSeconds(30), null),
                MovementEpoch(start.plusSeconds(60), 31.5),
            ),
        )
        repository.addEpochs(logId, listOf(MovementEpoch(start.plusSeconds(90), null)))
        repository.endMovementLog(logId, start.plusSeconds(120))

        val trace = repository.movement(today).first { list -> list.any { it.end != null } }.single()
        assertThat(trace.logId).isEqualTo(logId)
        assertThat(trace.mode).isEqualTo("WINDOW")
        assertThat(trace.end).isEqualTo(start.plusSeconds(120))
        assertThat(trace.epochs).containsExactly(MovementEpoch(start, 4.0), MovementEpoch(start.plusSeconds(60), 31.5)).inOrder()
    }

    // ------------------------------------------------------------ alarm events

    @Test
    fun `recording an event returns its id, also on update`() = runTest {
        val repository = repository()

        val id = repository.recordAlarmEvent(event(today, outcome = null))
        val updated = repository.recordAlarmEvent(
            event(today, outcome = RingOutcome.STOPPED).copy(id = id, snoozeTimes = listOf(instant("2026-10-06T06:21:00Z"))),
        )

        assertThat(id).isGreaterThan(0L)
        assertThat(updated).isEqualTo(id)
        val stored = repository.alarmEvent(today).first()!!
        assertThat(stored.outcome).isEqualTo(RingOutcome.STOPPED)
        assertThat(stored.snoozeTimes).containsExactly(instant("2026-10-06T06:21:00Z"))
        assertThat(db.alarmEventDao().getAll()).hasSize(1)
    }

    @Test
    fun `handled ids are planned events with an outcome since a date, and tests and catnaps stay apart`() = runTest {
        val repository = repository()
        repository.recordAlarmEvent(event(today.minusDays(2)))
        repository.recordAlarmEvent(event(today))
        repository.recordAlarmEvent(event(today.plusDays(1), outcome = null))
        repository.recordAlarmEvent(event(today, kind = RingKind.TEST, firstRingAt = instant("2026-10-06T07:00:00Z")))
        repository.recordAlarmEvent(event(today, kind = RingKind.NAP, firstRingAt = instant("2026-10-06T07:30:00Z")))

        assertThat(repository.handledOccurrenceIds(today.minusDays(1))).containsExactly("2026-10-06T06:30")
        assertThat(repository.alarmEvent(today).first()!!.kind).isEqualTo(RingKind.PLANNED)
        assertThat(repository.lastTestRing.first()!!.kind).isEqualTo(RingKind.TEST)
        assertThat(repository.recentAlarmEvents.first().map { it.kind })
            .containsExactly(RingKind.PLANNED, RingKind.NAP, RingKind.TEST, RingKind.PLANNED, RingKind.PLANNED).inOrder()
    }

    // ------------------------------------------------------------ Wake landing and tuning

    @Test
    fun `Wake landing grades the planned ring against the watch's night`() = runTest {
        sleep.state.value = listOf(deepNight(today))
        val repository = repository()
        repository.recordAlarmEvent(event(today))

        val landing = repository.landing(today).first { it != null }!!

        assertThat(landing.stageAtRing).isEqualTo(LandingStage.DEEP)
        assertThat(landing.minutesEarly).isEqualTo(10)
        assertThat(landing.hasStages).isTrue()
        assertThat(repository.landing(today.minusDays(1)).first()).isNull()
    }

    @Test
    fun `refreshing the tuning raises the trigger after deep restless rings, once`() = runTest {
        val dates = (0L..2L).map { today.minusDays(it) }
        sleep.state.value = dates.map { deepNight(it) }.reversed()
        val repository = repository()
        dates.forEach { repository.recordAlarmEvent(event(it)) }
        time.now = instant("2026-10-06T09:00:00Z")

        repository.refreshTuning()
        repository.refreshTuning()

        val tuning = repository.tuning.first()!!
        assertThat(tuning.reason).isEqualTo(TuningReason.RAISED_AFTER_DEEP_RINGS)
        assertThat(tuning.zThreshold).isWithin(1e-9).of(config.surfacing.restlessZ + config.surfacing.restlessZStep)
        assertThat(tuning.changedAt).isEqualTo(time.now)
    }

    @Test
    fun `refreshing the tuning does nothing in demo mode, and reset stores a RESET row at the default`() = runTest {
        sleep.state.value = (0L..2L).map { deepNight(today.minusDays(it)) }.reversed()
        val repository = repository()
        (0L..2L).forEach { repository.recordAlarmEvent(event(today.minusDays(it))) }
        settings.state.update { it.copy(demoMode = true) }

        repository.refreshTuning()
        assertThat(repository.tuning.first()).isNull()

        time.now = instant("2026-10-06T09:00:00Z")
        repository.resetTuning()
        val reset = repository.tuning.first()!!
        assertThat(reset.reason).isEqualTo(TuningReason.RESET)
        assertThat(reset.zThreshold).isEqualTo(config.surfacing.restlessZ)

        // The rings before the reset don't count again.
        settings.state.update { it.copy(demoMode = false) }
        time.now = time.now.plus(Duration.ofHours(8))
        repository.refreshTuning()
        assertThat(repository.tuning.first()!!.reason).isEqualTo(TuningReason.RESET)
    }

    // ------------------------------------------------------------ Hush

    @Test
    fun `a Hush play belongs to the wake date of its start and gets its end later`() = runTest {
        val repository = repository()

        val id = repository.startHushPlay(instant("2026-10-05T22:40:00Z"), ZoneOffset.UTC, HushColor.PINK)
        repository.endHushPlay(id, instant("2026-10-05T23:25:00Z"))

        val play = db.hushPlayDao().getAll().single()
        assertThat(play.wakeDate).isEqualTo("2026-10-06")
        assertThat(play.color).isEqualTo("PINK")
        assertThat(play.endEpochMs).isEqualTo(instant("2026-10-05T23:25:00Z").toEpochMilli())
    }
}
