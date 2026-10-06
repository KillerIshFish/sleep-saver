package com.sleepsaver.core.domain.night

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.AddedAwake
import com.sleepsaver.core.domain.model.AwakeKind
import com.sleepsaver.core.domain.model.AwakePeriod
import com.sleepsaver.core.domain.model.BlockKind
import com.sleepsaver.core.domain.model.ManualNap
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SleepBlock
import com.sleepsaver.core.domain.model.SleepStage
import com.sleepsaver.core.domain.model.SleepStageType
import com.sleepsaver.core.domain.model.SourceSelection
import com.sleepsaver.core.domain.model.StageTotals
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Turns raw sessions + user edits into one [Night] per wake date (docs/PLAN.md §3.1).
 *
 * Steps:
 *  1. Drop sessions shorter than `minSessionDuration`; apply [SourceFilter].
 *  2. Classify: nap = duration < `napMaxDuration` AND local start in [napWindowStart, napWindowEnd).
 *     Naps belong to the night whose wakeDate == local start date of the nap.
 *  3. Night sessions get a wake date via [wakeDateOf].
 *  4. Per wake date: sort, merge neighbours whose gap < `fragmentMergeMaxGap` into clusters (gap → AwakePeriod GAP).
 *     Main = cluster with the most asleep time; the rest are EXTRA blocks.
 *  5. Asleep time of a session = duration − awake-type stages (clipped to the session). No stages → all asleep.
 *  6. Apply [NightOverride]s, [ManualNap]s and [AddedAwake] (§3.1 "Overrides"). An override with
 *     isManualNight = true and no raw data creates a manual main block from fellAsleep..wokeUp.
 *  7. Interruptions = awake STAGE periods ≥ `interruptionMinDuration` + all GAP + all ADDED periods inside main.
 *
 * Details:
 *  - Time is counted on interval unions, so overlapping sessions or awake stages are never counted twice.
 *    Inside a block, time not covered by an awake period (UNKNOWN, SLEEPING, no stage) is asleep.
 *  - Main window = [fellAsleep ?: main.start, wokeUp ?: main.end] (an inverted override window is ignored);
 *    stages and awake periods are clipped to it, [AddedAwake] is added (also without an override row),
 *    awakeInMain = awakeMinutes ?: union of the awake periods, mainSleep = max(0, window − awakeInMain).
 *    EXTRA blocks overlapping the window are dropped (absorbed into main).
 *  - napSleep = napMinutes ?: union of the asleep time of detected and manual naps.
 *  - [StageTotals] cover asleep time only for deep/light/rem/unclassified (DEEP > REM > LIGHT where stages
 *    overlap); `awake` is the awake-type stage time in the window. Null when main has no stages.
 *  - Dates with only naps or only an override still produce a [Night] (with main == null unless it's a
 *    manual night).
 */
class NightAssembler(private val config: SleepConfig = SleepConfig.DEFAULT) {

    private val sourceFilter = SourceFilter(config)

    /** @return nights sorted by wakeDate ascending; only dates that have some sleep or an override. */
    fun assemble(
        sessions: List<RawSleepSession>,
        source: SourceSelection,
        overrides: List<NightOverride> = emptyList(),
        manualNaps: List<ManualNap> = emptyList(),
        addedAwake: List<AddedAwake> = emptyList(),
    ): List<Night> {
        val usable = sessions.filter { it.end > it.start && it.duration >= config.minSessionDuration }
        val (napSessions, nightSessions) = sourceFilter.apply(usable, source).partition(::isNap)

        val clustersByDate = nightSessions.groupBy(::wakeDateOf).mapValues { (_, list) -> clusters(list) }
        val napsByDate = (
            napSessions.map { it.localStart.toLocalDate() to blockOf(BlockKind.NAP, listOf(it)) } +
                manualNaps.filter { it.end > it.start }
                    .map { LocalDateTime.ofInstant(it.start, it.offset).toLocalDate() to manualNapBlock(it) }
            ).groupBy({ it.first }, { it.second })
        val overrideByDate = overrides.groupBy { it.wakeDate }.mapValues { (_, list) -> list.maxBy { it.updatedAt } }
        val addedByDate = addedAwake.filter { it.end > it.start }.groupBy { it.wakeDate }

        return (clustersByDate.keys + napsByDate.keys + overrideByDate.keys).sorted().map { date ->
            night(
                date = date,
                clusters = clustersByDate[date].orEmpty(),
                naps = napsByDate[date].orEmpty(),
                override = overrideByDate[date],
                added = addedByDate[date].orEmpty(),
            )
        }
    }

