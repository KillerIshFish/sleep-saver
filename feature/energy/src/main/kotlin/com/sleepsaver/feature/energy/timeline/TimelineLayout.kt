package com.sleepsaver.feature.energy.timeline

import androidx.compose.ui.geometry.Offset
import com.sleepsaver.core.domain.model.AwakePeriod
import com.sleepsaver.core.domain.model.Phase
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.domain.model.ScheduledHabit
import com.sleepsaver.core.domain.model.SleepBlock
import com.sleepsaver.feature.energy.EnergyUiState
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** Pixel measurements of the timeline's columns and labels (left to right). */
data class TimelineSpec(
    val width: Float,
    val pxPerHour: Float,
    val topInset: Float,
    val bottomInset: Float,
    /** Phase bars start here; lane n sits at barsLeft + n · (barWidth + barGap). */
    val barsLeft: Float,
    val barWidth: Float,
    val barGap: Float,
    /** Space between the last phase lane and the label column. */
    val labelInset: Float,
    /** Night blocks span blockLeft..blockRight. */
    val blockLeft: Float,
    val blockRight: Float,
    /** Energy 0 → curveLeft, 1 → curveRight. Naps are drawn across this column only. */
    val curveLeft: Float,
    val curveRight: Float,
    val pillHeight: Float,
    /** Extra height of a phase label that shows its shift vs yesterday. */
    val captionHeight: Float,
    val habitHeight: Float,
    val nowHeight: Float,
    val labelGap: Float,
)

enum class TimelineBlockKind {
    /** Last night's main sleep: tap to edit, press and hold to add awake time. */
    MAIN,

    /** Another stretch of night sleep (an evening doze, going back to sleep, a sleep that started tonight). */
    EXTRA,
    NAP,

    /** Where last night's sleep will go once it syncs. */
    PENDING,
}

/** A time span and where it lands. */
data class TimelineSpan(val start: Instant, val end: Instant, val top: Float, val bottom: Float) {
    val duration: Duration get() = Duration.between(start, end)
    val height: Float get() = bottom - top
}

data class TimelineBlock(
    val kind: TimelineBlockKind,
    val wakeDate: LocalDate,
    val start: Instant,
    val end: Instant,
    val top: Float,
    val bottom: Float,
    val left: Float,
    val right: Float,
    val asleep: Duration,
    val startOffset: ZoneOffset?,
    val endOffset: ZoneOffset?,
    /** Interruptions drawn as darker bands, already clipped to the block and the range. */
    val awake: List<TimelineSpan>,
) {
    val height: Float get() = bottom - top
    fun contains(x: Float, y: Float): Boolean = x in left..right && y in top..bottom
}

data class PhaseMark(
    val phase: Phase,
    val lane: Int,
    val barX: Float,
    val top: Float,
    val bottom: Float,
    /** y of the phase start, where its connector points. */
    val anchorY: Float,
    val labelTop: Float,
    val labelHeight: Float,
    val showsShift: Boolean,
)

data class HabitMark(
    val habit: ScheduledHabit,
    /** On the curve at the habit's time (or the curve's baseline outside the curve). */
    val anchorX: Float,
    val anchorY: Float,
    val labelTop: Float,
)

data class NowMark(val time: Instant, val y: Float, val dotX: Float?, val labelTop: Float)

/** Everything the timeline draws, positioned. Pure: built from the state and a [TimelineSpec]. */
data class TimelineLayout(
    val spec: TimelineSpec,
    val geometry: TimelineGeometry,
    val scale: EnergyScale,
    val ticks: List<TimelineTick>,
    val wakeZones: List<TimelineSpan>,
    val melatonin: TimelineSpan?,
    val blocks: List<TimelineBlock>,
    val phases: List<PhaseMark>,
    val habits: List<HabitMark>,
    val now: NowMark?,
    val curve: List<Offset>,
    val labelLeft: Float,
) {
    val height: Float get() = geometry.height
    val mainBlock: TimelineBlock? get() = blocks.firstOrNull { it.kind == TimelineBlockKind.MAIN }
}

