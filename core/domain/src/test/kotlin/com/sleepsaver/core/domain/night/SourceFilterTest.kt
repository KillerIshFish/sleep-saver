package com.sleepsaver.core.domain.night

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.testing.Fx
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SourceFilterTest {

    private val filter = SourceFilter()
    private val day = LocalDate.of(2026, 10, 6)
    private val prev = day.minusDays(1)
    private val third = "com.example.thirdtracker"

    private fun session(
        id: String,
        start: String,
        end: String,
        origin: String = Fx.GARMIN,
        withStages: Boolean = false,
    ): RawSleepSession {
        val startDate = if (start >= "14:00") prev else day
        val s = Fx.dt(startDate, start)
        val e = Fx.dt(day, end)
        val stages = if (withStages) listOf(Fx.LocalStage(s, e, SleepStageType.LIGHT)) else emptyList()
        return Fx.session(id, s, e, origin = origin, stages = stages)
    }

    private fun ids(sessions: List<RawSleepSession>, selection: SourceSelection = SourceSelection.All) =
        filter.apply(sessions, selection).map { it.id }

    @Test
    fun `single keeps only the chosen app`() {
        val garmin = session("g", "23:00", "07:00")
        val other = session("o", "23:00", "07:00", origin = Fx.OTHER)
        val nap = session("o-nap", "13:00", "13:40", origin = Fx.OTHER)

        assertThat(ids(listOf(garmin, other, nap), SourceSelection.Single(Fx.GARMIN))).containsExactly("g")
        assertThat(ids(listOf(garmin, other, nap), SourceSelection.Single(Fx.OTHER)))
            .containsExactly("o", "o-nap").inOrder()
        assertThat(ids(listOf(garmin, other), SourceSelection.Single("com.example.unknown"))).isEmpty()
    }

    @Test
    fun `single keeps overlapping sessions from the chosen app`() {
        val a = session("a", "23:00", "03:00")
        val b = session("b", "02:00", "07:00")

        assertThat(ids(listOf(a, b), SourceSelection.Single(Fx.GARMIN))).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `all keeps Garmin over an overlapping session from another app`() {
        val garmin = session("g", "23:00", "07:00")
        val other = session("o", "22:45", "07:30", origin = Fx.OTHER, withStages = true)

        assertThat(ids(listOf(other, garmin))).containsExactly("g")
    }

    @Test
    fun `all keeps sessions that do not overlap`() {
        val garmin = session("g", "23:00", "07:00")
        val otherNap = session("o", "13:00", "13:40", origin = Fx.OTHER)

        assertThat(ids(listOf(garmin, otherNap))).containsExactly("g", "o").inOrder()
    }

    @Test
    fun `all de-duplicates at half of the shorter session but not below`() {
        val garmin = session("g", "23:00", "07:00")
        val halfOverlap = session("half", "06:00", "08:00", origin = Fx.OTHER)
        val quarterOverlap = session("quarter", "06:30", "08:30", origin = Fx.OTHER)

        assertThat(ids(listOf(garmin, halfOverlap))).containsExactly("g")
        assertThat(ids(listOf(garmin, quarterOverlap))).containsExactly("g", "quarter").inOrder()
    }

    @Test
    fun `all never de-duplicates sessions from the same app`() {
        val a = session("a", "23:00", "03:00")
        val b = session("b", "23:30", "07:00")

        assertThat(ids(listOf(a, b))).containsExactly("a", "b").inOrder()
    }

    @Test
    fun `whole-night session from another app is dropped against Garmin fragments`() {
        val g1 = session("g1", "23:00", "03:00")
        val g2 = session("g2", "03:30", "07:00")
        val other = session("o", "23:00", "07:00", origin = Fx.OTHER, withStages = true)

        assertThat(ids(listOf(other, g1, g2))).containsExactly("g1", "g2").inOrder()
    }

    @Test
    fun `without Garmin the session with stages wins`() {
        val longer = session("longer", "22:00", "08:00", origin = Fx.OTHER)
        val staged = session("staged", "23:00", "07:00", origin = third, withStages = true)

        assertThat(ids(listOf(longer, staged))).containsExactly("staged")
    }

    @Test
    fun `then the longer session wins`() {
        val shorter = session("shorter", "23:30", "07:00", origin = Fx.OTHER)
        val longer = session("longer", "23:00", "07:00", origin = third)

        assertThat(ids(listOf(shorter, longer))).containsExactly("longer")
    }

    @Test
    fun `then the newer lastModified wins`() {
        val older = session("older", "23:00", "07:00", origin = Fx.OTHER)
        val newer = session("newer", "23:00", "07:00", origin = third)
            .let { it.copy(lastModified = it.lastModified.plusSeconds(60)) }

        assertThat(ids(listOf(older, newer))).containsExactly("newer")
    }

    @Test
    fun `then the id breaks ties`() {
        val b = session("b", "23:00", "07:00", origin = Fx.OTHER)
        val a = session("a", "23:00", "07:00", origin = third)

        assertThat(ids(listOf(b, a))).containsExactly("a")
        assertThat(ids(listOf(a, b))).containsExactly("a")
    }

    @Test
    fun `preferred origin comes from the config`() {
        val garmin = session("g", "23:00", "07:00", withStages = true)
        val other = session("o", "23:00", "07:00", origin = Fx.OTHER)

        val result = SourceFilter(SleepConfig(preferredOrigin = Fx.OTHER)).apply(listOf(garmin, other), SourceSelection.All)

        assertThat(result.map { it.id }).containsExactly("o")
    }

    @Test
    fun `empty input stays empty`() {
        assertThat(filter.apply(emptyList(), SourceSelection.All)).isEmpty()
        assertThat(filter.apply(emptyList(), SourceSelection.Single(Fx.GARMIN))).isEmpty()
    }
}
