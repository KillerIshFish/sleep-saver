package com.sleepsaver.core.data.repository

import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.di.DefaultDispatcher
import com.sleepsaver.core.data.healthconnect.catchingOrNull
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.debt.SleepDebtCalculator
import com.sleepsaver.core.domain.energy.EnergyScheduleCalculator
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.insights.InsightsEngine
import com.sleepsaver.core.domain.model.DebtChangeBreakdown
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.patterns.PersonalLatency
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.WeekPlan
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Runs the domain engine over the stored nights. All computation happens on the default dispatcher.
 *
 * The sleep settings it computes with carry the week plan, its one-offs and the active personal latency, so the
 * schedule (W, B, wind-down length, Night steps switch) follows the plan everywhere: home, timeline, reminders.
 *
 * DI edges (§3.4): the v1 dependencies plus [ScheduleRepository] and [PersonalLatencySource] (neither depends back on
 * Insights).
 */
@Singleton
class InsightsRepositoryImpl @Inject internal constructor(
    sleepRepository: SleepRepository,
    private val settingsRepository: SettingsRepository,
    habitsRepository: HabitsRepository,
    scheduleRepository: ScheduleRepository,
    latencySource: PersonalLatencySource,
    private val timeProvider: TimeProvider,
    @ApplicationScope scope: CoroutineScope,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : InsightsRepository {

    private val engine = InsightsEngine(SleepConfig.DEFAULT)
    private val debtCalculator = SleepDebtCalculator(SleepConfig.DEFAULT)
    private val scheduleCalculator = EnergyScheduleCalculator(SleepConfig.DEFAULT)

    /** Nights that never fail: an error reads as no nights (SleepRepositoryImpl already guarantees this). */
    private val nights: Flow<List<Night>> = sleepRepository.nights.orFallback(TAG, "Nights") { emptyList() }

    /** The Settings values with the week plan, its one-offs and the personal latency (once active) merged in. */
    private val sleepSettings: Flow<UserSleepSettings> = combine(
        settingsRepository.settings.orFallback(TAG, "Settings") { AppSettings() }.map { it.sleep },
        scheduleRepository.weekPlan.orFallback(TAG, "Week plan") { WeekPlan.DEFAULT },
        scheduleRepository.oneOffs.orFallback(TAG, "One-offs") { OneOffs() },
        latencySource.latency.orFallback(TAG, "Personal latency") { PersonalLatency(values = 0, median = null, active = false) },
    ) { sleep, week, oneOffs, latency ->
        sleep.copy(week = week, oneOffs = oneOffs, personalLatency = latency.median?.takeIf { latency.active })
    }
        .distinctUntilChanged()

    /**
     * Never stuck on Loading and never crashes the app scope: failing inputs fall back (see [orFallback]), and a
     * failed computation keeps the previous state, or reports [InsightsState.NoNights] (whose screen offers a way
     * forward) when there is none yet. Each failure is logged.
     */
    override val state: StateFlow<InsightsState> = combine(
        nights,
        sleepSettings,
        habitsRepository.habitSettings.orFallback(TAG, "Habit settings") { HabitCatalog(SleepConfig.DEFAULT).defaultSettings() },
        timeProvider.minuteTicks.orFallback(TAG, "Minute ticks") { timeProvider.now() },
    ) { nights, settings, habits, now ->
        guarded(TAG, "Insights") { computeState(nights, settings, habits, now, timeProvider.zone()) }
    }
        .orLastGood<InsightsState>(InsightsState.NoNights)
        .flowOn(defaultDispatcher)
        .orFallback(TAG, "Insights state") { InsightsState.NoNights }
        .stateIn(scope, SharingStarted.Eagerly, InsightsState.Loading)

    /** Keeps the previous series when a computation fails (none before the first success). */
    override fun debtSeries(from: LocalDate, to: LocalDate): Flow<List<SleepDebt>> =
        combine(nights, sleepSettings) { nights, settings ->
            guarded(TAG, "Debt series") {
                debtCalculator.series(from, to, nights.associateBy { it.wakeDate }, settings.sleepNeed)
            }
        }
            .orLastGood(emptyList())
            .flowOn(defaultDispatcher)
            .distinctUntilChanged()

    /** The device zone and the local date there, re-read on every tick (ticks restart at once when the zone changes). */
    private val deviceDay: Flow<DeviceDay> = timeProvider.minuteTicks
        .orFallback(TAG, "Minute ticks") { timeProvider.now() }
        .map { now -> timeProvider.zone().let { zone -> DeviceDay(zone, now.atZone(zone).toLocalDate()) } }
        .distinctUntilChanged()

    /**
     * Built like the home screen's schedule (same debt, same shifts, current zone), so today's matches [state].
     *
     * A day after today (the reminder planner asks for tomorrow) is planned from what is known now: only nights up to
     * today count (an evening doze already belongs to tomorrow's wake date but says nothing about tomorrow's wake),
     * with the debt the home screen shows today.
     *
     * null when there is no night with data on or before [date], or when the computation fails.
     */
    override fun schedule(date: LocalDate): Flow<EnergySchedule?> =
        combine(nights, sleepSettings, deviceDay) { nights, settings, day ->
            guarded(TAG, "Schedule for $date") { computeSchedule(date, nights, settings, day) }
        }
            .flowOn(defaultDispatcher)
            .distinctUntilChanged()

    /** A neutral breakdown (no change) if the computation fails, rather than an exception in the caller. */
    override suspend fun breakdown(wakeDate: LocalDate): DebtChangeBreakdown {
        val nights = this.nights.first()
        val need = (catchingOrNull { settingsRepository.current() } ?: AppSettings()).sleep.sleepNeed
        return withContext(defaultDispatcher) {
            guarded(TAG, "Breakdown for $wakeDate") {
                debtCalculator.breakdown(wakeDate, nights.associateBy { it.wakeDate }, need)
            } ?: DebtChangeBreakdown(
                wakeDate = wakeDate,
                need = need,
                slept = Duration.ZERO,
                before = Duration.ZERO,
                lastNightDelta = Duration.ZERO,
                fade = Duration.ZERO,
                after = Duration.ZERO,
            )
        }
    }

    private fun computeState(
        nights: List<Night>,
        settings: UserSleepSettings,
        habits: List<HabitSettings>,
        now: Instant,
        zone: ZoneId,
    ): InsightsState =
        if (nights.none { it.isPresent }) {
            InsightsState.NoNights
        } else {
            InsightsState.Ready(engine.compute(nights, settings, habits, now, zone))
        }

    private fun computeSchedule(date: LocalDate, nights: List<Night>, settings: UserSleepSettings, day: DeviceDay): EnergySchedule? {
        val known = if (date.isAfter(day.today)) nights.filter { !it.wakeDate.isAfter(day.today) } else nights
        val upToDate = known.filter { !it.wakeDate.isAfter(date) }
        if (upToDate.none { it.isPresent }) return null
        val yesterday = date.minusDays(1)
        val upToYesterday = upToDate.filter { it.wakeDate.isBefore(date) }
        val today = scheduleFor(date, upToDate, settings, day)
        return scheduleCalculator.withShifts(today, scheduleFor(yesterday, upToYesterday, settings, day))
    }

    /**
     * Uses the debt displayed on [date] (PLAN §3.3): debt(date) once its night is in, otherwise debt(date − 1).
     * For a day after today that is the debt displayed today.
     */
    private fun scheduleFor(date: LocalDate, nights: List<Night>, settings: UserSleepSettings, day: DeviceDay): EnergySchedule {
        val nightsByDate = nights.associateBy { it.wakeDate }
        val shownOn = minOf(date, day.today)
        val debtDate = if (nightsByDate[shownOn]?.isPresent == true) shownOn else shownOn.minusDays(1)
        val debt = debtCalculator.debtAsOf(debtDate, nightsByDate, settings.sleepNeed).debt
        return scheduleCalculator.schedule(date, day.zone, nights, settings.sleepNeed, debt, settings.timing)
    }

    private data class DeviceDay(val zone: ZoneId, val today: LocalDate)

    private companion object {
        const val TAG = "InsightsRepository"
    }
}
