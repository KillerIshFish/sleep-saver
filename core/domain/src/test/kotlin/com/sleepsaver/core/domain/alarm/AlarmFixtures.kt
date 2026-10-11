package com.sleepsaver.core.domain.alarm

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.util.Random
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * B1's helpers for the alarm engine tests (Fx is frozen). Times are UTC wall clock on [DATE] unless a test says
 * otherwise.
 */
internal object AlarmFixtures {
    val DATE: LocalDate = LocalDate.of(2026, 10, 8)
    val EPOCH: Duration = SleepConfig.DEFAULT.surfacing.epoch
    private const val G = 9.81

    /** "06:12" or "06:12:30" on [DATE]. */
    fun at(time: String, date: LocalDate = DATE): Instant = Fx.instant(date, time)

    // ---------------------------------------------------------------- Surfacing nights and epochs

    /**
     * A 06:30 surfacing night: window 06:00–06:30, sensing from 05:45 (a full lead-in), zT 2.5, onset 23:00 the
     * evening before, uniform prior.
     */
    fun surfacingNight(
        target: Instant = at("06:30"),
        windowStart: Instant = target - Duration.ofMinutes(30),
        sensingStart: Instant = windowStart - SleepConfig.DEFAULT.surfacing.sensingLeadIn,
        debtAware: Boolean = false,
        zThreshold: Double = SleepConfig.DEFAULT.surfacing.restlessZ,
        expectedOnset: Instant = at("23:00", DATE.minusDays(1)),
        prior: DeepPrior? = null,
    ): SurfacingNight = SurfacingNight(
        occurrenceId = AlarmOccurrence.occurrenceId(DATE, LocalTime.of(6, 30)),
        target = target,
        windowStart = windowStart,
        sensingStart = sensingStart,
        debtAware = debtAware,
        zThreshold = zThreshold,
        expectedOnset = expectedOnset,
        prior = prior,
    )

    /** Consecutive epochs from [start], one per activity (null = missing). */
    fun epochs(start: Instant, vararg activity: Double?): List<MovementEpoch> =
        activity.mapIndexed { i, value -> MovementEpoch(start + EPOCH.multipliedBy(i.toLong()), value) }

    /** Epochs of [activity] for every slot in [from, until). */
    fun steady(from: Instant, until: Instant, activity: Double?): List<MovementEpoch> =
        generateSequence(from) { it + EPOCH }.takeWhile { it.isBefore(until) }.map { MovementEpoch(it, activity) }.toList()

    /** [stream] with the epochs starting in [from, until) set to [activity]. */
    fun List<MovementEpoch>.withActivity(from: Instant, until: Instant, activity: Double?): List<MovementEpoch> =
        map { if (!it.start.isBefore(from) && it.start.isBefore(until)) it.copy(activity = activity) else it }

    /**
     * Feeds [stream] to [decider] the way NightService does: after each epoch closes (now = its end), decide with
     * every epoch so far. Stops at the first Fire or Done. Returns (now, decision) for every call.
     */
    fun replay(
        decider: SurfacingDecider,
        night: SurfacingNight,
        stream: List<MovementEpoch>,
    ): List<Pair<Instant, SurfacingDecision>> {
        val decisions = ArrayList<Pair<Instant, SurfacingDecision>>()
        for (i in stream.indices) {
            val now = stream[i].start + EPOCH
            val decision = decider.decide(night, stream.subList(0, i + 1), now)
            decisions += now to decision
            if (decision != SurfacingDecision.Wait) break
        }
        return decisions
    }

    /** A prior with every bin at [p]. */
    fun flatPrior(p: Double, bins: Int = 72): DeepPrior =
        DeepPrior(Duration.ofMinutes(10), List(bins) { p }, List(bins) { p }, nightsUsed = 30)

    // ---------------------------------------------------------------- Accelerometer motions (m/s², device axes)

    /** One accelerometer reading. */
    data class Accel(val x: Double, val y: Double, val z: Double)

    /** Acceleration at t seconds after the start of a recording. */
    fun interface Motion {
        fun at(seconds: Double): Accel
    }