    /** True when [session] is a nap under the config rules. */
    fun isNap(session: RawSleepSession): Boolean {
        val start = session.localStart.toLocalTime()
        return session.duration < config.napMaxDuration && start >= config.napWindowStart && start < config.napWindowEnd
    }

    /**
     * Wake date of a non-nap session (local end e, local start s, cutoff 14:00):
     *  - e.time < cutoff → e.date
     *  - s.date == e.date && s.time ≥ cutoff → e.date + 1 (evening sleep that hasn't crossed midnight)
     *  - else → e.date
     */
    fun wakeDateOf(session: RawSleepSession): LocalDate {
        val end = session.localEnd
        val start = session.localStart
        val cutoff = config.nightEndCutoff
        return when {
            end.toLocalTime() < cutoff -> end.toLocalDate()
            start.toLocalDate() == end.toLocalDate() && start.toLocalTime() >= cutoff -> end.toLocalDate().plusDays(1)
            else -> end.toLocalDate()
        }
    }

    private fun night(
        date: LocalDate,
        clusters: List<SleepBlock>,
        naps: List<SleepBlock>,
        override: NightOverride?,
        added: List<AddedAwake>,
    ): Night {
        val rawMain = clusters.maxWithOrNull(compareBy<SleepBlock> { it.asleep }.thenBy { it.end })
        val addedPeriods = added.map { AwakePeriod(it.start, it.end, AwakeKind.ADDED) }
        val main = when {
            rawMain != null -> effectiveMain(rawMain, override, addedPeriods)
            override?.isManualNight == true -> manualMain(date, override, addedPeriods)
            else -> null
        }
        val block = main?.block
        val extras = clusters
            .filter { it !== rawMain }
            .map { it.copy(kind = BlockKind.EXTRA) }
            .filter { block == null || it.end <= block.start || it.start >= block.end }
        val sortedNaps = naps.sortedWith(compareBy({ it.start }, { it.end }))

        val mainSleep = block?.asleep ?: Duration.ZERO
        val extraSleep = extras.fold(Duration.ZERO) { acc, extra -> acc + extra.asleep }
        val napSleep = override?.napMinutes?.let(::minutes) ?: union(sortedNaps.flatMap(::asleepSpans)).total()
        return Night(
            wakeDate = date,
            main = block,
            extraSegments = extras,
            naps = sortedNaps,
            fellAsleep = block?.start,
            wokeUp = block?.end,
            fellAsleepOffset = block?.startOffset,
            wokeUpOffset = block?.endOffset,
            mainSleep = mainSleep,
            awakeInMain = main?.awake ?: Duration.ZERO,
            extraSleep = extraSleep,
            napSleep = napSleep,
            totalSleep = mainSleep + extraSleep + napSleep,
            stageTotals = block?.let(::stageTotals),
            interruptions = block?.awakePeriods.orEmpty()
                .filter { it.kind != AwakeKind.STAGE || it.duration >= config.interruptionMinDuration },
            override = override,
            origin = block?.origin,
        )
    }

