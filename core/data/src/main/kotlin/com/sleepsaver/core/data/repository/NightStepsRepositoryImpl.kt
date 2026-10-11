package com.sleepsaver.core.data.repository

import com.sleepsaver.core.data.db.NightMarkEntity
import com.sleepsaver.core.data.db.RoutineActivityEntity
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.di.DefaultDispatcher
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.mapping.toDomain
import com.sleepsaver.core.data.mapping.toDomainOrNull
import com.sleepsaver.core.data.mapping.toEntity
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.nightsteps.NightStepsPlanner
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.domain.nightsteps.StepAdherence
import com.sleepsaver.core.domain.nightsteps.StepLog
import com.sleepsaver.core.domain.nightsteps.StepSuggestion
import com.sleepsaver.core.domain.nightsteps.StepSuggestionEngine
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.nightsteps.routineFor
import com.sleepsaver.core.domain.nightsteps.stepAdherence
import com.sleepsaver.core.domain.schedule.EveningPlan
import com.sleepsaver.core.domain.schedule.NightRules
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.ScheduleTimes
import com.sleepsaver.core.domain.schedule.WeekPlan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Night steps: tonight's checklist, its tracking and suggestions, and the routine activities it is built from.
 *
 * Tonight is the evening of the home screen's schedule (DailyInsights.schedule.date): its steps sit before that
 * schedule's bedtime B and the window starts at its wind-down phase. Without nights (first run, no Health Connect) the
 * evening, B and the window come from the evening plan's bedtime or Settings' bedtime instead (fromSettingsOnly).
 * Example: B = 23:00 with a 90-min wind-down → "Night steps 21:00–23:00", Slow tide (lead 30, 10 min) at 22:30–22:40.
 *
 * Every first write to routine_activities goes through [RoutineActivityStore], so the seven defaults are seeded first.
 *
 * DI edges (§3.4): [InsightsRepository], [ScheduleRepository], [HabitsRepository], [SleepRepository],
 * [SettingsRepository], [TimeProvider], the database, [PersonalLatencySource] and [RoutineActivityStore].
 */
