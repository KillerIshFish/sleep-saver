package com.sleepsaver.feature.tonight.ringing

import android.content.Context
import androidx.compose.runtime.Immutable
import androidx.core.os.UserManagerCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.sleepsaver.core.data.hooks.NightController
import com.sleepsaver.core.data.hooks.Ringing
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.JournalRepository
import com.sleepsaver.core.domain.alarm.RingKind
import com.sleepsaver.core.domain.journal.MorningFeel
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject

/** The ringing screen. Never name a state `Ringing`: inside this interface it would hide the hook's [Ringing]. */
internal sealed interface RingingUiState {
    /** A ring is sounding: [ringing] as core:alarm publishes it, and the time [now] for the big clock. */
    @Immutable
    data class Sounding(val ringing: Ringing, val now: Instant) : RingingUiState

    /**
     * Stop was tapped on a ring of [kind]. A planned morning alarm offers Morning feel for [wakeDate] ([feel] = the
     * answer so far); [unlocked] = the user has unlocked since boot, so the morning review can open.
     */
    @Immutable
    data class AfterStop(val kind: RingKind, val wakeDate: LocalDate?, val feel: MorningFeel?, val unlocked: Boolean) : RingingUiState

    /** Nothing is ringing (snoozed, stopped elsewhere, timed out): the screen closes. */
    data object Gone : RingingUiState
}

/** The ring Stop was tapped on, at [at]. */
internal data class StoppedRing(val kind: RingKind, val wakeDate: LocalDate?, val at: Instant)

/** Builds the ringing screen's state: a ring newer than the last Stop sounds; after Stop, the after-stop screen. */
internal fun ringingState(ringing: Ringing?, now: Instant, stopped: StoppedRing?, feel: MorningFeel?, unlocked: Boolean): RingingUiState =
    when {
        ringing != null && (stopped == null || ringing.since.isAfter(stopped.at)) -> RingingUiState.Sounding(ringing, now)
        stopped != null -> RingingUiState.AfterStop(stopped.kind, stopped.wakeDate, feel, unlocked)
        else -> RingingUiState.Gone
    }

/**
 * The ringing screen's ViewModel. Reachable before the first unlock after a reboot (direct boot, SPEC_V2 §4.9), so it
 * only holds direct-boot-safe things: the app context, [NightController] (whose state lives in the alarm service and
 * the device-protected mirror), [TimeProvider], and the [JournalRepository] behind a [Lazy] that is only resolved
 * once the user has unlocked (to show a Morning feel already given). A feel tapped while locked goes to
 * [NightController.recordMorningFeel], which buffers it until unlock.
 */
@HiltViewModel
internal class RingingViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val nightController: NightController,
    private val journal: Lazy<JournalRepository>,
    private val time: TimeProvider,
) : ViewModel() {

    private val stopped = MutableStateFlow<StoppedRing?>(null)
    private val feel = MutableStateFlow<MorningFeel?>(null)
    private val unlocked = MutableStateFlow(isUnlocked())

    /** A feel already saved for the stopped morning; only read once unlocked (credential storage). */
    private val savedFeel: Flow<MorningFeel?> = combine(stopped, unlocked, ::Pair).flatMapLatest { (stop, isUnlocked) ->
        val wakeDate = stop?.wakeDate
        if (stop?.kind == RingKind.PLANNED && wakeDate != null && isUnlocked) {
            journal.get().feel(wakeDate).map { it?.feel }.catch { emit(null) }
        } else {
            flowOf(null)
        }
    }

    val uiState: StateFlow<RingingUiState> = combine(
        nightController.state,
        time.minuteTicks,
        stopped,
        combine(feel, savedFeel.onStart { emit(null) }) { tapped, saved -> tapped ?: saved },
        unlocked,
    ) { night, now, stop, shownFeel, isUnlocked -> ringingState(night.ringing, now, stop, shownFeel, isUnlocked) }
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ringingState(nightController.state.value.ringing, time.now(), null, null, unlocked.value),
        )

    init {
        // RingingActivity is singleInstance, so this ViewModel can outlive its ring (left with Home instead of Done) and
        // be reused by the next one: a catnap, a test ring, tomorrow's alarm. The last Stop and its feel belong to the
        // old ring: once a newer ring shows, forget them, or that ring's Snooze would bring back the old after-stop
        // screen and its Stop would show the old feel as already chosen.
        viewModelScope.launch {
            nightController.state.map { it.ringing?.since }.distinctUntilChanged().collect { since ->
                val stop = stopped.value
                if (since != null && stop != null && since.isAfter(stop.at)) forgetStop()
            }
        }
    }

    fun onSnooze() = nightController.snooze()

    fun onStop() {
        val ringing = nightController.state.value.ringing ?: (uiState.value as? RingingUiState.Sounding)?.ringing ?: return
        val wakeDate = ringing.wakeDate ?: ringing.target?.takeIf { ringing.kind == RingKind.PLANNED }?.atZone(ringing.zone)?.toLocalDate()
        feel.value = null // a feel tapped after an earlier Stop isn't this ring's
        stopped.value = StoppedRing(ringing.kind, wakeDate, time.now())
        nightController.stopRinging()
    }

    /** A Morning feel tap after a planned alarm: saved now, or buffered until unlock. */
    fun onFeel(value: MorningFeel) {
        val stop = stopped.value ?: return
        val wakeDate = stop.wakeDate ?: return
        if (stop.kind != RingKind.PLANNED) return
        feel.value = value
        nightController.recordMorningFeel(wakeDate, value)
    }

    /** The screen came back: the user may have unlocked meanwhile. */
    fun onResume() {
        unlocked.value = isUnlocked()
    }

    private fun forgetStop() {
        stopped.value = null
        feel.value = null
    }

    private fun isUnlocked(): Boolean = UserManagerCompat.isUserUnlocked(context)
}
