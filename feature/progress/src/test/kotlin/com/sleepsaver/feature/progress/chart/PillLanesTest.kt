package com.sleepsaver.feature.progress.chart

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class PillLanesTest {

    /** 14 columns of 23.5 px: a phone's Sleep times plot in dp. */
    private val plot = 14 * 23.5f
    private val gap = 4f

    private fun widths(vararg pills: Pair<Int, Float>): List<Float?> =
        List(14) { index -> pills.firstOrNull { it.first == index }?.second }

    /** Left and right edges of each pill, by lane, from where [pillSlots] put them. */
    private fun edges(widths: List<Float?>, slots: List<PillSlot?>): Map<Int, List<Pair<Float, Float>>> {
        val column = plot / widths.size
        return widths.indices.filter { slots[it] != null }.groupBy({ slots[it]!!.lane }) { index ->
            val left = (index + 0.5f) * column - widths[index]!! / 2 + slots[index]!!.shift
            left to left + widths[index]!!
        }
    }

    @Test
    fun `a lone pill stays centred on its column in the first lane`() {
        val slots = pillSlots(widths(5 to 26f), plot, gap)

        assertThat(slots[5]).isEqualTo(PillSlot(lane = 0, shift = 0f))
        assertThat(slots.count { it != null }).isEqualTo(1)
        assertThat(pillLaneCount(slots)).isEqualTo(1)
    }

    @Test
    fun `neighbouring pills stagger, the next one returns to the first lane`() {
        // "33m", "39m" and "36m" on three nights in a row: each is wider than its column.
        val slots = pillSlots(widths(2 to 26f, 3 to 26f, 4 to 26f), plot, gap)

        assertThat(slots.subList(2, 5).map { it?.lane }).containsExactly(0, 1, 0).inOrder()
        assertThat(pillLaneCount(slots)).isEqualTo(2)
    }

    @Test
    fun `pills far enough apart share the first lane`() {
        val slots = pillSlots(widths(1 to 26f, 4 to 26f, 9 to 38f), plot, gap)

        assertThat(slots.filterNotNull().map { it.lane }).containsExactly(0, 0, 0)
        assertThat(pillLaneCount(slots)).isEqualTo(1)
    }

    @Test
    fun `no two pills in a lane come closer than the gap, even on a week of naps`() {
        val dense = List(14) { index -> listOf(26f, 38f, 30f, 44f)[index % 4] }

        val slots = pillSlots(dense, plot, gap)

        assertThat(slots).doesNotContain(null)
        edges(dense, slots).values.forEach { lane ->
            lane.sortedBy { it.first }.zipWithNext().forEach { (a, b) -> assertThat(b.first - a.second).isAtLeast(gap - 1e-3f) }
        }
        assertThat(pillLaneCount(slots)).isAtMost(3)
    }

    @Test
    fun `pills at the edges are nudged inside the plot`() {
        val slots = pillSlots(widths(0 to 38f, 13 to 38f), plot, gap)

        val lanes = edges(widths(0 to 38f, 13 to 38f), slots).values.flatten()
        assertThat(lanes.minOf { it.first }).isAtLeast(0f)
        assertThat(lanes.maxOf { it.second }).isAtMost(plot + 1e-3f)
        assertThat(slots[0]!!.shift).isGreaterThan(0f)
        assertThat(slots[13]!!.shift).isLessThan(0f)
    }

    @Test
    fun `no pills, no lanes`() {
        assertThat(pillLaneCount(pillSlots(List(14) { null }, plot, gap))).isEqualTo(0)
        assertThat(pillSlots(emptyList(), plot, gap)).isEmpty()
    }
}
