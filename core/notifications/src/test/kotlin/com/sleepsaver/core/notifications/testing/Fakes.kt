package com.sleepsaver.core.notifications.testing

import com.sleepsaver.core.data.hooks.ReminderPlanner
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.MorningReview
import com.sleepsaver.core.data.repository.ReviewRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.DebtChangeBreakdown
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.WakeTimeMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

class FakeTimeProvider(var now: Instant, private val zone: ZoneId = ZoneOffset.UTC) : TimeProvider {
    override fun now(): Instant = now
    override fun zone(): ZoneId = zone
    override val minuteTicks: Flow<Instant> get() = flowOf(now)
}

class FakeInsightsRepository(initial: InsightsState = InsightsState.Loading) : InsightsRepository {
    override val state = MutableStateFlow(initial)
    val schedules = mutableMapOf<LocalDate, EnergySchedule>()

    /** Dates whose schedule flow fails (e.g. an implementation that only supports today and past days). */
    val failingSchedules = mutableSetOf<LocalDate>()

    override fun debtSeries(from: LocalDate, to: LocalDate): Flow<List<SleepDebt>> = flowOf(emptyList())
    override fun schedule(date: LocalDate): Flow<EnergySchedule?> =
        if (date in failingSchedules) flow { throw IllegalArgumentException("no schedule for $date") } else flowOf(schedules[date])
    override suspend fun breakdown(wakeDate: LocalDate): DebtChangeBreakdown = error("not used")
}

class FakeHabitsRepository(settings: List<HabitSettings> = HabitCatalog().defaultSettings()) : HabitsRepository {
    override val habitSettings = MutableStateFlow(settings)
    val activities = MutableStateFlow<List<RoutineActivity>>(emptyList())
    override val routineActivities: Flow<List<RoutineActivity>> = activities

    override suspend fun setEnabled(type: HabitType, enabled: Boolean) = edit(type) { it.copy(enabled = enabled) }
    override suspend fun setReminder(type: HabitType, enabled: Boolean) = edit(type) { it.copy(reminderEnabled = enabled) }
    override suspend fun setOffset(type: HabitType, offset: Duration) = edit(type) { it.copy(offset = offset) }
    override suspend fun resetOffset(type: HabitType) = Unit
    override suspend fun setActivitySelected(id: Long, selected: Boolean) = Unit
    override suspend fun addCustomActivity(label: String, emoji: String): Long = 0
    override suspend fun deleteActivity(id: Long) = Unit

    private fun edit(type: HabitType, change: (HabitSettings) -> HabitSettings) =
        habitSettings.update { list -> list.map { if (it.type == type) change(it) else it } }
}

class FakeSettingsRepository(initial: AppSettings = AppSettings(onboardingDone = true)) : SettingsRepository {
    override val settings = MutableStateFlow(initial)

    /** When set, reading the settings throws it (a broken DataStore file). */
    var failure: Exception? = null

    override suspend fun current(): AppSettings = failure?.let { throw it } ?: settings.value
    override suspend fun setSleepNeed(need: Duration) = settings.update { it.copy(sleep = it.sleep.copy(sleepNeed = need)) }
    override suspend fun setTypicalWake(time: LocalTime) = settings.update { it.copy(sleep = it.sleep.copy(typicalWake = time)) }
    override suspend fun setWakeTimeMode(mode: WakeTimeMode) = settings.update { it.copy(sleep = it.sleep.copy(wakeTimeMode = mode)) }
    override suspend fun setBedtime(time: LocalTime) = settings.update { it.copy(sleep = it.sleep.copy(bedtime = time)) }
    override suspend fun setBedtimeMode(mode: BedtimeMode) = settings.update { it.copy(sleep = it.sleep.copy(bedtimeMode = mode)) }
    override suspend fun setStreakThreshold(threshold: Duration) = settings.update { it.copy(sleep = it.sleep.copy(streakThreshold = threshold)) }
    override suspend fun setDebtThresholds(lowMax: Duration, highMin: Duration) =
        settings.update { it.copy(sleep = it.sleep.copy(lowDebtMax = lowMax, highDebtMin = highMin)) }
    override suspend fun setSource(source: SourceSelection) = settings.update { it.copy(sleep = it.sleep.copy(source = source)) }
    override suspend fun setDemoModeFlag(enabled: Boolean, startedAt: Instant?) =
        settings.update { it.copy(demoMode = enabled, demoStartedAt = startedAt) }
    override suspend fun setClockFormat(format: ClockFormat) = settings.update { it.copy(clockFormat = format) }
    override suspend fun setMorningReviewEnabled(enabled: Boolean) = settings.update { it.copy(morningReviewEnabled = enabled) }
    override suspend fun setOnboardingDone(done: Boolean) = settings.update { it.copy(onboardingDone = done) }
    override suspend fun dismissNeedSuggestion(value: Duration, at: Instant) =
        settings.update { it.copy(needSuggestionDismissedValue = value, needSuggestionDismissedAt = at) }
}

class FakeReviewRepository : ReviewRepository {
    private val reviews = MutableStateFlow<Map<LocalDate, MorningReview>>(emptyMap())

    override fun review(wakeDate: LocalDate): Flow<MorningReview?> = reviews.map { it[wakeDate] }
    override suspend fun record(review: MorningReview) = reviews.update { it + (review.wakeDate to review) }
    override val pendingReview: Flow<LocalDate?> = flowOf(null)
}

class CountingReminderPlanner : ReminderPlanner {
    var replans = 0
        private set

    override suspend fun replan() {
        replans++
    }

    override fun cancelAll() = Unit
}