    /** Merges sessions of one wake date into clusters; every cluster comes back as a MAIN candidate. */
    private fun clusters(sessions: List<RawSleepSession>): List<SleepBlock> {
        val sorted = sessions.sortedWith(compareBy({ it.start }, { it.end }))
        val result = ArrayList<SleepBlock>()
        var members = mutableListOf(sorted.first())
        var gaps = mutableListOf<AwakePeriod>()
        var end = sorted.first().end
        for (session in sorted.drop(1)) {
            val gap = Duration.between(end, session.start)
            if (gap < config.fragmentMergeMaxGap) {
                if (gap > Duration.ZERO) gaps += AwakePeriod(end, session.start, AwakeKind.GAP)
                members += session
                end = maxOf(end, session.end)
            } else {
                result += blockOf(BlockKind.MAIN, members, gaps)
                members = mutableListOf(session)
                gaps = mutableListOf()
                end = session.end
            }
        }
        result += blockOf(BlockKind.MAIN, members, gaps)
        return result
    }

    /** One block from [sessions] sorted by start; [gaps] are the uncovered stretches between them. */
    private fun blockOf(
        kind: BlockKind,
        sessions: List<RawSleepSession>,
        gaps: List<AwakePeriod> = emptyList(),
    ): SleepBlock {
        val first = sessions.first()
        val last = sessions.maxWith(compareBy<RawSleepSession> { it.end }.thenBy { it.start })
        val stages = sessions
            .flatMap { session -> session.stages.mapNotNull { it.clipTo(session.start, session.end) } }
            .sortedWith(compareBy({ it.start }, { it.end }))
        val awakeStages = union(stages.filter { it.type.isAwake }.map { Span(it.start, it.end) })
            .map { AwakePeriod(it.start, it.end, AwakeKind.STAGE) }
        val periods = (awakeStages + gaps).sortedWith(periodOrder)
        return SleepBlock(
            kind = kind,
            start = first.start,
            end = last.end,
            startOffset = first.startOffset,
            endOffset = last.endOffset,
            asleep = asleepIn(first.start, last.end, union(periods.map(::spanOf)).total()),
            awakePeriods = periods,
            stages = stages,
            sessionIds = sessions.map { it.id },
            origin = sessions.maxBy { it.duration }.origin,
        )
    }

    private fun manualNapBlock(nap: ManualNap): SleepBlock = SleepBlock(
        kind = BlockKind.NAP,
        start = nap.start,
        end = nap.end,
        startOffset = nap.offset,
        endOffset = nap.offset,
        asleep = Duration.between(nap.start, nap.end),
        sessionIds = listOf("manual-nap:${nap.id}"),
        isManual = true,
    )

    private class MainSleep(val block: SleepBlock, val awake: Duration)

    private fun effectiveMain(raw: SleepBlock, override: NightOverride?, added: List<AwakePeriod>): MainSleep {
        val start = override?.fellAsleep ?: raw.start
        val end = override?.wokeUp ?: raw.end
        val valid = end > start
        val windowStart = if (valid) start else raw.start
        val windowEnd = if (valid) end else raw.end
        val periods = (raw.awakePeriods + added).mapNotNull { it.clipTo(windowStart, windowEnd) }.sortedWith(periodOrder)
        val awake = override?.awakeMinutes?.let(::minutes) ?: union(periods.map(::spanOf)).total()
        val block = raw.copy(
            start = windowStart,
            end = windowEnd,
            startOffset = override?.takeIf { valid && it.fellAsleep != null }?.offset ?: raw.startOffset,
            endOffset = override?.takeIf { valid && it.wokeUp != null }?.offset ?: raw.endOffset,
            asleep = asleepIn(windowStart, windowEnd, awake),
            awakePeriods = periods,
            stages = raw.stages.mapNotNull { it.clipTo(windowStart, windowEnd) },
        )
        return MainSleep(block, awake)
    }

    private fun manualMain(date: LocalDate, override: NightOverride, added: List<AwakePeriod>): MainSleep? {
        val start = override.fellAsleep ?: return null
        val end = override.wokeUp ?: return null
        if (end <= start) return null
        val periods = added.mapNotNull { it.clipTo(start, end) }.sortedWith(periodOrder)
        val awake = override.awakeMinutes?.let(::minutes) ?: union(periods.map(::spanOf)).total()
        val block = SleepBlock(
            kind = BlockKind.MAIN,
            start = start,
            end = end,
            startOffset = override.offset,
            endOffset = override.offset,
            asleep = asleepIn(start, end, awake),
            awakePeriods = periods,
            sessionIds = listOf("manual-night:$date"),
            isManual = true,
        )
        return MainSleep(block, awake)
    }

