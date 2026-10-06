package com.sleepsaver.core.data.repository

import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.di.DefaultDispatcher
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.debt.SleepDebtCalculator
import com.sleepsaver.core.domain.energy.EnergyScheduleCalculator
import com.sleepsaver.core.domain.insights.InsightsEngine
import com.sleepsaver.core.domain.model.DebtChangeBreakdown
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.UserSleepSettings
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
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/** Runs the domain engine over the stored nights. All computation happens on the default dispatcher. */
@Singleton
class InsightsRepositoryImpl @Inject constructor(
    private val sleepRepository: SleepRepository,
    private val settingsRepository: SettingsRepository,
    habitsRepository: HabitsRepository,
    private val timeProvider: TimeProvider,
    @ApplicationScope scope: CoroutineScope,
    @DefaultDispatcher private val defaultDispatcher: CoroutineDispatcher,
) : InsightsRepository {

    private val engine = InsightsEngine(SleepConfig.DEFAULT)
    private val debtCalculator = SleepDebtCalculator(SleepConfig.DEFAULT)
    private val scheduleCalculator = EnergyScheduleCalculator(SleepConfig.DEFAULT)

    private val sleepSettings: Flow<UserSleepSettings> =
        settingsRepository.settings.map { it.sleep }.distinctUntilChanged()

    /** A failed computation is logged and the previous state kept. */
    override val state: StateFlow<InsightsState> = combine(
        sleepRepository.nights,
        sleepSettings,
        habitsRepository.habitSettings,
        timeProvider.minuteTicks,
    ) { nights, settings, habits, now ->
        guarded(TAG, "Insights") { computeState(nights, settings, habits, now, timeProvider.zone()) }
    }
        .mapNotNull { it }
        .flowOn(defaultDispatcher)
        .stateIn(scope, SharingStarted.Eagerly, InsightsState.Loading)

    override fun debtSeries(from: LocalDate, to: LocalDate): Flow<List<SleepDebt>> =
        combine(sleepRepository.nights, sleepSettings) { nights, settings ->
            guarded(TAG, "Debt series") {
                debtCalculator.series(from, to, nights.associateBy { it.wakeDate }, settings.sleepNeed)
            }
        }
            .mapNotNull { it }
            .flowOn(defaultDispatcher)
            .distinctUntilChanged()

    /** The device zone, re-read on every tick (ticks restart at once when the zone changes). */
    private val deviceZone: Flow<ZoneId> = timeProvider.minuteTicks.map { timeProvider.zone() }.distinctUntilChanged()

    /**
     * Built like the home screen's schedule (same debt, same shifts, current zone), so today's matches [state].
     * null when there is no night with data on or before [date], or when the computation fails.
     */
    override fun schedule(date: LocalDate): Flow<EnergySchedule?> =
        combine(sleepRepository.nights, sleepSettings, deviceZone) { nights, settings, zone ->
            guarded(TAG, "Schedule for $date") { computeSchedule(date, nights, settings, zone) }
        }
            .flowOn(defaultDispatcher)
            .distinctUntilChanged()

    override suspend fun breakdown(wakeDate: LocalDate): DebtChangeBreakdown {
        val nights = sleepRepository.nights.first()
        val need = settingsRepository.current().sleep.sleepNeed
        return withContext(defaultDispatcher) {
            debtCalculator.breakdown(wakeDate, nights.associateBy { it.wakeDate }, need)
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

    private fun computeSchedule(date: LocalDate, nights: List<Night>, settings: UserSleepSettings, zone: ZoneId): EnergySchedule? {
        val upToDate = nights.filter { !it.wakeDate.isAfter(date) }
        if (upToDate.none { it.isPresent }) return null
        val yesterday = date.minusDays(1)
        val upToYesterday = upToDate.filter { it.wakeDate.isBefore(date) }
        val today = scheduleFor(date, upToDate, settings, zone)
        return scheduleCalculator.withShifts(today, scheduleFor(yesterday, upToYesterday, settings, zone))
    }

    /** Uses the debt displayed on [date] (PLAN §3.3): debt(date) once its night is in, otherwise debt(date − 1). */
    private fun scheduleFor(date: LocalDate, nights: List<Night>, settings: UserSleepSettings, zone: ZoneId): EnergySchedule {
        val nightsByDate = nights.associateBy { it.wakeDate }
        val debtDate = if (nightsByDate[date]?.isPresent == true) date else date.minusDays(1)
        val debt = debtCalculator.debtAsOf(debtDate, nightsByDate, settings.sleepNeed).debt
        return scheduleCalculator.schedule(date, zone, nights, settings.sleepNeed, debt, settings.typicalWake)
    }

    private companion object {
        const val TAG = "InsightsRepository"
    }
}
