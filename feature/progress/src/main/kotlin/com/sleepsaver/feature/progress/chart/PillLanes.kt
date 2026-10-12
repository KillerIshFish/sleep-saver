package com.sleepsaver.feature.progress.chart

/**
 * Where one column's nap pill sits under the Sleep times plot: [lane] 0 is the row right under the plot, 1 the row
 * below it, and so on; [shift] moves the pill sideways from its column's centre (px, + = right) to keep it inside the
 * plot.
 */
data class PillSlot(val lane: Int, val shift: Float)

/**
 * Lays out the nap pills of a chart's columns so that no two touch. A pill (usually wider than its column on a
 * 14-column chart: "1h 19m" spans almost two) is centred on its column, nudged inside 0..[plotWidth], and takes the
 * first lane where it stays [gap] clear of the pill before it. Neighbouring pills therefore stagger into a second
 * lane, and a pill two columns on goes back to the first.
 *
 * [widths] holds each column's pill width in px (null = no pill); columns are `plotWidth / widths.size` wide.
 * Returns one slot per column (null where there's no pill).
 *
 * Example: three 26 px pills over 23.5 px columns (gap 4) → lanes 0, 1, 0.
 */
fun pillSlots(widths: List<Float?>, plotWidth: Float, gap: Float): List<PillSlot?> {
    if (widths.isEmpty()) return emptyList()
    val columnWidth = plotWidth / widths.size
    val lefts = widths.mapIndexed { index, width ->
        width?.let {
            val centred = (index + 0.5f) * columnWidth - it / 2
            centred.coerceIn(0f, (plotWidth - it).coerceAtLeast(0f))
        }
    }
    val slots = arrayOfNulls<PillSlot>(widths.size)
    // Right edge of the last pill placed in each lane. First fit in order of left edges keeps the lanes to a minimum.
    val laneEnds = mutableListOf<Float>()
    widths.indices.filter { widths[it] != null }.sortedBy { lefts[it] }.forEach { index ->
        val width = widths[index]!!
        val left = lefts[index]!!
        val lane = laneEnds.indexOfFirst { end -> end + gap <= left }.takeIf { it >= 0 } ?: laneEnds.size
        if (lane == laneEnds.size) laneEnds += left + width else laneEnds[lane] = left + width
        slots[index] = PillSlot(lane, left - ((index + 0.5f) * columnWidth - width / 2))
    }
    return slots.toList()
}

/** How many lanes [slots] use: 0 without any pill. */
fun pillLaneCount(slots: List<PillSlot?>): Int = slots.maxOfOrNull { it?.lane ?: -1 }?.plus(1) ?: 0
