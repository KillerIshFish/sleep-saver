package com.sleepsaver.feature.sleep

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.repository.AppSettings
import com.sleepsaver.core.data.repository.SyncState
import com.sleepsaver.core.domain.model.PhaseType
import com.sleepsaver.core.testing.SampleData
import org.junit.Test
import java.time.LocalTime
import java.time.ZonedDateTime

class SleepUiStateTest {

    private fun stateAt(hour: Int, minute: Int): SleepUiState.Ready {
        val now = ZonedDateTime.of(SampleData.today, LocalTime.of(hour, minute), SampleData.zone).toInstant()
        return readyState(
            insights = SampleData.insights(now = now),
            settings = AppSettings(),
            sync = SyncState(),
            habitSettings = SampleData.habits,
            pendingReview = null,
            source = SourceLabel.Garmin,
        )
    }

    @Test
    fun `late at night with nothing left today the strip shows tonight's wind-down and melatonin window`() {
        val state = stateAt(23, 55)

        assertThat(state.phasesAreTonight).isTrue()
        assertThat(state.phases.map { it.phase.type }).containsExactly(PhaseType.WIND_DOWN, PhaseType.MELATONIN_WINDOW).inOrder()
        assertThat(state.phases.none { it.isCurrent }).isTrue()
    }

    @Test
    fun `during the day the current phase leads and is the only highlighted one`() {
        val state = stateAt(10, 20)

        assertThat(state.phasesAreTonight).isFalse()
        assertThat(state.phases.count { it.isCurrent }).isEqualTo(1)
        assertThat(state.phases.first().isCurrent).isTrue()
        assertThat(state.phases.map { it.phase.start }).isInOrder()
    }

    @Test
    fun `times and durations don't split across lines`() {
        assertThat("8h 35m".unbreakable()).isEqualTo("8h 35m")
        assertThat("9:30 AM – 12:30 PM".keepTimesTogether()).isEqualTo("9:30 AM – 12:30 PM")
        assertThat("21:30 – 23:00".keepTimesTogether()).isEqualTo("21:30 – 23:00")
    }

    @Test
    fun `header date reads like a calendar line`() {
        assertThat(formatHeaderDate(SampleData.today)).isEqualTo("Tuesday, Oct 6")
    }

    @Test
    fun `before waking every phase is upcoming`() {
        val state = stateAt(5, 0)

        assertThat(state.phases.none { it.isCurrent }).isTrue()
        assertThat(state.phases.first().phase.type).isEqualTo(PhaseType.GROGGINESS)
    }
}
