package com.sleepsaver.core.data.repository

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.testing.FakeSleepRepository
import com.sleepsaver.core.data.testing.FakeTimeProvider
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.data.testing.latencySource
import com.sleepsaver.core.data.testing.testNight
import com.sleepsaver.core.domain.journal.Anchor
import com.sleepsaver.core.domain.journal.AnchorSource
import com.sleepsaver.core.domain.patterns.PersonalLatency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset

class PersonalLatencySourceTest {

    private val today = LocalDate.of(2026, 10, 6)
    private val time = FakeTimeProvider(now = instant("2026-10-06T12:00:00Z"))
    private val sleep = FakeSleepRepository()
    private val anchors = MutableStateFlow<List<Anchor>>(emptyList())

    /** An anchor [before] the 23:00 onset of [wakeDate]'s night (negative = after it). */
    private fun anchor(wakeDate: LocalDate, before: Long) = Anchor(
        id = wakeDate.toEpochDay(),
        at = wakeDate.minusDays(1).atTime(23, 0).toInstant(ZoneOffset.UTC).minus(Duration.ofMinutes(before)),
        offset = ZoneOffset.UTC,
        wakeDate = wakeDate,
        source = AnchorSource.TONIGHT,
        wholeNightLog = false,
    )

    @Test
    fun `latencies are the valid ones, most recent night first`() = runTest {
        sleep.state.value = (0L..4L).map { testNight(today.minusDays(it)) }.reversed()
        anchors.value = listOf(
            anchor(today, before = 12),
            anchor(today.minusDays(1), before = 200), // more than 180 min before onset: not a latency
            anchor(today.minusDays(2), before = 22),
            // today − 3: no anchor
            anchor(today.minusDays(4), before = 15),
        )
        val source = latencySource(sleep, anchors, time)

        assertThat(source.latencies.first()).containsExactly(
            Duration.ofMinutes(12),
            Duration.ofMinutes(22),
            Duration.ofMinutes(15),
        ).inOrder()
        assertThat(source.latency.first()).isEqualTo(PersonalLatency(values = 3, median = Duration.ofMinutes(15), active = false))
    }

    @Test
    fun `the personal latency is active from seven values and follows new anchors`() = runTest {
        sleep.state.value = (0L..6L).map { testNight(today.minusDays(it)) }.reversed()
        anchors.value = (0L..5L).map { anchor(today.minusDays(it), before = 20) }
        val source = latencySource(sleep, anchors, time)
        assertThat(source.latency.first().active).isFalse()

        anchors.value = anchors.value + anchor(today.minusDays(6), before = 20)

        assertThat(source.latency.first { it.active }).isEqualTo(PersonalLatency(values = 7, median = Duration.ofMinutes(20), active = true))
    }

    @Test
    fun `nights without anchors or with an anchor long after onset give none`() = runTest {
        sleep.state.value = listOf(testNight(today), testNight(today.minusDays(1)))
        anchors.value = listOf(anchor(today, before = -45)) // 45 min after the watch saw sleep: not the night's anchor

        val source = latencySource(sleep, anchors, time)

        assertThat(source.latencies.first()).isEmpty()
        assertThat(source.latency.first()).isEqualTo(PersonalLatency(values = 0, median = null, active = false))
    }
}
