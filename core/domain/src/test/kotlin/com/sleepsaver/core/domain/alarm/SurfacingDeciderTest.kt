package com.sleepsaver.core.domain.alarm

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.AlarmFixtures.at
import com.sleepsaver.core.domain.alarm.AlarmFixtures.replay
import com.sleepsaver.core.domain.alarm.AlarmFixtures.steady
import com.sleepsaver.core.domain.alarm.AlarmFixtures.surfacingNight
import com.sleepsaver.core.domain.alarm.AlarmFixtures.withActivity
import com.sleepsaver.core.domain.alarm.SurfacingDecision.Done
import com.sleepsaver.core.domain.alarm.SurfacingDecision.Fire
import com.sleepsaver.core.domain.alarm.SurfacingDecision.Wait
import java.time.Duration
import java.time.Instant
import java.util.Random
import org.junit.jupiter.api.Test

/**
 * A 06:30 alarm with a 30-min window: sensing from 05:45, calibration 05:45–06:00, ringing allowed from 06:03.
 * A quiet calibration at activity 2 gives median 2 and sigma 5 (the floor), so z = (activity − 2) / 5: 14.5 is
 * exactly zT (2.5), 17 is z 3 and 40 is a spike (z 7.6).
 */
class SurfacingDeciderTest {

    private val decider = SurfacingDecider()
    private val night = surfacingNight()

    /** Every slot from 05:45 to the wake time at a still-phone level. */
    private val quiet: List<MovementEpoch> = steady(at("05:45"), at("06:30"), 2.0)

    /** The stream with the epoch starting at [time] set to [activity]. */
    private fun List<MovementEpoch>.withEpoch(time: String, activity: Double?): List<MovementEpoch> =
        withActivity(at(time), at(time) + AlarmFixtures.EPOCH, activity)

    private fun List<Pair<Instant, SurfacingDecision>>.final(): Pair<Instant, SurfacingDecision> = last()

    // ---------------------------------------------------------------- Calibration

    @Test
    fun `calibration is the median and MAD of the lead-in`() {
        val leadIn = AlarmFixtures.epochs(at("05:45"), *(1..30).map { it.toDouble() }.toTypedArray())
        val stream = AlarmFixtures.epochs(at("05:44:30"), 500.0) + leadIn + AlarmFixtures.epochs(at("06:00"), 500.0)

        val calibration = checkNotNull(decider.calibration(night, stream))

        // median 15.5; |c − 15.5| = 0.5, 0.5, 1.5, 1.5 … 14.5, 14.5 → MAD 7.5; sigma = 1.4826 · 7.5.
        assertThat(calibration.median).isWithin(1e-9).of(15.5)
        assertThat(calibration.sigma).isWithin(1e-9).of(1.4826 * 7.5)
        assertThat(calibration.epochs).isEqualTo(30)
    }

    @Test
    fun `a flat calibration uses the sigma floor`() {
        assertThat(decider.calibration(night, quiet)).isEqualTo(Calibration(median = 2.0, sigma = 5.0, epochs = 30))
    }

    @Test
    fun `calibration needs 20 epochs with activity`() {
        val nineteen = quiet.withActivity(at("05:45"), at("05:50:30"), null) // 11 missing, 19 left
        val twenty = quiet.withActivity(at("05:45"), at("05:50"), null)

        assertThat(decider.calibration(night, nineteen)).isNull()
        assertThat(decider.calibration(night, twenty)?.epochs).isEqualTo(20)
    }

    // ---------------------------------------------------------------- Firing

    @Test
    fun `a flat calibration then a big movement fires a spike`() {
        val decisions = replay(decider, night, quiet.withEpoch("06:10", 40.0))

        assertThat(decisions.dropLast(1).map { it.second }.toSet()).containsExactly(Wait)
        assertThat(decisions.final()).isEqualTo(at("06:10:30") to Fire(at("06:10:30"), 7.6, FireRule.SPIKE))
    }

