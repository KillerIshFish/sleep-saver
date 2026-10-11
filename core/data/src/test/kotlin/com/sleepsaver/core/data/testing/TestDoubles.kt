package com.sleepsaver.core.data.testing

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.okio.OkioStorage
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.PreferencesSerializer
import androidx.room.Room
import com.sleepsaver.core.data.db.SleepSaverDatabase
import com.sleepsaver.core.data.hooks.AlarmScheduler
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.AlarmPlan
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.ClockFormat
import com.sleepsaver.core.data.repository.HabitsRepository
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.InsightsState
import com.sleepsaver.core.data.repository.PersonalLatencySource
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.domain.schedule.DayPlan
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.WakeStyle
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.domain.schedule.WeekPreset
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import java.time.DayOfWeek
import com.sleepsaver.core.domain.habits.HabitCatalog
import com.sleepsaver.core.domain.model.BedtimeMode
import com.sleepsaver.core.domain.model.DebtChangeBreakdown
import com.sleepsaver.core.domain.model.EnergySchedule
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.insights.InsightsEngine
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.model.SleepDebt
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.model.VitalSample
import com.sleepsaver.core.domain.model.WakeTimeMode
import com.sleepsaver.core.domain.night.NightAssembler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assume.assumeTrue
import java.io.File
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

fun inMemoryDatabase(context: Context): SleepSaverDatabase =
    Room.inMemoryDatabaseBuilder(context, SleepSaverDatabase::class.java).allowMainThreadQueries().build()

/**
 * A file-backed preferences store in [dir]. Uses Okio storage: java.io.File renames can't replace an existing
 * file on Windows hosts, which breaks the second write of a File-based store there (Android is unaffected).
 */
fun preferencesStore(scope: CoroutineScope, dir: File): DataStore<Preferences> =
    PreferenceDataStoreFactory.create(
        storage = OkioStorage(FileSystem.SYSTEM, PreferencesSerializer) { File(dir, "settings.preferences_pb").toOkioPath() },
        scope = scope,
    )

class FakeTimeProvider(
    var now: Instant = Instant.parse("2026-10-06T12:00:00Z"),
    var zone: ZoneId = ZoneOffset.UTC,
) : TimeProvider {
    val ticks = MutableStateFlow(now)
    override fun now(): Instant = now
    override fun zone(): ZoneId = zone
    override val minuteTicks: Flow<Instant> = ticks
}

class FakeSettingsRepository(initial: AppSettings = AppSettings()) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<AppSettings> = state
    override suspend fun current(): AppSettings = state.value
    override suspend fun setSleepNeed(need: Duration) = state.update { it.copy(sleep = it.sleep.copy(sleepNeed = need)) }
    override suspend fun setTypicalWake(time: LocalTime) = state.update { it.copy(sleep = it.sleep.copy(typicalWake = time)) }
    override suspend fun setWakeTimeMode(mode: WakeTimeMode) = state.update { it.copy(sleep = it.sleep.copy(wakeTimeMode = mode)) }
    override suspend fun setBedtime(time: LocalTime) = state.update { it.copy(sleep = it.sleep.copy(bedtime = time)) }
    override suspend fun setBedtimeMode(mode: BedtimeMode) = state.update { it.copy(sleep = it.sleep.copy(bedtimeMode = mode)) }
    override suspend fun setStreakThreshold(threshold: Duration) =
        state.update { it.copy(sleep = it.sleep.copy(streakThreshold = threshold)) }
    override suspend fun setDebtThresholds(lowMax: Duration, highMin: Duration) =
        state.update { it.copy(sleep = it.sleep.copy(lowDebtMax = lowMax, highDebtMin = highMin)) }
    override suspend fun setSource(source: SourceSelection) = state.update { it.copy(sleep = it.sleep.copy(source = source)) }
    override suspend fun setDemoModeFlag(enabled: Boolean, startedAt: Instant?) =
        state.update { it.copy(demoMode = enabled, demoStartedAt = startedAt) }
    override suspend fun setClockFormat(format: ClockFormat) = state.update { it.copy(clockFormat = format) }
    override suspend fun setMorningReviewEnabled(enabled: Boolean) = state.update { it.copy(morningReviewEnabled = enabled) }
    override suspend fun setOnboardingDone(done: Boolean) = state.update { it.copy(onboardingDone = done) }
    override suspend fun dismissNeedSuggestion(value: Duration, at: Instant) =
        state.update { it.copy(needSuggestionDismissedValue = value, needSuggestionDismissedAt = at) }
}

