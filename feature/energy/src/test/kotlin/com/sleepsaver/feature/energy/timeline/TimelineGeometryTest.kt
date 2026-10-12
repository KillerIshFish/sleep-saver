package com.sleepsaver.feature.energy.timeline

import androidx.compose.ui.geometry.Offset
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.model.ReminderCategory
import com.sleepsaver.core.domain.model.ScheduledHabit
import com.sleepsaver.core.testing.SampleData
import com.sleepsaver.feature.energy.EnergyTestData
import com.sleepsaver.feature.energy.EnergyTestData.at
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import kotlin.math.abs
import kotlin.random.Random

class TimelineGeometryTest {

    private val pxPerHour = 72f
    private val zone = EnergyTestData.zone

    // ---------------------------------------------------------------- Range

    @Test
    fun `range runs from 30 min before last night's sleep to an hour after tonight's melatonin window`() {
        val state = EnergyTestData.ready()
        val night = state.lastNight!!

        val range = TimelineRange.of(state.schedule, night, state.sleepNeed, state.now)

        assertThat(range.start).isEqualTo(night.fellAsleep!! - Duration.ofMinutes(30))
        assertThat(range.end).isEqualTo(state.schedule.phase(PhaseType.MELATONIN_WINDOW)!!.end + Duration.ofHours(1))
        assertThat(range.duration.toHours()).isIn(23L..26L)
        assertThat(range.contains(state.now)).isTrue()
    }

    @Test
    fun `range starts at typical wake minus need minus 30 min when last night is missing`() {
        val state = EnergyTestData.waiting()
        assertThat(state.lastNight).isNull()

        val range = TimelineRange.of(state.schedule, null, state.sleepNeed, state.now)

        val wake = state.schedule.typicalWake.toInstant()
        assertThat(range.start).isEqualTo(wake - state.sleepNeed - Duration.ofMinutes(30))
    }

    @Test
    fun `range stretches so a late now stays inside`() {
        val state = EnergyTestData.ready()
        val melatoninEnd = state.schedule.phase(PhaseType.MELATONIN_WINDOW)!!.end
        val lateNow = melatoninEnd + Duration.ofHours(2)

        val range = TimelineRange.of(state.schedule, state.lastNight, state.sleepNeed, lateNow)

        assertThat(range.end).isEqualTo(lateNow + Duration.ofHours(1))
        assertThat(range.contains(lateNow)).isTrue()
    }

    @Test
    fun `range stretches so an early now stays inside`() {
        val state = EnergyTestData.waiting()
        val wake = state.schedule.typicalWake.toInstant()
        val earlyNow = wake - state.sleepNeed - Duration.ofHours(3)

        val range = TimelineRange.of(state.schedule, null, state.sleepNeed, earlyNow)

        assertThat(range.start).isEqualTo(earlyNow - Duration.ofMinutes(30))
    }

    // ---------------------------------------------------------------- y mapping

    @Test
    fun `y is linear in elapsed time and timeAt inverts it`() {
        val start = Instant.parse("2026-10-06T03:00:00Z")
        val geometry = TimelineGeometry(TimelineRange(start, start + Duration.ofHours(25)), pxPerHour, topInset = 30f, bottomInset = 20f)

        assertThat(geometry.yOf(start)).isEqualTo(30f)
        assertThat(geometry.yOf(start + Duration.ofMinutes(90))).isWithin(0.01f).of(30f + 1.5f * pxPerHour)
        assertThat(geometry.contentHeight).isWithin(0.01f).of(25 * pxPerHour)
        assertThat(geometry.height).isWithin(0.01f).of(30f + 25 * pxPerHour + 20f)
        val t = start + Duration.ofMinutes(437)
        assertThat(geometry.timeAt(geometry.yOf(t))).isEqualTo(t)
        assertThat(geometry.yOfClamped(start - Duration.ofHours(1))).isEqualTo(30f)
        assertThat(geometry.yOfClamped(start + Duration.ofHours(30))).isEqualTo(geometry.bottom)
    }