    @Test
    fun `two of the last three epochs at the threshold fire`() {
        val stream = quiet.withEpoch("06:10", 14.5).withEpoch("06:11", 17.0)

        val (now, decision) = replay(decider, night, stream).final()

        assertThat(now).isEqualTo(at("06:11:30"))
        assertThat(decision).isEqualTo(Fire(at("06:11:30"), 3.0, FireRule.TWO_OF_THREE))
    }

    @Test
    fun `one of three waits`() {
        val decisions = replay(decider, night, quiet.withEpoch("06:10", 17.0).withEpoch("06:20", 17.0))

        assertThat(decisions.final().second).isEqualTo(Done(SensingOutcome.NO_TRIGGER))
        assertThat(decisions.none { it.second is Fire }).isTrue()
    }

    @Test
    fun `a missing slot breaks two of three`() {
        val stream = quiet.withEpoch("06:10", 17.0).withEpoch("06:10:30", null).withEpoch("06:11", 17.0)

        assertThat(replay(decider, night, stream).final().second).isEqualTo(Done(SensingOutcome.NO_TRIGGER))
    }

    @Test
    fun `a missing slot doesn't stop a spike`() {
        val stream = quiet.withEpoch("06:10", null).withEpoch("06:10:30", 40.0).withEpoch("06:11", null)

        assertThat(replay(decider, night, stream).final().second).isEqualTo(Fire(at("06:11"), 7.6, FireRule.SPIKE))
    }

    @Test
    fun `nothing fires in the first 3 minutes of the window`() {
        val decisions = replay(decider, night, quiet.withActivity(at("06:00"), at("06:30"), 40.0))

        assertThat(decisions.dropLast(1).map { it.second }.toSet()).containsExactly(Wait)
        assertThat(decisions.final()).isEqualTo(at("06:03") to Fire(at("06:03"), 7.6, FireRule.SPIKE))
    }

    @Test
    fun `an alarm armed late waits for 15 minutes of calibration`() {
        // Sensing started at 05:55, so calibration runs 05:55–06:10 and the trigger opens at 06:10.
        val late = surfacingNight(sensingStart = at("05:55"))
        val stream = steady(at("05:55"), at("06:30"), 2.0).withActivity(at("06:05"), at("06:30"), 40.0)

        val decisions = replay(decider, late, stream)

        assertThat(decider.calibration(late, stream)?.epochs).isEqualTo(30)
        assertThat(decisions.dropLast(1).map { it.second }.toSet()).containsExactly(Wait)
        assertThat(decisions.final()).isEqualTo(at("06:10:30") to Fire(at("06:10:30"), 7.6, FireRule.SPIKE))
    }

    @Test
    fun `stillness never fires`() {
        val random = Random(11)
        val stream = quiet.map { it.copy(activity = 1.0 + 3.0 * random.nextDouble()) }

        assertThat(replay(decider, night, stream).final().second).isEqualTo(Done(SensingOutcome.FLAT))
    }

    // ---------------------------------------------------------------- Threshold adjustments

    @Test
    fun `likely deep sleep raises the threshold by one`() {
        val stream = quiet.withActivity(at("06:10"), at("06:11"), 17.0) // z 3 twice in a row

        assertThat(replay(decider, night, stream).final().second).isInstanceOf(Fire::class.java)
        assertThat(replay(decider, night.copy(prior = AlarmFixtures.flatPrior(0.8)), stream).final().second)
            .isEqualTo(Done(SensingOutcome.NO_TRIGGER))
        // The gate is strict: a prior of exactly 0.5 doesn't raise it.
        assertThat(replay(decider, night.copy(prior = AlarmFixtures.flatPrior(0.5)), stream).final().second)
            .isInstanceOf(Fire::class.java)
    }

