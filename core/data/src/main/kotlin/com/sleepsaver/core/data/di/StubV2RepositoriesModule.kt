package com.sleepsaver.core.data.di

import com.sleepsaver.core.data.repository.AlarmPlan
import com.sleepsaver.core.data.repository.DebtPreviewInputs
import com.sleepsaver.core.data.repository.JournalRepository
import com.sleepsaver.core.data.repository.MovementTrace
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.data.repository.NightStepsRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.PlannedAlarm
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.domain.alarm.AlarmEvent
import com.sleepsaver.core.domain.alarm.AlarmTuning
import com.sleepsaver.core.domain.alarm.DeepPrior
import com.sleepsaver.core.domain.alarm.HushColor
import com.sleepsaver.core.domain.alarm.MovementEpoch
import com.sleepsaver.core.domain.alarm.WakeLanding
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.journal.MorningFeelEntry
import com.sleepsaver.core.domain.journal.PromptKind
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.domain.model.RoutineActivity
import com.sleepsaver.core.domain.model.UserSleepSettings
import com.sleepsaver.core.domain.nightsteps.NightStepsSession
import com.sleepsaver.core.domain.nightsteps.StepAdherence
import com.sleepsaver.core.domain.nightsteps.StepSuggestion
import com.sleepsaver.core.domain.nightsteps.Technique
import com.sleepsaver.core.domain.patterns.AnchorNightStats
import com.sleepsaver.core.domain.patterns.KeelResult
import com.sleepsaver.core.domain.patterns.MarkEffect
import com.sleepsaver.core.domain.patterns.OddNight
import com.sleepsaver.core.domain.patterns.PersonalLatency
import com.sleepsaver.core.domain.patterns.PlanCheck
import com.sleepsaver.core.domain.patterns.SocialJetlag
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.domain.schedule.DayPlan
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.domain.schedule.WeekPreset
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import javax.inject.Inject

/**
 * Stage-2 placeholder bindings for the v2 repositories, so the Hilt graph builds before their implementations exist:
 * empty or default flows, no-op writes, and an empty alarm plan. B3 deletes this file when it binds the real
 * implementations in DataBindingsModule.
 */
@Module
@InstallIn(SingletonComponent::class)
internal abstract class StubV2RepositoriesModule {
    @Binds
    abstract fun scheduleRepository(impl: StubScheduleRepository): ScheduleRepository

    @Binds
    abstract fun nightStepsRepository(impl: StubNightStepsRepository): NightStepsRepository

    @Binds
    abstract fun journalRepository(impl: StubJournalRepository): JournalRepository

    @Binds
    abstract fun nightLogRepository(impl: StubNightLogRepository): NightLogRepository

    @Binds
    abstract fun patternsRepository(impl: StubPatternsRepository): PatternsRepository
}

// ---------------------------------------------------------------- Stubs

internal class StubScheduleRepository @Inject constructor() : ScheduleRepository {
    override val weekPlan: Flow<WeekPlan> = flowOf(WeekPlan.DEFAULT)
    override val oneOffs: Flow<OneOffs> = flowOf(OneOffs())
    override val usualWake: Flow<Map<DayOfWeek, LocalTime>> =
        flowOf(DayOfWeek.values().associateWith { UserSleepSettings().typicalWake })
    override val upcomingAlarms: Flow<List<PlannedAlarm>> = flowOf(emptyList())
    override val nextAlarm: Flow<PlannedAlarm?> = flowOf(null)
    override suspend fun computeAlarmPlan(now: Instant, exclude: Set<String>): AlarmPlan =
        AlarmPlan(next = null, following = emptyList(), plannedIds = emptySet(), computedAt = now)
    override suspend fun updateDay(day: DayOfWeek, change: (DayPlan) -> DayPlan) = Unit
    override suspend fun setDayRoutine(day: DayOfWeek, activityIds: List<Long>?) = Unit
    override suspend fun copyDay(from: DayOfWeek, to: Set<DayOfWeek>, parts: Set<DayPart>) = Unit
    override suspend fun applyPreset(preset: WeekPreset, wake: LocalTime) = Unit
    override suspend fun setOverride(override: DateOverride) = Unit
    override suspend fun clearOverride(date: LocalDate) = Unit
    override suspend fun setPause(pause: PlanPause?) = Unit
}

