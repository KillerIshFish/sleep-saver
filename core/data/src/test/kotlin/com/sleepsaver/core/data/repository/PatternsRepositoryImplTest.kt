package com.sleepsaver.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.AnchorEntity
import com.sleepsaver.core.data.db.HushPlayEntity
import com.sleepsaver.core.data.db.NightMarkEntity
import com.sleepsaver.core.data.db.NightStepLogEntity
import com.sleepsaver.core.data.testing.FakeScheduleRepository
import com.sleepsaver.core.data.testing.FakeSettingsRepository
import com.sleepsaver.core.data.testing.FakeSleepRepository
import com.sleepsaver.core.data.testing.FakeTimeProvider
import com.sleepsaver.core.data.testing.inMemoryDatabase
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.data.testing.latencySource
import com.sleepsaver.core.data.testing.testNight
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.patterns.EffectMetric
import com.sleepsaver.core.domain.patterns.KeelResult
import com.sleepsaver.core.domain.patterns.OddWay
import com.sleepsaver.core.domain.schedule.AlarmMode
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset

@RunWith(AndroidJUnit4::class)
class PatternsRepositoryImplTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = inMemoryDatabase(context)
    private val config = SleepConfig.DEFAULT

    /** Tuesday 2026-10-06, 12:00 UTC (the device zone). */
    private val today = LocalDate.of(2026, 10, 6)
    private val time = FakeTimeProvider(now = instant("2026-10-06T12:00:00Z"))
    private val sleep = FakeSleepRepository()
    private val schedule = FakeScheduleRepository()
    private val settings = FakeSettingsRepository()

    @After
    fun tearDown() = db.close()

    private fun TestScope.repository() = PatternsRepositoryImpl(
        sleep,
        schedule,
        settings,
        db,
        latencySource(sleep, db, time),
        time,
        StandardTestDispatcher(testScheduler),
    )

    /** The [count] nights ending today, oldest first. */
    private fun nights(count: Long, night: (LocalDate) -> Night = { testNight(it) }) =
        (0 until count).map { night(today.minusDays(it)) }.reversed()

    private fun epochMs(date: LocalDate, time: String) = date.atTime(LocalTime.parse(time)).toInstant(ZoneOffset.UTC).toEpochMilli()

    @Test
    fun `keel calibrates until seven nights, then scores regular nights 100`() = runTest {
        sleep.state.value = nights(3)
        val repository = repository()
        assertThat(repository.keel.first()).isEqualTo(KeelResult.Calibrating(validDays = 3, needed = config.keel.minValidDays))

        sleep.state.value = nights(14)

        val ready = repository.keel.first { it is KeelResult.Ready } as KeelResult.Ready
        assertThat(ready.score).isEqualTo(100)
    }

    @Test
    fun `the deep prior is built from staged nights, and never in demo mode`() = runTest {
        sleep.state.value = nights(config.surfacing.priorMinNights.toLong()) { date ->
            val night = testNight(date)
            val main = night.main!!
            val middle = main.start.plus(Duration.ofHours(1))
            night.copy(
                main = main.copy(
                    stages = listOf(
                        SleepStage(main.start, middle, SleepStageType.DEEP),
                        SleepStage(middle, main.end, SleepStageType.LIGHT),
                    ),
                ),
            )
        }
        val repository = repository()
        assertThat(repository.deepPrior.first { it != null }!!.nightsUsed).isEqualTo(config.surfacing.priorMinNights)

        settings.state.update { it.copy(demoMode = true) }

        assertThat(repository.deepPrior.first { it == null }).isNull()
    }

    @Test
    fun `mark statistics merge stored and derived marks, each from its first date`() = runTest {
        sleep.state.value = nights(14) // 2026-09-23 .. 10-06
        RoutineActivityStore(db, context).seedIfEmpty()
        val ids = db.routineActivityDao().getAll()
            .filter { it.techniqueKey in setOf(Technique.SCREENS_DOWN.name, Technique.TOMORROW_LIST.name) }
            .map { it.id }
        // Drop anchor on every other night from the first one: 7 with, 7 without.
        (0L until 14L step 2).forEach { back ->
            val date = today.minusDays(13 - back)
            db.anchorDao().insert(AnchorEntity(atEpochMs = epochMs(date.minusDays(1), "22:45"), offsetSec = 0, wakeDate = "$date", source = "TONIGHT", wholeNightLog = false))
        }
        // Hush on six nights from 09-25: nights before it don't count.
        (0L until 6L).forEach { i ->
            val date = LocalDate.of(2026, 9, 25).plusDays(i * 2)
            db.hushPlayDao().insert(HushPlayEntity(startEpochMs = epochMs(date.minusDays(1), "22:30"), endEpochMs = null, offsetSec = 0, color = "BROWN", wakeDate = "$date"))
        }
        // Alcohol on the last six nights only: nothing without it since its first date.
        (0L until 6L).forEach { back -> db.nightMarkDao().insert(NightMarkEntity("${today.minusDays(back)}", "ALCOHOL", 0, 0)) }
        // Night steps done on the evening of 09-30 (2 of 3 steps); one step on 10-02 isn't enough.
        ids.forEach { db.nightStepLogDao().upsert(NightStepLogEntity("2026-09-30", it, null, 0, 0)) }
        db.nightStepLogDao().upsert(NightStepLogEntity("2026-10-02", ids.first(), null, 0, 0))
        val repository = repository()

        val effects = repository.markEffects.first { it.isNotEmpty() }
            .filter { it.metric == EffectMetric.TOTAL_SLEEP }
            .associateBy { it.mark }

        assertThat(effects.getValue(Mark.ANCHOR_DROPPED).let { it.withNights to it.withoutNights }).isEqualTo(7 to 7)
        assertThat(effects.getValue(Mark.HUSH_USED).let { it.withNights to it.withoutNights }).isEqualTo(6 to 6)
        assertThat(effects.getValue(Mark.ALCOHOL).let { it.withNights to it.withoutNights }).isEqualTo(6 to 0)
        // From 10-01 (the evening of 09-30 + 1): only 10-01 is marked.
        assertThat(effects.getValue(Mark.NIGHT_STEPS_DONE).let { it.withNights to it.withoutNights }).isEqualTo(1 to 5)
        assertThat(effects.getValue(Mark.STRESS).withNights).isEqualTo(0)
        assertThat(effects.keys).containsExactlyElementsIn(Mark.entries)
    }

    @Test
    fun `anchor stats use the anchor that counts for the night, or the latest one before the night is in`() = runTest {
        sleep.state.value = listOf(testNight(today)) // asleep 23:00
        db.anchorDao().insert(AnchorEntity(atEpochMs = epochMs(today.minusDays(1), "22:40"), offsetSec = 0, wakeDate = "$today", source = "TONIGHT", wholeNightLog = false))
        // 50 min after the watch saw sleep: not this night's anchor.
        db.anchorDao().insert(AnchorEntity(atEpochMs = epochMs(today.minusDays(1), "23:50"), offsetSec = 0, wakeDate = "$today", source = "NIGHT_SCREEN", wholeNightLog = false))
        val tomorrow = today.plusDays(1)
        db.anchorDao().insert(AnchorEntity(atEpochMs = epochMs(today, "22:50"), offsetSec = 0, wakeDate = "$tomorrow", source = "TONIGHT", wholeNightLog = false))
        val repository = repository()

        val stats = repository.anchorStats(today).first { it != null }!!
        assertThat(stats.latency).isEqualTo(Duration.ofMinutes(20))
        assertThat(stats.latencyShown).isEqualTo(Duration.ofMinutes(20))
        val pending = repository.anchorStats(tomorrow).first { it != null }!!
        assertThat(pending.anchor.at).isEqualTo(instant("2026-10-06T22:50:00Z"))
        assertThat(pending.latency).isNull()
        assertThat(repository.anchorStats(today.minusDays(1)).first()).isNull()
    }

    @Test
    fun `an odd night shows against the four weeks before it`() = runTest {
        sleep.state.value = nights(10) + testNight(today.plusDays(1), wokeUp = "04:30")

        val odd = repository().oddNight(today.plusDays(1)).first { it != null }!!

        assertThat(odd.ways).containsExactly(OddWay.LESS_SLEEP)
        assertThat(repository().oddNight(today).first()).isNull()
    }

    @Test
    fun `the plan check and the debt preview inputs carry the plan and the personal latency`() = runTest {
        sleep.state.value = nights(7)
        // Seven Drop anchors 30 min before the watch's onset: the personal latency is 30 min.
        nights(7).forEach { night ->
            val date = night.wakeDate
            db.anchorDao().insert(AnchorEntity(atEpochMs = epochMs(date.minusDays(1), "22:30"), offsetSec = 0, wakeDate = "$date", source = "TONIGHT", wholeNightLog = false))
        }
        schedule.updateDay(DayOfWeek.WEDNESDAY) { day ->
            day.copy(morning = day.morning.copy(wakeTime = LocalTime.of(6, 0), alarm = day.morning.alarm.copy(mode = AlarmMode.FIXED)))
        }
        val repository = repository()

        val inputs = repository.debtPreviewInputs.first { it?.settings?.personalLatency != null }!!
        assertThat(inputs.settings.personalLatency).isEqualTo(Duration.ofMinutes(30))
        assertThat(inputs.settings.week).isEqualTo(schedule.weekState.value)
        assertThat(inputs.nights).hasSize(7)
        assertThat(inputs.zone).isEqualTo(ZoneOffset.UTC)
        assertThat(repository.personalLatency.first { it.active }.median).isEqualTo(Duration.ofMinutes(30))
        assertThat(repository.planCheck.first { it != null }!!.plannedKeel).isNotNull()
        assertThat(repository.socialJetlag.first()).isNull()
    }
}
