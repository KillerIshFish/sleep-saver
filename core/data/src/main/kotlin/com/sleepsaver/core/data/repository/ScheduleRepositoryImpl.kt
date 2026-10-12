package com.sleepsaver.core.data.repository

import androidx.room.withTransaction
import com.sleepsaver.core.data.db.AlarmEventEntity
import com.sleepsaver.core.data.db.DayPlanEntity
import com.sleepsaver.core.data.db.DayRoutineItemEntity
import com.sleepsaver.core.data.db.PlanOverrideEntity
import com.sleepsaver.core.data.db.PlanPauseEntity
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.di.DefaultDispatcher
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.mapping.enumOrNull
import com.sleepsaver.core.data.mapping.routineItems
import com.sleepsaver.core.data.mapping.toDomain
import com.sleepsaver.core.data.mapping.toDomainOrNull
import com.sleepsaver.core.data.mapping.toEntity
import com.sleepsaver.core.domain.alarm.AlarmPlanner
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.SurfacingWindows
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.debt.SleepDebtCalculator
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.patterns.PersonalLatency
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.domain.schedule.DayPlan
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.ScheduleTimes
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.domain.schedule.WeekPreset
import com.sleepsaver.core.domain.schedule.roundedTo
import com.sleepsaver.core.domain.schedule.sleepOpportunity
import com.sleepsaver.core.domain.schedule.withPreset
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The week plan (day_plans + day_routine_items), its one-offs (plan_overrides, plan_pauses) and the alarms they plan.
 *
 * The seven day rows are seeded from [WeekPlan.DEFAULT] whenever they are read incomplete (first launch, or after
 * "Reset all data"), the way habits are. Every write runs under one Mutex and one transaction, so a read-modify-write
 * never loses a concurrent edit. Example: `updateDay(MONDAY) { it.copy(morning = it.morning.copy(alarm = …SURFACING)) }`
 * on a day without a set time stores the current usual wake, rounded to 5 min, as Monday's time.
 *
 * DI edges (§3.4): [SleepRepository], [SettingsRepository], [TimeProvider], the database and [PersonalLatencySource].
 * It never injects InsightsRepository (which depends on it): the usual wake and the debt status come straight from the
 * nights and the settings.
 */
