package com.sleepsaver.feature.review.morning

import android.content.Intent
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.sleepsaver.core.data.healthconnect.HealthConnectGateway
import com.sleepsaver.core.data.healthconnect.garminSyncIntent
import com.sleepsaver.core.data.healthconnect.syncOpensGarmin
import com.sleepsaver.core.data.hooks.MorningReviewNotifier
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.InsightsRepository
import com.sleepsaver.core.data.repository.JournalRepository
import com.sleepsaver.core.data.repository.MorningReview
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.ReviewRepository
import com.sleepsaver.core.data.repository.ReviewStatus
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SleepRepository
import com.sleepsaver.core.data.repository.SyncError
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.data.repository.SyncTrigger
import com.sleepsaver.core.domain.alarm.AlarmTuning
import com.sleepsaver.core.domain.alarm.WakeLanding
import com.sleepsaver.core.domain.debt.SleepDebtCalculator
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.journal.MorningFeelEntry
import com.sleepsaver.core.domain.journal.PromptKind
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.domain.model.DebtChangeBreakdown
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.patterns.OddNight
import com.sleepsaver.feature.review.common.JournalWindow
import com.sleepsaver.feature.review.common.NightSource
import com.sleepsaver.feature.review.common.journalWindow
import com.sleepsaver.feature.review.common.loadEditor
import com.sleepsaver.feature.review.common.parseWakeDate
import com.sleepsaver.feature.review.common.today
import com.sleepsaver.feature.review.edit.NightEditorState
import com.sleepsaver.feature.review.edit.NightSave
import com.sleepsaver.feature.review.edit.apply
import com.sleepsaver.feature.review.edit.isVisibleIn
import com.sleepsaver.feature.review.landing.noteAsOf
import com.sleepsaver.feature.review.navigation.MorningReviewRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** One screen of the morning review flow. */
sealed interface ReviewStep {
    /** Syncing (or recalculating, with [recalculating]): a calm orb. */
    data class Loading(val recalculating: Boolean) : ReviewStep

    data class Summary(val night: Night, val source: NightSource) : ReviewStep

    /** The night isn't in. [syncError]: why the last sync couldn't read Health Connect (null = it could). */
    data class Missing(val syncError: SyncError? = null) : ReviewStep {
        val syncFailed: Boolean get() = syncError != null
    }

    /** The editor, inline. [saveFailed]: the last "Done" couldn't be written, so the edits are still here. */
    data class Adjust(val editor: NightEditorState, val saveFailed: Boolean = false) : ReviewStep

    data class Result(val update: DebtUpdate) : ReviewStep
}

/** How last night moved the debt, with the status colour before and after. */
data class DebtUpdate(
    val breakdown: DebtChangeBreakdown,
    val statusBefore: DebtStatus,
    val statusAfter: DebtStatus,
)

/**
 * The review screen. Besides [step], the cards around it: Morning feel, the odd-night prompt and the Tomorrow list
 * on the summary; Wake landing and the night's marks on the result.
 */
data class MorningReviewUiState(
    val wakeDate: LocalDate,
    val today: LocalDate,
    val step: ReviewStep,
    /** Recorded and done: the screen closes. */
    val finished: Boolean = false,
    /** This morning's Morning feel (null = not answered yet). */
    val feel: MorningFeelEntry? = null,
    /** `feelEditable`: the scale and "Change" show only while true; afterwards the answer is read-only. */
    val feelEditable: Boolean = true,
    /** "Anything different last night?", while it's up (null = an ordinary night, already asked, or skipped). */
    val oddNight: OddNight? = null,
    /** The Tomorrow list written the evening before (nightOf = wakeDate − 1), when it has items. */
    val tomorrowList: TomorrowList? = null,
    /** Where the planned alarm landed (null = no planned alarm rang for this wake date). */
    val landing: WakeLanding? = null,
    /** The restless-trigger change to explain on the landing card (RAISED/LOWERED this week), else null. */
    val tuningNote: AlarmTuning? = null,
    /** The night as loaded, for the Wake landing chart's stages. */
    val night: Night? = null,
    /** The marks set for this wake date. */
    val marks: Set<Mark> = emptySet(),
    /** `markEditable`: marks can still be changed. */
    val marksEditable: Boolean = true,
)