    @Test
    fun `ticks fall on every local half hour and flag hours and midnight`() {
        val start = at(LocalDate.of(2026, 10, 5), 22, 17)
        val geometry = TimelineGeometry(TimelineRange(start, start + Duration.ofHours(4)), pxPerHour)

        val ticks = geometry.ticks(zone)

        val local = ticks.map { it.time.atZone(zone).toLocalTime() }
        assertThat(local.first()).isEqualTo(LocalTime.of(22, 30))
        assertThat(local).containsExactly(
            LocalTime.of(22, 30), LocalTime.of(23, 0), LocalTime.of(23, 30), LocalTime.of(0, 0),
            LocalTime.of(0, 30), LocalTime.of(1, 0), LocalTime.of(1, 30), LocalTime.of(2, 0),
        ).inOrder()
        assertThat(ticks.filter { it.isHour }).hasSize(4)
        assertThat(ticks.single { it.isMidnight }.time).isEqualTo(at(LocalDate.of(2026, 10, 6), 0))
        ticks.zipWithNext().forEach { (a, b) -> assertThat(b.y - a.y).isWithin(0.01f).of(pxPerHour / 2) }
    }

    @Test
    fun `ticks follow a half-hour zone offset`() {
        val kolkata = java.time.ZoneId.of("Asia/Kolkata")
        val start = Instant.parse("2026-10-06T00:00:00Z") // 05:30 local
        val geometry = TimelineGeometry(TimelineRange(start, start + Duration.ofHours(2)), pxPerHour)

        val hours = geometry.ticks(kolkata).filter { it.isHour }.map { it.time.atZone(kolkata).toLocalTime() }

        assertThat(hours).containsExactly(LocalTime.of(6, 0), LocalTime.of(7, 0)).inOrder()
    }

    // ---------------------------------------------------------------- DST

    @Test
    fun `fall-back night is an hour longer and repeats 1 AM`() {
        // US clocks fall back at 2 AM on Sunday, Nov 1 2026.
        val wakeDate = LocalDate.of(2026, 11, 1)
        val nights = EnergyTestData.patternNights(wakeDate, LocalTime.of(23, 0), LocalTime.of(7, 0), count = 14)
        val state = EnergyTestData.ready(now = at(wakeDate, 9, 30), nights = nights)
        val night = state.lastNight!!
        val geometry = TimelineGeometry(TimelineRange.of(state.schedule, night, state.sleepNeed, state.now), pxPerHour)

        val asleepHeight = geometry.yOf(night.wokeUp!!) - geometry.yOf(night.fellAsleep!!)
        val elapsed = Duration.between(night.fellAsleep, night.wokeUp)
        assertThat(elapsed.toMinutes()).isAtLeast(8 * 60 + 50L) // 23:00 → 07:00 local is ~9 h
        assertThat(asleepHeight).isWithin(0.5f).of(elapsed.toMinutes() / 60f * pxPerHour)
        val hourLabels = geometry.ticks(zone).filter { it.isHour }.map { it.time.atZone(zone).toLocalTime() }
        assertThat(hourLabels.count { it == LocalTime.of(1, 0) }).isEqualTo(2)
        // Ticks stay evenly spaced in elapsed time.
        geometry.ticks(zone).zipWithNext().forEach { (a, b) -> assertThat(b.y - a.y).isWithin(0.01f).of(pxPerHour / 2) }
    }

    @Test
    fun `spring-forward night is an hour shorter and skips 2 AM`() {
        // US clocks spring forward at 2 AM on Sunday, Mar 8 2026.
        val wakeDate = LocalDate.of(2026, 3, 8)
        val nights = EnergyTestData.patternNights(wakeDate, LocalTime.of(23, 0), LocalTime.of(7, 0), count = 14)
        val state = EnergyTestData.ready(now = at(wakeDate, 12, 0), nights = nights)
        val night = state.lastNight!!
        val range = TimelineRange.of(state.schedule, night, state.sleepNeed, state.now)
        val geometry = TimelineGeometry(range, pxPerHour)

        val elapsed = Duration.between(night.fellAsleep, night.wokeUp)
        assertThat(elapsed.toMinutes()).isAtMost(7 * 60 + 10L)
        assertThat(geometry.yOf(night.wokeUp!!) - geometry.yOf(night.fellAsleep!!)).isWithin(0.5f).of(elapsed.toMinutes() / 60f * pxPerHour)
        val hourLabels = geometry.ticks(zone).filter { it.isHour }.map { it.time.atZone(zone).toLocalTime() }
        assertThat(hourLabels).doesNotContain(LocalTime.of(2, 0))
        assertThat(hourLabels).containsAtLeast(LocalTime.of(1, 0), LocalTime.of(3, 0)).inOrder()
        // The layout still builds and its labels don't overlap.
        assertNoLabelOverlap(buildTimelineLayout(state, testSpec()))
    }

