package com.sleepsaver.core.testing

import com.sleepsaver.core.data.hooks.ActiveCatnap
import com.sleepsaver.core.data.hooks.ActiveNight
import com.sleepsaver.core.data.hooks.AlarmScheduler
import com.sleepsaver.core.data.hooks.HushState
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.NightState
import com.sleepsaver.core.data.repository.AlarmPlan
import com.sleepsaver.core.data.repository.DebtPreviewInputs
import com.sleepsaver.core.data.repository.HushSettings
import com.sleepsaver.core.data.repository.HushSettingsRepository
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
import com.sleepsaver.core.domain.alarm.RingKind
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
import com.sleepsaver.core.domain.schedule.AlarmTone
import com.sleepsaver.core.domain.schedule.DateOverride
import com.sleepsaver.core.domain.schedule.DayPart
import com.sleepsaver.core.domain.schedule.DayPlan
import com.sleepsaver.core.domain.schedule.NightRules
import com.sleepsaver.core.domain.schedule.OneOffs
import com.sleepsaver.core.domain.schedule.PlanPause
import com.sleepsaver.core.domain.schedule.WakeStyle
import com.sleepsaver.core.domain.schedule.WeekPlan
import com.sleepsaver.core.domain.schedule.WeekPreset
import com.sleepsaver.core.domain.schedule.withPreset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

// In-memory fakes for the v2 repositories and hooks. Like FakeRepositories.kt: each exposes its backing state as
// MutableStateFlows so tests can drive it, and records calls so tests can assert on them. None calls a stubbed
// (B1/B2) domain engine.

// ---------------------------------------------------------------- Week plan

class FakeScheduleRepository(
    week: WeekPlan = WeekPlan.DEFAULT,
    oneOffs: OneOffs = OneOffs(),
    usualWake: Map<DayOfWeek, LocalTime> = DayOfWeek.values().associateWith { LocalTime.of(7, 0) },
    upcoming: List<PlannedAlarm> = emptyList(),
) : ScheduleRepository {
    val weekState = MutableStateFlow(week)
    val oneOffsState = MutableStateFlow(oneOffs)
    val usualWakeState = MutableStateFlow(usualWake)
    val upcomingState = MutableStateFlow(upcoming)

    /** What [computeAlarmPlan] returns; null = built from [upcomingState]. */
    var alarmPlanResult: AlarmPlan? = null

    /** When set, [computeAlarmPlan] throws it (a storage failure). */
    var alarmPlanError: Throwable? = null

    val alarmPlanCalls = mutableListOf<Pair<Instant, Set<String>>>()
    val updatedDays = mutableListOf<DayOfWeek>()
    val dayRoutines = mutableListOf<Pair<DayOfWeek, List<Long>?>>()
    val copies = mutableListOf<Triple<DayOfWeek, Set<DayOfWeek>, Set<DayPart>>>()
    val presets = mutableListOf<Pair<WeekPreset, LocalTime>>()

    /** Every setOverride call, in order. */
    val overrides = mutableListOf<DateOverride>()
    val clearedOverrides = mutableListOf<LocalDate>()

    /** Every setPause call, in order (null = resume). */
    val pauses = mutableListOf<PlanPause?>()

    override val weekPlan: Flow<WeekPlan> = weekState
    override val oneOffs: Flow<OneOffs> = oneOffsState
    override val usualWake: Flow<Map<DayOfWeek, LocalTime>> = usualWakeState
    override val upcomingAlarms: Flow<List<PlannedAlarm>> = upcomingState
    override val nextAlarm: Flow<PlannedAlarm?> = upcomingState.map { it.firstOrNull() }

    override suspend fun computeAlarmPlan(now: Instant, exclude: Set<String>): AlarmPlan {
        alarmPlanCalls += now to exclude
        alarmPlanError?.let { throw it }
        alarmPlanResult?.let { return it }
        val alarms = upcomingState.value.filter { it.occurrence.target > now && it.occurrence.id !in exclude }
        return AlarmPlan(
            next = alarms.firstOrNull(),
            following = alarms.drop(1).take(SleepConfig.DEFAULT.alarm.followingMirrored),
            plannedIds = upcomingState.value.mapTo(LinkedHashSet()) { it.occurrence.id },
            computedAt = now,
        )
    }

    /** Applies [change] to the stored day as it is (no normalising). */
    override suspend fun updateDay(day: DayOfWeek, change: (DayPlan) -> DayPlan) {
        updatedDays += day
        weekState.update { week -> week.with(change(week.day(day)).copy(day = day)) }
    }

    override suspend fun setDayRoutine(day: DayOfWeek, activityIds: List<Long>?) {
        dayRoutines += day to activityIds
        weekState.update { week ->
            val plan = week.day(day)
            week.with(plan.copy(evening = plan.evening.copy(routine = activityIds)))
        }
    }

    override suspend fun copyDay(from: DayOfWeek, to: Set<DayOfWeek>, parts: Set<DayPart>) {
        copies += Triple(from, to, parts)
        weekState.update { it.copyParts(from, to, parts) }
    }

    override suspend fun applyPreset(preset: WeekPreset, wake: LocalTime) {
        presets += preset to wake
        weekState.update { it.withPreset(preset, wake) }
    }

    override suspend fun setOverride(override: DateOverride) {
        overrides += override
        oneOffsState.update { it.copy(overrides = it.overrides.filterNot { o -> o.date == override.date } + override) }
    }

    override suspend fun clearOverride(date: LocalDate) {
        clearedOverrides += date
        oneOffsState.update { it.copy(overrides = it.overrides.filterNot { o -> o.date == date }) }
    }

    override suspend fun setPause(pause: PlanPause?) {
        pauses += pause
        oneOffsState.update { it.copy(pause = pause) }
    }
}

