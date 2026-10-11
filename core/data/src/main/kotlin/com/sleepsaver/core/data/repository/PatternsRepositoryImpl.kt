package com.sleepsaver.core.data.repository

import com.sleepsaver.core.data.db.AnchorEntity
import com.sleepsaver.core.data.db.HushPlayEntity
import com.sleepsaver.core.data.db.MorningFeelEntity
import com.sleepsaver.core.data.db.NightMarkEntity
import com.sleepsaver.core.data.db.NightStepLogEntity
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.di.DefaultDispatcher
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.mapping.toDomain
import com.sleepsaver.core.data.mapping.toDomainOrNull
import com.sleepsaver.core.domain.alarm.DeepPrior
import com.sleepsaver.core.domain.alarm.DeepPriorEstimator
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.nightsteps.EveningState
import com.sleepsaver.core.domain.nightsteps.routineFor
import com.sleepsaver.core.domain.nightsteps.stepAdherence
import com.sleepsaver.core.domain.patterns.AnchorNightStats
import com.sleepsaver.core.domain.patterns.AnchorStatsCalculator
import com.sleepsaver.core.domain.patterns.KeelCalculator
import com.sleepsaver.core.domain.patterns.KeelResult
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.MarkEffectsCalculator
import com.sleepsaver.core.domain.patterns.OddNight
import com.sleepsaver.core.domain.patterns.OddNightDetector
import com.sleepsaver.core.domain.patterns.PersonalLatency
import com.sleepsaver.core.domain.patterns.PlanCheck
import com.sleepsaver.core.domain.patterns.PlannedKeelCalculator
import com.sleepsaver.core.domain.patterns.SocialJetlag
import com.sleepsaver.core.domain.patterns.SocialJetlagCalculator
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.WeekPlan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Keel, social jetlag, the plan check, Marks statistics, odd nights, Drop anchor numbers, the personal latency, the
 * Garmin deep-sleep prior and the debt preview's inputs. Each output is recomputed when the nights, the settings or
 * plan, the journal rows it reads, or the local date change (never on minute ticks), on the default dispatcher; a
 * failed computation keeps the previous value (or gives none).
 *
 * DI edges (§3.4): [SleepRepository], [ScheduleRepository], [SettingsRepository], the database, [PersonalLatencySource]
 * and [TimeProvider]. Step logs, marks, Morning feel, anchors and Hush plays are read through their DAOs, never through
 * the Night steps, Journal or Night log repositories.
 */
