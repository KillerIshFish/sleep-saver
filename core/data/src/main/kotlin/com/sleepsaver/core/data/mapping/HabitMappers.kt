package com.sleepsaver.core.data.mapping

import com.sleepsaver.core.data.db.HabitEntity
import com.sleepsaver.core.data.db.MorningReviewEntity
import com.sleepsaver.core.data.db.RoutineActivityEntity
import com.sleepsaver.core.data.repository.MorningReview
import com.sleepsaver.core.data.repository.ReviewStatus
import com.sleepsaver.core.domain.model.HabitSettings
import com.sleepsaver.core.domain.model.HabitType
import com.sleepsaver.core.domain.model.RoutineActivity
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** null for a habit type this version doesn't know. */
fun HabitEntity.toDomainOrNull(): HabitSettings? {
    val habitType = HabitType.entries.firstOrNull { it.name == type } ?: return null
    return HabitSettings(
        type = habitType,
        enabled = enabled,
        reminderEnabled = reminderEnabled,
        offset = Duration.ofMinutes(offsetMinutes.toLong()),
    )
}

fun HabitSettings.toEntity(sortOrder: Int): HabitEntity = HabitEntity(
    type = type.name,
    enabled = enabled,
    reminderEnabled = reminderEnabled,
    offsetMinutes = offset.toMinutes().toInt(),
    sortOrder = sortOrder,
)

fun RoutineActivityEntity.toDomain(): RoutineActivity = RoutineActivity(
    id = id,
    label = label,
    emoji = emoji,
    isCustom = isCustom,
    selected = selected,
)

fun RoutineActivity.toEntity(sortOrder: Int): RoutineActivityEntity = RoutineActivityEntity(
    id = id,
    label = label,
    emoji = emoji,
    isCustom = isCustom,
    selected = selected,
    sortOrder = sortOrder,
)

/** null for a status this version doesn't know. */
fun MorningReviewEntity.toDomainOrNull(): MorningReview? {
    val reviewStatus = ReviewStatus.entries.firstOrNull { it.name == status } ?: return null
    return MorningReview(
        wakeDate = LocalDate.parse(wakeDate),
        status = reviewStatus,
        at = Instant.ofEpochMilli(atEpochMs),
        debtBefore = debtBeforeMin?.let { Duration.ofMinutes(it.toLong()) },
        debtAfter = debtAfterMin?.let { Duration.ofMinutes(it.toLong()) },
    )
}

fun MorningReview.toEntity(): MorningReviewEntity = MorningReviewEntity(
    wakeDate = wakeDate.toString(),
    status = status.name,
    atEpochMs = at.toEpochMilli(),
    debtBeforeMin = debtBefore?.toMinutes()?.toInt(),
    debtAfterMin = debtAfter?.toMinutes()?.toInt(),
)