// ---------------------------------------------------------------- Night steps

class FakeNightStepsRepository(
    tonight: NightStepsSession? = null,
    activities: List<RoutineActivity> = emptyList(),
    adherence: StepAdherence = StepAdherence(evenings = emptyList(), doneRecent = 0, countedRecent = 0, quietStreak = 0),
    suggestions: List<StepSuggestion> = emptyList(),
    /** The time setDone stamps on a checked step. */
    var clock: () -> Instant = { SampleData.now },
) : NightStepsRepository {
    val tonightState = MutableStateFlow(tonight)

    /** Sessions for other evenings; [session] falls back to [tonightState] when its nightOf matches. */
    val sessionsState = MutableStateFlow<Map<LocalDate, NightStepsSession>>(emptyMap())
    val activitiesState = MutableStateFlow(activities)
    val adherenceState = MutableStateFlow(adherence)
    val suggestionsState = MutableStateFlow(suggestions)

    val doneCalls = mutableListOf<Triple<LocalDate, Long, Boolean>>()
    val techniquesAdded = mutableListOf<Pair<Technique, String>>()
    val customsAdded = mutableListOf<RoutineActivity>()
    val timings = mutableListOf<Triple<Long, Duration, Duration>>()
    val included = mutableListOf<Pair<Long, Boolean>>()
    val deletedCustoms = mutableListOf<Long>()

    override val tonight: Flow<NightStepsSession?> = tonightState
    override fun session(nightOf: LocalDate): Flow<NightStepsSession?> =
        combine(tonightState, sessionsState) { tonight, sessions -> sessions[nightOf] ?: tonight?.takeIf { it.nightOf == nightOf } }
    override val activities: Flow<List<RoutineActivity>> = activitiesState
    override val adherence: Flow<StepAdherence> = adherenceState
    override val suggestions: Flow<List<StepSuggestion>> = suggestionsState

    /** Records the call and checks/unchecks the step in tonight's session when it is that evening. */
    override suspend fun setDone(nightOf: LocalDate, activityId: Long, done: Boolean) {
        doneCalls += Triple(nightOf, activityId, done)
        tonightState.update { session ->
            if (session == null || session.nightOf != nightOf) return@update session
            session.copy(
                steps = session.steps.map { step ->
                    if (step.activity.id != activityId) step else step.copy(doneAt = if (done) step.doneAt ?: clock() else null)
                },
            )
        }
    }

    override suspend fun addTechnique(technique: Technique, label: String): Long {
        techniquesAdded += technique to label
        val existing = activitiesState.value.firstOrNull { it.technique == technique }
        if (existing != null) {
            setSelected(existing.id, true)
            return existing.id
        }
        val timing = SleepConfig.DEFAULT.steps.timing(technique)
        val id = nextId()
        activitiesState.update {
            it + RoutineActivity(
                id = id,
                label = label,
                emoji = technique.emoji,
                isCustom = false,
                selected = true,
                technique = technique,
                lead = timing.lead,
                duration = timing.duration,
            )
        }
        return id
    }

    override suspend fun addCustom(label: String, emoji: String, lead: Duration, duration: Duration): Long {
        val activity = RoutineActivity(nextId(), label, emoji, isCustom = true, selected = true, lead = lead, duration = duration)
        customsAdded += activity
        activitiesState.update { it + activity }
        return activity.id
    }

    override suspend fun setTiming(activityId: Long, lead: Duration, duration: Duration) {
        timings += Triple(activityId, lead, duration)
        activitiesState.update { list -> list.map { if (it.id == activityId) it.copy(lead = lead, duration = duration) else it } }
    }

    override suspend fun setIncluded(activityId: Long, included: Boolean) {
        this.included += activityId to included
        setSelected(activityId, included)
    }

    override suspend fun deleteCustom(activityId: Long) {
        require(activitiesState.value.firstOrNull { it.id == activityId }?.isCustom == true) { "only custom activities can be deleted" }
        deletedCustoms += activityId
        activitiesState.update { list -> list.filterNot { it.id == activityId } }
    }

    private fun setSelected(id: Long, selected: Boolean) =
        activitiesState.update { list -> list.map { if (it.id == id) it.copy(selected = selected) else it } }

    private fun nextId(): Long = (activitiesState.value.maxOfOrNull { it.id } ?: 0) + 1
}

