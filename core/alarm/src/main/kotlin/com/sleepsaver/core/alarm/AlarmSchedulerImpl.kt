package com.sleepsaver.core.alarm

import android.content.Context
import android.util.Log
import androidx.core.os.UserManagerCompat
import com.sleepsaver.core.alarm.mirror.MirrorUpdate
import com.sleepsaver.core.alarm.mirror.unchanged
import com.sleepsaver.core.alarm.night.NightService
import com.sleepsaver.core.alarm.ring.AlarmRingService
import com.sleepsaver.core.alarm.ring.RingNotifications
import com.sleepsaver.core.data.di.ApplicationScope
import com.sleepsaver.core.data.hooks.AlarmScheduler
import com.sleepsaver.core.data.hooks.TimeProvider
import com.sleepsaver.core.data.repository.NightLogRepository
import com.sleepsaver.core.data.repository.PatternsRepository
import com.sleepsaver.core.data.repository.ScheduleRepository
import com.sleepsaver.core.data.repository.SettingsRepository
import com.sleepsaver.core.domain.config.SleepConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exact alarm arming (SPEC_V2 §4.4 T1, T2, T11, T18). Safe to call often; never starts a service.
 *
 * Direct-boot safe: its repositories are dagger.Lazy and only resolved when the user is unlocked, so the locked
 * re-arm ([rearmFromMirror]) works from the device-protected mirror alone.
 */
