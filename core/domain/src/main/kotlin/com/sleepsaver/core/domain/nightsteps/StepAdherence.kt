package com.sleepsaver.core.domain.nightsteps

import com.sleepsaver.core.domain.config.SleepConfig
import java.time.LocalDate
import kotlin.math.ceil

/**
 * How an evening counts: DONE (enough steps checked), MISSED, NEUTRAL (neither counts nor breaks: steps off, no
 * steps, or before the first ever log) or OPEN (tonight, still running).
 */
enum class EveningState { DONE, MISSED, NEUTRAL, OPEN }

/** One evening's result: [done] of [planned] steps checked. */
data class EveningResult(val nightOf: LocalDate, val planned: Int, val done: Int, val state: EveningState)

/** Night steps tracking: "4 of the last 7 nights" and the quiet streak (no "streak lost" copy anywhere). */
data class StepAdherence(
    /** The last historyEvenings (14) evenings, oldest first, ending with tonight. */
    val evenings: List<EveningResult>,
    /** DONE evenings among the recentEvenings (7) most recent. */
    val doneRecent: Int,
    /** DONE + MISSED among them ("4 of the last 6 nights with steps"). */
    val countedRecent: Int,
    val quietStreak: Int,
)

/**
 * Night steps adherence (DECISIONS A2). [plannedCount] returns 0 for an evening with Night steps off or no steps.
 *
 *  - done(e) = distinct activities logged for e. DONE when done ≥ 1 and done ≥ ceil(planned × doneShare).
 *  - NEUTRAL when planned == 0, there are no logs at all, or e is before the first log's nightOf (checked first, so a
 *    switched-off evening is never DONE).
 *  - OPEN when e == tonight, !tonightOver and not DONE. Otherwise MISSED.
 *  - quietStreak: walk back from tonight over up to streakLookbackDays evenings, skipping NEUTRAL and OPEN; DONE →
 *    streak + 1 and misses = 0; MISSED → misses + 1, stopping once misses > streakMaxMisses. A single miss (also one
 *    right at the start) is bridged and doesn't count.
 *
 * Example: Mon–Thu done, Fri and Sat steps off, Sun done → quietStreak 5, Fri and Sat NEUTRAL.
 */
fun stepAdherence(
    logs: List<StepLog>,
    plannedCount: (LocalDate) -> Int,
    tonight: LocalDate,
    tonightOver: Boolean,
    config: SleepConfig = SleepConfig.DEFAULT,
): StepAdherence {
    val steps = config.steps
    val doneByEvening = logs.groupBy { it.nightOf }.mapValues { (_, list) -> list.distinctBy { it.activityId }.size }
    val firstLog = logs.minOfOrNull { it.nightOf }

    fun result(evening: LocalDate): EveningResult {
        val planned = plannedCount(evening)
        val done = doneByEvening[evening] ?: 0
        val state = when {
            planned <= 0 || firstLog == null || evening.isBefore(firstLog) -> EveningState.NEUTRAL
            done >= 1 && done >= ceil(planned * steps.doneShare).toInt() -> EveningState.DONE
            evening == tonight && !tonightOver -> EveningState.OPEN
            else -> EveningState.MISSED
        }
        return EveningResult(evening, planned, done, state)
    }

    val evenings = (steps.historyEvenings - 1 downTo 0).map { back -> result(tonight.minusDays(back.toLong())) }
    val recent = evenings.takeLast(steps.recentEvenings)

    var streak = 0
    var misses = 0
    for (back in 0 until steps.streakLookbackDays) {
        val evening = tonight.minusDays(back.toLong())
        if (firstLog == null || evening.isBefore(firstLog)) break // everything earlier is NEUTRAL
        when (result(evening).state) {
            EveningState.DONE -> {
                streak++
                misses = 0
            }
            EveningState.MISSED -> if (++misses > steps.streakMaxMisses) break
            EveningState.NEUTRAL, EveningState.OPEN -> Unit
        }
    }

    return StepAdherence(
        evenings = evenings,
        doneRecent = recent.count { it.state == EveningState.DONE },
        countedRecent = recent.count { it.state == EveningState.DONE || it.state == EveningState.MISSED },
        quietStreak = streak,
    )
}