// ---------------------------------------------------------------- Journal

class FakeJournalRepository(
    /** The time setFeel / setTomorrowList / setMark stamp when no time is passed. */
    var clock: () -> Instant = { SampleData.now },
    var zone: ZoneId = SampleData.zone,
) : JournalRepository {
    /** One setFeel call. */
    data class FeelCall(val wakeDate: LocalDate, val feel: MorningFeel?, val source: FeelSource, val at: Instant?, val offset: ZoneOffset?)

    val marksState = MutableStateFlow<Map<LocalDate, Set<Mark>>>(emptyMap())
    val feelsState = MutableStateFlow<Map<LocalDate, MorningFeelEntry>>(emptyMap())
    val tomorrowListsState = MutableStateFlow<Map<LocalDate, TomorrowList>>(emptyMap())
    val promptsState = MutableStateFlow<Set<Pair<LocalDate, PromptKind>>>(emptySet())

    val marksSet = mutableListOf<Triple<LocalDate, Mark, Boolean>>()
    val feelsSet = mutableListOf<FeelCall>()
    val tomorrowListsSet = mutableListOf<Pair<LocalDate, String>>()
    val promptsMarked = mutableListOf<Pair<LocalDate, PromptKind>>()

    override fun marks(wakeDate: LocalDate): Flow<Set<Mark>> = marksState.map { it[wakeDate].orEmpty() }
    override val marksByDate: Flow<Map<LocalDate, Set<Mark>>> = marksState

    override suspend fun setMark(wakeDate: LocalDate, mark: Mark, on: Boolean) {
        require(!mark.derived) { "derived marks can't be set" }
        marksSet += Triple(wakeDate, mark, on)
        marksState.update { all ->
            val updated = if (on) all[wakeDate].orEmpty() + mark else all[wakeDate].orEmpty() - mark
            if (updated.isEmpty()) all - wakeDate else all + (wakeDate to updated)
        }
    }

    override fun feel(wakeDate: LocalDate): Flow<MorningFeelEntry?> = feelsState.map { it[wakeDate] }
    override val feels: Flow<Map<LocalDate, MorningFeelEntry>> = feelsState

    override suspend fun setFeel(wakeDate: LocalDate, feel: MorningFeel?, source: FeelSource, at: Instant?, offset: ZoneOffset?) {
        feelsSet += FeelCall(wakeDate, feel, source, at, offset)
        feelsState.update { all ->
            if (feel == null) {
                all - wakeDate
            } else {
                val time = at ?: clock()
                all + (wakeDate to MorningFeelEntry(wakeDate, feel, time, offset ?: zone.rules.getOffset(time), source))
            }
        }
    }

    override fun tomorrowList(nightOf: LocalDate): Flow<TomorrowList?> = tomorrowListsState.map { it[nightOf] }

    override suspend fun setTomorrowList(nightOf: LocalDate, text: String) {
        tomorrowListsSet += nightOf to text
        tomorrowListsState.update { all ->
            if (text.isBlank()) {
                all - nightOf
            } else {
                val time = clock()
                all + (nightOf to TomorrowList(nightOf, text, time, zone.rules.getOffset(time)))
            }
        }
    }

    override fun promptShown(wakeDate: LocalDate, kind: PromptKind): Flow<Boolean> = promptsState.map { (wakeDate to kind) in it }

    override suspend fun markPromptShown(wakeDate: LocalDate, kind: PromptKind) {
        promptsMarked += wakeDate to kind
        promptsState.update { it + (wakeDate to kind) }
    }
}