    // ---------------------------------------------------------------- Night owl

    @Test
    fun `night owl range starts after midnight and ends after the next midnight`() {
        val state = EnergyTestData.nightOwl()
        val night = state.lastNight!!
        val range = TimelineRange.of(state.schedule, night, state.sleepNeed, state.now)
        val geometry = TimelineGeometry(range, pxPerHour)

        assertThat(range.start).isEqualTo(night.fellAsleep!! - Duration.ofMinutes(30))
        assertThat(range.start.atZone(zone).toLocalDate()).isEqualTo(state.today)
        assertThat(range.start.atZone(zone).toLocalTime()).isLessThan(LocalTime.of(2, 0))
        assertThat(range.end.atZone(zone).toLocalDate()).isEqualTo(state.today.plusDays(1))
        val midnight = geometry.ticks(zone).single { it.isMidnight }
        assertThat(midnight.time).isEqualTo(at(state.today.plusDays(1), 0))
        // Tonight's bedtime falls after midnight, below the midnight tick.
        val melatonin = state.schedule.phase(PhaseType.MELATONIN_WINDOW)!!
        assertThat(geometry.yOf(melatonin.start)).isGreaterThan(midnight.y)

        val layout = buildTimelineLayout(state, testSpec())
        assertThat(layout.mainBlock!!.top).isWithin(0.5f).of(layout.geometry.yOf(night.fellAsleep!!))
        assertNoLabelOverlap(layout)
    }

    @Test
    fun `a night owl after midnight still sees yesterday's day around last night's block`() {
        val state = EnergyTestData.nightOwlAfterMidnight()
        assertThat(state.day).isEqualTo(state.today.minusDays(1))
        val night = state.lastNight!!
        assertThat(night.wakeDate).isEqualTo(state.day)
        assertThat(state.lastNightState).isEqualTo(com.sleepsaver.core.domain.model.LastNightState.AVAILABLE)

        val layout = buildTimelineLayout(state, testSpec())

        assertThat(layout.mainBlock!!.wakeDate).isEqualTo(state.day)
        assertThat(layout.blocks.none { it.kind == TimelineBlockKind.PENDING }).isTrue()
        val melatonin = state.schedule.phase(PhaseType.MELATONIN_WINDOW)!!
        assertThat(state.now).isLessThan(melatonin.end)
        assertThat(layout.geometry.range.contains(state.now)).isTrue()
        assertThat(layout.geometry.range.end).isAtLeast(melatonin.end + Duration.ofHours(1))
        assertThat(layout.now!!.y).isGreaterThan(layout.geometry.ticks(zone).single { it.isMidnight }.y)
        assertNoLabelOverlap(layout)
    }

    // ---------------------------------------------------------------- Labels

    @Test
    fun `isolated labels stay where they want to be`() {
        val tops = placeLabels(
            listOf(LabelSlot(100f, 20f), LabelSlot(300f, 20f), LabelSlot(500f, 30f)),
            minTop = 0f,
            maxBottom = 1000f,
            gap = 6f,
        )
        assertThat(tops).containsExactly(100f, 300f, 500f).inOrder()
    }

    @Test
    fun `labels at the same time spread evenly around it in input order`() {
        val tops = placeLabels(
            listOf(LabelSlot(100f, 20f, anchor = 110f), LabelSlot(100f, 20f, anchor = 110f), LabelSlot(100f, 20f, anchor = 110f)),
            minTop = 0f,
            maxBottom = 1000f,
            gap = 5f,
        )
        assertThat(tops).containsExactly(75f, 100f, 125f).inOrder()
    }

    @Test
    fun `a crowded evening keeps every label near its time instead of piling them below the first`() {
        // Wind-down (28-px pill + 18-px caption) and a habit at 500, the melatonin window at 576, bedtime at 614.
        val anchors = listOf(500f, 500f, 576f, 614f)
        val slots = listOf(
            LabelSlot(500f - 14f, 46f, anchor = 500f),
            LabelSlot(500f - 19f, 38f, anchor = 500f),
            LabelSlot(576f - 14f, 46f, anchor = 576f),
            LabelSlot(614f - 19f, 38f, anchor = 614f),
        )
        val centres = listOf(14f, 19f, 14f, 19f)

        val tops = placeLabels(slots, minTop = 0f, maxBottom = 2000f, gap = 6f)

        // Pushing each one below the last would leave the habit 57 px under its time; spread, none is 30 px off.
        assertThat(tops).containsExactly(457f, 509f, 553f, 605f).inOrder()
        tops.indices.forEach { assertThat(abs(tops[it] + centres[it] - anchors[it])).isAtMost(30f) }
        tops.indices.toList().zipWithNext().forEach { (a, b) -> assertThat(tops[a] + slots[a].height + 6f).isAtMost(tops[b]) }
    }

