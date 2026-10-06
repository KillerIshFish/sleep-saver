package com.sleepsaver.core.data.repository

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.testing.FakeInsightsRepository
import com.sleepsaver.core.data.testing.FakeSettingsRepository
import com.sleepsaver.core.data.testing.inMemoryDatabase
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.data.testing.testInsights
import com.sleepsaver.core.domain.model.LastNightState
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
class ReviewRepositoryImplTest {

    private val db = inMemoryDatabase(ApplicationProvider.getApplicationContext())
    private val settings = FakeSettingsRepository()
    private val insights = FakeInsightsRepository()
    private val repository = ReviewRepositoryImpl(db, settings, insights)
    private val today = LocalDate.of(2026, 10, 6)

    @After
    fun tearDown() = db.close()

    private fun review(status: ReviewStatus = ReviewStatus.CONFIRMED) = MorningReview(
        wakeDate = today,
        status = status,
        at = instant("2026-10-06T07:45:00Z"),
        debtBefore = Duration.ofMinutes(190),
        debtAfter = Duration.ofMinutes(170),
    )

    @Test
    fun `recorded review is observable and can be replaced`() = runTest {
        assertThat(repository.review(today).first()).isNull()

        repository.record(review())
        assertThat(repository.review(today).first()).isEqualTo(review())

        repository.record(review(ReviewStatus.ADJUSTED))
        assertThat(repository.review(today).first()?.status).isEqualTo(ReviewStatus.ADJUSTED)
        assertThat(repository.review(today.minusDays(1)).first()).isNull()
    }

    @Test
    fun `review is pending when enabled, last night is in and not yet reviewed`() = runTest {
        insights.mutableState.value = InsightsState.Ready(testInsights(today, LastNightState.AVAILABLE))

        assertThat(repository.pendingReview.first()).isEqualTo(today)

        repository.record(review(ReviewStatus.DISMISSED))
        assertThat(repository.pendingReview.first { it == null }).isNull()
    }

    @Test
    fun `no pending review while loading, waiting or disabled`() = runTest {
        assertThat(repository.pendingReview.first()).isNull()

        insights.mutableState.value = InsightsState.NoNights
        assertThat(repository.pendingReview.first()).isNull()

        insights.mutableState.value = InsightsState.Ready(testInsights(today, LastNightState.WAITING))
        assertThat(repository.pendingReview.first()).isNull()

        insights.mutableState.value = InsightsState.Ready(testInsights(today, LastNightState.AVAILABLE))
        settings.state.update { it.copy(morningReviewEnabled = false) }
        assertThat(repository.pendingReview.first()).isNull()
    }
}
