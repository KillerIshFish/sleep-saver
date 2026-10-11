package com.sleepsaver.core.data.repository

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.NightStepLogEntity
import com.sleepsaver.core.data.testing.FakeInsightsRepository
import com.sleepsaver.core.data.testing.FakeScheduleRepository
import com.sleepsaver.core.data.testing.FakeSettingsRepository
import com.sleepsaver.core.data.testing.FakeSleepRepository
import com.sleepsaver.core.data.testing.FakeTimeProvider
import com.sleepsaver.core.data.testing.inMemoryDatabase
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.data.testing.latencySource
import com.sleepsaver.core.data.testing.testInsights
import com.sleepsaver.core.data.testing.testNight
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.model.LastNightState
import com.sleepsaver.core.domain.model.Phase
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.nightsteps.EveningState
import com.sleepsaver.core.domain.nightsteps.StepSuggestion
import com.sleepsaver.core.domain.nightsteps.SuggestionReason
import com.sleepsaver.core.domain.nightsteps.Technique
import kotlinx.coroutines.flow.MutableStateFlow
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
class NightStepsRepositoryImplTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val db = inMemoryDatabase(context)
    private val steps = SleepConfig.DEFAULT.steps

    /** Tuesday 2026-10-06, 18:00 UTC (the device zone). */
    private val today = LocalDate.of(2026, 10, 6)
    private val time = FakeTimeProvider(now = instant("2026-10-06T18:00:00Z"))
    private val insights = FakeInsightsRepository(InsightsState.NoNights)
    private val schedule = FakeScheduleRepository()
    private val sleep = FakeSleepRepository()
    private val settings = FakeSettingsRepository()
    private val anchors = MutableStateFlow<List<Anchor>>(emptyList())
    private val store = RoutineActivityStore(db, context)

    @After
    fun tearDown() = db.close()

    private fun TestScope.repository() = NightStepsRepositoryImpl(
        insights,
        schedule,
        HabitsRepositoryImpl(db, store),
        sleep,
        settings,
        time,
        db,
        latencySource(sleep, anchors, time),
        store,
        StandardTestDispatcher(testScheduler),
    )

    private suspend fun activityId(technique: Technique): Long =
        db.routineActivityDao().getAll().first { it.techniqueKey == technique.name }.id

    // ------------------------------------------------------------ tonight

    @Test
    fun `without nights tonight is timed from Settings' bedtime`() = runTest {
        settings.state.update { it.copy(sleep = it.sleep.copy(bedtime = LocalTime.of(22, 30))) }
        val repository = repository()

        val session = repository.tonight.first { it != null && it.steps.isNotEmpty() }!!

        assertThat(session.fromSettingsOnly).isTrue()
        assertThat(session.nightOf).isEqualTo(today)
        assertThat(session.bedtime).isEqualTo(instant("2026-10-06T22:30:00Z"))
        // The wind-down phase start: B − 30 min melatonin half-width − the default 60 min.
        assertThat(session.windowStart).isEqualTo(instant("2026-10-06T21:00:00Z"))
        assertThat(session.enabled).isTrue()
        // The three default steps, each lead before B: Screens down 60, Tomorrow list 45, Slow tide 30.
        assertThat(session.steps.map { it.activity.technique })
            .containsExactly(Technique.SCREENS_DOWN, Technique.TOMORROW_LIST, Technique.SLOW_TIDE).inOrder()
        assertThat(session.steps.map { it.start }).containsExactly(
            instant("2026-10-06T21:30:00Z"),
            instant("2026-10-06T21:45:00Z"),
            instant("2026-10-06T22:00:00Z"),
        ).inOrder()
    }

    @Test
    fun `without nights the evening plan's own bedtime, length and switch win`() = runTest {
        schedule.updateDay(DayOfWeek.TUESDAY) { day ->
            day.copy(evening = day.evening.copy(bedtime = LocalTime.of(0, 15), windDownLength = Duration.ofMinutes(90), nightStepsOn = false))
        }
        // 00:40 on Wednesday: still Tuesday's evening (its bedtime 00:15 + 30 min hasn't passed).
        time.now = instant("2026-10-07T00:40:00Z")
        time.ticks.value = time.now
        val repository = repository()

        val session = repository.tonight.first { it != null && it.steps.isNotEmpty() }!!

        assertThat(session.nightOf).isEqualTo(today)
        assertThat(session.bedtime).isEqualTo(instant("2026-10-07T00:15:00Z"))
        assertThat(session.windowStart).isEqualTo(instant("2026-10-06T22:15:00Z"))
        assertThat(session.enabled).isFalse()
        assertThat(session.fromSettingsOnly).isTrue()
    }

    @Test
    fun `with nights tonight follows the schedule's evening, bedtime and wind-down phase`() = runTest {
        val insights = testInsights(today, LastNightState.AVAILABLE)
        val bedtime = insights.schedule.targetBedtime
        val windDown = Phase(PhaseType.WIND_DOWN, bedtime.minus(Duration.ofMinutes(105)), bedtime.minus(Duration.ofMinutes(30)))
        this@NightStepsRepositoryImplTest.insights.mutableState.value =
            InsightsState.Ready(insights.copy(schedule = insights.schedule.copy(phases = listOf(windDown), nightStepsOn = false)))
        val repository = repository()

        val session = repository.tonight.first { it != null && it.steps.isNotEmpty() }!!

        assertThat(session.fromSettingsOnly).isFalse()
        assertThat(session.nightOf).isEqualTo(today)
        assertThat(session.bedtime).isEqualTo(bedtime)
        assertThat(session.windowStart).isEqualTo(windDown.start)
        assertThat(session.enabled).isFalse()
    }

    @Test
    fun `an evening's own routine replaces the every-evening list`() = runTest {
        store.seedIfEmpty()
        val reading = activityId(Technique.READ_ON_PAPER)
        schedule.setDayRoutine(DayOfWeek.TUESDAY, listOf(reading))
        val repository = repository()

        val session = repository.tonight.first { it != null && it.steps.isNotEmpty() }!!

        assertThat(session.steps.map { it.activity.id }).containsExactly(reading)
    }

    @Test
    fun `checking a step logs it for that evening, and unchecking removes it`() = runTest {
        val repository = repository()
        repository.tonight.first { it != null && it.steps.isNotEmpty() }
        val slowTide = activityId(Technique.SLOW_TIDE)

        repository.setDone(today, slowTide, done = true)
        val done = repository.tonight.first { it?.doneCount == 1 }!!
        assertThat(done.steps.single { it.done }.activity.id).isEqualTo(slowTide)
        assertThat(db.nightStepLogDao().getAll().single().techniqueKey).isEqualTo(Technique.SLOW_TIDE.name)
        assertThat(repository.session(today).first { it?.doneCount == 1 }!!.nightOf).isEqualTo(today)

        repository.setDone(today, slowTide, done = false)
        assertThat(repository.tonight.first { it?.doneCount == 0 }!!.doneCount).isEqualTo(0)
    }

    @Test
    fun `a past evening's session falls back to Settings' bedtime without a schedule`() = runTest {
        val repository = repository()

        val session = repository.session(today.minusDays(2)).first { it != null && it.steps.isNotEmpty() }!!

        assertThat(session.nightOf).isEqualTo(today.minusDays(2))
        assertThat(session.bedtime).isEqualTo(instant("2026-10-04T23:00:00Z"))
        assertThat(session.fromSettingsOnly).isTrue()
    }

    // ------------------------------------------------------------ tracking and suggestions

    @Test
    fun `adherence counts done evenings and leaves tonight open`() = runTest {
        store.seedIfEmpty()
        val ids = listOf(Technique.SCREENS_DOWN, Technique.TOMORROW_LIST).map { activityId(it) }
        for (evening in listOf(today.minusDays(2), today.minusDays(1))) {
            ids.forEach { id -> db.nightStepLogDao().upsert(NightStepLogEntity(evening.toString(), id, null, 0, 0)) }
        }
        val repository = repository()

        val adherence = repository.adherence.first { it.evenings.isNotEmpty() }

        assertThat(adherence.doneRecent).isEqualTo(2)
        assertThat(adherence.countedRecent).isEqualTo(2)
        assertThat(adherence.quietStreak).isEqualTo(2)
        assertThat(adherence.evenings.last().nightOf).isEqualTo(today)
        assertThat(adherence.evenings.last().state).isEqualTo(EveningState.OPEN)
    }

    @Test
    fun `suggestions use the Drop anchor latencies, most recent first`() = runTest {
        sleep.state.value = (0L..3L).map { testNight(today.minusDays(it)) }.reversed()
        val repository = repository()
        // Tomorrow list out of the routine, so the slow-to-settle rule can offer it.
        store.seedIfEmpty()
        repository.setIncluded(activityId(Technique.TOMORROW_LIST), included = false)
        val starters = repository.suggestions.first { it.isNotEmpty() }
        assertThat(starters.map { it.reason }.toSet()).containsExactly(SuggestionReason.STARTER)

        // Three nights settling 40 min after Drop anchor (the watch saw sleep at 23:00).
        anchors.value = (0L..2L).map { back ->
            val wakeDate = today.minusDays(back)
            Anchor(back + 1, wakeDate.minusDays(1).atTime(22, 20).toInstant(ZoneOffset.UTC), ZoneOffset.UTC, wakeDate, AnchorSource.TONIGHT, false)
        }

        val suggestions = repository.suggestions.first { list -> list.any { it.reason == SuggestionReason.SLOW_TO_SETTLE } }
        assertThat(suggestions.first()).isEqualTo(StepSuggestion(Technique.TOMORROW_LIST, SuggestionReason.SLOW_TO_SETTLE))
        assertThat(suggestions).hasSize(steps.suggestionMax)
    }

    // ------------------------------------------------------------ routine activities

    @Test
    fun `adding a technique seeds the defaults first, and selects an existing one instead of adding it twice`() = runTest {
        val repository = repository()

        val bodyScan = repository.addTechnique(Technique.BODY_SCAN, "Body scan")
        val rows = db.routineActivityDao().getAll()
        assertThat(rows).hasSize(8)
        val added = rows.single { it.id == bodyScan }
        assertThat(added.techniqueKey).isEqualTo(Technique.BODY_SCAN.name)
        assertThat(added.emoji).isEqualTo(Technique.BODY_SCAN.emoji)
        assertThat(added.selected).isTrue()
        assertThat(added.sortOrder).isEqualTo(7)
        assertThat(added.leadMin.toLong()).isEqualTo(steps.timing(Technique.BODY_SCAN).lead.toMinutes())

        val reading = repository.addTechnique(Technique.READ_ON_PAPER, "Read on paper")
        assertThat(reading).isEqualTo(activityId(Technique.READ_ON_PAPER))
        assertThat(db.routineActivityDao().getAll()).hasSize(8)
        assertThat(db.routineActivityDao().getAll().single { it.id == reading }.selected).isTrue()
        assertThat(repository.activities.first { list -> list.any { it.id == bodyScan } }).hasSize(8)
    }

    @Test
    fun `custom activities are clamped, retimed through the store and the only ones that can be deleted`() = runTest {
        val repository = repository()

        val tea = repository.addCustom(" Tea ", "🍵", lead = Duration.ofMinutes(300), duration = Duration.ofMinutes(90))
        val row = db.routineActivityDao().getAll().single { it.id == tea }
        assertThat(row.label).isEqualTo("Tea")
        assertThat(row.isCustom).isTrue()
        assertThat(row.leadMin.toLong()).isEqualTo(steps.leadMax.toMinutes())
        assertThat(row.durationMin.toLong()).isEqualTo(steps.durationMax.toMinutes())

        repository.setTiming(tea, Duration.ofMinutes(25), Duration.ofMinutes(5))
        assertThat(db.routineActivityDao().getAll().single { it.id == tea }.leadMin).isEqualTo(25)

        val seeded = activityId(Technique.SLOW_TIDE)
        assertThat(runCatching { repository.deleteCustom(seeded) }.exceptionOrNull()).isInstanceOf(IllegalArgumentException::class.java)
        repository.deleteCustom(tea)
        assertThat(db.routineActivityDao().getAll().map { it.id }).doesNotContain(tea)
    }
}
