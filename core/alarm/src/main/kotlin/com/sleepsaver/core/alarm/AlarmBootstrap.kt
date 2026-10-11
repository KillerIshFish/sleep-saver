package com.sleepsaver.core.alarm

import android.content.Context
import android.util.Log
import com.sleepsaver.core.alarm.mirror.MirrorUpdate
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.repository.JournalRepository
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.data.repository.SyncRepository
import com.sleepsaver.core.domain.journal.FeelSource
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.time.Duration.Companion.seconds

/**
 * Starts the alarm machinery from SleepSaverApplication (SPEC_V2 §4.9). [startLocked] runs on every process start,
 * also before the first unlock after a reboot; [startUnlocked] runs once after unlock. Public because the app injects
 * it; its constructor is internal because it takes core:alarm's internal types.
 */
@Singleton
class AlarmBootstrap @Inject internal constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: AlarmCoordinator,
    private val nightController: NightControllerImpl,
    private val scheduler: dagger.Lazy<AlarmSchedulerImpl>,
    private val schedule: dagger.Lazy<ScheduleRepository>,
    private val nightLog: dagger.Lazy<NightLogRepository>,
    private val journal: dagger.Lazy<JournalRepository>,
    private val patterns: dagger.Lazy<PatternsRepository>,
    private val settings: dagger.Lazy<SettingsRepository>,
    private val sync: dagger.Lazy<SyncRepository>,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val unlockedStarted = AtomicBoolean(false)

    /**
     * Before or after unlock: channels, reconcile, re-arm from the mirror, restore NightController's state. Touches no
     * credential-encrypted storage and resolves no repository.
     */
    fun startLocked() {
        AlarmChannels.ensure(context)
        scheduler.get().rearmFromMirror()
        nightController.refresh()
    }

    /**
     * After unlock, once: flush the events and Morning feel buffered while locked, reschedule from Room, then follow
     * the plan: debounce(1 s) + distinctUntilChanged + collectLatest { reschedule() } over the next alarm (id, window,
     * debt-aware), the tuning, the active anchor, the prior (nights used) and the clock format and demo mode. Each sync
     * that succeeds also refreshes the restless-trigger tuning.
     */
    @OptIn(FlowPreview::class)
    fun startUnlocked() {
        if (!unlockedStarted.compareAndSet(false, true)) return
        scope.launch {
            flushPendingFeel()
            flushPendingLogEnds()
            scheduler.get().flushEvents()
            rescheduleSafely()
        }
        scope.launch {
            combine(
                schedule.get().nextAlarm.map { next -> next?.let { Triple(it.occurrence.id, it.window, it.window.debtAware) } }.orNull(),
                nightLog.get().tuning.orNull(),
                nightLog.get().activeAnchor.map { it?.id }.orNull(),
                patterns.get().deepPrior.map { it?.nightsUsed }.orNull(),
                settings.get().settings.map { it.clockFormat to it.demoMode }.orNull(),
            ) { next, tuning, anchor, prior, clock -> listOf(next, tuning, anchor, prior, clock) }
                .debounce(1.seconds)
                .distinctUntilChanged()
                .collectLatest { rescheduleSafely() }
        }
        scope.launch {
            sync.get().state.map { it.lastSuccessAt }
                .filterNotNull()
                .distinctUntilChanged()
                .catch { Log.w(TAG, "Sync state unreadable", it) }
                .collect {
                    try {
                        nightLog.get().refreshTuning()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "Tuning refresh failed", e)
                    }
                }
        }
    }

    private suspend fun rescheduleSafely() {
        try {
            scheduler.get().reschedule()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Reschedule failed; the armed alarms stay", e)
        }
    }

    /** A Morning feel tapped on the ringing screen before unlock goes to the journal now. */
    private suspend fun flushPendingFeel() {
        val pending = coordinator.read().pendingFeel ?: return
        val feel = pending.feel
        if (feel != null) {
            try {
                journal.get().setFeel(
                    wakeDate = LocalDate.parse(pending.wakeDate),
                    feel = feel,
                    source = FeelSource.RING,
                    at = Instant.ofEpochMilli(pending.atEpochMs),
                    offset = ZoneOffset.ofTotalSeconds(pending.offsetSec),
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Buffered Morning feel not saved yet", e)
                return
            }
        }
        coordinator.apply { m -> MirrorUpdate(if (m.pendingFeel == pending) m.copy(pendingFeel = null) else m, Unit) }
    }

    /** Movement logs whose night a stop before unlock ended are closed now (each one leaves the mirror once saved). */
    private suspend fun flushPendingLogEnds() {
        for (pending in coordinator.read().pendingLogEnds) {
            try {
                nightLog.get().endMovementLog(pending.logId, Instant.ofEpochMilli(pending.endEpochMs))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Buffered movement log end not saved yet", e)
                return
            }
            coordinator.apply { m -> MirrorUpdate(m.copy(pendingLogEnds = m.pendingLogEnds - pending), Unit) }
        }
    }

    /** An input that fails reads as null instead of ending the collector. */
    private fun <T> Flow<T>.orNull(): Flow<T?> {
        val nullable: Flow<T?> = this
        return nullable.catch { error ->
            Log.w(TAG, "Alarm plan input failed", error)
            emit(null)
        }
    }

    private companion object {
        const val TAG = "SleepSaverAlarm"
    }
}