@HiltViewModel
class MorningReviewViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val sleepRepository: SleepRepository,
    private val insightsRepository: InsightsRepository,
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
    private val reviewRepository: ReviewRepository,
    private val notifier: MorningReviewNotifier,
    private val timeProvider: TimeProvider,
    private val healthConnect: HealthConnectGateway,
    private val journalRepository: JournalRepository,
    private val nightLogRepository: NightLogRepository,
    private val patternsRepository: PatternsRepository,
) : ViewModel() {

    private val wakeDate: LocalDate = timeProvider.parseWakeDate(savedStateHandle.toRoute<MorningReviewRoute>().wakeDate)
    private val today = timeProvider.today()

    private enum class Phase { SYNCING, REVIEW, ADJUST, RECALCULATING, RESULT }

    private val phase = MutableStateFlow(Phase.SYNCING)
    private val editor = MutableStateFlow<NightEditorState?>(null)
    private val update = MutableStateFlow<DebtUpdate?>(null)
    private val syncError = MutableStateFlow<SyncError?>(null)
    private val saveFailed = MutableStateFlow(false)
    private val finished = MutableStateFlow(false)

    /** What the user decided (set on "Looks right" or a saved adjustment), recorded on Done. */
    private var outcome: ReviewStatus? = null
    private var work: Job? = null

    /** Set once the review is being recorded, so a double tap can't record (or close) twice. */
    private var finishing = false

    /** null until the night has loaded once. */
    private val night: StateFlow<Loaded?> = sleepRepository.night(wakeDate)
        .map { Loaded(it) }
        .catch { emit(Loaded(null)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private class Loaded(val night: Night?)

    // ---------------------------------------------------------------- Journal, odd night, landing

    /** Can feel and marks still change (re-checked every minute; feel stays editable until today's wake time). */
    private val window: StateFlow<JournalWindow?> = journalWindow(wakeDate, timeProvider, patternsRepository)
        .catch { emit(JournalWindow(today, timeProvider.now(), feelEditable = false, marksEditable = false)) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val marks: StateFlow<Set<Mark>> = journalRepository.marks(wakeDate)
        .catch { emit(emptySet()) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /** The odd-night prompt once it has been shown: it stays up until Skip, even after promptShown turns true. */
    private val oddPrompt = MutableStateFlow<OddNight?>(null)
    private val oddDismissed = MutableStateFlow(false)

    private data class Extras(
        val feel: MorningFeelEntry? = null,
        val window: JournalWindow? = null,
        val tomorrowList: TomorrowList? = null,
        val landing: WakeLanding? = null,
        val tuning: AlarmTuning? = null,
        val marks: Set<Mark> = emptySet(),
        val oddNight: OddNight? = null,
    )

    private val extras: Flow<Extras> = combine(
        journalRepository.feel(wakeDate).orNull(),
        window,
        journalRepository.tomorrowList(wakeDate.minusDays(1)).orNull(),
        combine(nightLogRepository.landing(wakeDate).orNull(), nightLogRepository.tuning.orNull(), ::Pair),
        combine(marks, oddPrompt, oddDismissed) { marks, odd, dismissed -> marks to odd?.takeUnless { dismissed } },
    ) { feel, window, list, (landing, tuning), (marks, odd) ->
        Extras(feel, window, list?.takeIf { it.items.isNotEmpty() }, landing, tuning, marks, odd)
    }

    private data class Flags(val syncError: SyncError?, val saveFailed: Boolean, val finished: Boolean, val extras: Extras)

    val uiState: StateFlow<MorningReviewUiState> = combine(
        phase,
        night,
        editor,
        update,
        combine(syncError, saveFailed, finished, extras, ::Flags),
    ) { phase, loaded, editor, update, flags ->
        val extras = flags.extras
        MorningReviewUiState(
            wakeDate = wakeDate,
            // The journal window is re-checked every minute: a review left open past midnight moves on to the new day.
            today = extras.window?.today ?: today,
            step = step(phase, loaded, editor, update, flags),
            finished = flags.finished,
            feel = extras.feel,
            feelEditable = extras.window?.feelEditable ?: false,
            oddNight = extras.oddNight,
            tomorrowList = extras.tomorrowList,
            landing = extras.landing,
            tuningNote = extras.landing?.let { extras.tuning.noteAsOf(extras.window?.now ?: timeProvider.now()) },
            night = loaded?.night,
            marks = extras.marks,
            marksEditable = extras.window?.marksEditable ?: false,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MorningReviewUiState(wakeDate, today, ReviewStep.Loading(false)))

    init {
        sync(first = true)
        watchOddNight()
    }

    /**
     * Puts up "Anything different last night?" the first time the summary shows a night that differed from the usual
     * (oddNight != null) and the prompt wasn't shown for this wake date before. Showing it records markPromptShown,
     * so it is asked at most once a day.
     */
    private fun watchOddNight() {
        viewModelScope.launch {
            val odd = runCatching {
                combine(
                    phase,
                    night,
                    patternsRepository.oddNight(wakeDate),
                    journalRepository.promptShown(wakeDate, PromptKind.ODD_NIGHT),
                ) { phase, loaded, odd, shown ->
                    odd.takeIf { phase == Phase.REVIEW && loaded?.night?.isPresent == true && !shown }
                }.filterNotNull().first()
            }.getOrNull() ?: return@launch
            oddPrompt.value = odd
            runCatching { journalRepository.markPromptShown(wakeDate, PromptKind.ODD_NIGHT) }
        }
    }

    private fun step(phase: Phase, loaded: Loaded?, editor: NightEditorState?, update: DebtUpdate?, flags: Flags): ReviewStep =
        when (phase) {
            Phase.SYNCING -> ReviewStep.Loading(recalculating = false)
            Phase.REVIEW -> {
                val night = loaded?.night
                when {
                    loaded == null -> ReviewStep.Loading(recalculating = false)
                    night != null && night.isPresent ->
                        ReviewStep.Summary(night, NightSource.of(night, timeProvider.zone(), timeProvider.now()))
                    else -> ReviewStep.Missing(syncError = flags.syncError)
                }
            }
            Phase.ADJUST -> editor?.let { ReviewStep.Adjust(it, flags.saveFailed) } ?: ReviewStep.Loading(recalculating = false)
            Phase.RECALCULATING -> ReviewStep.Loading(recalculating = true)
            Phase.RESULT -> update?.let(ReviewStep::Result) ?: ReviewStep.Loading(recalculating = true)
        }

    private val opensGarmin: StateFlow<Boolean> = settingsRepository.settings
        .map { it.syncOpensGarmin() }
        .catch { emit(false) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    /**
     * The missing night's "Sync now": with Garmin as the source, Garmin Connect has to sync the watch first, so the
     * screen opens it and calls [onSyncNow] when it's back. null = sync right away.
     */
    fun garminSyncIntent(): Intent? = if (opensGarmin.value) healthConnect.garminSyncIntent() else null

    /** Pulls the latest night (skipped in demo mode), showing the orb for at least [MIN_SYNC_MS]. */
    fun onSyncNow() {
        if (phase.value == Phase.REVIEW) sync()
    }

    /** [first]: the sync on opening, after which the alarm tuning is refreshed once (last night's landing counts). */
    private fun sync(first: Boolean = false) {
        phase.value = Phase.SYNCING
        work = viewModelScope.launch {
            coroutineScope {
                launch { delay(MIN_SYNC_MS) }
                val demo = runCatching { settingsRepository.current().demoMode }.getOrDefault(false)
                syncError.value = if (demo) {
                    null
                } else {
                    val result = runCatching { syncRepository.sync(SyncTrigger.MANUAL) }.getOrNull()
                    if (result?.success == true) null else result?.error ?: SyncError.UNKNOWN
                }
            }
            phase.value = Phase.REVIEW
            if (first) runCatching { nightLogRepository.refreshTuning() }
        }
    }

    /** Morning feel from the review's scale, saved over an earlier answer so the latest one counts. */
    fun onFeel(feel: MorningFeel) {
        if (window.value?.feelEditable != true) return
        viewModelScope.launch { runCatching { journalRepository.setFeel(wakeDate, feel, FeelSource.REVIEW) } }
    }

    /** Ticks or unticks one of the eight marks for this wake date (derived marks and old nights are read-only). */
    fun onMarkToggle(mark: Mark) {
        if (mark.derived || window.value?.marksEditable != true) return
        val on = mark !in marks.value
        viewModelScope.launch { runCatching { journalRepository.setMark(wakeDate, mark, on) } }
    }

    /** "Skip" on the odd-night prompt. */
    fun onOddNightDismiss() {
        oddDismissed.value = true
    }

    /** Wake landing's "Reset to default": the restless trigger goes back to its standard threshold. */
    fun onResetTuning() {
        viewModelScope.launch { runCatching { nightLogRepository.resetTuning() } }
    }

    /** "Looks right": recalculate with the night as it is. */
    fun onLooksRight() {
        if (phase.value != Phase.REVIEW || night.value?.night?.isPresent != true) return
        outcome = ReviewStatus.CONFIRMED
        recalculate(NightSave.NoChange)
    }

    /** "Adjust" (or a tap on any value): the editor, inline. Also "Add it manually" for a missing night. */
    fun onAdjust() {
        if (phase.value != Phase.REVIEW) return
        phase.value = Phase.ADJUST
        editor.value = null
        saveFailed.value = false
        work = viewModelScope.launch {
            val loaded = runCatching {
                loadEditor(wakeDate, sleepRepository, settingsRepository, insightsRepository, timeProvider)
            }.getOrNull()
            if (loaded != null) editor.value = loaded else phase.value = Phase.REVIEW
        }
    }

    fun onFellAsleepChange(time: Instant) = edit { it.withFellAsleep(time) }

    fun onWokeUpChange(time: Instant) = edit { it.withWokeUp(time) }

    fun onAwakeChange(minutes: Int) = edit { it.withAwake(minutes) }

    fun onNapsChange(minutes: Int) = edit { it.withNaps(minutes) }

    fun onAdjustCancel() {
        if (phase.value != Phase.ADJUST) return
        work?.cancel()
        editor.value = null
        phase.value = Phase.REVIEW
    }

    /** Saves the adjustment (only what changed), then recalculates. No change counts as "Looks right". */
    fun onAdjustDone() {
        val current = editor.value ?: return
        if (phase.value != Phase.ADJUST || current.error != null) return
        val save = current.toSave(timeProvider.now())
        outcome = if (save == NightSave.NoChange) ReviewStatus.CONFIRMED else ReviewStatus.ADJUSTED
        recalculate(save)
    }

    private fun recalculate(save: NightSave) {
        phase.value = Phase.RECALCULATING
        work = viewModelScope.launch {
            val saved = coroutineScope {
                launch { delay(MIN_RECALC_MS) }
                val ok = runCatching { sleepRepository.apply(save) }.isSuccess
                if (ok && save != NightSave.NoChange) {
                    // The data layer re-assembles nights asynchronously: wait (briefly) until the edit shows up.
                    runCatching {
                        withTimeoutOrNull(PROPAGATION_TIMEOUT_MS) {
                            sleepRepository.night(wakeDate).first { save.isVisibleIn(it) }
                        }
                    }
                }
                ok
            }
            if (!saved) {
                // Couldn't write the edit: back to the editor (with a note) so nothing is lost.
                outcome = null
                saveFailed.value = true
                phase.value = if (editor.value != null) Phase.ADJUST else Phase.REVIEW
                return@launch
            }
            val breakdown = runCatching { insightsRepository.breakdown(wakeDate) }.getOrNull()
            if (breakdown == null) {
                finish(outcome ?: ReviewStatus.CONFIRMED, null)
                return@launch
            }
            val sleep = runCatching { settingsRepository.current().sleep }.getOrNull()
            val calculator = SleepDebtCalculator()
            fun status(debt: Duration) = if (sleep != null) {
                calculator.status(debt, sleep.lowDebtMax, sleep.highDebtMin)
            } else {
                calculator.status(debt)
            }
            update.value = DebtUpdate(breakdown, status(breakdown.before), status(breakdown.after))
            phase.value = Phase.RESULT
        }
    }

    /** "Done" on the result: record how the review went and clear the notification. */
    fun onDone() {
        if (phase.value != Phase.RESULT) return
        finish(outcome ?: ReviewStatus.CONFIRMED, update.value?.breakdown)
    }

    /** The close X. After a confirmation it counts as done; before, as dismissed (when there was a night to review). */
    fun onClose() {
        if (finished.value || finishing) return
        val decided = outcome
        if (decided != null && (phase.value == Phase.RESULT || phase.value == Phase.RECALCULATING)) {
            finish(decided, update.value?.breakdown)
            return
        }
        finishing = true
        work?.cancel()
        viewModelScope.launch {
            if (night.value?.night?.isPresent == true) recordDismissed()
            notifier.cancel()
            finished.value = true
        }
    }

    /** "Don't show these reviews again": turn them off, dismiss this one, close. */
    fun onDontShowAgain() {
        if (finished.value || finishing) return
        finishing = true
        work?.cancel()
        viewModelScope.launch {
            runCatching { settingsRepository.setMorningReviewEnabled(false) }
            recordDismissed()
            notifier.cancel()
            finished.value = true
        }
    }

    private fun finish(status: ReviewStatus, breakdown: DebtChangeBreakdown?) {
        if (finishing) return
        finishing = true
        viewModelScope.launch {
            runCatching {
                reviewRepository.record(
                    MorningReview(
                        wakeDate = wakeDate,
                        status = status,
                        at = timeProvider.now(),
                        debtBefore = breakdown?.before,
                        debtAfter = breakdown?.after,
                    ),
                )
            }
            notifier.cancel()
            finished.value = true
        }
    }

    /** A dismissal never overwrites an earlier confirmation or adjustment of the same night. */
    private suspend fun recordDismissed() {
        runCatching {
            val existing = reviewRepository.review(wakeDate).first()
            if (existing == null || existing.status == ReviewStatus.DISMISSED) {
                reviewRepository.record(MorningReview(wakeDate, ReviewStatus.DISMISSED, timeProvider.now(), null, null))
            }
        }
    }

    private fun edit(block: (NightEditorState) -> NightEditorState) {
        if (phase.value != Phase.ADJUST) return
        editor.update { it?.let(block) }
        saveFailed.value = false
    }

    companion object {
        /** The sync orb stays up at least this long, so the screen doesn't flash. */
        const val MIN_SYNC_MS = 1_200L

        /** "Updating your sleep debt" stays up this long. */
        const val MIN_RECALC_MS = 2_000L

        const val PROPAGATION_TIMEOUT_MS = 3_000L
    }
}

/**
 * A flow that reads as "nothing" until it has a value and instead of failing, so a slow or broken extra never holds up
 * or takes down the review.
 */
private fun <T> Flow<T?>.orNull(): Flow<T?> = onStart { emit(null) }.catch { emit(null) }