    @Test
    fun `a crowd never moves above the fixed slot before it`() {
        // The now pill at 400..426; three labels want to centre on 440, just after it.
        val tops = placeLabels(
            listOf(
                LabelSlot(400f, 26f, fixed = true, anchor = 413f),
                LabelSlot(430f, 20f, anchor = 440f),
                LabelSlot(430f, 20f, anchor = 440f),
                LabelSlot(430f, 20f, anchor = 440f),
            ),
            minTop = 0f,
            maxBottom = 1000f,
            gap = 6f,
        )
        assertThat(tops.drop(1)).containsExactly(432f, 458f, 484f).inOrder()
    }

    @Test
    fun `labels make room above a fixed slot and the bottom edge`() {
        val tops = placeLabels(
            listOf(LabelSlot(90f, 20f), LabelSlot(100f, 20f, fixed = true), LabelSlot(195f, 20f)),
            minTop = 0f,
            maxBottom = 200f,
            gap = 4f,
        )
        assertThat(tops[1]).isEqualTo(100f)
        assertThat(tops[0] + 20f + 4f).isAtMost(100f)
        assertThat(tops[2] + 20f).isAtMost(200f)
    }

    @Test
    fun `labels pushed toward the top edge stay inside it`() {
        val tops = placeLabels(
            listOf(LabelSlot(-10f, 20f), LabelSlot(5f, 20f), LabelSlot(30f, 20f, fixed = true)),
            minTop = 0f,
            maxBottom = 400f,
            gap = 4f,
        )
        assertThat(tops[0]).isAtLeast(0f)
        assertThat(tops[2]).isEqualTo(30f)
        assertThat(tops[1]).isAtLeast(54f)
    }

    @Test
    fun `dense random labels never overlap and keep their order`() {
        val random = Random(42)
        repeat(50) {
            // Up to 8 labels of ≤ 46 px always fit above a fixed slot at 400 px or lower.
            val slots = List(random.nextInt(2, 9)) {
                LabelSlot(random.nextFloat() * 600f, 20f + random.nextFloat() * 20f, fixed = false)
            } + LabelSlot(400f + random.nextFloat() * 200f, 26f, fixed = true)
            val tops = placeLabels(slots, minTop = 0f, maxBottom = 2000f, gap = 6f)

            val placed = slots.indices.sortedBy { tops[it] }
            placed.zipWithNext().forEach { (a, b) ->
                assertThat(tops[a] + slots[a].height + 6f).isAtMost(tops[b] + 0.001f)
            }
            slots.indices.filter { !slots[it].fixed }.forEach { assertThat(tops[it]).isAtLeast(0f) }
            assertThat(tops.last()).isEqualTo(slots.last().desiredTop)
            val movable = slots.indices.filter { !slots[it].fixed }
            movable.zipWithNext().forEach { (a, b) ->
                if (slots[a].anchor < slots[b].anchor) assertThat(tops[a]).isLessThan(tops[b])
            }
        }
    }

    @Test
    fun `labels stay on their side of a fixed slot`() {
        // A fixed "now" pill at 100..126 (anchor 113); one label just before now, one just after.
        val tops = placeLabels(
            listOf(
                LabelSlot(100f, 26f, fixed = true, anchor = 113f),
                LabelSlot(95f, 30f, anchor = 110f),
                LabelSlot(105f, 30f, anchor = 120f),
            ),
            minTop = 0f,
            maxBottom = 1000f,
            gap = 6f,
        )
        assertThat(tops[0]).isEqualTo(100f)
        assertThat(tops[1] + 30f + 6f).isAtMost(100f)
        assertThat(tops[2]).isAtLeast(126f + 6f)
    }

    @Test
    fun `a label anchored inside a night block goes below it`() {
        val tops = placeLabels(
            listOf(LabelSlot(100f, 400f, fixed = true, anchor = 100f), LabelSlot(480f, 30f, anchor = 495f)),
            minTop = 0f,
            maxBottom = 2000f,
            gap = 6f,
        )
        assertThat(tops[1]).isEqualTo(506f)
    }