@Singleton
class NightStepsRepositoryImpl @Inject internal constructor(
    private val insightsRepository: InsightsRepository,
    scheduleRepository: ScheduleRepository,
    habitsRepository: HabitsRepository,
    sleepRepository: SleepRepository,
    settingsRepository: SettingsRepository,
    private val timeProvider: TimeProvider,
    database: SleepSaverDatabase,
    latencySource: PersonalLatencySource,
    private val store: RoutineActivityStore,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : NightStepsRepository {

    private val config = SleepConfig.DEFAULT
    private val activityDao = database.routineActivityDao()
    private val logDao = database.nightStepLogDao()
    private val markDao = database.nightMarkDao()
    private val planner = NightStepsPlanner(config)
    private val suggestionEngine = StepSuggestionEngine(config)
    private val times = ScheduleTimes(config)

    private val weekAndOneOffs: Flow<Pair<WeekPlan, OneOffs>> = combine(
        scheduleRepository.weekPlan.orFallback(TAG, "Week plan") { WeekPlan.DEFAULT },
        scheduleRepository.oneOffs.orFallback(TAG, "One-offs") { OneOffs() },
        ::Pair,
    )
    private val sleepSettings: Flow<UserSleepSettings> = settingsRepository.settings
        .orFallback(TAG, "Settings") { AppSettings() }
        .map { it.sleep }
        .distinctUntilChanged()
    private val nights: Flow<List<Night>> = sleepRepository.nights.orFallback(TAG, "Nights") { emptyList() }
    private val ticks: Flow<Instant> = timeProvider.minuteTicks.orFallback(TAG, "Minute ticks") { timeProvider.now() }

    /** Seeded on first read (HabitsRepository); falls back to none when unreadable. */
    override val activities: Flow<List<RoutineActivity>> = habitsRepository.routineActivities

    /** Tonight's frame (null when it can't be computed); waits while the insights are still loading. */
    private val tonightEvening: Flow<Evening?> = combine(
        insightsRepository.state.filter { it !is InsightsState.Loading },
        weekAndOneOffs,
        activities,
        sleepSettings,
        ticks,
    ) { state, (week, oneOffs), activities, settings, now ->
        guarded(TAG, "Tonight's evening") {
            val schedule = (state as? InsightsState.Ready)?.insights?.schedule
            if (schedule != null) {
                eveningOf(schedule, week, oneOffs, activities)
            } else {
                fallbackEvening(nightOf = null, now, timeProvider.zone(), week, oneOffs, settings, activities)
            }
        }
    }
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()

    override val tonight: Flow<NightStepsSession?> = sessionOf(tonightEvening)

    override fun session(nightOf: LocalDate): Flow<NightStepsSession?> = sessionOf(
        combine(insightsRepository.schedule(nightOf), weekAndOneOffs, activities, sleepSettings) { schedule, (week, oneOffs), activities, settings ->
            guarded(TAG, "Evening of $nightOf") {
                if (schedule != null) {
                    eveningOf(schedule, week, oneOffs, activities)
                } else {
                    fallbackEvening(nightOf, timeProvider.now(), timeProvider.zone(), week, oneOffs, settings, activities)
                }
            }
        }
            .flowOn(defaultDispatcher)
            .distinctUntilChanged(),
    )

    /**
     * Evenings count by the plan as it is now: Night steps off on that evening, or an empty routine, makes it neutral.
     * Tonight is over from B + melatoninHalfWidth.
     */
    override val adherence: Flow<StepAdherence> = combine(
        tonightEvening.filterNotNull(),
        logDao.observeAll().orFallback(TAG, "Step logs query") { emptyList() }.map { rows -> rows.map { it.toDomain() } },
        weekAndOneOffs,
        activities,
        ticks,
    ) { evening, logs, (week, oneOffs), activities, now ->
        guarded(TAG, "Adherence") {
            stepAdherence(
                logs = logs,
                plannedCount = { date ->
                    val plan = week.resolve(date, oneOffs).evening
                    if (!plan.nightStepsOn) 0 else routineFor(plan, activities).size
                },
                tonight = evening.nightOf,
                tonightOver = now >= evening.bedtime + config.melatoninHalfWidth,
                config = config,
            )
        }
    }
        .orLastGood(NO_ADHERENCE)
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()

    /**
     * [StepSuggestionEngine] over the nights, the techniques already in tonight's routine or the every-evening list,
     * the stored marks, the Drop anchor latencies (most recent first) and today's debt status.
     */
    override val suggestions: Flow<List<StepSuggestion>> = combine(
        combine(nights, insightsRepository.state, ::Pair),
        tonightEvening.filterNotNull(),
        activities,
        markDao.observeAll().orFallback(TAG, "Marks query") { emptyList() }.map(::marksByDate),
        latencySource.latencies,
    ) { (nights, state), evening, activities, marks, latencies ->
        guarded(TAG, "Suggestions") {
            val insights = (state as? InsightsState.Ready)?.insights
            val inRoutine = activities.filter { it.selected } + evening.routine
            suggestionEngine.suggest(
                nights = nights,
                inRoutine = inRoutine.mapNotNullTo(HashSet()) { it.technique },
                routineHasScreens = inRoutine.any { it.technique?.screenBased == true },
                marks = marks,
                latencies = latencies,
                status = insights?.status,
                asOf = insights?.today ?: timeProvider.today(),
            )
        }
    }
        .orLastGood(emptyList())
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()

    // ---------------------------------------------------------------- Writes

    override suspend fun setDone(nightOf: LocalDate, activityId: Long, done: Boolean) {
        if (!done) {
            logDao.delete(nightOf.toString(), activityId)
            return
        }
        val technique = activityDao.getAll().firstOrNull { it.id == activityId }?.toDomain()?.technique
        logDao.upsert(StepLog(nightOf, activityId, technique, timeProvider.now(), timeProvider.currentOffset()).toEntity())
    }

    /** Selects the first activity carrying [technique], or adds it at the end of the grid, all under the store's lock. */
    override suspend fun addTechnique(technique: Technique, label: String): Long {
        val trimmed = label.trim()
        require(trimmed.isNotEmpty()) { "label must not be blank" }
        val timing = config.steps.timing(technique)
        return store.locked { dao ->
            val existing = dao.findByTechnique(technique.name)
            if (existing != null) {
                if (!existing.selected) dao.setSelected(existing.id, true)
                existing.id
            } else {
                dao.insert(
                    RoutineActivityEntity(
                        label = trimmed,
                        emoji = technique.emoji,
                        isCustom = false,
                        selected = true,
                        sortOrder = (dao.maxSortOrder() ?: -1) + 1,
                        techniqueKey = technique.name,
                        leadMin = timing.lead.toMinutes().toInt(),
                        durationMin = timing.duration.toMinutes().toInt(),
                    ),
                )
            }
        }
    }

    /** Selected, at the end of the grid; lead and duration clamped to the pickers' ranges. */
    override suspend fun addCustom(label: String, emoji: String, lead: Duration, duration: Duration): Long {
        val trimmed = label.trim()
        require(trimmed.isNotEmpty()) { "label must not be blank" }
        return store.insertAtEnd(
            RoutineActivityEntity(
                label = trimmed,
                emoji = emoji.trim(),
                isCustom = true,
                selected = true,
                sortOrder = 0, // the store puts it last
                techniqueKey = null,
                leadMin = lead.coerceIn(Duration.ZERO, config.steps.leadMax).toMinutes().toInt(),
                durationMin = duration.coerceIn(Duration.ZERO, config.steps.durationMax).toMinutes().toInt(),
            ),
        )
    }

    override suspend fun setTiming(activityId: Long, lead: Duration, duration: Duration) = store.setTiming(activityId, lead, duration)

    override suspend fun setIncluded(activityId: Long, included: Boolean) = activityDao.setSelected(activityId, included)

    /** Its step logs stay (history); evening-plan lists drop it (foreign key). */
    override suspend fun deleteCustom(activityId: Long) {
        val row = activityDao.getAll().firstOrNull { it.id == activityId } ?: return
        require(row.isCustom) { "only custom activities can be deleted" }
        activityDao.delete(activityId)
    }

    // ---------------------------------------------------------------- Evenings

    /** An evening's Night steps frame, before the checked steps are applied. */
    private data class Evening(
        val nightOf: LocalDate,
        val windowStart: Instant,
        val bedtime: Instant,
        val enabled: Boolean,
        val fromSettingsOnly: Boolean,
        val routine: List<RoutineActivity>,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private fun sessionOf(evening: Flow<Evening?>): Flow<NightStepsSession?> = evening
        .distinctUntilChanged()
        .flatMapLatest { frame ->
            if (frame == null) {
                flowOf(null)
            } else {
                logDao.observeFor(frame.nightOf.toString())
                    .orFallback(TAG, "Step logs query") { emptyList() }
                    .map { rows ->
                        guarded(TAG, "Night steps") {
                            planner.plan(
                                nightOf = frame.nightOf,
                                windowStart = frame.windowStart,
                                bedtime = frame.bedtime,
                                routine = frame.routine,
                                logs = rows.map { it.toDomain() },
                                enabled = frame.enabled,
                                fromSettingsOnly = frame.fromSettingsOnly,
                            )
                        }
                    }
            }
        }
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()

    /** E = the schedule's date, B its target bedtime, the window its wind-down phase; enabled = the evening plan's switch. */
    private fun eveningOf(schedule: EnergySchedule, week: WeekPlan, oneOffs: OneOffs, activities: List<RoutineActivity>): Evening {
        val plan = week.resolve(schedule.date, oneOffs).evening
        val bedtime = schedule.targetBedtime
        return Evening(
            nightOf = schedule.date,
            windowStart = schedule.phase(PhaseType.WIND_DOWN)?.start ?: windowStart(bedtime, plan),
            bedtime = bedtime,
            enabled = schedule.nightStepsOn,
            fromSettingsOnly = false,
            routine = routineFor(plan, activities),
        )
    }

    /**
     * Without a schedule: B = the evening plan's bedtime, else Settings' bedtime, on the evening [nightOf] (tonight =
     * NightRules.eveningFor, so a night owl's evening runs past midnight); the window starts a wind-down length before
     * the melatonin window.
     */
    private fun fallbackEvening(
        nightOf: LocalDate?,
        now: Instant,
        zone: ZoneId,
        week: WeekPlan,
        oneOffs: OneOffs,
        settings: UserSleepSettings,
        activities: List<RoutineActivity>,
    ): Evening {
        val bedtimeOn = { date: LocalDate ->
            times.eveningOf(date, week.resolve(date, oneOffs).evening.bedtime ?: settings.bedtime, zone)
        }
        val evening = nightOf ?: NightRules.eveningFor(now, zone, bedtimeOn, config)
        val plan = week.resolve(evening, oneOffs).evening
        val bedtime = bedtimeOn(evening)
        return Evening(
            nightOf = evening,
            windowStart = windowStart(bedtime, plan),
            bedtime = bedtime,
            enabled = plan.nightStepsOn,
            fromSettingsOnly = true,
            routine = routineFor(plan, activities),
        )
    }

    /** The wind-down phase start: B − melatoninHalfWidth − the evening's wind-down length (clamped). */
    private fun windowStart(bedtime: Instant, plan: EveningPlan): Instant =
        bedtime - config.melatoninHalfWidth - plan.windDownLength.coerceIn(config.week.windDownMinLength, config.week.windDownMaxLength)

    private companion object {
        const val TAG = "NightStepsRepository"
        val NO_ADHERENCE = StepAdherence(evenings = emptyList(), doneRecent = 0, countedRecent = 0, quietStreak = 0)
    }
}

/** Stored marks by wake date (unknown and derived names dropped). */
internal fun marksByDate(rows: List<NightMarkEntity>): Map<LocalDate, Set<Mark>> = rows
    .mapNotNull { row -> row.toDomainOrNull()?.let { LocalDate.parse(row.wakeDate) to it } }
    .groupBy({ it.first }, { it.second })
    .mapValues { (_, marks) -> marks.toSet() }