private val InterruptionMin: Duration = Duration.ofMinutes(5)

/** A shift under a minute isn't worth a caption on the timeline. */
fun Phase.hasShift(): Boolean = shiftVsYesterday?.let { it.abs() >= Duration.ofMinutes(1) } == true

fun buildTimelineLayout(state: EnergyUiState.Ready, spec: TimelineSpec): TimelineLayout {
    val schedule = state.schedule
    val range = TimelineRange.of(schedule, state.lastNight, state.sleepNeed, state.now)
    val geometry = TimelineGeometry(range, spec.pxPerHour, spec.topInset, spec.bottomInset)
    val scale = EnergyScale(spec.curveLeft, spec.curveRight)

    fun span(start: Instant, end: Instant): TimelineSpan? {
        if (!range.overlaps(start, end)) return null
        return TimelineSpan(start, end, geometry.yOfClamped(start), geometry.yOfClamped(end))
    }

    val blocks = buildBlocks(state, range, geometry, spec)

    // Phases: slim bars in lanes beside the axis.
    val phases = schedule.phases.filter { range.overlaps(it.start, it.end) }
    val lanes = assignLanes(phases.map { it.start to it.end })
    val laneCount = (lanes.maxOrNull() ?: 0) + 1
    val labelLeft = spec.barsLeft + laneCount * (spec.barWidth + spec.barGap) - spec.barGap + spec.labelInset

    // Labels: phase pills and habit markers share one column, around the fixed now pill and the night blocks.
    val habits = state.timelineHabits.filter { range.contains(it.time) }
    val nowInRange = range.contains(state.now)
    val slots = buildList {
        phases.forEach { phase ->
            val height = spec.pillHeight + if (phase.hasShift()) spec.captionHeight else 0f
            add(LabelSlot(geometry.yOfClamped(phase.start) - spec.pillHeight / 2, height))
        }
        habits.forEach { add(LabelSlot(geometry.yOf(it.time) - spec.habitHeight / 2, spec.habitHeight)) }
        if (nowInRange) add(LabelSlot(geometry.yOf(state.now) - spec.nowHeight / 2, spec.nowHeight, fixed = true))
        blocks.filter { it.kind != TimelineBlockKind.NAP }.forEach { add(LabelSlot(it.top, it.height, fixed = true)) }
    }
    val tops = placeLabels(slots, minTop = 0f, maxBottom = geometry.height, gap = spec.labelGap)

    val phaseMarks = phases.mapIndexed { i, phase ->
        PhaseMark(
            phase = phase,
            lane = lanes[i],
            barX = spec.barsLeft + lanes[i] * (spec.barWidth + spec.barGap),
            top = geometry.yOfClamped(phase.start),
            bottom = geometry.yOfClamped(phase.end),
            anchorY = geometry.yOfClamped(phase.start),
            labelTop = tops[i],
            labelHeight = slots[i].height,
            showsShift = phase.hasShift(),
        )
    }
    val habitMarks = habits.mapIndexed { i, habit ->
        HabitMark(
            habit = habit,
            anchorX = schedule.curve.valueAt(habit.time)?.let(scale::xOf) ?: scale.left,
            anchorY = geometry.yOf(habit.time),
            labelTop = tops[phases.size + i],
        )
    }
    val now = if (nowInRange) {
        NowMark(
            time = state.now,
            y = geometry.yOf(state.now),
            dotX = schedule.curve.valueAt(state.now)?.let(scale::xOf),
            labelTop = tops[phases.size + habits.size],
        )
    } else {
        null
    }

    val curve = schedule.curve.points
        .filter { range.contains(it.time) }
        .map { Offset(scale.xOf(it.value), geometry.yOf(it.time)) }

    return TimelineLayout(
        spec = spec,
        geometry = geometry,
        scale = scale,
        ticks = geometry.ticks(state.zone),
        wakeZones = listOf(schedule.wakeZone, schedule.tomorrowWakeZone).mapNotNull { span(it.start, it.end) },
        melatonin = schedule.phase(PhaseType.MELATONIN_WINDOW)?.let { span(it.start, it.end) },
        blocks = blocks,
        phases = phaseMarks,
        habits = habitMarks,
        now = now,
        curve = curve,
        labelLeft = labelLeft,
    )
}