    @Test
    fun `a full interval hands its last labels on to the next one`() {
        // Room for one 30 px label above the fixed slot at 40; the second goes below it.
        val tops = placeLabels(
            listOf(LabelSlot(0f, 30f), LabelSlot(5f, 30f), LabelSlot(40f, 20f, fixed = true, anchor = 50f)),
            minTop = 0f,
            maxBottom = 400f,
            gap = 4f,
        )
        assertThat(tops[0]).isEqualTo(0f)
        assertThat(tops[1]).isEqualTo(64f)
    }

    @Test
    fun `a phase pill sits above a habit that starts at the same minute`() {
        val layout = buildTimelineLayout(EnergyTestData.ready(), testSpec())

        val windDown = layout.phases.single { it.phase.type == PhaseType.WIND_DOWN }
        val dim = layout.habits.single { row -> row.habits.any { it.type == HabitType.DIM_LIGHTS } }
        assertThat(dim.habit.time).isEqualTo(windDown.phase.start)
        assertThat(windDown.labelTop + windDown.labelHeight).isAtMost(dim.labelTop)
    }

    @Test
    fun `habits at the same minute share a row while they fit side by side`() {
        val t = Instant.parse("2026-10-05T01:15:00Z")
        fun habit(type: HabitType, at: Instant) = ScheduledHabit(type, at, null, enabled = true, reminderEnabled = true, category = ReminderCategory.WIND_DOWN)
        val dim = habit(HabitType.DIM_LIGHTS, t)
        val steps = habit(HabitType.EVENING_ROUTINE, t + Duration.ofSeconds(20))
        val bedtime = habit(HabitType.BEDTIME, t + Duration.ofMinutes(90))
        // Dim the lights takes 130 + 250 px, Night steps 130 + 200 px: 710 px side by side.
        val spec = testSpec().copy(
            habitTitleWidths = mapOf(HabitType.DIM_LIGHTS to 250f, HabitType.EVENING_ROUTINE to 200f),
            habitTimeWidth = 140f,
            habitChromeWidth = 130f,
        )

        assertThat(habitRows(listOf(dim, steps, bedtime), spec, width = 760f))
            .containsExactly(listOf(dim, steps), listOf(bedtime)).inOrder()
        // Too narrow (large text): each keeps its own row at that minute.
        assertThat(habitRows(listOf(dim, steps, bedtime), spec, width = 700f))
            .containsExactly(listOf(dim), listOf(steps), listOf(bedtime)).inOrder()
    }

    @Test
    fun `on a dense evening the co-timed habits share a row and every label stays near its time`() {
        // Sunday 8:20 PM: wind-down, Dim the lights and Night steps at one minute, then the melatonin window and
        // bedtime, within two hours; wind-down and melatonin window carry "earlier" captions.
        val state = EnergyTestData.sundayEvening()
        val layout = buildTimelineLayout(state, testSpec())
        val windDown = layout.phases.single { it.phase.type == PhaseType.WIND_DOWN }

        val shared = layout.habits.single { row -> row.habits.any { it.type == HabitType.DIM_LIGHTS } }
        assertThat(shared.habits.map { it.type }).containsExactly(HabitType.DIM_LIGHTS, HabitType.EVENING_ROUTINE).inOrder()
        assertThat(shared.habit.time).isEqualTo(windDown.phase.start)
        assertThat(windDown.showsShift).isTrue()
        val bedtime = layout.habits.single { it.habit.type == HabitType.BEDTIME }
        assertThat(bedtime.habit.time).isEqualTo(state.schedule.targetBedtime)
        // testSpec draws 200 px an hour: no evening label ends up more than 30 min from its time.
        val evening = state.schedule.phase(PhaseType.EVENING_PEAK)!!.start
        layout.phases.filter { it.phase.start >= evening }.forEach {
            assertThat(abs(it.labelTop + testSpec().pillHeight / 2 - it.anchorY)).isAtMost(100f)
        }
        layout.habits.filter { it.habit.time >= evening }.forEach {
            assertThat(abs(it.labelTop + testSpec().habitHeight / 2 - it.anchorY)).isAtMost(100f)
        }
        assertNoLabelOverlap(layout)
    }