    /** Lying flat and still: gravity on z only. */
    val still = Motion { Accel(0.0, 0.0, G) }

    /** Gravity tilted by [degrees] about the y axis. */
    private fun tilted(degrees: Double): Accel {
        val angle = degrees * PI / 180
        return Accel(G * sin(angle), 0.0, G * cos(angle))
    }

    /** A raised cosine from 0 to 1 over u in [0, 1]. */
    private fun ease(u: Double): Double = when {
        u <= 0 -> 0.0
        u >= 1 -> 1.0
        else -> 0.5 - 0.5 * cos(PI * u)
    }

    /** The phone rolls 30° (a raised-cosine angle profile over 2 s) at [from] s and back 10 s later: a sleeper turning. */
    fun roll(from: Double = 40.0) = Motion { t -> tilted(30.0 * (ease((t - from) / 2) - ease((t - from - 10) / 2))) }

    /** Gravity rotating 30° at a steady rate over 2 s from [from] s, then staying tilted. */
    fun tilt(from: Double = 40.0) = Motion { t -> tilted(30.0 * ((t - from) / 2).coerceIn(0.0, 1.0)) }

    /**
     * A 2-s jolt of [amplitude] m/s² on every axis from [from] s (|Δa| = amplitude·√3), the mattress bumping when the
     * sleeper shifts; [axes] = 1 moves x only.
     */
    fun jolt(amplitude: Double = 0.3, from: Double = 40.0, axes: Int = 3) = Motion { t ->
        val on = if (t >= from && t < from + 2) amplitude else 0.0
        Accel(on, if (axes >= 2) on else 0.0, G + if (axes >= 3) on else 0.0)
    }

    /** Still, with independent Gaussian sensor noise of [sd] m/s² per axis (seeded, so every run reads the same). */
    fun noise(sd: Double = 0.015, seed: Long = 7L): Motion {
        val random = Random(seed)
        return Motion { Accel(sd * random.nextGaussian(), sd * random.nextGaussian(), G + sd * random.nextGaussian()) }
    }

    /**
     * Samples [motion] at [hz] from [start] for [length] (both ends included), feeds them to [meter] and flushes it.
     * Returns every epoch, oldest first.
     */
    fun record(
        motion: Motion,
        hz: Int = 25,
        length: Duration = Duration.ofSeconds(60),
        start: Instant = at("06:00"),
        meter: MovementMeter = MovementMeter(),
    ): List<MovementEpoch> {
        val stepMs = 1000L / hz
        val epochs = ArrayList<MovementEpoch>()
        var t = 0L
        while (t <= length.toMillis()) {
            val a = motion.at(t / 1000.0)
            epochs += meter.add(start.plusMillis(t), a.x.toFloat(), a.y.toFloat(), a.z.toFloat())
            t += stepMs
        }
        meter.flush()?.let { epochs += it }
        return epochs
    }

    /** The activity of the epoch starting at [start]. */
    fun List<MovementEpoch>.activityAt(start: Instant): Double? = single { it.start == start }.activity

    // ---------------------------------------------------------------- Landings

    /** A Wake landing for ZTuner: a 06:30 alarm on [wakeDate] that rang at [firstRingAt] for [reason]. */
    fun landing(
        wakeDate: LocalDate,
        reason: RingReason,
        stageAtRing: LandingStage = LandingStage.LIGHT,
        windowNonDeepMinutes: Int? = 20,
        firstRingAt: String = if (reason == RingReason.RESTLESS) "06:12" else "06:30",
        mode: AlarmMode = AlarmMode.SURFACING,
        hasStages: Boolean = true,
    ): WakeLanding {
        val event = Fx.alarmEvent(wakeDate, "06:30", firstRingAt, reason, mode = mode)
        return WakeLanding(
            event = event,
            stageAtRing = stageAtRing,
            majorityBefore = stageAtRing,
            minutesEarly = event.earlyBy.toMinutes().toInt(),
            windowDeepShare = 0.2,
            windowNonDeepMinutes = windowNonDeepMinutes,
            hasStages = hasStages,
        )
    }
}
