package com.sleepsaver.core.domain.night

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SourceSelection
import java.time.Duration
import java.time.Instant

/**
 * Applies the user's data-source choice.
 *  - Single(pkg): keep only sessions whose origin == pkg.
 *  - All: keep everything, but never two *duplicates*: sessions from different origins that overlap by
 *    ≥ `dedupeOverlapRatio` of the shorter one. Between duplicates:
 *     1. a session less than `dedupeShortDuplicateRatio` (half) as long as the other loses to the longer one, whatever
 *        its origin, so a short fragment from the preferred app doesn't knock out another app's whole night;
 *     2. otherwise priority decides: origin == preferredOrigin > has stages > longer duration > newer lastModified >
 *        id (stable tiebreak).
 *    Sessions from the same origin are never de-duplicated against each other (they are fragments). When a session
 *    duplicates several fragments of one origin, their combined time (union) is what is compared in rule 1, so a
 *    night the preferred app recorded in two halves still wins over another app's single session.
 *
 * Resolution is greedy in priority order: a session with no kept duplicate is kept; one whose kept duplicates are,
 * per origin, all less than half as long as it replaces them; otherwise it's dropped. A replaced session is out for
 * good and the rest is resolved again, so a session that only lost to the replaced one comes back.
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
        val byPriority = sessions.indices.sortedWith { a, b -> priority.compare(sessions[a], sessions[b]) }
        val replaced = BooleanArray(sessions.size)
        // Every pass that replaces something rules out at least one more session, so this ends.
        while (true) {
            val kept = ArrayList<Int>(sessions.size)
            var replacedAny = false
            for (i in byPriority) {
                if (replaced[i]) continue
                val candidate = sessions[i]
                // Everything kept so far comes earlier in priority order, so on a tie in length the kept side wins.
                val duplicates = kept.filter { sessions[it].origin != candidate.origin && isDuplicate(sessions[it], candidate) }
                when {
                    duplicates.isEmpty() -> kept += i
                    duplicates.groupBy { sessions[it].origin }.values.all { isMuchShorter(it.map(sessions::get), candidate) } -> {
                        duplicates.forEach { replaced[it] = true }
                        kept.removeAll(duplicates.toSet())
                        kept += i
                        replacedAny = true
                    }
                }
            }
            if (!replacedAny) {
                val keep = BooleanArray(sessions.size).also { flags -> kept.forEach { flags[it] = true } }
                return sessions.filterIndexed { i, _ -> keep[i] }
            }
        }
    }

    private fun isDuplicate(a: RawSleepSession, b: RawSleepSession): Boolean {
        val overlap = Duration.between(maxOf(a.start, b.start), minOf(a.end, b.end))
        if (overlap.isNegative || overlap.isZero) return false
        val shorter = minOf(a.duration, b.duration)
        return overlap.toMillis() >= config.dedupeOverlapRatio * shorter.toMillis()
    }

    /** True when the time covered by [fragments] (one origin) is less than the short-duplicate share of [other]. */
    private fun isMuchShorter(fragments: List<RawSleepSession>, other: RawSleepSession): Boolean =
        coveredMillis(fragments) < config.dedupeShortDuplicateRatio * other.duration.toMillis()

    private fun coveredMillis(sessions: List<RawSleepSession>): Long {
        var total = 0L
        var cursor = Instant.MIN
        for (session in sessions.sortedBy { it.start }) {
            val from = maxOf(session.start, cursor)
            if (session.end > from) {
                total += Duration.between(from, session.end).toMillis()
                cursor = session.end
            }
        }
        return total
    }
}