    @Test
    fun `a shared row that would reach the curve goes back to one row per habit`() {
        // Sunday 8:20 PM: Dim the lights and Night steps at the wind-down's start. The label column starts at about
        // 200 px and the curve runs between 594 and 1030 px: with 20 px of clearance a 300-px row always fits, and an
        // 800-px one (still inside the column) never does.
        val state = EnergyTestData.sundayEvening()
        fun spec(habitWidth: Float) = testSpec().copy(habitChromeWidth = habitWidth, curveClearance = 20f)

        val narrow = buildTimelineLayout(state, spec(150f))
        val wide = buildTimelineLayout(state, spec(400f))

        assertThat(narrow.rowOf(HabitType.DIM_LIGHTS).habits.map { it.type })
            .containsExactly(HabitType.DIM_LIGHTS, HabitType.EVENING_ROUTINE).inOrder()
        assertThat(wide.rowOf(HabitType.DIM_LIGHTS).habits.map { it.type }).containsExactly(HabitType.DIM_LIGHTS)
        assertThat(wide.rowOf(HabitType.EVENING_ROUTINE).habits.map { it.type }).containsExactly(HabitType.EVENING_ROUTINE)
        listOf(narrow, wide).forEach { layout ->
            layout.habits.forEach { row ->
                val curveX = layout.curve.minXBetween(row.labelTop, row.labelTop + layout.spec.habitHeight) ?: return@forEach
                if (row.habits.size > 1) assertThat(layout.labelLeft + row.width + layout.spec.curveClearance).isAtMost(curveX)
            }
            assertNoLabelOverlap(layout)
        }
    }

    @Test
    fun `the curve's leftmost point between two heights counts its edges`() {
        val curve = listOf(Offset(300f, 0f), Offset(200f, 100f), Offset(250f, 200f))

        assertThat(curve.minXBetween(50f, 150f)).isEqualTo(200f)
        assertThat(curve.minXBetween(0f, 50f)).isEqualTo(250f)
        assertThat(curve.minXBetween(150f, 400f)).isEqualTo(225f)
        assertThat(curve.minXBetween(300f, 400f)).isNull()
    }

    @Test
    fun `a crowded stretch drops its pills' shift captions, a roomy one keeps them`() {
        // 9:10 PM: wind-down, Dim the lights and Night steps (all at 9:31), the melatonin window and bedtime, all in
        // the stretch under the now pill, can't all sit within 30 min of their times.
        val crowded = buildTimelineLayout(EnergyTestData.ready(now = at(21, 10)), testSpec())
        val shifted = crowded.phases.filter { it.phase.hasShift() && it.anchorY > crowded.now!!.y }
        assertThat(shifted.map { it.phase.type }).containsAtLeast(PhaseType.WIND_DOWN, PhaseType.MELATONIN_WINDOW)
        shifted.forEach {
            assertThat(it.showsShift).isFalse()
            assertThat(it.labelHeight).isEqualTo(testSpec().pillHeight)
        }
        assertNoLabelOverlap(crowded)

        // Sunday 8:20 PM has the room: wind-down keeps its "↑47m earlier".
        val roomy = buildTimelineLayout(EnergyTestData.sundayEvening(), testSpec())
        val windDown = roomy.phases.single { it.phase.type == PhaseType.WIND_DOWN }
        assertThat(windDown.showsShift).isTrue()
        assertThat(windDown.labelHeight).isEqualTo(testSpec().pillHeight + testSpec().captionHeight)
    }

    @Test
    fun `in a crowded evening nothing after now moves above it and the range runs on to fit`() {
        val state = EnergyTestData.ready(now = at(21, 10))
        val layout = buildTimelineLayout(state, testSpec())
        val now = layout.now!!
        val nowBottom = now.labelTop + testSpec().nowHeight

        layout.phases.filter { it.anchorY > now.y }.forEach { assertThat(it.labelTop).isAtLeast(nowBottom) }
        layout.habits.filter { it.anchorY > now.y }.forEach { assertThat(it.labelTop).isAtLeast(nowBottom) }
        val melatoninEnd = state.schedule.phase(PhaseType.MELATONIN_WINDOW)!!.end
        assertThat(layout.geometry.range.end).isAtLeast(melatoninEnd + Duration.ofHours(1))
        assertThat(layout.geometry.range.end).isAtMost(melatoninEnd + Duration.ofHours(4))
        assertNoLabelOverlap(layout)
    }