@Singleton
internal class AlarmSchedulerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val coordinator: AlarmCoordinator,
    private val time: TimeProvider,
    @ApplicationScope private val scope: CoroutineScope,
    private val schedule: dagger.Lazy<ScheduleRepository>,
    private val nightLog: dagger.Lazy<NightLogRepository>,
    private val patterns: dagger.Lazy<PatternsRepository>,
    private val settings: dagger.Lazy<SettingsRepository>,
) : AlarmScheduler {

    private val config = SleepConfig.DEFAULT
    private val reconciler = MirrorReconciler(config)
    private val arming = PlanArming(config)

    /**
     * T1: (1) under planMutex, (2) reconcile, (3) locked → re-arm from the mirror (T11). (4) Compute the plan (8 s);
     * on failure or timeout keep AlarmManager and the mirror as they are. (5–7) Apply it inside the coordinator when
     * what it depended on (the excluded ids and the armed id) is unchanged; else compute once more; else ask for one
     * more reschedule after the mutex. (8) Tell a window run for another occurrence that the plan moved.
     */
    override suspend fun reschedule() {
        coordinator.planMutex.withLock { rescheduleLocked() }
        if (coordinator.rescheduleAgain.getAndSet(false)) scope.launch { reschedule() }
    }

    /** T18: cancels RING_PLAN, WINDOW, RING_EXTRA and RING_CATNAP and clears the mirror (Reset all data). */
    override fun cancelAll() {
        coordinator.cancelAllAlarms()
        coordinator.clear()
    }

    /**
     * T11, the locked re-arm (also the first step of every boot path): reconcile (missed rings catch up on RING_EXTRA,
     * never for an occurrence that is sounding, snoozed, resuming or handled), re-resolve the armed alarm's date + local
     * time in the current zone, promote from `upcoming` when it passed, and arm RING_PLAN, WINDOW and the extra
     * channels from the mirror. Never touches credential-encrypted storage; never starts a service.
     */
    fun rearmFromMirror() {
        val now = time.now()
        // Read before the coordinator's lock: NightService takes its own lock first, then the coordinator's.
        val windowRunId = NightService.current?.windowRunId
        val reconciled = coordinator.apply { m ->
            val r = reconciler.reconcile(m, now, AlarmRingService.running, NightService.running, time.zone())
            val armed = arming.arm(r.state, now, windowRunId)
            MirrorUpdate(armed.state, r, r.actions + armed.actions)
        }
        afterReconcile(reconciled)
    }

    /** Writes queued alarm events to Room (the mirror's outbox) when unlocked. Ids come back into the mirror's copies. */
    suspend fun flushEvents() {
        if (!unlocked()) return
        flushMutex.withLock {
            for (event in coordinator.read().pendingEvents) {
                val id = try {
                    nightLog.get().recordAlarmEvent(event.toDomain())
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "Alarm event not saved yet; it stays queued", e)
                    return
                }
                coordinator.apply { m -> MirrorUpdate(m.afterFlush(event, id), Unit) }
            }
        }
    }

    /** [flushEvents] on the app scope (no-op while locked). */
    fun flushEventsAsync() {
        if (unlocked()) scope.launch { flushEvents() }
    }

    /** After a ring starts or stops: reschedule when unlocked, else the locked re-arm from `upcoming`. */
    fun replanAfterRing() {
        if (unlocked()) scope.launch { reschedule() } else rearmFromMirror()
    }

    fun unlocked(): Boolean = UserManagerCompat.isUserUnlocked(context)

    private suspend fun rescheduleLocked() {
        if (!unlocked()) {
            rearmFromMirror()
            return
        }
        val reconciled = coordinator.apply { m ->
            val r = reconciler.reconcile(m, time.now(), AlarmRingService.running, NightService.running, time.zone())
            MirrorUpdate(r.state, r, r.actions)
        }
        afterReconcile(reconciled)
        repeat(PLAN_ATTEMPTS) {
            val before = coordinator.read()
            val excluded = before.excludedIds
            val armedId = before.armed?.occurrenceId
            val now = time.now()
            val inputs = readInputs(now, excluded) ?: return
            val windowRunId = NightService.current?.windowRunId
            val applied = coordinator.apply<PlanApplied?> { m ->
                if (m.excludedIds != excluded || m.armed?.occurrenceId != armedId) {
                    m.unchanged(null)
                } else {
                    arming.apply(m, inputs, now, windowRunId)
                }
            }
            if (applied != null) {
                afterPlan(applied)
                return
            }
        }
        coordinator.rescheduleAgain.set(true)
    }

    /** The plan (required) and the optional reads around it (defaults when they fail or are slow). */
    private suspend fun readInputs(now: Instant, excluded: Set<String>): PlanInputs? {
        val plan = try {
            withTimeoutOrNull(AlarmPlatform.PLAN_TIMEOUT_MS) { schedule.get().computeAlarmPlan(now, excluded) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Alarm plan unreadable; keeping what is armed", e)
            return null
        }
        if (plan == null) {
            Log.w(TAG, "Alarm plan timed out; keeping what is armed")
            return null
        }
        val fallback = PlanInputs(plan, use24h = coordinator.read().use24h)
        return withTimeoutOrNull(AlarmPlatform.PLAN_EXTRAS_TIMEOUT_MS) {
            coroutineScope {
                val tuning = async { optional { nightLog.get().tuning.first() } }
                val anchor = async { optional { nightLog.get().activeAnchor.first() } }
                val prior = async { optional { patterns.get().deepPrior.first() } }
                val clock = async { optional { settings.get().settings.first().clockFormat } }
                PlanInputs(
                    plan = plan,
                    tuning = tuning.await(),
                    activeAnchor = anchor.await(),
                    prior = prior.await(),
                    use24h = clock.await()?.let { AlarmText.use24h(context, it) } ?: fallback.use24h,
                )
            }
        } ?: fallback
    }

    private fun afterPlan(applied: PlanApplied) {
        if (applied.cancelledSnooze) RingNotifications.cancel(context, AlarmActions.ID_SNOOZED)
        flushEventsAsync()
        val armed = coordinator.read().armed
        NightService.current?.let { service -> if (service.windowRunId != null && service.windowRunId != armed?.occurrenceId) service.onArmedChanged(armed) }
    }

    /** Room writes the reconcile asked for, once unlocked: close expired movement logs, end orphaned Hush plays, flush. */
    private fun afterReconcile(r: Reconciled) {
        if (!unlocked()) return
        scope.launch {
            r.logsToClose.forEach { (id, end) -> optional { nightLog.get().endMovementLog(id, end) } }
            r.hushPlaysToEnd.forEach { (id, end) -> optional { nightLog.get().endHushPlay(id, end) } }
            flushEvents()
        }
    }

    private suspend fun <T> optional(read: suspend () -> T): T? = try {
        read()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "Optional alarm input unreadable; using the default", e)
        null
    }

    private companion object {
        const val TAG = "SleepSaverAlarm"

        /** Compute once, and once more when the mirror moved during the compute (§4.3). */
        const val PLAN_ATTEMPTS = 2

        /** One flush at a time across every caller, so a queued event is never inserted twice. */
        val flushMutex = Mutex()
    }
}