// ---------------------------------------------------------------- Night log

class FakeNightLogRepository(
    var clock: () -> Instant = { SampleData.now },
) : NightLogRepository {
    /** One addAnchor call. */
    data class AnchorCall(val at: Instant, val offset: ZoneOffset, val source: AnchorSource, val wholeNightLog: Boolean)

    /** One startMovementLog call. */
    data class MovementLogCall(
        val start: Instant,
        val offset: ZoneOffset,
        val mode: String,
        val wakeDate: LocalDate,
        val anchorId: Long?,
        val occurrenceId: String?,
    )

    val activeAnchorState = MutableStateFlow<Anchor?>(null)
    val anchorsState = MutableStateFlow<List<Anchor>>(emptyList())
    val movementState = MutableStateFlow<Map<LocalDate, List<MovementTrace>>>(emptyMap())
    val eventsState = MutableStateFlow<List<AlarmEvent>>(emptyList())
    val landingsState = MutableStateFlow<Map<LocalDate, WakeLanding>>(emptyMap())
    val tuningState = MutableStateFlow<AlarmTuning?>(null)

    val anchorsAdded = mutableListOf<AnchorCall>()
    val anchorsDeleted = mutableListOf<Long>()
    val movementLogsStarted = mutableListOf<MovementLogCall>()
    val epochsAdded = mutableListOf<Pair<Long, List<MovementEpoch>>>()
    val movementLogsEnded = mutableListOf<Pair<Long, Instant>>()
    val recordedEvents = mutableListOf<AlarmEvent>()
    val hushPlaysStarted = mutableListOf<Pair<Instant, HushColor>>()
    val hushPlaysEnded = mutableListOf<Pair<Long, Instant>>()
    var refreshTuningCalls = 0
    var resetTuningCalls = 0
    private var nextLogId = 1L
    private var nextHushId = 1L

    override val activeAnchor: Flow<Anchor?> = activeAnchorState
    override fun anchors(wakeDate: LocalDate): Flow<List<Anchor>> = anchorsState.map { list -> list.filter { it.wakeDate == wakeDate } }
    override val allAnchors: Flow<List<Anchor>> = anchorsState

    /** Adds the anchor (wakeDate = NightRules.wakeDateOf) and makes it the active one. */
    override suspend fun addAnchor(at: Instant, offset: ZoneOffset, source: AnchorSource, wholeNightLog: Boolean): Long {
        anchorsAdded += AnchorCall(at, offset, source, wholeNightLog)
        val anchor = Anchor(
            id = (anchorsState.value.maxOfOrNull { it.id } ?: 0) + 1,
            at = at,
            offset = offset,
            wakeDate = NightRules.wakeDateOf(at, offset),
            source = source,
            wholeNightLog = wholeNightLog,
        )
        anchorsState.update { it + anchor }
        activeAnchorState.value = anchor
        return anchor.id
    }

    override suspend fun deleteAnchor(id: Long) {
        anchorsDeleted += id
        anchorsState.update { list -> list.filterNot { it.id == id } }
        if (activeAnchorState.value?.id == id) activeAnchorState.value = null
    }

    override suspend fun startMovementLog(
        start: Instant,
        offset: ZoneOffset,
        mode: String,
        wakeDate: LocalDate,
        anchorId: Long?,
        occurrenceId: String?,
    ): Long {
        movementLogsStarted += MovementLogCall(start, offset, mode, wakeDate, anchorId, occurrenceId)
        return nextLogId++
    }

    override suspend fun addEpochs(logId: Long, epochs: List<MovementEpoch>) {
        epochsAdded += logId to epochs.filter { it.activity != null }
    }

    override suspend fun endMovementLog(logId: Long, end: Instant) {
        movementLogsEnded += logId to end
    }

    override fun movement(wakeDate: LocalDate): Flow<List<MovementTrace>> = movementState.map { it[wakeDate].orEmpty() }

    /** Inserts (id 0 → the next id) or replaces the event with the same id; returns its id. */
    override suspend fun recordAlarmEvent(event: AlarmEvent): Long {
        val stored = if (event.id == 0L) event.copy(id = (eventsState.value.maxOfOrNull { it.id } ?: 0) + 1) else event
        recordedEvents += stored
        eventsState.update { list -> list.filterNot { it.id == stored.id } + stored }
        return stored.id
    }

    override fun alarmEvent(wakeDate: LocalDate): Flow<AlarmEvent?> = eventsState.map { list ->
        list.filter { it.kind == RingKind.PLANNED && it.wakeDate == wakeDate }.maxByOrNull { it.firstRingAt }
    }

    override val recentAlarmEvents: Flow<List<AlarmEvent>> = eventsState.map { list ->
        list.sortedWith(compareByDescending<AlarmEvent> { it.firstRingAt }.thenByDescending { it.id })
            .take(SleepConfig.DEFAULT.alarm.recentEventsLimit)
    }

    override val lastTestRing: Flow<AlarmEvent?> = eventsState.map { list ->
        list.filter { it.kind == RingKind.TEST }.maxByOrNull { it.firstRingAt }
    }

    override suspend fun handledOccurrenceIds(since: LocalDate): Set<String> = eventsState.value
        .filter { it.kind == RingKind.PLANNED && it.outcome != null && !it.wakeDate.isBefore(since) }
        .mapNotNullTo(LinkedHashSet()) { it.occurrenceId }

    override fun landing(wakeDate: LocalDate): Flow<WakeLanding?> = landingsState.map { it[wakeDate] }
    override val tuning: Flow<AlarmTuning?> = tuningState

    override suspend fun refreshTuning() {
        refreshTuningCalls++
    }

    override suspend fun resetTuning() {
        resetTuningCalls++
        tuningState.value = null
    }

    override suspend fun startHushPlay(start: Instant, offset: ZoneOffset, color: HushColor): Long {
        hushPlaysStarted += start to color
        return nextHushId++
    }

    override suspend fun endHushPlay(id: Long, end: Instant) {
        hushPlaysEnded += id to end
    }
}