/** Only [nights] is functional; enough for export and insights tests. */
class FakeSleepRepository(initial: List<Night> = emptyList()) : SleepRepository {
    val state = MutableStateFlow(initial)
    override val nights: Flow<List<Night>> = state
    override fun night(wakeDate: LocalDate): Flow<Night?> = state.map { all -> all.firstOrNull { it.wakeDate == wakeDate } }
    override fun rawNight(wakeDate: LocalDate): Flow<Night?> = night(wakeDate)
    override val origins: Flow<List<String>> = flowOf(emptyList())
    override fun vitals(from: Instant, to: Instant): Flow<List<VitalSample>> = flowOf(emptyList())
    override suspend fun saveOverride(override: NightOverride) = unsupported()
    override suspend fun revert(wakeDate: LocalDate) = unsupported()
    override suspend fun addManualNight(wakeDate: LocalDate, fellAsleep: Instant, wokeUp: Instant, offset: ZoneOffset) = unsupported()
    override suspend fun addNap(start: Instant, end: Instant, offset: ZoneOffset): Long = unsupported()
    override suspend fun deleteNap(id: Long) = unsupported()
    override suspend fun addAwake(wakeDate: LocalDate, start: Instant, end: Instant): Long = unsupported()
    override suspend fun deleteAwake(id: Long) = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException()
}

class FakeHabitsRepository : HabitsRepository {
    val settings = MutableStateFlow(HabitCatalog().defaultSettings())
    override val habitSettings: Flow<List<HabitSettings>> = settings
    override val routineActivities: Flow<List<RoutineActivity>> = emptyFlow()
    override suspend fun setEnabled(type: HabitType, enabled: Boolean) = Unit
    override suspend fun setReminder(type: HabitType, enabled: Boolean) = Unit
    override suspend fun setOffset(type: HabitType, offset: Duration) = Unit
    override suspend fun resetOffset(type: HabitType) = Unit
    override suspend fun setActivitySelected(id: Long, selected: Boolean) = Unit
    override suspend fun addCustomActivity(label: String, emoji: String): Long = 0
    override suspend fun deleteActivity(id: Long) = Unit
}

class FakeInsightsRepository(initial: InsightsState = InsightsState.Loading) : InsightsRepository {
    val mutableState = MutableStateFlow(initial)

    /** What [schedule] answers per date (none = null). */
    val schedules = MutableStateFlow<Map<LocalDate, EnergySchedule>>(emptyMap())
    override val state: StateFlow<InsightsState> = mutableState
    override fun debtSeries(from: LocalDate, to: LocalDate): Flow<List<SleepDebt>> = emptyFlow()
    override fun schedule(date: LocalDate): Flow<EnergySchedule?> = schedules.map { it[date] }
    override suspend fun breakdown(wakeDate: LocalDate): DebtChangeBreakdown = throw UnsupportedOperationException()
}

// ---------------------------------------------------------------- v2

/** A real [PersonalLatencySource] over [sleep]'s nights and [db]'s anchors, shared in the test's background scope. */
internal fun TestScope.latencySource(sleep: SleepRepository, db: SleepSaverDatabase, time: TimeProvider) =
    PersonalLatencySource(sleep, db, time, backgroundScope, StandardTestDispatcher(testScheduler))

/** The same over in-memory [anchors] (no Room, so it answers within the test's virtual time). */
internal fun TestScope.latencySource(sleep: SleepRepository, anchors: Flow<List<Anchor>>, time: TimeProvider) =
    PersonalLatencySource(sleep, anchors, time, backgroundScope, StandardTestDispatcher(testScheduler))

