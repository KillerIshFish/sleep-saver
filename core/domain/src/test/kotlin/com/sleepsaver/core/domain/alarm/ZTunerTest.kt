package com.sleepsaver.core.domain.alarm

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.alarm.AlarmFixtures.landing
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.testing.Fx
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Test

class ZTunerTest {

    private val tuner = ZTuner()
    private val today = LocalDate.of(2026, 10, 20)
    private val now: Instant = Fx.instant(today, "08:00")
    private val offset: ZoneOffset = ZoneOffset.ofHours(2)

    /** Restless rings in watch-scored deep sleep on the [count] mornings before today. */
    private fun deepRings(count: Int, daysBack: Int = 1): List<WakeLanding> =
        (0 until count).map { landing(today.minusDays((daysBack + it).toLong()), RingReason.RESTLESS, LandingStage.DEEP) }

    /** Wake-time rings with [nonDeep] minutes of non-deep window on the [count] mornings before today. */
    private fun lateRings(count: Int, nonDeep: Int? = 10, daysBack: Int = 1): List<WakeLanding> =
        (0 until count).map {
            landing(today.minusDays((daysBack + it).toLong()), RingReason.DEADLINE, windowNonDeepMinutes = nonDeep)
        }

    private fun tuning(z: Double, daysAgo: Long, reason: TuningReason = TuningReason.RAISED_AFTER_DEEP_RINGS) =
        AlarmTuning(z, reason, now - Duration.ofDays(daysAgo), offset)

    @Test
    fun `three restless rings in deep sleep raise the threshold one step`() {
        assertThat(tuner.next(null, deepRings(3), now, offset))
            .isEqualTo(AlarmTuning(2.75, TuningReason.RAISED_AFTER_DEEP_RINGS, now, offset))
        assertThat(tuner.next(null, deepRings(2), now, offset)).isNull()
    }

    @Test
    fun `five wake-time rings with lighter sleep in the window lower it one step`() {
        assertThat(tuner.next(null, lateRings(5), now, offset))
            .isEqualTo(AlarmTuning(2.25, TuningReason.LOWERED_AFTER_LATE_RINGS, now, offset))
        assertThat(tuner.next(null, lateRings(4), now, offset)).isNull()
        assertThat(tuner.next(null, lateRings(5, nonDeep = 9), now, offset)).isNull()
        assertThat(tuner.next(null, lateRings(5, nonDeep = null), now, offset)).isNull()
    }

    @Test
    fun `raising wins when both are due`() {
        // 3 deep + 5 late rings need 8 landings, one more than the default reads.
        val reads8 = SleepConfig.DEFAULT.copy(surfacing = SleepConfig.DEFAULT.surfacing.copy(tuneRecentLandings = 8))
        val landings = deepRings(3) + lateRings(5, daysBack = 4)

        assertThat(ZTuner(reads8).next(null, landings, now, offset)?.reason).isEqualTo(TuningReason.RAISED_AFTER_DEEP_RINGS)
    }

    @Test
    fun `the threshold stays within its bounds`() {
        assertThat(tuner.next(tuning(3.5, daysAgo = 8), deepRings(3), now, offset)).isNull()
        assertThat(tuner.next(tuning(1.5, daysAgo = 8, TuningReason.LOWERED_AFTER_LATE_RINGS), lateRings(5), now, offset))
            .isNull()
        assertThat(tuner.next(tuning(3.4, daysAgo = 8), deepRings(3), now, offset)?.zThreshold).isEqualTo(3.5)
        assertThat(tuner.next(tuning(3.25, daysAgo = 8), deepRings(3), now, offset)?.zThreshold).isEqualTo(3.5)
    }

    @Test
    fun `no step within 7 days of the last one`() {
        // The landings rang after the change, so only the cooldown holds the step back.
        assertThat(tuner.next(tuning(2.75, daysAgo = 6), deepRings(3), now, offset)).isNull()
        val due = tuner.next(tuning(2.75, daysAgo = 7), deepRings(3), now, offset)

        assertThat(due).isEqualTo(AlarmTuning(3.0, TuningReason.RAISED_AFTER_DEEP_RINGS, now, offset))
    }

    @Test
    fun `landings from before the last change are ignored`() {
        val older = deepRings(3, daysBack = 9)

        assertThat(tuner.next(tuning(2.75, daysAgo = 8), older, now, offset)).isNull()
        assertThat(tuner.next(tuning(2.75, daysAgo = 8), older + deepRings(3), now, offset)?.zThreshold).isEqualTo(3.0)
    }

    @Test
    fun `only the 7 most recent landings count`() {
        val landings = lateRings(7) + deepRings(3, daysBack = 8)

        assertThat(tuner.next(null, landings, now, offset)?.reason).isEqualTo(TuningReason.LOWERED_AFTER_LATE_RINGS)
    }

    @Test
    fun `fixed alarms and nights without stages don't count`() {
        val fixed = (1..3).map {
            landing(today.minusDays(it.toLong()), RingReason.RESTLESS, LandingStage.DEEP, mode = AlarmMode.FIXED)
        }
        val unstaged = (4..6).map {
            landing(today.minusDays(it.toLong()), RingReason.RESTLESS, LandingStage.DEEP, hasStages = false)
        }

        assertThat(tuner.next(null, fixed + unstaged, now, offset)).isNull()
    }

    @Test
    fun `alarms that never rang don't count, nor take a place among the 7 most recent`() {
        val neverRang = (1..3).map { landing(today.minusDays(it.toLong()), RingReason.NEVER_RANG) }
        val landings = neverRang + lateRings(5, daysBack = 4)

        assertThat(tuner.next(null, landings, now, offset)?.reason).isEqualTo(TuningReason.LOWERED_AFTER_LATE_RINGS)
    }

    @Test
    fun `restless rings outside deep sleep never raise it`() {
        val light = (1..7).map { landing(today.minusDays(it.toLong()), RingReason.RESTLESS, LandingStage.LIGHT) }

        assertThat(tuner.next(null, light, now, offset)).isNull()
    }

    @Test
    fun `after a reset the default threshold is back and tuning starts from it`() {
        assertThat(tuner.effectiveZ(null)).isEqualTo(2.5)
        assertThat(tuner.effectiveZ(tuning(3.0, daysAgo = 1))).isEqualTo(3.0)
        assertThat(tuner.next(null, deepRings(3), now, offset)?.zThreshold).isEqualTo(2.75)
    }

    @Test
    fun `a reset goes back to the default and forgets the rings before it`() {
        val resetAt = now - Duration.ofDays(4)
        val reset = tuner.reset(resetAt, offset)

        assertThat(reset).isEqualTo(AlarmTuning(2.5, TuningReason.RESET, resetAt, offset))
        assertThat(tuner.effectiveZ(reset)).isEqualTo(2.5)
        // The deep rings that caused the raise rang before the reset: they don't bring it straight back.
        assertThat(tuner.next(reset, deepRings(3, daysBack = 5), now, offset)).isNull()
        // Rings after the reset count at once: a reset has no cooldown.
        assertThat(tuner.next(reset, deepRings(3, daysBack = 0), now, offset))
            .isEqualTo(AlarmTuning(2.75, TuningReason.RAISED_AFTER_DEEP_RINGS, now, offset))
    }
}