    @Test
    fun `the deep-sleep prior is read at time since onset and time before the wake`() {
        // Onset 23:00: since-onset bin 43 is 06:10–06:20. Blended with 0.6 before the wake it reads 0.775 there, 0.35
        // elsewhere, so only movement between 06:10 and 06:20 needs the higher threshold.
        val sinceOnset = List(72) { if (it == 43) 0.95 else 0.1 }
        val prior = DeepPrior(Duration.ofMinutes(10), sinceOnset, List(72) { 0.6 }, nightsUsed = 20)
        val gated = night.copy(prior = prior)

        val inBin = replay(decider, gated, quiet.withActivity(at("06:12"), at("06:13"), 17.0))
        val after = replay(decider, gated, quiet.withActivity(at("06:22"), at("06:23"), 17.0))

        assertThat(inBin.final().second).isEqualTo(Done(SensingOutcome.NO_TRIGGER))
        assertThat(after.final()).isEqualTo(at("06:23") to Fire(at("06:23"), 3.0, FireRule.TWO_OF_THREE))
    }

    @Test
    fun `debt-aware nights lower the threshold by half`() {
        val stream = quiet.withActivity(at("06:10"), at("06:11"), 13.0) // z 2.2

        assertThat(replay(decider, night, stream).final().second).isEqualTo(Done(SensingOutcome.NO_TRIGGER))
        assertThat(replay(decider, night.copy(debtAware = true), stream).final().second).isInstanceOf(Fire::class.java)
    }

    @Test
    fun `a tuned threshold is used as given`() {
        val stream = quiet.withActivity(at("06:10"), at("06:11"), 17.0) // z 3

        assertThat(replay(decider, night.copy(zThreshold = 3.25), stream).final().second)
            .isEqualTo(Done(SensingOutcome.NO_TRIGGER))
        assertThat(replay(decider, night.copy(zThreshold = 3.0), stream).final().second).isInstanceOf(Fire::class.java)
    }

    // ---------------------------------------------------------------- At the wake time

    @Test
    fun `at the wake time it is done and never fires`() {
        val spikeAtTheEnd = quiet.withEpoch("06:29:30", 40.0)

        assertThat(replay(decider, night, spikeAtTheEnd).final()).isEqualTo(at("06:30") to Done(SensingOutcome.NO_TRIGGER))
        assertThat(decider.decide(night, spikeAtTheEnd, at("06:30:10"))).isEqualTo(Done(SensingOutcome.NO_TRIGGER))
    }

    @Test
    fun `a phone that never moved is done as flat`() {
        val stream = quiet.withEpoch("06:15", 9.9)

        assertThat(replay(decider, night, stream).final()).isEqualTo(at("06:30") to Done(SensingOutcome.FLAT))
        // One epoch above the placement level is enough to say it was on the bed.
        assertThat(replay(decider, night, stream.withEpoch("06:16", 11.0)).final().second).isEqualTo(Done(SensingOutcome.NO_TRIGGER))
    }

    @Test
    fun `too few epochs are done as too few`() {
        val gappy = quiet.withActivity(at("05:45"), at("06:30"), null).withEpoch("06:10", 40.0)

        val decisions = replay(decider, night, gappy)

        assertThat(decisions.dropLast(1).map { it.second }.toSet()).containsExactly(Wait)
        assertThat(decisions.final().second).isEqualTo(Done(SensingOutcome.TOO_FEW_EPOCHS))
    }

    // ---------------------------------------------------------------- maxZ and determinism

    @Test
    fun `max z is the highest z after calibration`() {
        val stream = quiet.withEpoch("05:50", 40.0).withEpoch("06:01", 17.0).withEpoch("06:20", 12.0)

        assertThat(decider.maxZ(night, stream)).isWithin(1e-9).of(3.0)
        assertThat(decider.maxZ(night, stream.take(10))).isNull()
    }

    @Test
    fun `the same night always gives the same decisions`() {
        val random = Random(2026)
        val stream = steady(at("05:45"), at("06:30"), null).map {
            it.copy(activity = if (random.nextInt(10) == 0) null else 30.0 * random.nextDouble() * random.nextDouble())
        }

        val first = replay(SurfacingDecider(), night, stream)
        val second = replay(SurfacingDecider(), night, stream)

        assertThat(first).isEqualTo(second)
        assertThat(first.map { decider.decide(night, stream.filter { e -> e.start < it.first }, it.first) })
            .isEqualTo(first.map { it.second })
    }
}
