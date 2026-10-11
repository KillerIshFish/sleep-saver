package com.sleepsaver.core.alarm

import com.sleepsaver.core.alarm.mirror.AlarmAction
import com.sleepsaver.core.alarm.mirror.MirrorState
import com.sleepsaver.core.alarm.mirror.MirrorUpdate
import com.sleepsaver.core.alarm.mirror.MirroredAlarm
import com.sleepsaver.core.alarm.mirror.MirroredEvent
import com.sleepsaver.core.alarm.mirror.MirroredPrior
import com.sleepsaver.core.alarm.mirror.PendingAlarm
import com.sleepsaver.core.data.repository.AlarmPlan
import com.sleepsaver.core.domain.alarm.AlarmTuning
import com.sleepsaver.core.domain.alarm.DeepPrior
import com.sleepsaver.core.domain.alarm.RingOutcome
import com.sleepsaver.core.domain.alarm.SensingOutcome
import com.sleepsaver.core.domain.alarm.ZTuner
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.schedule.expectedOnset
import java.time.Instant

/** What a reschedule read from storage: the plan and the optional inputs around it (defaults when unreadable). */
internal data class PlanInputs(
    val plan: AlarmPlan,
    val tuning: AlarmTuning? = null,
    val activeAnchor: Anchor? = null,
    val prior: DeepPrior? = null,
    val use24h: Boolean = false,
)

/** What a plan apply changed besides the mirror. */
internal data class PlanApplied(
    /** A snoozed alarm the user turned off was cancelled: remove notification 302. */
    val cancelledSnooze: Boolean,
)

/**
 * The arming rules shared by the full reschedule (T1, from Room) and the locked re-arm (T11, from the mirror). Pure.
 */
internal class PlanArming(private val config: SleepConfig = SleepConfig.DEFAULT) {
    private val tuner = ZTuner(config)

    /**
     * T1 step 5–7 inside the coordinator's apply: arm [inputs]' next alarm (or cancel the plan channel on a successful
     * "none"), keep tonight's sensing results for the same occurrence, re-assert the extra channels, and cancel a
     * pending planned ring whose occurrence the user switched off (recorded CANCELLED). [windowRunId] = the occurrence
     * NightService is sensing for right now.
     */
    fun apply(m: MirrorState, inputs: PlanInputs, now: Instant, windowRunId: String?): MirrorUpdate<PlanApplied> {
        val plan = inputs.plan
        val zThreshold = tuner.effectiveZ(inputs.tuning)
        val actions = mutableListOf<AlarmAction>()
        var state = m.copy(use24h = inputs.use24h, prior = inputs.prior?.let(MirroredPrior::from))

        val next = plan.next
        state = if (next == null) {
            state.copy(armed = null, upcoming = emptyList())
        } else {
            val onset = expectedOnset(inputs.activeAnchor?.at, next.plannedBedtime, next.latency, next.occurrence.target, next.need)
            val armed = MirroredAlarm.from(next, zThreshold, onset).withSensingOf(m.armed)
            val upcoming = plan.following.map { following ->
                val followingOnset = expectedOnset(null, following.plannedBedtime, following.latency, following.occurrence.target, following.need)
                MirroredAlarm.from(following, zThreshold, followingOnset).withSensingOf(m.upcoming.firstOrNull { it.occurrenceId == following.occurrence.id })
            }
            state.copy(armed = armed, upcoming = upcoming)
        }
        val armedUpdate = arm(state, now, windowRunId)
        state = armedUpdate.state
        actions += armedUpdate.actions

        // Step 7: a pending planned ring (snooze, restless trigger, catch-up, backup, resume) of an alarm switched off.
        var cancelledSnooze = false
        val extra = state.extra
        if (extra != null && extra.isPlanned && extra.occurrenceId !in plan.plannedIds) {
            val history = extra.event ?: extra.alarm?.let {
                MirroredEvent.firstRing(extra.kind, extra.occurrenceId, it, extra.at, extra.ringReason)
            }
            state = state.copy(extra = null)
            if (history != null) state = state.queueEvent(history.ended(RingOutcome.CANCELLED))
            actions += AlarmAction.Cancel(PendingAlarm.RING_EXTRA)
            cancelledSnooze = true
        }
        return MirrorUpdate(state, PlanApplied(cancelledSnooze), actions)
    }

    /**
     * The AlarmManager calls for what the mirror holds (T1 step 5–6, T11): RING_PLAN at the armed target, WINDOW per
     * the window rule, and the pending extra and catnap rings. A cancelled plan channel when nothing is armed.
     */
    fun arm(m: MirrorState, now: Instant, windowRunId: String?): MirrorUpdate<Unit> {
        val actions = mutableListOf<AlarmAction>()
        var state = m
        val armed = m.armed
        if (armed == null) {
            actions += AlarmAction.Cancel(PendingAlarm.RING_PLAN)
            actions += AlarmAction.Cancel(PendingAlarm.WINDOW)
        } else {
            actions += AlarmAction.ArmPlan(armed.target, armed.occurrenceId)
            val (windowed, windowAction) = window(armed, now, runGoing = windowRunId == armed.occurrenceId)
            state = state.copy(armed = windowed)
            actions += windowAction
        }
        m.extra?.let { if (it.at > now) actions += AlarmAction.ArmExtra(it.at) }
        m.catnap?.let { if (it.ringAt > now) actions += AlarmAction.ArmCatnap(it.ringAt) }
        return MirrorUpdate(state, Unit, actions)
    }

    /**
     * The WINDOW rule: SURFACING with a window → w = window start − sensingLeadIn. Before w: WINDOW at w. Inside the
     * lead-in or window (until target − minLead) with no run going: WINDOW at now + catchUpDelay, unless tonight's run
     * already ended with a result (an UNAVAILABLE attempt, e.g. locked or refused, is tried again). Otherwise no WINDOW;
     * too late for any attempt means the ring logs SENSING_UNAVAILABLE.
     */
    fun window(armed: MirroredAlarm, now: Instant, runGoing: Boolean): Pair<MirroredAlarm, AlarmAction> {
        val cancel = AlarmAction.Cancel(PendingAlarm.WINDOW)
        if (!armed.isSurfacing) return armed to cancel
        val surfacing = config.surfacing
        val opens = armed.windowStart - surfacing.sensingLeadIn
        val ran = armed.outcome != null && armed.outcome != SensingOutcome.UNAVAILABLE
        return when {
            now < opens -> armed to AlarmAction.ArmWindow(opens, armed.occurrenceId)
            runGoing || ran -> armed to cancel
            now < armed.target - surfacing.minLead -> armed to AlarmAction.ArmWindow(now + config.alarm.catchUpDelay, armed.occurrenceId)
            armed.outcome == null -> armed.copy(sensingOutcome = SensingOutcome.UNAVAILABLE.name) to cancel
            else -> armed to cancel
        }
    }
}
