package com.sleepsaver.feature.winddown

import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.data.hooks.ReminderPlanner
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.domain.nightsteps.StepAdherence
import com.sleepsaver.core.domain.nightsteps.StepSuggestion
import com.sleepsaver.core.domain.nightsteps.SuggestionReason
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.patterns.EffectMetric
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.PatternLabel
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.testing.FakeInsightsRepository
import com.sleepsaver.core.testing.FakeNightController
import com.sleepsaver.core.testing.FakeNightLogRepository
import com.sleepsaver.core.testing.FakePatternsRepository
import com.sleepsaver.core.testing.FakeScheduleRepository
import com.sleepsaver.core.testing.PlanSamples
import com.sleepsaver.feature.winddown.steps.NightStepsUiState
import com.sleepsaver.feature.winddown.steps.nightStepsUiState
import com.sleepsaver.feature.winddown.tools.GuidedClock
import com.sleepsaver.feature.winddown.tools.Haptics
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import java.time.Instant
import java.time.LocalTime

/** Records every haptic cue, in order: "tick", "doubleTick", "pulse". */
class RecordingHaptics : Haptics {
    val cues = mutableListOf<String>()

    override fun tick() {
        cues += "tick"
    }

    override fun doubleTick() {
        cues += "doubleTick"
    }

    override fun pulse() {
        cues += "pulse"
    }
}

/** The guided tools' clock on the test scheduler's virtual time (the "fake clock"). */
internal fun TestScope.testClock(): GuidedClock = GuidedClock { testScheduler.currentTime }

/** Counts replans, so tests can check that a reminder change re-arms reminders. */
class RecordingReminderPlanner : ReminderPlanner {
    var replans = 0
        private set

    override suspend fun replan() {
        replans++
    }

    override fun cancelAll() = Unit
}

/** A writer whose "app scope" and IO dispatcher run eagerly on the test scheduler: a save has finished when the call returns. */
internal fun TestScope.writer(): WinddownWriter {
    val eager = UnconfinedTestDispatcher(testScheduler)
    return WinddownWriter(backgroundScope + eager, eager)
}

/**
 * The night in focus over fakes. The insights default to no nights, so each night is timed from tonight's steps and
 * that morning's alarm in [schedule] (its plannedBedtime and window).
 */
internal fun nightSource(
    nightSteps: NightStepsRepository,
    time: TimeProvider,
    schedule: ScheduleRepository = FakeScheduleRepository(),
    nightController: NightController = FakeNightController(),
    insights: InsightsRepository = FakeInsightsRepository(InsightsState.NoNights),
    nightLog: NightLogRepository = FakeNightLogRepository(),
    patterns: PatternsRepository = FakePatternsRepository(),
): WinddownNightSource = WinddownNightSource(insights, schedule, nightLog, patterns, nightSteps, nightController, time)

/** PlanSamples' seven activities plus Body scan (8), Hush (9) and a custom Herbal tea (10), all three selected. */
internal fun richActivities(): List<RoutineActivity> {
    val config = SleepConfig.DEFAULT
    fun technique(id: Long, technique: Technique, label: String) = config.steps.timing(technique).let { timing ->
        RoutineActivity(id, label, technique.emoji, isCustom = false, selected = true, technique = technique, lead = timing.lead, duration = timing.duration)
    }
    return PlanSamples.activities() +
        technique(8, Technique.BODY_SCAN, "Body scan") +
        technique(9, Technique.HUSH, "Hush") +
        RoutineActivity(10, "Herbal tea", "🍵", isCustom = true, selected = true)
}

/**
 * Tonight (Tue Oct 6, bedtime 23:00, Night steps from 21:30) with [richActivities]: Screens down 22:00, Tomorrow list
 * and Herbal tea 22:15, Slow tide 22:30, Body scan 22:40, Hush 22:50; the first [done] checked.
 */
internal fun richSession(
    done: Int = 2,
    enabled: Boolean = true,
    fromSettingsOnly: Boolean = false,
    bedtime: LocalTime = PlanSamples.bedtime,
    activities: List<RoutineActivity> = richActivities(),
): NightStepsSession =
    PlanSamples.nightStepsSession(done = done, enabled = enabled, fromSettingsOnly = fromSettingsOnly, activities = activities, bedtime = bedtime)

/** Screen states for content and screenshot tests. */
internal object WinddownSamples {
    /** Tue Oct 6, 21:50: the Night steps window is open, before bedtime. */
    val evening: Instant = PlanSamples.at(PlanSamples.today, LocalTime.of(21, 50))

    /** Slow tide and Cool, dark room: the content test's "Add" checks Slow tide's label. */
    val suggestions = listOf(
        StepSuggestion(Technique.SLOW_TIDE, SuggestionReason.SLOW_TO_SETTLE),
        StepSuggestion(Technique.COOL_DARK_ROOM, SuggestionReason.RESTLESS_NIGHTS),
    )

    /** Night steps evenings: a clear sleep pattern and a possible feel pattern. */
    val effects = listOf(
        MarkEffect(Mark.NIGHT_STEPS_DONE, EffectMetric.TOTAL_SLEEP, PatternLabel.CLEAR, withNights = 9, withoutNights = 12, difference = 24.0, low = 16.0, high = 33.0),
        MarkEffect(Mark.NIGHT_STEPS_DONE, EffectMetric.MORNING_FEEL, PatternLabel.POSSIBLE, withNights = 8, withoutNights = 10, difference = 0.5, low = 0.1, high = 0.9),
    )

    fun nightSteps(
        session: NightStepsSession? = richSession(),
        nightState: NightState = NightState(),
        suggestions: List<StepSuggestion> = this.suggestions,
        effects: List<MarkEffect> = emptyList(),
        marks: Set<Mark> = setOf(Mark.LATE_MEAL),
        adherence: StepAdherence = PlanSamples.adherence(),
        week: WeekPlan = PlanSamples.weekPlan(),
        now: Instant = evening,
        alarm: PlannedAlarm? = PlanSamples.plannedAlarm(),
    ): NightStepsUiState = nightStepsUiState(
        session = session,
        focus = winddownFocus(
            now = now,
            zone = PlanSamples.zone,
            schedule = null,
            session = session,
            inputs = null,
            alarms = listOfNotNull(alarm),
            morningStop = null,
            anchoredAt = nightState.night?.anchoredAt,
        ),
        adherence = adherence,
        suggestions = suggestions,
        effects = effects,
        marks = marks,
        week = week,
        nextAlarm = alarm,
        nightState = nightState,
        hush = HushChoice(),
        zone = PlanSamples.zone,
    )
}
