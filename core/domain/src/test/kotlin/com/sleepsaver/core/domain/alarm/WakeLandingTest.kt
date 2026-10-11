package com.sleepsaver.core.domain.alarm

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.SleepStageType.AWAKE
import com.sleepsaver.core.domain.model.SleepStageType.DEEP
import com.sleepsaver.core.domain.model.SleepStageType.LIGHT
import com.sleepsaver.core.domain.model.SleepStageType.REM
import com.sleepsaver.core.domain.model.SleepStageType.SLEEPING
import com.sleepsaver.core.domain.schedule.AlarmMode
import com.sleepsaver.core.domain.testing.Fx
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.jupiter.api.Test

class WakeLandingTest {

    private val calculator = WakeLandingCalculator()
    private val date = LocalDate.of(2026, 10, 8)

    /**
     * LIGHT from 23:00, then DEEP 05:50–06:05, LIGHT 06:05–06:12, REM 06:12–06:25, AWAKE 06:25–06:40 (local times on
     * [wakeDate]).
     */
    private fun morning(wakeDate: LocalDate): List<Fx.LocalStage> =
        Fx.hypnogram(Fx.dt(wakeDate.minusDays(1), "23:00"), LIGHT to 410L, DEEP to 15L, LIGHT to 7L, REM to 13L, AWAKE to 15L)

    /** A 06:30 alarm with a 30-min window that rang at [firstRingAt]. */
    private fun event(
        firstRingAt: String = "06:12",
        reason: RingReason = RingReason.RESTLESS,
        wakeDate: LocalDate = date,
        offset: ZoneOffset = Fx.UTC,
        target: String = "06:30",
        mode: AlarmMode = AlarmMode.SURFACING,
        kind: RingKind = RingKind.PLANNED,
    ): AlarmEvent = Fx.alarmEvent(wakeDate, target, firstRingAt, reason, mode = mode, kind = kind, offset = offset)

    /** The shared expectations for [morning] and [event] with its defaults. */
    private fun assertMorningLanding(landing: WakeLanding?) {
        checkNotNull(landing)
        assertThat(landing.stageAtRing).isEqualTo(LandingStage.REM)
        assertThat(landing.majorityBefore).isEqualTo(LandingStage.LIGHT)
        assertThat(landing.minutesEarly).isEqualTo(18)
        // Window 06:00–06:30: 5 min deep, 7 light, 13 REM, 5 awake.
        assertThat(landing.windowDeepShare).isWithin(1e-12).of(5.0 / 30)
        assertThat(landing.windowNonDeepMinutes).isEqualTo(25)
        assertThat(landing.hasStages).isTrue()
    }

    // ---------------------------------------------------------------- Landing

    @Test
    fun `a ring exactly on a stage boundary takes the later stage`() {
        val night = Fx.stagedNight(date, morning(date))
        val landing = calculator.land(event(), night)

        assertMorningLanding(landing)
        assertThat(landing?.event).isEqualTo(event())
    }

    @Test
    fun `majority ties go awake, then light, then REM, then deep`() {
        fun majority(vararg parts: Pair<SleepStageType, Long>): LandingStage? {
            // LIGHT until 06:15, then the 5 parts before the 06:20 ring, then LIGHT until 07:00.
            val stages = Fx.hypnogram(Fx.dt(date.minusDays(1), "23:00"), LIGHT to 435L, *parts, LIGHT to 40L)
            return calculator.land(event(firstRingAt = "06:20"), Fx.stagedNight(date, stages))?.majorityBefore
        }

        assertThat(majority(LIGHT to 2L, SLEEPING to 1L, DEEP to 2L)).isEqualTo(LandingStage.LIGHT)
        assertThat(majority(DEEP to 2L, SLEEPING to 1L, AWAKE to 2L)).isEqualTo(LandingStage.AWAKE)
        assertThat(majority(DEEP to 2L, SLEEPING to 1L, REM to 2L)).isEqualTo(LandingStage.REM)
        assertThat(majority(REM to 2L, SLEEPING to 1L, LIGHT to 2L)).isEqualTo(LandingStage.LIGHT)
        assertThat(majority(DEEP to 3L, LIGHT to 2L)).isEqualTo(LandingStage.DEEP)
        assertThat(majority(SLEEPING to 5L)).isNull()
    }

    @Test
    fun `stages of extra segments count, gaps after main sleep read as awake`() {
        val main = Fx.hypnogram(Fx.dt(date.minusDays(1), "23:00"), LIGHT to 360L) // until 05:00
        val extra = Fx.hypnogram(Fx.dt(date, "05:40"), LIGHT to 30L, DEEP to 30L) // 05:40–06:40
        val night = Fx.stagedNight(date, main, extra = listOf(extra))

        val landing = checkNotNull(calculator.land(event(firstRingAt = "06:15"), night))
        val beforeExtra = checkNotNull(calculator.land(event(firstRingAt = "05:30"), night))

        assertThat(landing.stageAtRing).isEqualTo(LandingStage.DEEP)
        assertThat(landing.windowDeepShare).isWithin(1e-12).of(20.0 / 30)
        assertThat(landing.windowNonDeepMinutes).isEqualTo(10)
        assertThat(beforeExtra.stageAtRing).isEqualTo(LandingStage.AWAKE)
        assertThat(beforeExtra.majorityBefore).isEqualTo(LandingStage.AWAKE)
    }