internal class StubNightStepsRepository @Inject constructor() : NightStepsRepository {
    override val tonight: Flow<NightStepsSession?> = flowOf(null)
    override fun session(nightOf: LocalDate): Flow<NightStepsSession?> = flowOf(null)
    override val activities: Flow<List<RoutineActivity>> = flowOf(emptyList())
    override val adherence: Flow<StepAdherence> =
        flowOf(StepAdherence(evenings = emptyList(), doneRecent = 0, countedRecent = 0, quietStreak = 0))
    override val suggestions: Flow<List<StepSuggestion>> = flowOf(emptyList())
    override suspend fun setDone(nightOf: LocalDate, activityId: Long, done: Boolean) = Unit
    override suspend fun addTechnique(technique: Technique, label: String): Long = 0L
    override suspend fun addCustom(label: String, emoji: String, lead: Duration, duration: Duration): Long = 0L
    override suspend fun setTiming(activityId: Long, lead: Duration, duration: Duration) = Unit
    override suspend fun setIncluded(activityId: Long, included: Boolean) = Unit
    override suspend fun deleteCustom(activityId: Long) = Unit
}

internal class StubJournalRepository @Inject constructor() : JournalRepository {
    override fun marks(wakeDate: LocalDate): Flow<Set<Mark>> = flowOf(emptySet())
    override val marksByDate: Flow<Map<LocalDate, Set<Mark>>> = flowOf(emptyMap())
    override suspend fun setMark(wakeDate: LocalDate, mark: Mark, on: Boolean) = Unit
    override fun feel(wakeDate: LocalDate): Flow<MorningFeelEntry?> = flowOf(null)
    override val feels: Flow<Map<LocalDate, MorningFeelEntry>> = flowOf(emptyMap())
    override suspend fun setFeel(wakeDate: LocalDate, feel: MorningFeel?, source: FeelSource, at: Instant?, offset: ZoneOffset?) = Unit
    override fun tomorrowList(nightOf: LocalDate): Flow<TomorrowList?> = flowOf(null)
    override suspend fun setTomorrowList(nightOf: LocalDate, text: String) = Unit
    override fun promptShown(wakeDate: LocalDate, kind: PromptKind): Flow<Boolean> = flowOf(false)
    override suspend fun markPromptShown(wakeDate: LocalDate, kind: PromptKind) = Unit
}

internal class StubNightLogRepository @Inject constructor() : NightLogRepository {
    override val activeAnchor: Flow<Anchor?> = flowOf(null)
    override fun anchors(wakeDate: LocalDate): Flow<List<Anchor>> = flowOf(emptyList())
    override val allAnchors: Flow<List<Anchor>> = flowOf(emptyList())
    override suspend fun addAnchor(at: Instant, offset: ZoneOffset, source: AnchorSource, wholeNightLog: Boolean): Long = 0L
    override suspend fun deleteAnchor(id: Long) = Unit
    override suspend fun startMovementLog(
        start: Instant,
        offset: ZoneOffset,
        mode: String,
        wakeDate: LocalDate,
        anchorId: Long?,
        occurrenceId: String?,
    ): Long = 0L
    override suspend fun addEpochs(logId: Long, epochs: List<MovementEpoch>) = Unit
    override suspend fun endMovementLog(logId: Long, end: Instant) = Unit
    override fun movement(wakeDate: LocalDate): Flow<List<MovementTrace>> = flowOf(emptyList())
    override suspend fun recordAlarmEvent(event: AlarmEvent): Long = event.id
    override fun alarmEvent(wakeDate: LocalDate): Flow<AlarmEvent?> = flowOf(null)
    override val recentAlarmEvents: Flow<List<AlarmEvent>> = flowOf(emptyList())
    override val lastTestRing: Flow<AlarmEvent?> = flowOf(null)
    override suspend fun handledOccurrenceIds(since: LocalDate): Set<String> = emptySet()
    override fun landing(wakeDate: LocalDate): Flow<WakeLanding?> = flowOf(null)
    override val tuning: Flow<AlarmTuning?> = flowOf(null)
    override suspend fun refreshTuning() = Unit
    override suspend fun resetTuning() = Unit
    override suspend fun startHushPlay(start: Instant, offset: ZoneOffset, color: HushColor): Long = 0L
    override suspend fun endHushPlay(id: Long, end: Instant) = Unit
}

internal class StubPatternsRepository @Inject constructor() : PatternsRepository {
    override val keel: Flow<KeelResult> =
        flowOf(KeelResult.Calibrating(validDays = 0, needed = SleepConfig.DEFAULT.keel.minValidDays))
    override val socialJetlag: Flow<SocialJetlag?> = flowOf(null)
    override val planCheck: Flow<PlanCheck?> = flowOf(null)
    override val markEffects: Flow<List<MarkEffect>> = flowOf(emptyList())
    override fun oddNight(wakeDate: LocalDate): Flow<OddNight?> = flowOf(null)
    override fun anchorStats(wakeDate: LocalDate): Flow<AnchorNightStats?> = flowOf(null)
    override val personalLatency: Flow<PersonalLatency> = flowOf(PersonalLatency(values = 0, median = null, active = false))
    override val deepPrior: Flow<DeepPrior?> = flowOf(null)
    override val debtPreviewInputs: Flow<DebtPreviewInputs?> = flowOf(null)
}