@Singleton
class PatternsRepositoryImpl @Inject internal constructor(
    private val sleepRepository: SleepRepository,
    scheduleRepository: ScheduleRepository,
    settingsRepository: SettingsRepository,
    database: SleepSaverDatabase,
    private val latencySource: PersonalLatencySource,
    timeProvider: TimeProvider,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : PatternsRepository {

    private val config = SleepConfig.DEFAULT
    private val keelCalculator = KeelCalculator(config)
    private val jetlagCalculator = SocialJetlagCalculator(config)
    private val plannedKeelCalculator = PlannedKeelCalculator(config)
    private val markEffectsCalculator = MarkEffectsCalculator(config)
    private val oddNightDetector = OddNightDetector(config)
    private val anchorStatsCalculator = AnchorStatsCalculator(config)
    private val deepPriorEstimator = DeepPriorEstimator(config)

    private val anchorDao = database.anchorDao()

    private val nights: Flow<List<Night>> = sleepRepository.nights.orFallback(TAG, "Nights") { emptyList() }
    private val settings: Flow<AppSettings> = settingsRepository.settings
        .orFallback(TAG, "Settings") { AppSettings() }
        .distinctUntilChanged()
    private val week: Flow<WeekPlan> = scheduleRepository.weekPlan.orFallback(TAG, "Week plan") { WeekPlan.DEFAULT }
    private val oneOffs: Flow<OneOffs> = scheduleRepository.oneOffs.orFallback(TAG, "One-offs") { OneOffs() }
    private val days: Flow<DeviceDate> = timeProvider.deviceDays(TAG)
    private val today: Flow<LocalDate> = timeProvider.localDates(TAG)

    /** Settings' values with the personal latency (once active) merged in; the plan is added where it is needed. */
    private val sleepSettings: Flow<UserSleepSettings> = combine(settings, latencySource.latency) { settings, latency ->
        settings.sleep.copy(personalLatency = latency.median?.takeIf { latency.active })
    }.distinctUntilChanged()

    override val keel: Flow<KeelResult> = combine(nights, today) { nights, today ->
        guarded(TAG, "Keel") { keelCalculator.keel(nights, today) }
    }
        .orLastGood(KeelResult.Calibrating(validDays = 0, needed = config.keel.minValidDays))
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()

    /** null with too few free or alarm nights (or when the computation fails). */
    override val socialJetlag: Flow<SocialJetlag?> = combine(nights, week, oneOffs, today) { nights, week, oneOffs, today ->
        guarded(TAG, "Social jetlag") { jetlagCalculator.compute(nights, week, oneOffs, today) }
    }
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()

    /** The plan's own keel and warnings over the 14 wake dates from tomorrow, in the device zone. */
    override val planCheck: Flow<PlanCheck?> = combine(
        combine(week, oneOffs, ::Pair),
        nights,
        sleepSettings,
        days,
    ) { (week, oneOffs), nights, sleep, day ->
        guarded(TAG, "Plan check") { plannedKeelCalculator.check(week, oneOffs, nights, sleep, day.date.plusDays(1), day.zone) }
    }
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()

    /** Stored marks plus the derived ones (Night steps done the evening before, Hush used, anchor dropped). */
    override val markEffects: Flow<List<MarkEffect>> = combine(
        combine(
            database.nightMarkDao().observeAll().orFallback(TAG, "Marks query") { emptyList() },
            database.morningFeelDao().observeAll().orFallback(TAG, "Morning feel query") { emptyList() },
            database.nightStepLogDao().observeAll().orFallback(TAG, "Step logs query") { emptyList() },
            ::JournalRows,
        ),
        combine(
            database.hushPlayDao().observeAll().orFallback(TAG, "Hush plays query") { emptyList() },
            anchorDao.observeAll().orFallback(TAG, "Anchors query") { emptyList() },
            ::Pair,
        ),
        combine(
            week,
            oneOffs,
            database.routineActivityDao().observeAll().orFallback(TAG, "Routine activities query") { emptyList() }
                .map { rows -> rows.map { it.toDomain() } },
            ::PlanRows,
        ),
        nights,
        today,
    ) { journal, (hush, anchors), plan, nights, today ->
        guarded(TAG, "Mark effects") { markEffects(journal, hush, anchors, plan, nights, today) }
    }
        .orLastGood(emptyList())
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()

    override fun oddNight(wakeDate: LocalDate): Flow<OddNight?> = nights
        .map { nights -> guarded(TAG, "Odd night") { oddNightDetector.check(nights, wakeDate) } }
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()

    /**
     * The anchor that counts for the night (AnchorStatsCalculator.choose) with its latency and efficiency; without the
     * night yet, the latest anchor of that wake date. null without an anchor that counts.
     */
    override fun anchorStats(wakeDate: LocalDate): Flow<AnchorNightStats?> = combine(
        anchorDao.observeFor(wakeDate.toString()).orFallback(TAG, "Anchors query") { emptyList() }
            .map { rows -> rows.map { it.toDomain() } },
        sleepRepository.night(wakeDate).orFallback(TAG, "Night") { null },
    ) { anchors, night ->
        guarded(TAG, "Anchor stats") {
            val anchor = if (night != null) anchorStatsCalculator.choose(anchors, night) else anchors.maxByOrNull { it.at }
            anchor?.let { anchorStatsCalculator.night(it, night) }
        }
    }
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()

    override val personalLatency: Flow<PersonalLatency> = latencySource.latency

    /** Demo nights never feed the alarm: null in demo mode (the alarm then uses no prior). */
    override val deepPrior: Flow<DeepPrior?> = combine(nights, settings.map { it.demoMode }.distinctUntilChanged(), today) { nights, demo, today ->
        if (demo) null else guarded(TAG, "Deep prior") { deepPriorEstimator.estimate(nights, today) }
    }
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()

    /** The nights and the settings with the plan and personal latency merged, in the device zone. */
    override val debtPreviewInputs: Flow<DebtPreviewInputs?> = combine(
        nights,
        sleepSettings,
        week,
        oneOffs,
        days,
    ) { nights, sleep, week, oneOffs, day ->
        DebtPreviewInputs(nights = nights, settings = sleep.copy(week = week, oneOffs = oneOffs), zone = day.zone)
    }.distinctUntilChanged()

    // ---------------------------------------------------------------- Marks

    private class JournalRows(val marks: List<NightMarkEntity>, val feel: List<MorningFeelEntity>, val stepLogs: List<NightStepLogEntity>)

    private class PlanRows(val week: WeekPlan, val oneOffs: OneOffs, val activities: List<RoutineActivity>)

    private fun markEffects(
        journal: JournalRows,
        hush: List<HushPlayEntity>,
        anchorRows: List<AnchorEntity>,
        plan: PlanRows,
        nights: List<Night>,
        today: LocalDate,
    ): List<MarkEffect> {
        val stored = marksByDate(journal.marks)
        val logs = journal.stepLogs.map { it.toDomain() }
        val anchors: List<Anchor> = anchorRows.map { it.toDomain() }
        val hushDates = hush.map { LocalDate.parse(it.wakeDate) }

        // Night steps done on evening E → mark on wake date E + 1, by stepAdherence's own DONE rule.
        val plannedCount = { evening: LocalDate ->
            val eveningPlan = plan.week.resolve(evening, plan.oneOffs).evening
            if (!eveningPlan.nightStepsOn) 0 else routineFor(eveningPlan, plan.activities).size
        }
        val stepsDone = logs.groupBy { it.nightOf }
            .filter { (evening, eveningLogs) ->
                stepAdherence(eveningLogs, plannedCount, tonight = evening, tonightOver = true, config = config)
                    .evenings.last().state == EveningState.DONE
            }
            .keys
            .map { it.plusDays(1) }

        val derived = mapOf(
            Mark.NIGHT_STEPS_DONE to stepsDone.toSet(),
            Mark.HUSH_USED to hushDates.toSet(),
            Mark.ANCHOR_DROPPED to anchors.mapTo(HashSet()) { it.wakeDate },
        )
        val marks = HashMap<LocalDate, MutableSet<Mark>>()
        stored.forEach { (date, set) -> marks.getOrPut(date) { HashSet() } += set }
        derived.forEach { (mark, dates) -> dates.forEach { marks.getOrPut(it) { HashSet() } += mark } }

        // Stored: the first wake date it was set for (as NightMarkDao.firstDates); derived: the first date it could be.
        val firstDates = HashMap<Mark, LocalDate>()
        stored.forEach { (date, set) -> set.forEach { mark -> firstDates.merge(mark, date) { a, b -> minOf(a, b) } } }
        logs.minOfOrNull { it.nightOf }?.let { firstDates[Mark.NIGHT_STEPS_DONE] = it.plusDays(1) }
        hushDates.minOrNull()?.let { firstDates[Mark.HUSH_USED] = it }
        anchors.minOfOrNull { it.wakeDate }?.let { firstDates[Mark.ANCHOR_DROPPED] = it }

        val feel = journal.feel.mapNotNull { it.toDomainOrNull() }.associate { it.wakeDate to it.feel }
        return markEffectsCalculator.effects(nights, marks, feel, firstDates, today)
    }

    private companion object {
        const val TAG = "PatternsRepository"
    }
}
