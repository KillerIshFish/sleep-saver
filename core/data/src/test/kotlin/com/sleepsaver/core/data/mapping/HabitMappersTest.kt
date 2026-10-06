package com.sleepsaver.core.data.mapping

import com.google.common.truth.Truth.assertThat
import com.sleepsaver.core.data.db.HabitEntity
import com.sleepsaver.core.data.db.MorningReviewEntity
import com.sleepsaver.core.data.db.RoutineActivityEntity
import com.sleepsaver.core.data.repository.MorningReview
import com.sleepsaver.core.data.repository.ReviewStatus
import com.sleepsaver.core.data.testing.instant
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.RoutineActivity
import org.junit.Test
import java.time.Duration
import java.time.LocalDate

class HabitMappersTest {

    @Test
    fun `habit settings round-trip with a negative offset`() {
        val settings = HabitSettings(HabitType.CAFFEINE_CUTOFF, enabled = true, reminderEnabled = false, offset = Duration.ofHours(-10))

        val entity = settings.toEntity(sortOrder = 1)

        assertThat(entity).isEqualTo(HabitEntity("CAFFEINE_CUTOFF", true, false, -600, 1))
        assertThat(entity.toDomainOrNull()).isEqualTo(settings)
    }

    @Test
    fun `unknown habit type is dropped`() {
        assertThat(HabitEntity("POWER_POSE", true, true, 0, 0).toDomainOrNull()).isNull()
    }

    @Test
    fun `routine activity round-trips`() {
        val activity = RoutineActivity(id = 4, label = "Puzzles", emoji = "🧩", isCustom = true, selected = true)

        val entity = activity.toEntity(sortOrder = 9)

        assertThat(entity).isEqualTo(RoutineActivityEntity(4, "Puzzles", "🧩", isCustom = true, selected = true, sortOrder = 9))
        assertThat(entity.toDomain()).isEqualTo(activity)
    }

    @Test
    fun `morning review round-trips, with and without debts`() {
        val confirmed = MorningReview(
            wakeDate = LocalDate.of(2026, 10, 6),
            status = ReviewStatus.CONFIRMED,
            at = instant("2026-10-06T07:40:00Z"),
            debtBefore = Duration.ofMinutes(200),
            debtAfter = Duration.ofMinutes(185),
        )
        val dismissed = confirmed.copy(status = ReviewStatus.DISMISSED, debtBefore = null, debtAfter = null)

        assertThat(confirmed.toEntity()).isEqualTo(MorningReviewEntity("2026-10-06", "CONFIRMED", confirmed.at.toEpochMilli(), 200, 185))
        assertThat(confirmed.toEntity().toDomainOrNull()).isEqualTo(confirmed)
        assertThat(dismissed.toEntity().toDomainOrNull()).isEqualTo(dismissed)
    }

    @Test
    fun `unknown review status is dropped`() {
        assertThat(MorningReviewEntity("2026-10-06", "SNOOZED", 0, null, null).toDomainOrNull()).isNull()
    }
}
