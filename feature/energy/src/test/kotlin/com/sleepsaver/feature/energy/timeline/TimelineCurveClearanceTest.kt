package com.sleepsaver.feature.energy.timeline

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.ui.format.LocalUse24HourClock
import com.sleepsaver.core.ui.testing.PHONE_PORTRAIT_DARK
import com.sleepsaver.core.ui.testing.SCREENSHOT_SDK
import com.sleepsaver.core.ui.theme.SleepSaverTheme
import com.sleepsaver.feature.energy.EnergyTestData
import com.sleepsaver.feature.energy.EnergyTestData.at
import com.sleepsaver.feature.energy.EnergyUiState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.abs

/**
 * The timeline's label column against the energy curve, with the spec measured the way the Energy screen measures it
 * (a 411-dp phone, the real fonts): no habit label covers the curve line, and co-timed habits share a row only where
 * that row stays left of it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [SCREENSHOT_SDK], qualifiers = PHONE_PORTRAIT_DARK)
class TimelineCurveClearanceTest {

    @get:Rule
    val compose = createComposeRule()

    private val fixtures: List<Pair<String, EnergyUiState.Ready>> by lazy {
        listOf(
            "mid-morning" to EnergyTestData.ready(),
            "9:10 PM" to EnergyTestData.ready(now = at(21, 10)),
            "night mode" to EnergyTestData.nightMode(),
            "Sunday 8:20 PM" to EnergyTestData.sundayEvening(),
            "Sunday 9:40 PM" to EnergyTestData.sundayEvening(now = at(EnergyTestData.sunday, 21, 40)),
            "night owl" to EnergyTestData.nightOwl(),
            "long need" to EnergyTestData.ready(now = at(17, 30), settings = EnergyTestData.longNeedSettings),
        )
    }

    private fun measuredSpec(use24h: Boolean = false, fontScale: Float = 1f): TimelineSpec {
        var spec: TimelineSpec? = null
        compose.setContent {
            SleepSaverTheme {
                CompositionLocalProvider(
                    LocalUse24HourClock provides use24h,
                    LocalDensity provides Density(LocalDensity.current.density, fontScale),
                ) {
                    spec = rememberTimelineSpec(411.dp)
                }
            }
        }
        compose.waitForIdle()
        return checkNotNull(spec)
    }

    @Test
    fun `no habit label covers the curve`() {
        val spec = measuredSpec()
        fixtures.forEach { (name, state) -> assertClearOfCurve(name, buildTimelineLayout(state, spec), sharedOnly = false) }
    }

    @Test
    fun `no habit label covers the curve on a 24-hour clock`() {
        val spec = measuredSpec(use24h = true)
        fixtures.forEach { (name, state) -> assertClearOfCurve(name, buildTimelineLayout(state, spec), sharedOnly = false) }
    }

    /** At large text one habit's label may be wider than the label column; rows are only shared clear of the curve. */
    @Test
    fun `at large text shared rows stay clear of the curve`() {
        val spec = measuredSpec(fontScale = 1.6f)
        fixtures.forEach { (name, state) -> assertClearOfCurve(name, buildTimelineLayout(state, spec), sharedOnly = true) }
    }

    @Test
    fun `co-timed habits share a row only where it stays left of the curve`() {
        val spec = measuredSpec()

        // Sunday 8:20 PM: the wind-down starts while energy is still high, so Dim the lights and Night steps share.
        val sunday = buildTimelineLayout(EnergyTestData.sundayEvening(), spec)
        assertThat(sunday.rowOf(HabitType.DIM_LIGHTS).habits.map { it.type })
            .containsExactly(HabitType.DIM_LIGHTS, HabitType.EVENING_ROUTINE).inOrder()

        // 9:10 PM and the no-clock night: the pair sits where the curve has swung left, so each keeps its own row.
        listOf(EnergyTestData.ready(now = at(21, 10)), EnergyTestData.nightMode()).forEach { state ->
            val layout = buildTimelineLayout(state, spec)
            assertThat(layout.rowOf(HabitType.DIM_LIGHTS).habits).hasSize(1)
            assertThat(layout.rowOf(HabitType.EVENING_ROUTINE).habits).hasSize(1)
        }
    }

    @Test
    fun `just before the wind-down every label stays as near its time as the column allows`() {
        val spec = measuredSpec()
        val state = EnergyTestData.ready(now = at(21, 10))
        val layout = buildTimelineLayout(state, spec)

        // Five labels within 90 min, all after a now pill 21 min before the first three: the stretch is crowded, so
        // its pills drop their "↑5m earlier" captions.
        val windDown = layout.phases.single { it.phase.type == PhaseType.WIND_DOWN }
        val melatonin = layout.phases.single { it.phase.type == PhaseType.MELATONIN_WINDOW }
        assertThat(windDown.phase.hasShift()).isTrue()
        assertThat(windDown.showsShift).isFalse()
        assertThat(melatonin.showsShift).isFalse()
        // Wind-down, Dim the lights and Night steps (one row each: shared, they would cover the curve) all want 9:31
        // but can't go above now, and take about 100 min of timeline between them, so 30 min each can't be had here:
        // wind-down sits ~5 min off, Dim the lights ~36, Night steps ~71, the melatonin window and Bedtime ~42.
        val drift = minutesOff(layout, spec)
        drift.forEach { (label, minutes) -> assertWithMessage(label).that(minutes).isAtMost(75f) }
        assertThat(drift.getValue("WIND_DOWN")).isAtMost(10f)
        assertThat(drift.getValue("MELATONIN_WINDOW")).isAtMost(45f)
        assertThat(drift.getValue("BEDTIME")).isAtMost(45f)
    }

    /** How far each label after now sits from its time, in minutes, by phase or first habit name. */
    private fun minutesOff(layout: TimelineLayout, spec: TimelineSpec): Map<String, Float> {
        val now = layout.now!!
        val pxPerMinute = spec.pxPerHour / 60f
        return layout.phases.filter { it.anchorY > now.y }
            .associate { it.phase.type.name to abs(it.labelTop + spec.pillHeight / 2 - it.anchorY) / pxPerMinute } +
            layout.habits.filter { it.anchorY > now.y }
                .associate { it.habit.type.name to abs(it.labelTop + spec.habitHeight / 2 - it.anchorY) / pxPerMinute }
    }

    private fun TimelineLayout.rowOf(type: HabitType): HabitMark = habits.single { row -> row.habits.any { it.type == type } }

    /**
     * Each habit row's box (from the label column's start to its measured width, plus the curve clearance) misses every
     * segment of the curve's polyline. [sharedOnly]: only rows several habits share.
     */
    private fun assertClearOfCurve(name: String, layout: TimelineLayout, sharedOnly: Boolean) {
        val spec = layout.spec
        layout.habits.filter { !sharedOnly || it.habits.size > 1 }.forEach { row ->
            val top = row.labelTop
            val bottom = top + spec.habitHeight
            val right = layout.labelLeft + row.width + spec.curveClearance
            val hit = layout.curve.zipWithNext().firstOrNull { (a, b) -> segmentReaches(a, b, top, bottom, right) }
            assertWithMessage("$name: ${row.habits.map { it.type }} at $top..$bottom up to x $right meets the curve at $hit")
                .that(hit).isNull()
        }
    }

    /** Whether the segment a–b has a point with y in [top]..[bottom] and x at most [right]. */
    private fun segmentReaches(a: Offset, b: Offset, top: Float, bottom: Float, right: Float): Boolean {
        val from = maxOf(minOf(a.y, b.y), top)
        val to = minOf(maxOf(a.y, b.y), bottom)
        if (from > to) return false
        fun xAt(y: Float) = if (a.y == b.y) minOf(a.x, b.x) else a.x + (b.x - a.x) * (y - a.y) / (b.y - a.y)
        return minOf(xAt(from), xAt(to)) <= right
    }
}