private fun buildBlocks(
    state: EnergyUiState.Ready,
    range: TimelineRange,
    geometry: TimelineGeometry,
    spec: TimelineSpec,
): List<TimelineBlock> {
    val out = mutableListOf<TimelineBlock>()

    fun add(
        kind: TimelineBlockKind,
        wakeDate: LocalDate,
        start: Instant,
        end: Instant,
        asleep: Duration,
        startOffset: ZoneOffset?,
        endOffset: ZoneOffset?,
        awake: List<AwakePeriod> = emptyList(),
    ) {
        if (!end.isAfter(start) || !range.overlaps(start, end)) return
        val nap = kind == TimelineBlockKind.NAP
        out += TimelineBlock(
            kind = kind,
            wakeDate = wakeDate,
            start = start,
            end = end,
            top = geometry.yOfClamped(start),
            bottom = geometry.yOfClamped(end),
            left = if (nap) spec.curveLeft else spec.blockLeft,
            right = spec.blockRight,
            asleep = asleep,
            startOffset = startOffset,
            endOffset = endOffset,
            awake = awake
                .mapNotNull { period ->
                    val from = maxOf(period.start, start, range.start)
                    val to = minOf(period.end, end, range.end)
                    if (to.isAfter(from)) TimelineSpan(from, to, geometry.yOf(from), geometry.yOf(to)) else null
                }
                .sortedBy { it.start },
        )
    }

    fun SleepBlock.addAs(kind: TimelineBlockKind, wakeDate: LocalDate) = add(
        kind = kind,
        wakeDate = wakeDate,
        start = start,
        end = end,
        asleep = asleep,
        startOffset = startOffset,
        endOffset = endOffset,
        awake = awakePeriods.filter { it.duration >= InterruptionMin },
    )

    val lastNight = state.lastNight
    if (lastNight != null) {
        val start = lastNight.fellAsleep ?: lastNight.main?.start
        val end = lastNight.wokeUp ?: lastNight.main?.end
        if (start != null && end != null) {
            add(
                kind = TimelineBlockKind.MAIN,
                wakeDate = lastNight.wakeDate,
                start = start,
                end = end,
                asleep = lastNight.mainSleep,
                startOffset = lastNight.fellAsleepOffset,
                endOffset = lastNight.wokeUpOffset,
                awake = lastNight.interruptions,
            )
        }
    } else {
        val wake = state.schedule.typicalWake.toInstant()
        add(TimelineBlockKind.PENDING, state.day, wake - state.sleepNeed, wake, state.sleepNeed, null, null)
    }

    // Everything else that falls inside the range: extra segments and naps, the latest available nights,
    // and a sleep that already started tonight.
    val nights = (state.nights + listOfNotNull(lastNight)).distinctBy { it.wakeDate }
    for (night in nights) {
        val isLastNight = lastNight != null && night.wakeDate == lastNight.wakeDate
        if (!isLastNight) night.main?.addAs(TimelineBlockKind.EXTRA, night.wakeDate)
        night.extraSegments.forEach { it.addAs(TimelineBlockKind.EXTRA, night.wakeDate) }
        night.naps.forEach { it.addAs(TimelineBlockKind.NAP, night.wakeDate) }
    }
    // A pending block is only a placeholder: drop it if real sleep already covers that slot.
    val pending = out.firstOrNull { it.kind == TimelineBlockKind.PENDING }
    if (pending != null && out.any { it.kind == TimelineBlockKind.EXTRA && it.top < pending.bottom && it.bottom > pending.top }) {
        out.remove(pending)
    }
    return out.sortedBy { it.start }
}
