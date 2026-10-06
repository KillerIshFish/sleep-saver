package com.sleepsaver.core.domain.night

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SourceSelection
import java.time.Duration

/**
 * Applies the user's data-source choice.
 *  - Single(pkg): keep only sessions whose origin == pkg.
 *  - All: keep everything, but when sessions from *different* origins overlap by ≥ `dedupeOverlapRatio`
 *    of the shorter one, keep one per overlapping group by priority:
 *    origin == preferredOrigin > has stages > longer duration > newer lastModified > id (stable tiebreak).
 *    Sessions from the same origin are never de-duplicated against each other (they are fragments).
 *
 * De-duplication is greedy in priority order: a session is kept unless it duplicates an already kept session
 * from another origin. So one app's whole-night session is dropped against another app's fragments when it
 * duplicates any of them, while all of those fragments survive.
 *
 * The result keeps the input order.
 */
class SourceFilter(private val config: SleepConfig = SleepConfig.DEFAULT) {

    private val priority: Comparator<RawSleepSession> =
        compareByDescending<RawSleepSession> { it.origin == config.preferredOrigin }
            .thenByDescending { it.stages.isNotEmpty() }
            .thenByDescending { it.duration }
            .thenByDescending { it.lastModified }
            .thenBy { it.id }

    fun apply(sessions: List<RawSleepSession>, selection: SourceSelection): List<RawSleepSession> =
        when (selection) {
            is SourceSelection.Single -> sessions.filter { it.origin == selection.packageName }
            SourceSelection.All -> dedupe(sessions)
        }

    private fun dedupe(sessions: List<RawSleepSession>): List<RawSleepSession> {
        val keep = BooleanArray(sessions.size)
        val kept = ArrayList<RawSleepSession>(sessions.size)
        sessions.indices.sortedWith { a, b -> priority.compare(sessions[a], sessions[b]) }.forEach { i ->
            val candidate = sessions[i]
            if (kept.none { it.origin != candidate.origin && isDuplicate(it, candidate) }) {
                keep[i] = true
                kept += candidate
            }
        }
        return sessions.filterIndexed { i, _ -> keep[i] }
    }

    private fun isDuplicate(a: RawSleepSession, b: RawSleepSession): Boolean {
        val overlap = Duration.between(maxOf(a.start, b.start), minOf(a.end, b.end))
        if (overlap.isNegative || overlap.isZero) return false
        val shorter = minOf(a.duration, b.duration)
        return overlap.toMillis() >= config.dedupeOverlapRatio * shorter.toMillis()
    }
}