// ---------------------------------------------------------------- Patterns

class FakePatternsRepository(
    keel: KeelResult = KeelResult.Calibrating(validDays = 0, needed = SleepConfig.DEFAULT.keel.minValidDays),
    socialJetlag: SocialJetlag? = null,
    planCheck: PlanCheck? = null,
    markEffects: List<MarkEffect> = emptyList(),
    personalLatency: PersonalLatency = PersonalLatency(values = 0, median = null, active = false),
    deepPrior: DeepPrior? = null,
    debtPreviewInputs: DebtPreviewInputs? = null,
) : PatternsRepository {
    val keelState = MutableStateFlow(keel)
    val socialJetlagState = MutableStateFlow(socialJetlag)
    val planCheckState = MutableStateFlow(planCheck)
    val markEffectsState = MutableStateFlow(markEffects)
    val oddNightsState = MutableStateFlow<Map<LocalDate, OddNight>>(emptyMap())
    val anchorStatsState = MutableStateFlow<Map<LocalDate, AnchorNightStats>>(emptyMap())
    val personalLatencyState = MutableStateFlow(personalLatency)
    val deepPriorState = MutableStateFlow(deepPrior)
    val debtPreviewInputsState = MutableStateFlow(debtPreviewInputs)

    override val keel: Flow<KeelResult> = keelState
    override val socialJetlag: Flow<SocialJetlag?> = socialJetlagState
    override val planCheck: Flow<PlanCheck?> = planCheckState
    override val markEffects: Flow<List<MarkEffect>> = markEffectsState
    override fun oddNight(wakeDate: LocalDate): Flow<OddNight?> = oddNightsState.map { it[wakeDate] }
    override fun anchorStats(wakeDate: LocalDate): Flow<AnchorNightStats?> = anchorStatsState.map { it[wakeDate] }
    override val personalLatency: Flow<PersonalLatency> = personalLatencyState
    override val deepPrior: Flow<DeepPrior?> = deepPriorState
    override val debtPreviewInputs: Flow<DebtPreviewInputs?> = debtPreviewInputsState
}

