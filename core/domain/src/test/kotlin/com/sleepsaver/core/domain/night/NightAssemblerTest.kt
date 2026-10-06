package com.sleepsaver.core.domain.night

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.AddedAwake
import com.sleepsaver.core.domain.model.AwakeKind
import com.sleepsaver.core.domain.model.AwakePeriod
import com.sleepsaver.core.domain.model.BlockKind
import com.sleepsaver.core.domain.model.ManualNap
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.SleepStageType.AWAKE
import com.sleepsaver.core.domain.model.SleepStageType.AWAKE_IN_BED
import com.sleepsaver.core.domain.model.SleepStageType.DEEP
import com.sleepsaver.core.domain.model.SleepStageType.LIGHT
import com.sleepsaver.core.domain.model.SleepStageType.OUT_OF_BED
import com.sleepsaver.core.domain.model.SleepStageType.REM
import com.sleepsaver.core.domain.model.SleepStageType.SLEEPING
import com.sleepsaver.core.domain.model.SleepStageType.UNKNOWN
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.StageTotals
import com.sleepsaver.core.domain.testing.Fx
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class NightAssemblerTest {

    private val assembler = NightAssembler()

    /** Wake date used by most tests; "last night" started on [prev]. */
    private val day: LocalDate = LocalDate.of(2026, 10, 6)
    private val prev: LocalDate = day.minusDays(1)
    private val next: LocalDate = day.plusDays(1)

    private fun dt(date: LocalDate, time: String): LocalDateTime = Fx.dt(date, time)
    private fun at(date: LocalDate, time: String, offset: ZoneOffset = Fx.UTC): Instant = dt(date, time).toInstant(offset)
    private fun hm(hours: Long, minutes: Long = 0): Duration = Duration.ofHours(hours).plusMinutes(minutes)
    private fun min(minutes: Long): Duration = Fx.minutes(minutes)

    /** A stage on [day] (or on [prev] when it starts at 14:00 or later), local UTC times. */
    private fun st(start: String, end: String, type: SleepStageType): Fx.LocalStage {
        val startDate = if (start >= "14:00") prev else day
        val endDate = if (end >= "14:00" && startDate == prev) prev else day
        return Fx.LocalStage(dt(startDate, start), dt(endDate, end), type)
    }

    /** prev 23:00 → day 07:00 unless told otherwise. */
    private fun mainSession(
        id: String = "main",
        start: LocalDateTime = dt(prev, "23:00"),
        end: LocalDateTime = dt(day, "07:00"),
        stages: List<Fx.LocalStage> = emptyList(),
        origin: String = Fx.GARMIN,
    ): RawSleepSession = Fx.session(id, start, end, origin = origin, stages = stages)

    private fun napSession(id: String, date: LocalDate, start: String, end: String, stages: List<Fx.LocalStage> = emptyList()) =
        Fx.session(id, dt(date, start), dt(date, end), stages = stages)

    private fun assemble(
        vararg sessions: RawSleepSession,
        overrides: List<NightOverride> = emptyList(),
        manualNaps: List<ManualNap> = emptyList(),
        addedAwake: List<AddedAwake> = emptyList(),
        source: SourceSelection = SourceSelection.All,
    ): List<Night> = assembler.assemble(sessions.toList(), source, overrides, manualNaps, addedAwake)

    private fun override(
        wakeDate: LocalDate = day,
        fellAsleep: Instant? = null,
        wokeUp: Instant? = null,
        awakeMinutes: Int? = null,
        napMinutes: Int? = null,
        isManualNight: Boolean = false,
        offset: ZoneOffset = Fx.UTC,
    ) = NightOverride(
        wakeDate = wakeDate,
        fellAsleep = fellAsleep,
        wokeUp = wokeUp,
        awakeMinutes = awakeMinutes,
        napMinutes = napMinutes,
        isManualNight = isManualNight,
        offset = offset,
        updatedAt = at(wakeDate, "09:00"),
    )

    private fun localTime(instant: Instant?, offset: ZoneOffset?): LocalDateTime = LocalDateTime.ofInstant(instant, offset)

    @Test
    fun `zero sessions give no nights`() {
        assertThat(assemble()).isEmpty()
        assertThat(assemble(source = SourceSelection.Single(Fx.GARMIN))).isEmpty()
    }

    @Test
    fun `sessions shorter than five minutes are ignored`() {
        val tooShort = Fx.session("short", dt(day, "03:00"), dt(day, "03:04"))
        val longEnough = Fx.session("ok", dt(next, "03:00"), dt(next, "03:05"))

        val nights = assemble(tooShort, longEnough)

        assertThat(nights.map { it.wakeDate }).containsExactly(next)
        assertThat(nights.single().mainSleep).isEqualTo(min(5))
    }

    @Test
    fun `nights come back sorted by wake date`() {
        val later = mainSession("later", dt(day, "23:00"), dt(next, "07:00"))
        val earlier = mainSession("earlier")

        assertThat(assemble(later, earlier).map { it.wakeDate }).containsExactly(day, next).inOrder()
    }

    @Nested
    inner class WakeDateAssignment {

        @Test
        fun `23_00 to 07_00 wakes on the end date`() {
            val session = mainSession()

            assertThat(assembler.wakeDateOf(session)).isEqualTo(day)
            val night = assemble(session).single()
            assertThat(night.wakeDate).isEqualTo(day)
            assertThat(night.nightOf).isEqualTo(prev)
            assertThat(night.isPresent).isTrue()
            assertThat(night.mainSleep).isEqualTo(hm(8))
            assertThat(night.totalSleep).isEqualTo(hm(8))
            assertThat(night.awakeInMain).isEqualTo(Duration.ZERO)
            assertThat(night.fellAsleep).isEqualTo(at(prev, "23:00"))
            assertThat(night.wokeUp).isEqualTo(at(day, "07:00"))
            assertThat(night.origin).isEqualTo(Fx.GARMIN)
            assertThat(night.main!!.kind).isEqualTo(BlockKind.MAIN)
            assertThat(night.main.sessionIds).containsExactly("main")
            assertThat(night.override).isNull()
            assertThat(night.isEdited).isFalse()
        }

        @Test
        fun `sleep starting after midnight wakes on the same date`() {
            val session = mainSession(start = dt(day, "01:30"), end = dt(day, "09:00"))

            assertThat(assembler.wakeDateOf(session)).isEqualTo(day)
            assertThat(assemble(session).single().mainSleep).isEqualTo(hm(7, 30))
        }

        @Test
        fun `long sleep ending after the cutoff wakes on the end date`() {
            val session = mainSession(start = dt(prev, "23:00"), end = dt(day, "15:00"))

            assertThat(assembler.wakeDateOf(session)).isEqualTo(day)
            assertThat(assemble(session).single().wakeDate).isEqualTo(day)
        }

        @Test
        fun `evening-only sleep belongs to the next wake date`() {
            val session = mainSession(start = dt(prev, "20:00"), end = dt(prev, "23:30"))

            assertThat(assembler.wakeDateOf(session)).isEqualTo(day)
            val night = assemble(session).single()
            assertThat(night.wakeDate).isEqualTo(day)
            assertThat(night.mainSleep).isEqualTo(hm(3, 30))
        }

        @Test
        fun `night owl 03_00 to 11_00 wakes on the same date`() {
            val session = mainSession(start = dt(day, "03:00"), end = dt(day, "11:00"))

            assertThat(assembler.wakeDateOf(session)).isEqualTo(day)
            assertThat(assemble(session).single().wakeDate).isEqualTo(day)
        }

        @Test
        fun `the cutoff is exclusive`() {
            assertThat(assembler.wakeDateOf(mainSession(end = dt(day, "13:59")))).isEqualTo(day)
            assertThat(assembler.wakeDateOf(mainSession(end = dt(day, "14:00")))).isEqualTo(day)
            assertThat(assembler.wakeDateOf(mainSession(start = dt(day, "14:00"), end = dt(day, "22:00")))).isEqualTo(next)
            assertThat(assembler.wakeDateOf(mainSession(start = dt(day, "13:59"), end = dt(day, "22:00")))).isEqualTo(day)
        }

        @Test
        fun `wake date uses the recorded offsets, not UTC`() {
            // 10:00–13:30 in UTC-4 is 14:00–17:30 UTC, which would read as an evening sleep belonging to `next`.
            val session = Fx.session("s", dt(day, "10:00"), dt(day, "13:30"), startOffset = ZoneOffset.ofHours(-4))

            assertThat(assembler.isNap(session)).isFalse()
            assertThat(assembler.wakeDateOf(session)).isEqualTo(day)
        }

        @Test
        fun `evening doze becomes an extra segment of tonight`() {
            val doze = mainSession("doze", dt(prev, "20:00"), dt(prev, "20:40"))
            val night = assemble(doze, mainSession()).single()

            assertThat(night.wakeDate).isEqualTo(day)
            assertThat(night.main!!.sessionIds).containsExactly("main")
            assertThat(night.extraSegments.single().kind).isEqualTo(BlockKind.EXTRA)
            assertThat(night.extraSegments.single().sessionIds).containsExactly("doze")
            assertThat(night.extraSleep).isEqualTo(min(40))
            assertThat(night.mainSleep).isEqualTo(hm(8))
            assertThat(night.totalSleep).isEqualTo(hm(8, 40))
            assertThat(night.naps).isEmpty()
        }
    }

    @Nested
    inner class NapDetection {

        @Test
        fun `40 minutes after lunch is a nap`() {
            assertThat(assembler.isNap(napSession("n", day, "13:00", "13:40"))).isTrue()
        }

        @Test
        fun `starting before the nap window is not a nap`() {
            assertThat(assembler.isNap(napSession("n", day, "08:30", "09:30"))).isFalse()
        }

        @Test
        fun `starting at the end of the nap window is not a nap`() {
            assertThat(assembler.isNap(napSession("n", day, "20:00", "20:40"))).isFalse()
            assertThat(assembler.isNap(napSession("n", day, "19:59", "20:30"))).isTrue()
        }

        @Test
        fun `exactly three hours is not a nap`() {
            assertThat(assembler.isNap(napSession("n", day, "12:00", "15:00"))).isFalse()
        }

        @Test
        fun `two hours 59 minutes is a nap`() {
            assertThat(assembler.isNap(napSession("n", day, "12:00", "14:59"))).isTrue()
        }

        @Test
        fun `nap window uses the session's own offset`() {
            val pacific = ZoneOffset.ofHours(-7)
            val nap = Fx.session("n", dt(day, "13:00"), dt(day, "13:40"), startOffset = pacific)

            assertThat(LocalDateTime.ofInstant(nap.start, Fx.UTC).toLocalTime().toString()).isEqualTo("20:00")
            assertThat(assembler.isNap(nap)).isTrue()
        }

        @Test
        fun `a morning session that is not a nap is night sleep of that day`() {
            val morning = napSession("morning", day, "08:30", "09:30")
            val night = assemble(mainSession(), morning).single()

            assertThat(night.naps).isEmpty()
            assertThat(night.extraSegments.single().sessionIds).containsExactly("morning")
            assertThat(night.totalSleep).isEqualTo(hm(9))
        }
    }

    @Nested
    inner class Naps {

        @Test
        fun `naps count toward that day's total`() {
            val night = assemble(mainSession(), napSession("nap", day, "13:00", "13:40")).single()

            assertThat(night.wakeDate).isEqualTo(day)
            assertThat(night.naps.single().kind).isEqualTo(BlockKind.NAP)
            assertThat(night.naps.single().sessionIds).containsExactly("nap")
            assertThat(night.naps.single().isManual).isFalse()
            assertThat(night.mainSleep).isEqualTo(hm(8))
            assertThat(night.napSleep).isEqualTo(min(40))
            assertThat(night.totalSleep).isEqualTo(hm(8, 40))
            assertThat(night.extraSegments).isEmpty()
            assertThat(night.interruptions).isEmpty()
        }

        @Test
        fun `a nap attaches to its local start date even before that night arrives`() {
            val nights = assemble(mainSession(), napSession("nap", next, "13:00", "13:40"))

            assertThat(nights.map { it.wakeDate }).containsExactly(day, next).inOrder()
            assertThat(nights[0].napSleep).isEqualTo(Duration.ZERO)
            val napDay = nights[1]
            assertThat(napDay.main).isNull()
            assertThat(napDay.isPresent).isFalse()
            assertThat(napDay.fellAsleep).isNull()
            assertThat(napDay.wokeUp).isNull()
            assertThat(napDay.mainSleep).isEqualTo(Duration.ZERO)
            assertThat(napDay.napSleep).isEqualTo(min(40))
            assertThat(napDay.totalSleep).isEqualTo(min(40))
            assertThat(napDay.stageTotals).isNull()
            assertThat(napDay.origin).isNull()
        }

        @Test
        fun `awake time inside a nap is not counted`() {
            val nap = napSession("nap", day, "13:00", "14:00", stages = listOf(st("13:20", "13:30", AWAKE)))

            val night = assemble(nap).single()

            assertThat(night.napSleep).isEqualTo(min(50))
            assertThat(night.naps.single().asleep).isEqualTo(min(50))
            assertThat(night.naps.single().awakePeriods)
                .containsExactly(AwakePeriod(at(day, "13:20"), at(day, "13:30"), AwakeKind.STAGE))
        }

        @Test
        fun `several naps on one day add up and are sorted`() {
            val late = napSession("late", day, "17:00", "17:20")
            val early = napSession("early", day, "10:00", "10:30")

            val night = assemble(late, early).single()

            assertThat(night.naps.map { it.sessionIds.single() }).containsExactly("early", "late").inOrder()
            assertThat(night.napSleep).isEqualTo(min(50))
        }

        @Test
        fun `a nap inside a late main sleep is not counted twice`() {
            val owl = mainSession("owl", dt(day, "03:00"), dt(day, "11:00"))
            val inside = napSession("inside", day, "10:00", "10:40")
            val manual = ManualNap(1, at(day, "10:30"), at(day, "11:30"), Fx.UTC)

            val night = assemble(owl, inside, manualNaps = listOf(manual)).single()

            assertThat(night.naps).hasSize(2)
            assertThat(night.mainSleep).isEqualTo(hm(8))
            assertThat(night.napSleep).isEqualTo(min(30))
            assertThat(night.totalSleep).isEqualTo(hm(8, 30))
        }

        @Test
        fun `nap time inside an awake stretch of the main sleep still counts`() {
            val owl = mainSession("owl", dt(day, "03:00"), dt(day, "11:00"), stages = listOf(st("09:30", "10:30", AWAKE)))
            val nap = napSession("nap", day, "09:45", "10:15")

            val night = assemble(owl, nap).single()

            assertThat(night.mainSleep).isEqualTo(hm(7))
            assertThat(night.napSleep).isEqualTo(min(30))
        }

        @Test
        fun `moving woke-up over a nap does not count the nap twice`() {
            val owl = mainSession("owl", dt(day, "03:00"), dt(day, "09:30"))
            val nap = napSession("nap", day, "10:00", "11:00")

            val night = assemble(owl, nap, overrides = listOf(override(wokeUp = at(day, "11:00")))).single()

            assertThat(night.mainSleep).isEqualTo(hm(8))
            assertThat(night.napSleep).isEqualTo(Duration.ZERO)
            assertThat(night.totalSleep).isEqualTo(hm(8))
        }
    }

    @Nested
    inner class Fragments {

        @Test
        fun `fragments 45 minutes apart merge with the gap as an interruption`() {
            val a = mainSession("a", dt(prev, "23:00"), dt(day, "03:00"))
            val b = mainSession("b", dt(day, "03:45"), dt(day, "07:00"))

            val night = assemble(b, a).single()

            assertThat(night.main!!.sessionIds).containsExactly("a", "b").inOrder()
            assertThat(night.fellAsleep).isEqualTo(at(prev, "23:00"))
            assertThat(night.wokeUp).isEqualTo(at(day, "07:00"))
            assertThat(night.mainSleep).isEqualTo(hm(7, 15))
            assertThat(night.awakeInMain).isEqualTo(min(45))
            assertThat(night.totalSleep).isEqualTo(hm(7, 15))
            assertThat(night.extraSegments).isEmpty()
            assertThat(night.interruptions)
                .containsExactly(AwakePeriod(at(day, "03:00"), at(day, "03:45"), AwakeKind.GAP))
        }

        @Test
        fun `fragments 75 minutes apart stay separate and both count`() {
            val a = mainSession("a", dt(prev, "23:00"), dt(day, "01:00"))
            val b = mainSession("b", dt(day, "02:15"), dt(day, "07:00"))

            val night = assemble(a, b).single()

            assertThat(night.main!!.sessionIds).containsExactly("b")
            assertThat(night.fellAsleep).isEqualTo(at(day, "02:15"))
            assertThat(night.mainSleep).isEqualTo(hm(4, 45))
            val extra = night.extraSegments.single()
            assertThat(extra.kind).isEqualTo(BlockKind.EXTRA)
            assertThat(extra.sessionIds).containsExactly("a")
            assertThat(extra.asleep).isEqualTo(hm(2))
            assertThat(night.extraSleep).isEqualTo(hm(2))
            assertThat(night.totalSleep).isEqualTo(hm(6, 45))
            assertThat(night.interruptions).isEmpty()
        }

        @Test
        fun `a gap of exactly the merge limit does not merge`() {
            val a = mainSession("a", dt(prev, "23:00"), dt(day, "03:00"))
            val b = mainSession("b", dt(day, "04:00"), dt(day, "07:00"))

            val night = assemble(a, b).single()

            assertThat(night.main!!.sessionIds).containsExactly("a")
            assertThat(night.extraSegments.single().sessionIds).containsExactly("b")
        }

        @Test
        fun `overlapping fragments from one app are not double counted`() {
            val a = mainSession("a", dt(prev, "23:00"), dt(day, "03:00"))
            val b = mainSession("b", dt(day, "02:30"), dt(day, "07:00"))

            val night = assemble(a, b).single()

            assertThat(night.main!!.sessionIds).containsExactly("a", "b").inOrder()
            assertThat(night.mainSleep).isEqualTo(hm(8))
            assertThat(night.awakeInMain).isEqualTo(Duration.ZERO)
            assertThat(night.interruptions).isEmpty()
        }

        @Test
        fun `a fragment inside another one does not end the cluster early`() {
            val outer = mainSession("outer", dt(prev, "23:00"), dt(day, "05:00"))
            val inner = mainSession("inner", dt(prev, "23:30"), dt(day, "01:00"))
            val tail = mainSession("tail", dt(day, "05:30"), dt(day, "07:00"))

            val night = assemble(outer, inner, tail).single()

            assertThat(night.main!!.sessionIds).containsExactly("outer", "inner", "tail").inOrder()
            assertThat(night.interruptions)
                .containsExactly(AwakePeriod(at(day, "05:00"), at(day, "05:30"), AwakeKind.GAP))
            assertThat(night.mainSleep).isEqualTo(hm(7, 30))
        }

        @Test
        fun `equal clusters pick the later one as main`() {
            val early = mainSession("early", dt(prev, "21:00"), dt(prev, "23:00"))
            val late = mainSession("late", dt(day, "05:00"), dt(day, "07:00"))

            val night = assemble(early, late).single()

            assertThat(night.main!!.sessionIds).containsExactly("late")
            assertThat(night.extraSegments.single().sessionIds).containsExactly("early")
        }

        @Test
        fun `main is the cluster with the most sleep, not the longest in bed`() {
            val restless = mainSession(
                "restless", dt(prev, "21:00"), dt(day, "01:00"),
                stages = listOf(st("21:00", "23:30", AWAKE)),
            )
            val solid = mainSession("solid", dt(day, "03:00"), dt(day, "06:00"))

            val night = assemble(restless, solid).single()

            assertThat(night.main!!.sessionIds).containsExactly("solid")
            assertThat(night.extraSegments.single().asleep).isEqualTo(hm(1, 30))
            assertThat(night.totalSleep).isEqualTo(hm(4, 30))
        }

        @Test
        fun `block offsets come from the first start and the last end`() {
            val edt = ZoneOffset.ofHours(-4)
            val est = ZoneOffset.ofHours(-5)
            val a = Fx.session("a", dt(prev, "23:00"), dt(day, "03:00"), startOffset = edt)
            val b = Fx.session("b", dt(day, "02:30"), dt(day, "06:00"), startOffset = est)

            val main = assemble(a, b).single().main!!

            assertThat(main.startOffset).isEqualTo(edt)
            assertThat(main.endOffset).isEqualTo(est)
            assertThat(main.localStart).isEqualTo(dt(prev, "23:00"))
            assertThat(main.localEnd).isEqualTo(dt(day, "06:00"))
            assertThat(main.awakePeriods.single().duration).isEqualTo(min(30))
        }
    }

    @Nested
    inner class Stages {

        private val staged = mainSession(
            stages = listOf(
                st("23:00", "00:30", LIGHT),
                st("00:30", "02:00", DEEP),
                st("02:00", "02:20", AWAKE),
                st("02:20", "03:30", REM),
                st("03:30", "06:00", LIGHT),
                // 06:00–06:30 has no stage
                st("06:30", "06:50", SLEEPING),
                st("06:50", "07:00", AWAKE_IN_BED),
            ),
        )

        @Test
        fun `awake stages are subtracted and stage totals add up`() {
            val night = assemble(staged).single()

            assertThat(night.mainSleep).isEqualTo(hm(7, 30))
            assertThat(night.awakeInMain).isEqualTo(min(30))
            assertThat(night.stageTotals).isEqualTo(
                StageTotals(deep = hm(1, 30), light = hm(4), rem = hm(1, 10), awake = min(30), unclassified = min(50)),
            )
            assertThat(night.stageTotals!!.hasStageData).isTrue()
            assertThat(night.interruptions).containsExactly(
                AwakePeriod(at(day, "02:00"), at(day, "02:20"), AwakeKind.STAGE),
                AwakePeriod(at(day, "06:50"), at(day, "07:00"), AwakeKind.STAGE),
            ).inOrder()
            assertThat(night.main!!.stages).hasSize(7)
            assertThat(night.efficiency).isWithin(1e-9).of(7.5 / 8)
        }

        @Test
        fun `overlapping awake stages are counted once`() {
            val session = mainSession(stages = listOf(st("02:00", "02:20", AWAKE), st("02:10", "02:30", OUT_OF_BED)))

            val night = assemble(session).single()

            assertThat(night.awakeInMain).isEqualTo(min(30))
            assertThat(night.mainSleep).isEqualTo(hm(7, 30))
            assertThat(night.interruptions)
                .containsExactly(AwakePeriod(at(day, "02:00"), at(day, "02:30"), AwakeKind.STAGE))
            assertThat(night.stageTotals!!.awake).isEqualTo(min(30))
        }

        @Test
        fun `stages reaching outside the session are clipped`() {
            val session = mainSession(stages = listOf(st("06:50", "07:30", AWAKE)))

            val night = assemble(session).single()

            assertThat(night.awakeInMain).isEqualTo(min(10))
            assertThat(night.mainSleep).isEqualTo(hm(7, 50))
            assertThat(night.main!!.stages.single().end).isEqualTo(at(day, "07:00"))
        }

        @Test
        fun `unknown and sleeping stages count as asleep`() {
            val session = mainSession(stages = listOf(st("01:00", "02:00", UNKNOWN), st("02:00", "03:00", SLEEPING)))

            val night = assemble(session).single()

            assertThat(night.mainSleep).isEqualTo(hm(8))
            assertThat(night.stageTotals!!.unclassified).isEqualTo(hm(8))
            assertThat(night.stageTotals.hasStageData).isFalse()
        }

        @Test
        fun `sleep without stages is all asleep and has no stage totals`() {
            val night = assemble(mainSession()).single()

            assertThat(night.mainSleep).isEqualTo(hm(8))
            assertThat(night.stageTotals).isNull()
        }

        @Test
        fun `stages of merged fragments are combined`() {
            val a = mainSession("a", dt(prev, "23:00"), dt(day, "03:00"), stages = listOf(st("23:00", "03:00", DEEP)))
            val b = mainSession("b", dt(day, "03:30"), dt(day, "07:00"), stages = listOf(st("03:30", "07:00", REM)))

            val night = assemble(b, a).single()

            assertThat(night.main!!.stages.map { it.type }).containsExactly(DEEP, REM).inOrder()
            assertThat(night.stageTotals).isEqualTo(
                StageTotals(deep = hm(4), light = Duration.ZERO, rem = hm(3, 30), awake = Duration.ZERO, unclassified = Duration.ZERO),
            )
            assertThat(night.mainSleep).isEqualTo(hm(7, 30))
        }

        @Test
        fun `short awake stage is excluded from sleep but is not an interruption`() {
            val session = mainSession(stages = listOf(st("03:00", "03:04", AWAKE)))

            val night = assemble(session).single()

            assertThat(night.mainSleep).isEqualTo(hm(7, 56))
            assertThat(night.awakeInMain).isEqualTo(min(4))
            assertThat(night.interruptions).isEmpty()
            assertThat(night.main!!.awakePeriods)
                .containsExactly(AwakePeriod(at(day, "03:00"), at(day, "03:04"), AwakeKind.STAGE))
        }

        @Test
        fun `awake stage of exactly the threshold is an interruption`() {
            val session = mainSession(stages = listOf(st("03:00", "03:05", AWAKE)))

            assertThat(assemble(session).single().interruptions).hasSize(1)
        }
    }

    @Nested
    inner class Sources {

        @Test
        fun `single source ignores other apps`() {
            val other = mainSession("other", dt(day, "23:00"), dt(next, "07:00"), origin = Fx.OTHER)
            val otherNap = napSession("other-nap", day, "13:00", "13:40").copy(origin = Fx.OTHER)

            val nights = assemble(mainSession(), other, otherNap, source = SourceSelection.Single(Fx.GARMIN))

            assertThat(nights.map { it.wakeDate }).containsExactly(day)
            assertThat(nights.single().napSleep).isEqualTo(Duration.ZERO)
        }

        @Test
        fun `all sources keep Garmin and do not double count the duplicate`() {
            val other = mainSession(
                "other", dt(prev, "23:30"), dt(day, "07:30"), origin = Fx.OTHER,
                stages = listOf(st("23:30", "07:30", LIGHT)),
            )

            val night = assemble(other, mainSession(), source = SourceSelection.All).single()

            assertThat(night.origin).isEqualTo(Fx.GARMIN)
            assertThat(night.main!!.sessionIds).containsExactly("main")
            assertThat(night.mainSleep).isEqualTo(hm(8))
        }

        @Test
        fun `block origin is the origin of its longest session`() {
            val longOther = mainSession("other", dt(prev, "23:00"), dt(day, "05:00"), origin = Fx.OTHER)
            val shortGarmin = mainSession("garmin", dt(day, "04:00"), dt(day, "07:00"))

            val night = assemble(longOther, shortGarmin).single()

            assertThat(night.main!!.sessionIds).containsExactly("other", "garmin").inOrder()
            assertThat(night.origin).isEqualTo(Fx.OTHER)
            assertThat(night.mainSleep).isEqualTo(hm(8))
        }

        @Test
        fun `a slightly overlapping session from another app extends the night without double counting`() {
            val other = mainSession("other", dt(day, "06:30"), dt(day, "08:30"), origin = Fx.OTHER)

            val night = assemble(mainSession(), other).single()

            assertThat(night.main!!.sessionIds).containsExactly("main", "other").inOrder()
            assertThat(night.origin).isEqualTo(Fx.GARMIN)
            assertThat(night.mainSleep).isEqualTo(hm(9, 30))
            assertThat(night.interruptions).isEmpty()
        }
    }

    @Nested
    inner class Overrides {

        /** prev 23:00 → day 07:00 with 35 min awake: 23:10–23:30 and 06:45–07:00. Raw main sleep 7 h 25 m. */
        private val raw = mainSession(
            "raw",
            stages = listOf(
                st("23:00", "23:10", LIGHT),
                st("23:10", "23:30", AWAKE),
                st("23:30", "02:00", LIGHT),
                st("02:00", "04:00", DEEP),
                st("04:00", "06:00", REM),
                st("06:00", "06:45", LIGHT),
                st("06:45", "07:00", AWAKE),
            ),
        )

        @Test
        fun `no override leaves the raw night`() {
            val night = assemble(raw).single()

            assertThat(night.override).isNull()
            assertThat(night.isEdited).isFalse()
            assertThat(night.fellAsleep).isEqualTo(at(prev, "23:00"))
            assertThat(night.wokeUp).isEqualTo(at(day, "07:00"))
            assertThat(night.mainSleep).isEqualTo(hm(7, 25))
            assertThat(night.awakeInMain).isEqualTo(min(35))
            assertThat(night.interruptions).hasSize(2)
        }

        @Test
        fun `later fell-asleep trims the window and the awake time before it`() {
            val plusOne = ZoneOffset.ofHours(1)
            val edit = override(fellAsleep = at(prev, "23:40"), offset = plusOne)

            val night = assemble(raw, overrides = listOf(edit)).single()

            assertThat(night.override).isEqualTo(edit)
            assertThat(night.isEdited).isTrue()
            assertThat(night.fellAsleep).isEqualTo(at(prev, "23:40"))
            assertThat(night.fellAsleepOffset).isEqualTo(plusOne)
            assertThat(night.wokeUpOffset).isEqualTo(Fx.UTC)
            assertThat(night.mainSleep).isEqualTo(hm(7, 5))
            assertThat(night.awakeInMain).isEqualTo(min(15))
            assertThat(night.interruptions)
                .containsExactly(AwakePeriod(at(day, "06:45"), at(day, "07:00"), AwakeKind.STAGE))
            assertThat(night.main!!.stages.first().start).isEqualTo(at(prev, "23:40"))
            assertThat(night.main.stages.map { it.type }).containsExactly(LIGHT, DEEP, REM, LIGHT, AWAKE).inOrder()
            assertThat(night.stageTotals!!.awake).isEqualTo(min(15))
            assertThat(night.main.localStart).isEqualTo(dt(day, "00:40"))
        }

        @Test
        fun `earlier woke-up trims the end`() {
            val night = assemble(raw, overrides = listOf(override(wokeUp = at(day, "06:30")))).single()

            assertThat(night.wokeUp).isEqualTo(at(day, "06:30"))
            assertThat(night.fellAsleep).isEqualTo(at(prev, "23:00"))
            assertThat(night.mainSleep).isEqualTo(hm(7, 10))
            assertThat(night.awakeInMain).isEqualTo(min(20))
            assertThat(night.main!!.stages.last().end).isEqualTo(at(day, "06:30"))
            assertThat(night.interruptions)
                .containsExactly(AwakePeriod(at(prev, "23:10"), at(prev, "23:30"), AwakeKind.STAGE))
        }

        @Test
        fun `awake minutes replace the measured awake time`() {
            val night = assemble(raw, overrides = listOf(override(awakeMinutes = 50))).single()

            assertThat(night.awakeInMain).isEqualTo(min(50))
            assertThat(night.mainSleep).isEqualTo(hm(7, 10))
            assertThat(night.main!!.asleep).isEqualTo(hm(7, 10))
            assertThat(night.totalSleep).isEqualTo(hm(7, 10))
            assertThat(night.interruptions).hasSize(2)
        }

        @Test
        fun `nap minutes replace the measured naps`() {
            val nap = napSession("nap", day, "13:00", "13:40")

            val night = assemble(raw, nap, overrides = listOf(override(napMinutes = 90))).single()

            assertThat(night.naps).hasSize(1)
            assertThat(night.napSleep).isEqualTo(hm(1, 30))
            assertThat(night.mainSleep).isEqualTo(hm(7, 25))
            assertThat(night.totalSleep).isEqualTo(hm(8, 55))
        }

        @Test
        fun `nap minutes on a day without a night still create that day`() {
            val nights = assemble(raw, overrides = listOf(override(wakeDate = next, napMinutes = 20)))

            assertThat(nights.map { it.wakeDate }).containsExactly(day, next).inOrder()
            val napOnly = nights[1]
            assertThat(napOnly.main).isNull()
            assertThat(napOnly.isPresent).isFalse()
            assertThat(napOnly.isEdited).isTrue()
            assertThat(napOnly.napSleep).isEqualTo(min(20))
            assertThat(napOnly.totalSleep).isEqualTo(min(20))
        }

        @Test
        fun `manual night creates a main block`() {
            val edt = ZoneOffset.ofHours(-4)
            val edit = override(
                fellAsleep = at(prev, "23:30", edt),
                wokeUp = at(day, "06:45", edt),
                isManualNight = true,
                offset = edt,
            )

            val night = assemble(overrides = listOf(edit)).single()

            val main = night.main!!
            assertThat(main.kind).isEqualTo(BlockKind.MAIN)
            assertThat(main.isManual).isTrue()
            assertThat(main.startOffset).isEqualTo(edt)
            assertThat(main.endOffset).isEqualTo(edt)
            assertThat(main.localStart).isEqualTo(dt(prev, "23:30"))
            assertThat(main.localEnd).isEqualTo(dt(day, "06:45"))
            assertThat(main.stages).isEmpty()
            assertThat(night.isPresent).isTrue()
            assertThat(night.isManual).isTrue()
            assertThat(night.mainSleep).isEqualTo(hm(7, 15))
            assertThat(night.totalSleep).isEqualTo(hm(7, 15))
            assertThat(night.fellAsleepOffset).isEqualTo(edt)
            assertThat(night.stageTotals).isNull()
            assertThat(night.origin).isNull()
        }

        @Test
        fun `manual night honours awake minutes and added awake time`() {
            val edit = override(fellAsleep = at(prev, "23:30"), wokeUp = at(day, "06:30"), isManualNight = true)
            val awake = AddedAwake(1, day, at(day, "03:00"), at(day, "03:20"))

            val withAdded = assemble(overrides = listOf(edit), addedAwake = listOf(awake)).single()
            assertThat(withAdded.mainSleep).isEqualTo(hm(6, 40))
            assertThat(withAdded.interruptions)
                .containsExactly(AwakePeriod(at(day, "03:00"), at(day, "03:20"), AwakeKind.ADDED))

            val withMinutes = assemble(overrides = listOf(edit.copy(awakeMinutes = 45)), addedAwake = listOf(awake)).single()
            assertThat(withMinutes.mainSleep).isEqualTo(hm(6, 15))
            assertThat(withMinutes.awakeInMain).isEqualTo(min(45))
        }

        @Test
        fun `manual night uses raw data once it arrives`() {
            val edit = override(fellAsleep = at(prev, "23:30"), wokeUp = at(day, "06:30"), isManualNight = true)

            val night = assemble(raw, overrides = listOf(edit)).single()

            assertThat(night.main!!.isManual).isFalse()
            assertThat(night.main.sessionIds).containsExactly("raw")
            assertThat(night.fellAsleep).isEqualTo(at(prev, "23:30"))
            assertThat(night.wokeUp).isEqualTo(at(day, "06:30"))
            assertThat(night.mainSleep).isEqualTo(hm(7))
            assertThat(night.stageTotals).isNotNull()
        }

        @Test
        fun `manual nap is a nap on its local start date`() {
            val plusTwo = ZoneOffset.ofHours(2)
            val nap = ManualNap(7, at(day, "14:00", plusTwo), at(day, "14:30", plusTwo), plusTwo)

            val night = assemble(raw, manualNaps = listOf(nap)).single()

            val block = night.naps.single()
            assertThat(block.kind).isEqualTo(BlockKind.NAP)
            assertThat(block.isManual).isTrue()
            assertThat(block.sessionIds).containsExactly("manual-nap:7")
            assertThat(block.startOffset).isEqualTo(plusTwo)
            assertThat(block.localStart).isEqualTo(dt(day, "14:00"))
            assertThat(night.napSleep).isEqualTo(min(30))
            assertThat(night.totalSleep).isEqualTo(hm(7, 55))
        }

        @Test
        fun `manual nap ignores the nap window and uses its own offset for the date`() {
            val tokyo = ZoneOffset.ofHours(9)
            // 07:30 local on `next` is 22:30 UTC on `day`.
            val nap = ManualNap(3, at(next, "07:30", tokyo), at(next, "08:00", tokyo), tokyo)

            val nights = assemble(raw, manualNaps = listOf(nap))

            assertThat(nights.map { it.wakeDate }).containsExactly(day, next).inOrder()
            assertThat(nights[1].main).isNull()
            assertThat(nights[1].napSleep).isEqualTo(min(30))
        }

        @Test
        fun `overlapping manual and detected naps are not double counted`() {
            val detected = napSession("nap", day, "13:00", "13:40")
            val manual = ManualNap(1, at(day, "13:20"), at(day, "14:00"), Fx.UTC)

            val night = assemble(raw, detected, manualNaps = listOf(manual)).single()

            assertThat(night.naps).hasSize(2)
            assertThat(night.napSleep).isEqualTo(hm(1))
        }

        @Test
        fun `added awake time is subtracted and listed even without an override row`() {
            val awake = AddedAwake(1, day, at(day, "03:00"), at(day, "03:30"))

            val night = assemble(mainSession(), addedAwake = listOf(awake)).single()

            assertThat(night.override).isNull()
            assertThat(night.mainSleep).isEqualTo(hm(7, 30))
            assertThat(night.awakeInMain).isEqualTo(min(30))
            assertThat(night.interruptions)
                .containsExactly(AwakePeriod(at(day, "03:00"), at(day, "03:30"), AwakeKind.ADDED))
        }

        @Test
        fun `added awake time overlapping an awake stage is counted once`() {
            val awake = AddedAwake(1, day, at(day, "06:30"), at(day, "07:00"))

            val night = assemble(raw, addedAwake = listOf(awake)).single()

            assertThat(night.awakeInMain).isEqualTo(min(50))
            assertThat(night.mainSleep).isEqualTo(hm(7, 10))
            assertThat(night.interruptions.map { it.kind })
                .containsExactly(AwakeKind.STAGE, AwakeKind.ADDED, AwakeKind.STAGE).inOrder()
        }

        @Test
        fun `added awake time outside the edited window is ignored`() {
            val awake = AddedAwake(1, day, at(day, "06:15"), at(day, "06:45"))
            val edit = override(wokeUp = at(day, "06:00"))

            val night = assemble(raw, overrides = listOf(edit), addedAwake = listOf(awake)).single()

            assertThat(night.interruptions.map { it.kind }).containsExactly(AwakeKind.STAGE)
            assertThat(night.mainSleep).isEqualTo(hm(6, 40))
        }

        @Test
        fun `added awake time for another date is ignored`() {
            val awake = AddedAwake(1, next, at(day, "03:00"), at(day, "03:30"))

            assertThat(assemble(mainSession(), addedAwake = listOf(awake)).single().mainSleep).isEqualTo(hm(8))
        }

        @Test
        fun `extra segment is absorbed when the window grows over it`() {
            val doze = mainSession("doze", dt(prev, "20:00"), dt(prev, "21:00"))
            val main = mainSession()

            val untouched = assemble(doze, main).single()
            assertThat(untouched.extraSegments).hasSize(1)
            assertThat(untouched.totalSleep).isEqualTo(hm(9))

            val night = assemble(doze, main, overrides = listOf(override(fellAsleep = at(prev, "20:00")))).single()

            assertThat(night.extraSegments).isEmpty()
            assertThat(night.extraSleep).isEqualTo(Duration.ZERO)
            assertThat(night.fellAsleep).isEqualTo(at(prev, "20:00"))
            assertThat(night.main!!.sessionIds).containsExactly("doze", "main").inOrder()
            assertThat(night.awakeInMain).isEqualTo(hm(2))
            assertThat(night.mainSleep).isEqualTo(hm(9))
            assertThat(night.totalSleep).isEqualTo(hm(9))
            assertThat(night.interruptions)
                .containsExactly(AwakePeriod(at(prev, "21:00"), at(prev, "23:00"), AwakeKind.GAP))
        }

        @Test
        fun `moving woke-up to the end of a later extra segment keeps the gap awake`() {
            val first = mainSession("first", dt(prev, "23:00"), dt(day, "03:00"))
            val second = mainSession("second", dt(day, "04:15"), dt(day, "07:00"))
            assertThat(assemble(first, second).single().totalSleep).isEqualTo(hm(6, 45))

            val night = assemble(first, second, overrides = listOf(override(wokeUp = at(day, "07:00")))).single()

            assertThat(night.extraSegments).isEmpty()
            assertThat(night.main!!.sessionIds).containsExactly("first", "second").inOrder()
            assertThat(night.wokeUp).isEqualTo(at(day, "07:00"))
            assertThat(night.awakeInMain).isEqualTo(min(75))
            assertThat(night.mainSleep).isEqualTo(hm(6, 45))
            assertThat(night.totalSleep).isEqualTo(hm(6, 45))
            assertThat(night.interruptions)
                .containsExactly(AwakePeriod(at(day, "03:00"), at(day, "04:15"), AwakeKind.GAP))
        }

        @Test
        fun `an absorbed extra segment keeps its awake time and stages`() {
            val doze = mainSession(
                "doze", dt(prev, "20:00"), dt(prev, "21:00"),
                stages = listOf(st("20:00", "20:20", AWAKE), st("20:20", "21:00", DEEP)),
            )
            val main = mainSession(stages = listOf(st("23:00", "07:00", LIGHT)))

            val night = assemble(doze, main, overrides = listOf(override(fellAsleep = at(prev, "20:00")))).single()

            assertThat(night.awakeInMain).isEqualTo(hm(2, 20))
            assertThat(night.mainSleep).isEqualTo(hm(8, 40))
            assertThat(night.stageTotals).isEqualTo(
                StageTotals(deep = min(40), light = hm(8), rem = Duration.ZERO, awake = min(20), unclassified = Duration.ZERO),
            )
            assertThat(night.main!!.stages.map { it.type }).containsExactly(AWAKE, DEEP, LIGHT).inOrder()
            assertThat(night.interruptions).containsExactly(
                AwakePeriod(at(prev, "20:00"), at(prev, "20:20"), AwakeKind.STAGE),
                AwakePeriod(at(prev, "21:00"), at(prev, "23:00"), AwakeKind.GAP),
            ).inOrder()
        }

        @Test
        fun `a partly absorbed extra segment only counts inside the window`() {
            val doze = mainSession("doze", dt(prev, "20:00"), dt(prev, "21:00"))

            val night = assemble(doze, mainSession(), overrides = listOf(override(fellAsleep = at(prev, "20:30")))).single()

            assertThat(night.extraSegments).isEmpty()
            assertThat(night.fellAsleep).isEqualTo(at(prev, "20:30"))
            assertThat(night.mainSleep).isEqualTo(hm(8, 30))
            assertThat(night.awakeInMain).isEqualTo(hm(2))
        }

        @Test
        fun `extra segment outside the edited window is kept`() {
            val doze = mainSession("doze", dt(prev, "20:00"), dt(prev, "21:00"))

            val night = assemble(doze, mainSession(), overrides = listOf(override(wokeUp = at(day, "06:30")))).single()

            assertThat(night.extraSegments.single().sessionIds).containsExactly("doze")
            assertThat(night.totalSleep).isEqualTo(hm(8, 30))
        }

        @Test
        fun `an inverted window falls back to the raw times`() {
            val night = assemble(raw, overrides = listOf(override(fellAsleep = at(day, "08:00")))).single()

            assertThat(night.fellAsleep).isEqualTo(at(prev, "23:00"))
            assertThat(night.fellAsleepOffset).isEqualTo(Fx.UTC)
            assertThat(night.mainSleep).isEqualTo(hm(7, 25))
        }

        @Test
        fun `the newest override for a date wins`() {
            val older = override(awakeMinutes = 10).copy(updatedAt = at(day, "08:00"))
            val newer = override(awakeMinutes = 60).copy(updatedAt = at(day, "10:00"))

            val night = assemble(raw, overrides = listOf(newer, older)).single()

            assertThat(night.override).isEqualTo(newer)
            assertThat(night.mainSleep).isEqualTo(hm(7))
        }
    }

    @Nested
    inner class TimeZones {

        @Test
        fun `New York fall-back night lasts nine hours`() {
            val wake = LocalDate.of(2026, 11, 1)
            val session = Fx.sessionInZone("fb", dt(wake.minusDays(1), "23:00"), dt(wake, "07:00"), Fx.NEW_YORK)

            val transition = Fx.NEW_YORK.rules.nextTransition(session.start)
            assertThat(transition.isOverlap).isTrue()
            assertThat(transition.instant).isLessThan(session.end)
            assertThat(session.startOffset).isEqualTo(ZoneOffset.ofHours(-4))
            assertThat(session.endOffset).isEqualTo(ZoneOffset.ofHours(-5))

            val night = assemble(session).single()

            assertThat(night.wakeDate).isEqualTo(wake)
            assertThat(night.mainSleep).isEqualTo(hm(9))
            assertThat(night.timeInBed).isEqualTo(hm(9))
            assertThat(localTime(night.fellAsleep, night.fellAsleepOffset)).isEqualTo(dt(wake.minusDays(1), "23:00"))
            assertThat(localTime(night.wokeUp, night.wokeUpOffset)).isEqualTo(dt(wake, "07:00"))
        }

        @Test
        fun `New York spring-forward night lasts seven hours`() {
            val wake = LocalDate.of(2026, 3, 8)
            val session = Fx.sessionInZone("sf", dt(wake.minusDays(1), "23:00"), dt(wake, "07:00"), Fx.NEW_YORK)

            val transition = Fx.NEW_YORK.rules.nextTransition(session.start)
            assertThat(transition.isGap).isTrue()
            assertThat(transition.instant).isLessThan(session.end)
            assertThat(session.startOffset).isEqualTo(ZoneOffset.ofHours(-5))
            assertThat(session.endOffset).isEqualTo(ZoneOffset.ofHours(-4))

            val night = assemble(session).single()

            assertThat(night.wakeDate).isEqualTo(wake)
            assertThat(night.mainSleep).isEqualTo(hm(7))
            assertThat(localTime(night.wokeUp, night.wokeUpOffset)).isEqualTo(dt(wake, "07:00"))
        }

        @Test
        fun `fragments across the fall-back hour are merged by elapsed time`() {
            val wake = LocalDate.of(2026, 11, 1)
            val est = ZoneOffset.ofHours(-5)
            // 01:30 EDT is 05:30 UTC; 01:15 EST is 06:15 UTC, so the clock goes back but 45 min pass.
            val a = Fx.sessionInZone("a", dt(wake.minusDays(1), "23:00"), dt(wake, "01:30"), Fx.NEW_YORK)
            val b = Fx.session("b", dt(wake, "01:15"), dt(wake, "07:00"), startOffset = est)
            assertThat(a.endOffset).isEqualTo(ZoneOffset.ofHours(-4))

            val night = assemble(b, a).single()

            assertThat(night.wakeDate).isEqualTo(wake)
            assertThat(night.main!!.sessionIds).containsExactly("a", "b").inOrder()
            assertThat(night.interruptions.single().kind).isEqualTo(AwakeKind.GAP)
            assertThat(night.interruptions.single().duration).isEqualTo(min(45))
            assertThat(night.timeInBed).isEqualTo(hm(9))
            assertThat(night.mainSleep).isEqualTo(hm(8, 15))
        }

        @Test
        fun `travel from New York to London keeps one night per wake date`() {
            val d = { dayOfMonth: Int -> LocalDate.of(2026, 10, dayOfMonth) }
            val ny = Fx.NEW_YORK
            val london = Fx.LONDON
            val edt = Fx.offsetAt(ny, dt(d(5), "22:00"))
            val bst = Fx.offsetAt(london, dt(d(6), "08:00"))
            assertThat(edt).isEqualTo(ZoneOffset.ofHours(-4))
            assertThat(bst).isEqualTo(ZoneOffset.ofHours(1))

            val sessions = listOf(
                Fx.sessionInZone("ny1", dt(d(3), "23:00"), dt(d(4), "07:00"), ny),
                Fx.sessionInZone("ny2", dt(d(4), "23:00"), dt(d(5), "07:00"), ny),
                // 17:30 local is 21:30 UTC: only a nap when read in its own offset.
                Fx.sessionInZone("ny-nap", dt(d(5), "17:30"), dt(d(5), "18:00"), ny),
                // Overnight flight: leaves New York 22:00 EDT, wakes in London 08:00 BST (5 h).
                Fx.session("flight", dt(d(5), "22:00"), dt(d(6), "08:00"), startOffset = edt, endOffset = bst),
                Fx.sessionInZone("ldn1", dt(d(6), "23:00"), dt(d(7), "07:00"), london),
                Fx.sessionInZone("ldn2", dt(d(7), "23:30"), dt(d(8), "07:30"), london),
            )

            val nights = assembler.assemble(sessions.shuffled(java.util.Random(42)), SourceSelection.All)

            assertThat(nights.map { it.wakeDate }).containsExactly(d(4), d(5), d(6), d(7), d(8)).inOrder()
            assertThat(nights.all { it.isPresent }).isTrue()
            assertThat(nights.map { it.main!!.sessionIds.single() })
                .containsExactly("ny1", "ny2", "flight", "ldn1", "ldn2").inOrder()
            assertThat(nights.map { it.mainSleep }).containsExactly(hm(8), hm(8), hm(5), hm(8), hm(8)).inOrder()
            assertThat(nights.map { localTime(it.wokeUp, it.wokeUpOffset).toLocalTime().toString() })
                .containsExactly("07:00", "07:00", "08:00", "07:00", "07:30").inOrder()
            assertThat(nights[1].napSleep).isEqualTo(min(30))
            assertThat(nights[1].naps.single().sessionIds).containsExactly("ny-nap")
            assertThat(nights[2].fellAsleepOffset).isEqualTo(edt)
            assertThat(nights[2].wokeUpOffset).isEqualTo(bst)
        }
    }
}