    @Test
    fun `the real timeline never overlaps its labels`() {
        listOf(
            EnergyTestData.ready(),
            EnergyTestData.ready(now = at(21, 10)),
            EnergyTestData.waiting(),
            EnergyTestData.nightOwl(),
            EnergyTestData.nightOwlAfterMidnight(),
            EnergyTestData.waiting(now = at(15, 0)),
            EnergyTestData.ready(now = at(17, 30), settings = EnergyTestData.longNeedSettings),
            EnergyTestData.ready(settings = EnergyTestData.longNeedSettings),
            EnergyTestData.sundayEvening(),
            EnergyTestData.sundayEvening(now = at(EnergyTestData.sunday, 21, 40)),
        ).forEach { assertNoLabelOverlap(buildTimelineLayout(it, testSpec())) }
    }

    // ---------------------------------------------------------------- Lanes, phases, blocks

    @Test
    fun `phases that don't overlap share one lane, overlapping ones get another`() {
        val t = Instant.parse("2026-10-06T12:00:00Z")
        fun h(x: Long) = t + Duration.ofMinutes(x * 60)
        assertThat(assignLanes(listOf(h(0) to h(2), h(2) to h(4), h(5) to h(6)))).containsExactly(0, 0, 0).inOrder()
        assertThat(assignLanes(listOf(h(0) to h(3), h(2) to h(4), h(3) to h(5)))).containsExactly(0, 1, 0).inOrder()
    }

    @Test
    fun `a long sleep need overlaps wind-down with the evening peak and splits their lanes`() {
        val state = EnergyTestData.ready(settings = EnergyTestData.longNeedSettings)
        val layout = buildTimelineLayout(state, testSpec())

        val evening = layout.phases.single { it.phase.type == PhaseType.EVENING_PEAK }
        val windDown = layout.phases.single { it.phase.type == PhaseType.WIND_DOWN }
        assertThat(windDown.phase.start).isLessThan(evening.phase.end)
        assertThat(windDown.lane).isNotEqualTo(evening.lane)
        assertThat(layout.labelLeft).isGreaterThan(testSpec().barsLeft + 2 * testSpec().barWidth)
    }

    @Test
    fun `last night is the main block, from fell asleep to wake, with its interruptions`() {
        val state = EnergyTestData.ready()
        val layout = buildTimelineLayout(state, testSpec())
        val night = state.lastNight!!

        val main = layout.mainBlock!!
        assertThat(main.wakeDate).isEqualTo(state.today)
        assertThat(main.start).isEqualTo(night.fellAsleep)
        assertThat(main.end).isEqualTo(night.wokeUp)
        assertThat(main.awake.map { it.start }).isEqualTo(night.interruptions.map { it.start }.sorted())
        main.awake.forEach { assertThat(it.top).isAtLeast(main.top) ; assertThat(it.bottom).isAtMost(main.bottom) }
        assertThat(layout.blocks.none { it.kind == TimelineBlockKind.PENDING }).isTrue()
    }

    @Test
    fun `without last night a pending block holds its place`() {
        val state = EnergyTestData.waiting()
        val layout = buildTimelineLayout(state, testSpec())

        assertThat(layout.mainBlock).isNull()
        val pending = layout.blocks.single { it.kind == TimelineBlockKind.PENDING }
        assertThat(pending.end).isEqualTo(state.schedule.typicalWake.toInstant())
        assertThat(pending.start).isEqualTo(pending.end - state.sleepNeed)
    }

    @Test
    fun `naps are drawn in the energy column`() {
        // SampleData naps every 6th day (k % 6 == 2): two days before today has one, so use that day as today.
        val today = SampleData.today.minusDays(2)
        val state = EnergyTestData.ready(now = at(today, 18, 0), nights = SampleData.nights(today = SampleData.today).filter { !it.wakeDate.isAfter(today) })
        val layout = buildTimelineLayout(state, testSpec())

        val nap = layout.blocks.single { it.kind == TimelineBlockKind.NAP }
        assertThat(nap.left).isEqualTo(testSpec().curveLeft)
        assertThat(nap.start.atZone(zone).toLocalTime()).isAtLeast(LocalTime.of(14, 0))
    }

    @Test
    fun `the now mark sits on the curve`() {
        val state = EnergyTestData.ready()
        val layout = buildTimelineLayout(state, testSpec())

        val now = layout.now!!
        assertThat(now.y).isWithin(0.01f).of(layout.geometry.yOf(state.now))
        val expected = layout.scale.xOf(state.schedule.curve.valueAt(state.now)!!)
        assertThat(now.dotX!!).isWithin(0.01f).of(expected)
        assertThat(now.labelTop).isWithin(0.01f).of(now.y - testSpec().nowHeight / 2)
    }