@Singleton
class ScheduleRepositoryImpl @Inject internal constructor(
    private val database: SleepSaverDatabase,
    private val sleepRepository: SleepRepository,
    private val settingsRepository: SettingsRepository,
    private val timeProvider: TimeProvider,
    private val latencySource: PersonalLatencySource,
    @ApplicationScope scope: CoroutineScope,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : ScheduleRepository {

    private val config = SleepConfig.DEFAULT
    private val dayDao = database.dayPlanDao()
    private val itemDao = database.dayRoutineItemDao()
    private val overrideDao = database.planOverrideDao()
    private val pauseDao = database.planPauseDao()
    private val anchorDao = database.anchorDao()
    private val eventDao = database.alarmEventDao()
    private val planner = AlarmPlanner(config)
    private val times = ScheduleTimes(config)
    private val debtCalculator = SleepDebtCalculator(config)

    /** Orders every write, seeding included. */
    private val seedLock = Mutex()

    private val nights: Flow<List<Night>> = sleepRepository.nights.orFallback(TAG, "Nights") { emptyList() }
    private val settings: Flow<AppSettings> = settingsRepository.settings
        .orFallback(TAG, "Settings") { AppSettings() }
        .distinctUntilChanged()
    private val today: Flow<LocalDate> = timeProvider.localDates(TAG)

    // ---------------------------------------------------------------- Reads

    override val weekPlan: Flow<WeekPlan> = combine(
        // Seeding writes the table, and Room then emits again with all seven days.
        dayDao.observeAll().transform { rows -> if (missingDays(rows).isEmpty()) emit(rows) else seed() },
        itemDao.observeAll(),
    ) { rows, items -> weekOf(rows, items) }
        .orFallback(TAG, "Week plan") { WeekPlan.DEFAULT }
        .distinctUntilChanged()

    @OptIn(ExperimentalCoroutinesApi::class)
    override val oneOffs: Flow<OneOffs> = today
        .flatMapLatest { date ->
            combine(
                overrideDao.observeFrom(date.minusDays(config.week.overrideHistoryDays).toString()),
                pauseDao.observe(),
            ) { overrides, pause -> oneOffsOf(overrides, pause, date) }
        }
        .orFallback(TAG, "One-offs") { OneOffs() }
        .distinctUntilChanged()

    /** Falls back to Settings' wake time for every day when the computation fails. */
    override val usualWake: Flow<Map<DayOfWeek, LocalTime>> = combine(nights, settings, today) { nights, settings, date ->
        guarded(TAG, "Usual wake") {
            (1L..DAYS_PER_WEEK).associate { ahead ->
                val day = date.plusDays(ahead)
                day.dayOfWeek to times.usualWake(day, nights, settings.sleep.timing).first
            }
        } ?: DayOfWeek.values().associateWith { settings.sleep.typicalWake }
    }
        .flowOn(defaultDispatcher)
        .distinctUntilChanged()

    /** The PLANNED events since yesterday that already ended (stopped, timed out, missed, cancelled). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val handledEvents: Flow<Handled> = today
        .flatMapLatest { date -> eventDao.observePlannedFrom(date.minusDays(1).toString()) }
        .map(::handledOf)
        .orFallback(TAG, "Alarm events query") { Handled() }
        .distinctUntilChanged()

    /**
     * Every alarm of the planning horizon (not yet capped at upcomingLimit), recomputed only when an input changes:
     * the plan, one-offs, settings, nights, anchors, the personal latency, handled events, or the local date and zone.
     */
    private val plannedAlarms: Flow<List<PlannedAlarm>> = combine(
        combine(weekPlan, oneOffs, ::Pair),
        combine(settings, nights, ::Pair),
        combine(
            // Mapped before the fallback, so an unreadable row can't escape into the app-scoped share below.
            anchorDao.observeAll().map { rows -> rows.map { it.toDomain() } }.orFallback(TAG, "Anchors query") { emptyList() },
            latencySource.latency,
            ::Pair,
        ),
        handledEvents,
        timeProvider.deviceDays(TAG),
    ) { (week, oneOffs), (settings, nights), (anchors, personal), handled, day ->
        guarded(TAG, "Upcoming alarms") {
            plan(
                inputs = PlanInputs(week, oneOffs, settings.sleep, nights, anchors, personal),
                now = timeProvider.now(),
                zone = day.zone,
                exclude = handled.ids,
                endedDates = handled.endedDates,
                // At most one alarm per date.
                limit = config.week.alarmPlanningDays,
            )
        }
    }
        .orLastGood(emptyList())
        .flowOn(defaultDispatcher)

    /** Minute ticks only drop alarms whose wake time has passed; the planning above doesn't rerun for them. */
    override val upcomingAlarms: Flow<List<PlannedAlarm>> = combine(
        plannedAlarms,
        timeProvider.minuteTicks.orFallback(TAG, "Minute ticks") { timeProvider.now() },
    ) { alarms, now -> alarms.filter { it.occurrence.target > now }.take(config.week.upcomingLimit) }
        .distinctUntilChanged()
        .shareIn(scope, SharingStarted.WhileSubscribed(SHARE_STOP_TIMEOUT_MS), replay = 1)

    override val nextAlarm: Flow<PlannedAlarm?> = upcomingAlarms.map { it.firstOrNull() }.distinctUntilChanged()

    /**
     * The same plan as [upcomingAlarms] from fresh reads, with no fallbacks: a failed read of the plan, one-offs,
     * anchors, alarm events, settings or nights propagates, so the scheduler keeps what is armed.
     */
    override suspend fun computeAlarmPlan(now: Instant, exclude: Set<String>): AlarmPlan {
        val zone = timeProvider.zone()
        val today = now.atZone(zone).toLocalDate()
        val stored = database.withTransaction {
            StoredPlan(
                week = weekOf(dayDao.getAll(), itemDao.getAll()),
                oneOffs = oneOffsOf(
                    overrideDao.getFrom(today.minusDays(config.week.overrideHistoryDays).toString()),
                    pauseDao.get(),
                    today,
                ),
                anchors = anchorDao.getAll().map { it.toDomain() },
                handled = handledOf(eventDao.getPlannedFrom(today.minusDays(1).toString())),
            )
        }
        val settings = settingsRepository.current()
        val nights = sleepRepository.nights.first()
        return withContext(defaultDispatcher) {
            val personal = latencySource.compute(stored.anchors, nights, today).personal
            val alarms = plan(
                inputs = PlanInputs(stored.week, stored.oneOffs, settings.sleep, nights, stored.anchors, personal),
                now = now,
                zone = zone,
                exclude = exclude + stored.handled.ids,
                endedDates = stored.handled.endedDates,
                limit = 1 + config.alarm.followingMirrored,
            )
            AlarmPlan(
                next = alarms.firstOrNull(),
                following = alarms.drop(1),
                plannedIds = planner.plannedIds(stored.week, stored.oneOffs, now, zone),
                computedAt = now,
            )
        }
    }

    // ---------------------------------------------------------------- Writes

    override suspend fun updateDay(day: DayOfWeek, change: (DayPlan) -> DayPlan) {
        // Read before the transaction: the nights come from other queries.
        val usual = currentUsualWake(day)
        seedLock.withLock {
            database.withTransaction {
                val current = readDay(day)
                val changed = change(current)
                // The routine items are written by setDayRoutine only, so ownRoutine stays as stored.
                val kept = changed.copy(day = day, evening = changed.evening.copy(routine = current.evening.routine))
                dayDao.upsert(normalise(kept, usual).toEntity(config))
            }
        }
    }

    /** Ids missing from routine_activities are dropped (a repeated id is kept once); null clears the day's own list. */
    override suspend fun setDayRoutine(day: DayOfWeek, activityIds: List<Long>?) {
        seedLock.withLock {
            database.withTransaction {
                val kept = activityIds?.distinct()?.let { ids ->
                    val existing = if (ids.isEmpty()) emptySet() else itemDao.existingActivityIds(ids).toSet()
                    ids.filter { it in existing }
                }
                itemDao.replaceForDay(day.value, kept.orEmpty().mapIndexed { position, id -> DayRoutineItemEntity(day.value, id, position) })
                val row = dayDao.get(day.value) ?: WeekPlan.defaultDay(day, config).toEntity(config)
                dayDao.upsert(row.copy(ownRoutine = kept != null))
            }
        }
    }

    override suspend fun copyDay(from: DayOfWeek, to: Set<DayOfWeek>, parts: Set<DayPart>) {
        val targets = to - from
        rewrite(targets, routines = DayPart.EVENING in parts) { it.copyParts(from, targets, parts) }
    }

    override suspend fun applyPreset(preset: WeekPreset, wake: LocalTime) =
        rewrite(DayOfWeek.values().toSet(), routines = false) { it.withPreset(preset, wake, config) }

    override suspend fun setOverride(override: DateOverride) {
        val now = timeProvider.now()
        val pruneBefore = timeProvider.today().minusDays(config.week.overridePruneDays)
        database.withTransaction {
            overrideDao.upsert(override.toEntity(now, timeProvider.currentOffset()))
            overrideDao.deleteBefore(pruneBefore.toString())
        }
    }

    override suspend fun clearOverride(date: LocalDate) = overrideDao.delete(date.toString())

    override suspend fun setPause(pause: PlanPause?) {
        if (pause == null) {
            pauseDao.clear()
            return
        }
        val latest = timeProvider.today().plusDays(config.week.pauseMaxDays)
        require(!pause.until.isAfter(latest)) { "a pause can't end after $latest" }
        pauseDao.upsert(pause.toEntity(timeProvider.now(), timeProvider.currentOffset()))
    }

    // ---------------------------------------------------------------- Planning

    private class PlanInputs(
        val week: WeekPlan,
        val oneOffs: OneOffs,
        val sleep: UserSleepSettings,
        val nights: List<Night>,
        val anchors: List<Anchor>,
        val personal: PersonalLatency,
    )

    private class StoredPlan(val week: WeekPlan, val oneOffs: OneOffs, val anchors: List<Anchor>, val handled: Handled)

    /**
     * What already happened to planned alarms: [ids] of the occurrences that ended (any outcome), never planned again,
     * and the [endedDates] whose alarm ended the morning ([RingOutcome.endsMorning]), which get no other plan time.
     */
    private data class Handled(val ids: Set<String> = emptySet(), val endedDates: Set<LocalDate> = emptySet())

    /**
     * The planned alarms after [now], each with tonight's window. latency = the active personal median, else
     * config.sleepLatency; the planned bedtime (plan-aware B on the evening before) uses it too. The window is
     * debt-aware when the opportunity from the night's Drop anchor (else the planned bedtime) is short, or the debt
     * displayed now is MODERATE or HIGH. A morning in [endedDates] keeps only a just-once alarm (AlarmPlanner.upcoming):
     * Monday's 06:30 stopped at 06:22, then Monday moved to 07:15 at 06:40, rings next Monday, not again today.
     */
    private fun plan(
        inputs: PlanInputs,
        now: Instant,
        zone: ZoneId,
        exclude: Set<String>,
        endedDates: Set<LocalDate>,
        limit: Int,
    ): List<PlannedAlarm> {
        val latency = inputs.personal.median?.takeIf { inputs.personal.active } ?: config.sleepLatency
        val sleep = inputs.sleep.copy(week = inputs.week, oneOffs = inputs.oneOffs, personalLatency = latency)
        val status = guarded(TAG, "Debt status") { displayedStatus(inputs.nights, sleep, now.atZone(zone).toLocalDate()) }
        return planner.upcoming(inputs.week, inputs.oneOffs, now, zone, exclude, endedDates, limit).map { occurrence ->
            val bedtime = guarded(TAG, "Planned bedtime") {
                times.bedtime(occurrence.date.minusDays(1), zone, inputs.nights, sleep.sleepNeed, sleep.timing).first
            }
            val anchorAt = inputs.anchors.filter { it.wakeDate == occurrence.date }.maxByOrNull { it.at }?.at
            val opportunity = (anchorAt ?: bedtime)?.let { sleepOpportunity(it, occurrence.target, latency) }
            PlannedAlarm(
                occurrence = occurrence,
                window = SurfacingWindows.effective(occurrence, opportunity, status, config),
                plannedBedtime = bedtime,
                debtStatus = status,
                latency = latency,
                need = sleep.sleepNeed,
            )
        }
    }

    /** The status of the debt shown now (today's once last night is in, else yesterday's); null without any night. */
    private fun displayedStatus(nights: List<Night>, sleep: UserSleepSettings, today: LocalDate): DebtStatus? {
        val byDate = nights.associateBy { it.wakeDate }
        if (byDate.values.none { it.isPresent && !it.wakeDate.isAfter(today) }) return null
        val shown = if (byDate[today]?.isPresent == true) today else today.minusDays(1)
        val debt = debtCalculator.debtAsOf(shown, byDate, sleep.sleepNeed).debt
        return debtCalculator.status(debt, sleep.lowDebtMax, sleep.highDebtMin)
    }

    // ---------------------------------------------------------------- Helpers

    /** Only events with an outcome count; a row with an unknown outcome or an unreadable date ends no morning. */
    private fun handledOf(events: List<AlarmEventEntity>): Handled {
        val ended = events.filter { it.outcome != null }
        return Handled(
            ids = ended.mapNotNullTo(HashSet()) { it.occurrenceId },
            endedDates = ended.mapNotNullTo(HashSet()) { event ->
                val endsMorning = enumOrNull<RingOutcome>(event.outcome)?.endsMorning == true
                if (endsMorning) runCatching { LocalDate.parse(event.wakeDate) }.getOrNull() else null
            },
        )
    }

    private fun weekOf(rows: List<DayPlanEntity>, items: List<DayRoutineItemEntity>): WeekPlan {
        val byDay = rows.associateBy { it.dayOfWeek }
        val itemsByDay = items.groupBy { it.dayOfWeek }
        return WeekPlan(
            DayOfWeek.values().map { day ->
                val routine = itemsByDay[day.value].orEmpty().sortedBy { it.position }.map { it.activityId }
                byDay[day.value]?.toDomain(routine, config) ?: WeekPlan.defaultDay(day, config)
            },
        )
    }

    /** Unreadable rows are dropped; a pause that ended before [today] is ignored. */
    private fun oneOffsOf(overrides: List<PlanOverrideEntity>, pause: PlanPauseEntity?, today: LocalDate): OneOffs = OneOffs(
        overrides = overrides.mapNotNull { it.toDomainOrNull() },
        pause = pause?.toDomainOrNull()?.takeIf { !it.until.isBefore(today) },
    )

    private fun missingDays(rows: List<DayPlanEntity>): List<DayOfWeek> {
        val stored = rows.mapTo(HashSet()) { it.dayOfWeek }
        return DayOfWeek.values().filter { it.value !in stored }
    }

    /** Adds the default row for each missing day; existing rows are left alone. */
    private suspend fun seed() {
        seedLock.withLock {
            val missing = missingDays(dayDao.getAll())
            if (missing.isNotEmpty()) dayDao.upsertAll(missing.map { WeekPlan.defaultDay(it, config).toEntity(config) })
        }
    }

    /** Callers hold [seedLock] and a transaction. */
    private suspend fun readDay(day: DayOfWeek): DayPlan {
        val routine = itemDao.getForDay(day.value).sortedBy { it.position }.map { it.activityId }
        return dayDao.get(day.value)?.toDomain(routine, config) ?: WeekPlan.defaultDay(day, config)
    }

    /** Applies [change] to the stored week and writes [days] (with their routine items when [routines]). */
    private suspend fun rewrite(days: Set<DayOfWeek>, routines: Boolean, change: (WeekPlan) -> WeekPlan) {
        seedLock.withLock {
            database.withTransaction {
                val week = change(weekOf(dayDao.getAll(), itemDao.getAll()))
                dayDao.upsertAll(days.map { week.day(it).toEntity(config) })
                if (routines) days.forEach { itemDao.replaceForDay(it.value, week.day(it).routineItems()) }
            }
        }
    }

    /**
     * Alarm on without a set time → [usualWake] rounded to timeRounding (no alarm ever follows the learned time);
     * every range clamped to config (window, volume, snooze length and count, overrun, wind-down length).
     */
    private fun normalise(plan: DayPlan, usualWake: LocalTime): DayPlan {
        val alarmConfig = config.alarm
        val morning = plan.morning
        val alarm = morning.alarm
        val snooze = alarm.snooze
        val wakeTime = morning.wakeTime
            ?: usualWake.roundedTo(config.week.timeRounding).takeIf { alarm.mode != AlarmMode.OFF }
        return plan.copy(
            morning = morning.copy(
                wakeTime = wakeTime,
                alarm = alarm.copy(
                    window = alarm.window.coerceIn(alarmConfig.windowMin, alarmConfig.windowMax),
                    volume = alarm.volume.coerceIn(alarmConfig.minVolume, 1f),
                    snooze = snooze.copy(
                        fixedLength = snooze.fixedLength.coerceIn(alarmConfig.fixedSnoozeMin, alarmConfig.fixedSnoozeMax),
                        fixedCount = snooze.fixedCount.coerceIn(alarmConfig.fixedSnoozeCountMin, alarmConfig.fixedSnoozeCountMax),
                        overrun = snooze.overrun.coerceIn(Duration.ZERO, alarmConfig.overrunMax),
                    ),
                ),
            ),
            evening = plan.evening.copy(
                windDownLength = plan.evening.windDownLength.coerceIn(config.week.windDownMinLength, config.week.windDownMaxLength),
            ),
        )
    }

    /** The usual wake (plan ignored) on [day]'s next date, tomorrow .. today + 7; Settings' wake time if unreadable. */
    private suspend fun currentUsualWake(day: DayOfWeek): LocalTime {
        val settings = guarded(TAG, "Settings") { settingsRepository.current() } ?: AppSettings()
        return guarded(TAG, "Usual wake") {
            val today = timeProvider.today()
            val date = (1L..DAYS_PER_WEEK).map { today.plusDays(it) }.first { it.dayOfWeek == day }
            times.usualWake(date, sleepRepository.nights.first(), settings.sleep.timing).first
        } ?: settings.sleep.typicalWake
    }

    private companion object {
        const val TAG = "ScheduleRepository"
        const val SHARE_STOP_TIMEOUT_MS = 5_000L
        const val DAYS_PER_WEEK = 7L
    }
}