// ---------------------------------------------------------------- Hush settings

/** The shared Hush pick in memory; one instance given to two screens' ViewModels shows them sharing it. */
class FakeHushSettingsRepository(initial: HushSettings = HushSettings()) : HushSettingsRepository {
    val state = MutableStateFlow(initial)
    override val settings: Flow<HushSettings> = state
    override fun update(transform: (HushSettings) -> HushSettings) = state.update(transform)
}

// ---------------------------------------------------------------- Hooks

class FakeAlarmScheduler : AlarmScheduler {
    var reschedules = 0
    var cancelAlls = 0

    /** "reschedule" / "cancelAll", in call order. */
    val calls = mutableListOf<String>()

    override suspend fun reschedule() {
        reschedules++
        calls += "reschedule"
    }

    override fun cancelAll() {
        cancelAlls++
        calls += "cancelAll"
    }
}

/**
 * Records every call as "name(args)" in [nightControllerCalls] and applies the obvious state change, so screens can
 * be driven through [state] (`fake.state.value = PlanSamples.nightState(...)`). [dropAnchorResult] null = the anchor
 * couldn't be saved.
 */
class FakeNightController(
    initial: NightState = NightState(),
    var clock: () -> Instant = { SampleData.now },
    var zone: ZoneId = SampleData.zone,
) : NightController {
    /** Writable, as SPEC_V2 §3.5 promises (`FakeNightController.state: MutableStateFlow<NightState>`). */
    override val state: MutableStateFlow<NightState> = MutableStateFlow(initial)

    /** The same flow as [state], under the name the other fakes in this module use. */
    val mutableState: MutableStateFlow<NightState> get() = state

    val nightControllerCalls = mutableListOf<String>()
    var dropAnchorResult: Long? = 1L
    var catnapAllowed = true
    var testRingAllowed = true
    val feelsRecorded = mutableListOf<Pair<LocalDate, MorningFeel>>()

    override suspend fun dropAnchor(source: AnchorSource, wholeNightLog: Boolean): Long? {
        nightControllerCalls += "dropAnchor($source, $wholeNightLog)"
        val id = dropAnchorResult ?: return null
        val now = clock()
        mutableState.update { it.copy(night = ActiveNight(id, now, zone.rules.getOffset(now), wholeNightLog)) }
        return id
    }

    override suspend fun undoAnchor(anchorId: Long) {
        nightControllerCalls += "undoAnchor($anchorId)"
        mutableState.update { if (it.night?.anchorId == anchorId) it.copy(night = null, sensing = null) else it }
    }

    override fun endNight() {
        nightControllerCalls += "endNight"
        mutableState.update { it.copy(night = null, sensing = null, endedAt = clock()) }
    }

    override fun resumeMovementLog() {
        nightControllerCalls += "resumeMovementLog"
        mutableState.update { it.copy(sensing = it.sensing?.copy(running = true)) }
    }

    override fun startHush(color: HushColor, timer: Duration?, volume: Float) {
        nightControllerCalls += "startHush($color, $timer, $volume)"
        mutableState.update { it.copy(hush = HushState(color, volume, timer, timer?.let { t -> clock() + t })) }
    }

    override fun extendHush(by: Duration) {
        nightControllerCalls += "extendHush($by)"
        mutableState.update { it.copy(hush = it.hush?.let { h -> h.copy(endsAt = h.endsAt?.plus(by)) }) }
    }

    override fun setHushVolume(volume: Float) {
        nightControllerCalls += "setHushVolume($volume)"
        mutableState.update { it.copy(hush = it.hush?.copy(volume = volume)) }
    }

    override fun stopHush() {
        nightControllerCalls += "stopHush"
        mutableState.update { it.copy(hush = null) }
    }

    override fun startCatnap(length: Duration): Boolean {
        nightControllerCalls += "startCatnap($length)"
        if (!catnapAllowed) return false
        val now = clock()
        val ringsAt = now + SleepConfig.DEFAULT.catnap.fallAsleep + length
        mutableState.update { it.copy(catnap = ActiveCatnap(now, ringsAt, length)) }
        return true
    }

    override fun cancelCatnap() {
        nightControllerCalls += "cancelCatnap"
        mutableState.update { it.copy(catnap = null) }
    }

    override fun testRing(): Boolean {
        nightControllerCalls += "testRing"
        if (!testRingAllowed) return false
        mutableState.update { it.copy(testPendingAt = clock() + SleepConfig.DEFAULT.alarm.testRingDelay) }
        return true
    }

    override fun snooze() {
        nightControllerCalls += "snooze"
        mutableState.update { it.copy(ringing = null, snoozedUntil = it.ringing?.snooze?.until) }
    }

    override fun stopRinging() {
        nightControllerCalls += "stopRinging"
        mutableState.update { it.copy(ringing = null, snoozedUntil = null) }
    }

    override fun recordMorningFeel(wakeDate: LocalDate, feel: MorningFeel) {
        nightControllerCalls += "recordMorningFeel($wakeDate, $feel)"
        feelsRecorded += wakeDate to feel
    }

    override fun previewTone(tone: AlarmTone, style: WakeStyle, volume: Float) {
        nightControllerCalls += "previewTone($tone, $style, $volume)"
    }

    override fun stopPreview() {
        nightControllerCalls += "stopPreview"
    }

    override fun stopEverything() {
        nightControllerCalls += "stopEverything"
        mutableState.value = NightState()
    }
}