    @Test
    fun `only enabled habits get markers, anchored at their times`() {
        val state = EnergyTestData.ready()
        val layout = buildTimelineLayout(state, testSpec())

        assertThat(layout.habits.flatMap { row -> row.habits.map { it.type } })
            .containsExactlyElementsIn(state.habits.filter { it.enabled }.map { it.type })
        layout.habits.forEach { row ->
            row.habits.forEach { assertThat(layout.geometry.yOf(it.time)).isWithin(0.01f).of(row.anchorY) }
        }
    }

    @Test
    fun `energy maps across the curve column and clamps`() {
        val scale = EnergyScale(200f, 400f)
        assertThat(scale.xOf(0f)).isEqualTo(200f)
        assertThat(scale.xOf(0.5f)).isEqualTo(300f)
        assertThat(scale.xOf(1f)).isEqualTo(400f)
        assertThat(scale.xOf(1.4f)).isEqualTo(400f)
        assertThat(scale.xOf(-1f)).isEqualTo(200f)
    }

    // ---------------------------------------------------------------- Add awake time

    @Test
    fun `a long press starts awake time at the nearest 5 minutes, inside the block`() {
        val blockStart = Instant.parse("2026-10-06T03:02:00Z")
        val blockEnd = blockStart + Duration.ofHours(8)

        assertThat(awakeStartFor(blockStart + Duration.ofMinutes(91), blockStart, blockEnd)).isEqualTo(Instant.parse("2026-10-06T04:35:00Z"))
        assertThat(awakeStartFor(blockStart + Duration.ofMinutes(93), blockStart, blockEnd)).isEqualTo(Instant.parse("2026-10-06T04:35:00Z"))
        assertThat(awakeStartFor(blockStart - Duration.ofMinutes(20), blockStart, blockEnd)).isEqualTo(blockStart)
        assertThat(awakeStartFor(blockEnd, blockStart, blockEnd)).isEqualTo(blockEnd - Duration.ofMinutes(5))
    }

    @Test
    fun `awake length stays within 5 to 120 minutes and inside the block`() {
        val start = Instant.parse("2026-10-06T05:00:00Z")
        assertThat(maxAwakeMinutes(start, start + Duration.ofHours(5))).isEqualTo(120)
        assertThat(maxAwakeMinutes(start, start + Duration.ofMinutes(37))).isEqualTo(37)
        assertThat(maxAwakeMinutes(start, start + Duration.ofMinutes(2))).isEqualTo(5)
    }

    // ---------------------------------------------------------------- Helpers

    private fun testSpec() = TimelineSpec(
        width = 1080f,
        pxPerHour = 200f,
        topInset = 90f,
        bottomInset = 70f,
        barsLeft = 150f,
        barWidth = 13f,
        barGap = 10f,
        labelInset = 30f,
        blockLeft = 150f,
        blockRight = 1040f,
        curveLeft = 594f,
        curveRight = 1030f,
        pillHeight = 74f,
        captionHeight = 44f,
        habitHeight = 100f,
        nowHeight = 68f,
        labelGap = 16f,
    )

    private fun TimelineLayout.rowOf(type: HabitType): HabitMark = habits.single { row -> row.habits.any { it.type == type } }

    private fun assertNoLabelOverlap(layout: TimelineLayout) {
        val spec = layout.spec
        val labels = layout.phases.map { it.labelTop to it.labelHeight } +
            layout.habits.map { it.labelTop to spec.habitHeight } +
            listOfNotNull(layout.now?.let { it.labelTop to spec.nowHeight })
        val blocks = layout.blocks.filter { it.kind != TimelineBlockKind.NAP }.map { it.top to it.height }
        val sorted = labels.sortedBy { it.first }
        sorted.zipWithNext().forEach { (a, b) ->
            assertThat(a.first + a.second).isAtMost(b.first + 0.01f)
        }
        // Movable labels stay clear of the night blocks (the now pill may sit on one if now is inside it).
        val movable = layout.phases.map { it.labelTop to it.labelHeight } + layout.habits.map { it.labelTop to spec.habitHeight }
        movable.forEach { (top, height) ->
            blocks.forEach { (blockTop, blockHeight) ->
                val overlaps = top < blockTop + blockHeight && top + height > blockTop
                assertThat(overlaps).isFalse()
            }
        }
        labels.forEach { (top, height) ->
            assertThat(top).isAtLeast(0f)
            assertThat(top + height).isAtMost(layout.height + 0.01f)
        }
    }
}