    // ---------------------------------------------------------------- Missing data

    @Test
    fun `a night without stages reads unscored inside main sleep and awake outside it`() {
        val night = Fx.night(date, fellAsleep = "23:00", wokeUp = "06:20")

        val inside = checkNotNull(calculator.land(event(firstRingAt = "06:12"), night))
        val after = checkNotNull(calculator.land(event(firstRingAt = "06:25", reason = RingReason.DEADLINE), night))

        assertThat(inside.hasStages).isFalse()
        assertThat(inside.stageAtRing).isEqualTo(LandingStage.UNSCORED)
        assertThat(inside.majorityBefore).isNull()
        // Only 06:20–06:30 is known (awake): a third of the window, below the half needed.
        assertThat(inside.windowDeepShare).isNull()
        assertThat(inside.windowNonDeepMinutes).isNull()
        assertThat(after.stageAtRing).isEqualTo(LandingStage.AWAKE)
        assertThat(after.majorityBefore).isEqualTo(LandingStage.AWAKE)
    }

    @Test
    fun `a night scored only as sleeping has no stages`() {
        val night = Fx.stagedNight(date, Fx.hypnogram(Fx.dt(date.minusDays(1), "23:00"), SLEEPING to 460L))

        val landing = checkNotNull(calculator.land(event(), night))

        assertThat(landing.hasStages).isFalse()
        assertThat(landing.stageAtRing).isEqualTo(LandingStage.UNSCORED)
    }

    @Test
    fun `a partly scored window reports deep time over the whole window`() {
        fun landing(deep: Long): WakeLanding {
            // LIGHT until 06:00, then [deep] min DEEP and the rest of the window SLEEPING (unscored), then LIGHT.
            val stages = Fx.hypnogram(
                Fx.dt(date.minusDays(1), "23:00"), LIGHT to 420L, DEEP to deep, SLEEPING to 30 - deep, LIGHT to 10L,
            )
            val ring = event(firstRingAt = "06:30", reason = RingReason.DEADLINE)
            return checkNotNull(calculator.land(ring, Fx.stagedNight(date, stages)))
        }

        assertThat(landing(deep = 20).windowDeepShare).isWithin(1e-12).of(20.0 / 30)
        assertThat(landing(deep = 20).windowNonDeepMinutes).isEqualTo(0)
        assertThat(landing(deep = 15).windowDeepShare).isWithin(1e-12).of(0.5) // exactly half scored
        assertThat(landing(deep = 14).windowDeepShare).isNull()
    }

    @Test
    fun `only planned rings of present nights land`() {
        val night = Fx.stagedNight(date, morning(date))

        assertThat(calculator.land(event(kind = RingKind.NAP, reason = RingReason.CATNAP_END), night)).isNull()
        assertThat(calculator.land(event(kind = RingKind.TEST, reason = RingReason.TEST), night)).isNull()
        assertThat(calculator.land(event(), null)).isNull()
        assertThat(calculator.land(event(), night.copy(main = null))).isNull()
    }

    // ---------------------------------------------------------------- Time

    @Test
    fun `nights across a clock change land on the right stages`() {
        for (wakeDate in listOf(Fx.FALL_BACK, Fx.SPRING_FORWARD)) {
            val night = Fx.stagedNightInZone(wakeDate, morning(wakeDate), Fx.NEW_YORK)
            val offset = Fx.offsetAt(Fx.NEW_YORK, Fx.dt(wakeDate, "06:30"))

            assertMorningLanding(calculator.land(event(wakeDate = wakeDate, offset = offset), night))
        }
    }

    @Test
    fun `an offset change between the night and the alarm doesn't move the landing`() {
        // Recorded at +02:00; the alarm was set after flying to +01:00, so 05:12 there is 06:12 on the night's clock.
        val night = Fx.stagedNight(date, morning(date), offset = ZoneOffset.ofHours(2))

        val ring = event(firstRingAt = "05:12", target = "05:30", offset = ZoneOffset.ofHours(1))

        val landing = calculator.land(ring, night)

        assertMorningLanding(landing)
    }

    @Test
    fun `a fixed alarm has no window share`() {
        val night = Fx.stagedNight(date, morning(date))

        val ring = event(firstRingAt = "06:30", reason = RingReason.FIXED_TIME, mode = AlarmMode.FIXED)

        val landing = checkNotNull(calculator.land(ring, night))

        assertThat(landing.windowDeepShare).isNull()
        assertThat(landing.windowNonDeepMinutes).isNull()
        assertThat(landing.stageAtRing).isEqualTo(LandingStage.AWAKE)
        assertThat(landing.majorityBefore).isEqualTo(LandingStage.AWAKE)
        assertThat(landing.minutesEarly).isEqualTo(0)
    }

    @Test
    fun `minutes early counts whole clock minutes, like the ring time shown beside it`() {
        // A restless ring fires a few seconds after an epoch ends: 06:11:05 reads "Rang 6:11 · 19 min early".
        val landing = calculator.land(event(firstRingAt = "06:11:05"), Fx.stagedNight(date, morning(date)))

        assertThat(checkNotNull(landing).minutesEarly).isEqualTo(19)
    }
}
