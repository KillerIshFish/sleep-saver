package com.sleepsaver.core.data.mapping

import com.sleepsaver.core.data.db.MorningFeelEntity
import com.sleepsaver.core.data.db.NightMarkEntity
import com.sleepsaver.core.data.db.NightStepLogEntity
import com.sleepsaver.core.data.db.PromptLogEntity
import com.sleepsaver.core.data.db.TomorrowListEntity
import com.sleepsaver.core.domain.journal.FeelSource
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.journal.MorningFeel
import com.sleepsaver.core.domain.journal.MorningFeelEntry
import com.sleepsaver.core.domain.journal.PromptKind
import com.sleepsaver.core.domain.journal.TomorrowList
import com.sleepsaver.core.domain.nightsteps.StepLog
import com.sleepsaver.core.domain.nightsteps.Technique
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

// ---------------------------------------------------------------- Night step logs

/** The technique is null for a custom activity and for a technique name this version doesn't know. */
fun NightStepLogEntity.toDomain(): StepLog = StepLog(
    nightOf = LocalDate.parse(nightOf),
    activityId = activityId,
    technique = enumOrNull<Technique>(techniqueKey),
    doneAt = Instant.ofEpochMilli(doneAtEpochMs),
    offset = ZoneOffset.ofTotalSeconds(offsetSec),
)

fun StepLog.toEntity(): NightStepLogEntity = NightStepLogEntity(
    nightOf = nightOf.toString(),
    activityId = activityId,
    techniqueKey = technique?.name,
    doneAtEpochMs = doneAt.toEpochMilli(),
    offsetSec = offset.totalSeconds,
)

// ---------------------------------------------------------------- Tomorrow list

fun TomorrowListEntity.toDomain(): TomorrowList = TomorrowList(
    nightOf = LocalDate.parse(nightOf),
    text = text,
    updatedAt = Instant.ofEpochMilli(updatedAtEpochMs),
    offset = ZoneOffset.ofTotalSeconds(offsetSec),
)

fun TomorrowList.toEntity(): TomorrowListEntity = TomorrowListEntity(
    nightOf = nightOf.toString(),
    text = text,
    updatedAtEpochMs = updatedAt.toEpochMilli(),
    offsetSec = offset.totalSeconds,
)

// ---------------------------------------------------------------- Marks

/** null for a mark this version doesn't know, and for a derived mark (those are computed, never stored). */
fun NightMarkEntity.toDomainOrNull(): Mark? = enumOrNull<Mark>(mark)?.takeIf { !it.derived }

/** The stored row for a mark the user set for [wakeDate]. Stored marks only. */
fun Mark.toEntity(wakeDate: LocalDate, createdAt: Instant, offset: ZoneOffset): NightMarkEntity {
    require(!derived) { "$name is derived and never stored" }
    return NightMarkEntity(
        wakeDate = wakeDate.toString(),
        mark = name,
        createdAtEpochMs = createdAt.toEpochMilli(),
        offsetSec = offset.totalSeconds,
    )
}

// ---------------------------------------------------------------- Morning feel

/** null for a level outside 1..5 or a source this version doesn't know. */
fun MorningFeelEntity.toDomainOrNull(): MorningFeelEntry? {
    val feel = MorningFeel.of(level) ?: return null
    val feelSource = enumOrNull<FeelSource>(source) ?: return null
    return MorningFeelEntry(
        wakeDate = LocalDate.parse(wakeDate),
        feel = feel,
        at = Instant.ofEpochMilli(atEpochMs),
        offset = ZoneOffset.ofTotalSeconds(offsetSec),
        source = feelSource,
    )
}

fun MorningFeelEntry.toEntity(): MorningFeelEntity = MorningFeelEntity(
    wakeDate = wakeDate.toString(),
    level = feel.level,
    atEpochMs = at.toEpochMilli(),
    offsetSec = offset.totalSeconds,
    source = source.name,
)

// ---------------------------------------------------------------- Prompt log

/** null for a prompt kind this version doesn't know. */
fun PromptLogEntity.toDomainOrNull(): PromptKind? = enumOrNull<PromptKind>(kind)

fun PromptKind.toEntity(wakeDate: LocalDate, shownAt: Instant, offset: ZoneOffset): PromptLogEntity = PromptLogEntity(
    wakeDate = wakeDate.toString(),
    kind = name,
    shownAtEpochMs = shownAt.toEpochMilli(),
    offsetSec = offset.totalSeconds,
)