/** The plan and one-offs as state; [updateDay] applies the change as it is. Planning answers nothing. */
class FakeScheduleRepository(week: WeekPlan = WeekPlan.DEFAULT, oneOffs: OneOffs = OneOffs()) : ScheduleRepository {
    val weekState = MutableStateFlow(week)
    val oneOffsState = MutableStateFlow(oneOffs)
    override val weekPlan: Flow<WeekPlan> = weekState
    override val oneOffs: Flow<OneOffs> = oneOffsState
    override val usualWake: Flow<Map<DayOfWeek, LocalTime>> = flowOf(DayOfWeek.values().associateWith { LocalTime.of(7, 0) })
    override val upcomingAlarms: Flow<List<PlannedAlarm>> = flowOf(emptyList())
    override val nextAlarm: Flow<PlannedAlarm?> = flowOf(null)
    override suspend fun computeAlarmPlan(now: Instant, exclude: Set<String>): AlarmPlan =
        AlarmPlan(next = null, following = emptyList(), plannedIds = emptySet(), computedAt = now)
    override suspend fun updateDay(day: DayOfWeek, change: (DayPlan) -> DayPlan) =
        weekState.update { week -> week.with(change(week.day(day)).copy(day = day)) }
    override suspend fun setDayRoutine(day: DayOfWeek, activityIds: List<Long>?) = weekState.update { week ->
        val plan = week.day(day)
        week.with(plan.copy(evening = plan.evening.copy(routine = activityIds)))
    }
    override suspend fun copyDay(from: DayOfWeek, to: Set<DayOfWeek>, parts: Set<DayPart>) = unsupported()
    override suspend fun applyPreset(preset: WeekPreset, wake: LocalTime) = unsupported()
    override suspend fun setOverride(override: DateOverride) = unsupported()
    override suspend fun clearOverride(date: LocalDate) = unsupported()
    override suspend fun setPause(pause: PlanPause?) = unsupported()
    private fun unsupported(): Nothing = throw UnsupportedOperationException()
}

/** Records "reschedule" / "cancelAll" in [calls] (shared with other recorders to check the order). */
class RecordingAlarmScheduler(val calls: MutableList<String> = mutableListOf()) : AlarmScheduler {
    override suspend fun reschedule() {
        calls += "reschedule"
    }

    override fun cancelAll() {
        calls += "cancelAll"
    }
}

/** Records "stopEverything" in [calls]; every other call is a no-op. */
class RecordingNightController(val calls: MutableList<String> = mutableListOf()) : NightController {
    override val state: StateFlow<NightState> = MutableStateFlow(NightState())
    override suspend fun dropAnchor(source: AnchorSource, wholeNightLog: Boolean): Long? = null
    override suspend fun undoAnchor(anchorId: Long) = Unit
    override fun endNight() = Unit
    override fun resumeMovementLog() = Unit
    override fun startHush(color: HushColor, timer: Duration?, volume: Float) = Unit
    override fun extendHush(by: Duration) = Unit
    override fun setHushVolume(volume: Float) = Unit
    override fun stopHush() = Unit
    override fun startCatnap(length: Duration): Boolean = false
    override fun cancelCatnap() = Unit
    override fun testRing(): Boolean = false
    override fun snooze() = Unit
    override fun stopRinging() = Unit
    override fun recordMorningFeel(wakeDate: LocalDate, feel: MorningFeel) = Unit
    override fun previewTone(tone: AlarmTone, style: WakeStyle, volume: Float) = Unit
    override fun stopPreview() = Unit
    override fun stopEverything() {
        calls += "stopEverything"
    }
}

/**
 * Skips the calling test while the part of the domain engine it needs is still a TODO() stub on this branch.
 * The engine is implemented in parallel, so these tests start running as soon as it is merged.
 */
private fun assumeImplemented(what: String, probe: () -> Unit) {
    val implemented = try {
        probe()
        true
    } catch (_: NotImplementedError) {
        false
    }
    assumeTrue("$what is not implemented on this branch yet", implemented)
}

fun assumeNightAssemblyImplemented() = assumeImplemented("NightAssembler") {
    val session = RawSleepSession(
        id = "probe",
        origin = GARMIN,
        start = Instant.parse("2026-10-05T23:00:00Z"),
        end = Instant.parse("2026-10-06T07:00:00Z"),
        startOffset = ZoneOffset.UTC,
        endOffset = ZoneOffset.UTC,
    )
    NightAssembler().assemble(listOf(session), SourceSelection.All)
}

fun assumeInsightsEngineImplemented() = assumeImplemented("InsightsEngine") {
    val today = LocalDate.of(2026, 10, 6)
    val nights = (0L..3L).map { testNight(today.minusDays(it)) }.reversed()
    InsightsEngine().compute(nights, UserSleepSettings(), HabitCatalog().defaultSettings(), Instant.parse("2026-10-06T09:00:00Z"), ZoneOffset.UTC)
}