    private fun stageTotals(main: SleepBlock): StageTotals? {
        if (main.stages.isEmpty()) return null
        var rest = subtract(listOf(Span(main.start, main.end)), union(main.awakePeriods.map(::spanOf)))
        fun take(type: SleepStageType): Duration {
            val part = intersect(rest, union(main.stages.filter { it.type == type }.map { Span(it.start, it.end) }))
            rest = subtract(rest, part)
            return part.total()
        }
        val deep = take(SleepStageType.DEEP)
        val rem = take(SleepStageType.REM)
        val light = take(SleepStageType.LIGHT)
        return StageTotals(
            deep = deep,
            light = light,
            rem = rem,
            awake = union(main.stages.filter { it.type.isAwake }.map { Span(it.start, it.end) }).total(),
            unclassified = rest.total(),
        )
    }

    private fun asleepSpans(block: SleepBlock): List<Span> =
        subtract(listOf(Span(block.start, block.end)), union(block.awakePeriods.map(::spanOf)))
}

private val periodOrder = compareBy<AwakePeriod>({ it.start }, { it.end })

private fun minutes(value: Int): Duration = Duration.ofMinutes(value.coerceAtLeast(0).toLong())

private fun asleepIn(start: Instant, end: Instant, awake: Duration): Duration =
    maxOf(Duration.ZERO, Duration.between(start, end) - awake)

private fun SleepStage.clipTo(from: Instant, to: Instant): SleepStage? {
    val s = maxOf(start, from)
    val e = minOf(end, to)
    return if (s < e) SleepStage(s, e, type) else null
}

private fun AwakePeriod.clipTo(from: Instant, to: Instant): AwakePeriod? {
    val s = maxOf(start, from)
    val e = minOf(end, to)
    return if (s < e) AwakePeriod(s, e, kind) else null
}

/** Half-open time interval used for union arithmetic. */
private data class Span(val start: Instant, val end: Instant)

private fun spanOf(period: AwakePeriod) = Span(period.start, period.end)

/** Sorted, disjoint union; overlapping or touching spans merge. */
private fun union(spans: List<Span>): List<Span> {
    val result = ArrayList<Span>(spans.size)
    for (span in spans.sortedBy { it.start }) {
        val last = result.lastOrNull()
        if (last != null && span.start <= last.end) {
            if (span.end > last.end) result[result.lastIndex] = Span(last.start, span.end)
        } else if (span.start < span.end) {
            result += span
        }
    }
    return result
}

private fun List<Span>.total(): Duration = fold(Duration.ZERO) { acc, span -> acc + Duration.between(span.start, span.end) }

/** [a] minus [b]; both sorted and disjoint. */
private fun subtract(a: List<Span>, b: List<Span>): List<Span> {
    val result = ArrayList<Span>()
    for (span in a) {
        var cursor = span.start
        for (cut in b) {
            if (cut.end <= cursor) continue
            if (cut.start >= span.end) break
            if (cut.start > cursor) result += Span(cursor, cut.start)
            cursor = maxOf(cursor, cut.end)
            if (cursor >= span.end) break
        }
        if (cursor < span.end) result += Span(cursor, span.end)
    }
    return result
}

/** [a] ∩ [b]; both sorted and disjoint. */
private fun intersect(a: List<Span>, b: List<Span>): List<Span> {
    val result = ArrayList<Span>()
    var i = 0
    var j = 0
    while (i < a.size && j < b.size) {
        val start = maxOf(a[i].start, b[j].start)
        val end = minOf(a[i].end, b[j].end)
        if (start < end) result += Span(start, end)
        if (a[i].end < b[j].end) i++ else j++
    }
    return result
}
