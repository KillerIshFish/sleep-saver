package com.sleepsaver.core.domain.nightsteps

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.patterns.circularSdMinutes
import com.sleepsaver.core.domain.patterns.localFellAsleep
import com.sleepsaver.core.domain.patterns.median
import com.sleepsaver.core.domain.patterns.minuteOfDay
import com.sleepsaver.core.domain.patterns.minutesExact
import java.time.Duration
import java.time.LocalDate

/** Why a technique is suggested (rule-based, in this order; STARTER when nothing else applies). */
enum class SuggestionReason { SLOW_TO_SETTLE, RESTLESS_NIGHTS, STRESSFUL_DAYS, DRIFTING_BEDTIMES, SCREENS_IN_ROUTINE, SHORT_ON_SLEEP, STARTER }

/** One suggestion: "Try Slow tide: it often takes you a while to settle." */
data class StepSuggestion(val technique: Technique, val reason: SuggestionReason)

/**
 * Rule-based Night steps suggestions (§2.6): no suggestions in a week with AWAY_FROM_HOME or UNWELL; never a technique
 * already in the routine, a LITTLE-evidence or screen-based one, or the same one twice; at most suggestionMax.
 *
 * Rules, in order, each adding its techniques until suggestionMax are found ("the last 7 wake dates" = asOf − 6 ..
 * asOf; "present nights" are those up to asOf, most recent first):
 *  0. a quietWeekMarks mark on any of the last recentNights wake dates → nothing at all;
 *  1. SLOW_TO_SETTLE: median of the latest latencyNights latencies (≥ latencyMinValues) > slowToSettleLatency →
 *     Tomorrow list, Slow tide;
 *  2. RESTLESS_NIGHTS: mean awakeInMain of the last recentNights present nights ≥ restlessAwake → Muscle release,
 *     Body scan, Cool dark room;
 *  3. STRESSFUL_DAYS: STRESS on ≥ markMinEvenings of the last recentNights wake dates → Tomorrow list, Slow tide;
 *  4. DRIFTING_BEDTIMES: circular SD of local fell-asleep over the last driftingNights present nights
 *     (≥ driftingMinNights) ≥ driftingBedtimeSd → Screens down, Warm shower;
 *  5. SCREENS_IN_ROUTINE: [routineHasScreens], or SCREENS_IN_BED on ≥ markMinEvenings of the last recentNights wake
 *     dates → Screens down, Read on paper;
 *  6. SHORT_ON_SLEEP: debt status HIGH → Warm shower, Slow tide;
 *  7. STARTER: GOOD, then MODERATE techniques in enum order (fills whatever the rules above left open).
 *
 * Example: a median latency of 40 min with Tomorrow list already in the routine → Slow tide (SLOW_TO_SETTLE), then the
 * first starter, Warm shower.
 */
class StepSuggestionEngine(private val config: SleepConfig = SleepConfig.DEFAULT) {
    /**
     * [inRoutine] = the techniques of the selected activities plus tonight's evening-plan activities.
     * [latencies] = valid anchor latencies, most recent first.
     */
    fun suggest(
        nights: List<Night>,
        inRoutine: Set<Technique>,
        routineHasScreens: Boolean,
        marks: Map<LocalDate, Set<Mark>>,
        latencies: List<Duration>,
        status: DebtStatus?,
        asOf: LocalDate,
    ): List<StepSuggestion> {
        val steps = config.steps
        val recentDates = (0 until steps.recentNights).map { asOf.minusDays(it.toLong()) }
        fun markedEvenings(mark: Mark): Int = recentDates.count { marks[it]?.contains(mark) == true }
        if (recentDates.any { date -> marks[date].orEmpty().any { it in steps.quietWeekMarks } }) return emptyList()

        val present = nights
            .filter { it.isPresent && !it.wakeDate.isAfter(asOf) }
            .associateBy { it.wakeDate }
            .values
            .sortedByDescending { it.wakeDate }

        val result = ArrayList<StepSuggestion>()
        fun offer(reason: SuggestionReason, vararg techniques: Technique) {
            for (technique in techniques) {
                if (result.size >= steps.suggestionMax) return
                val allowed = technique !in inRoutine &&
                    technique.evidence != EvidenceLevel.LITTLE &&
                    !technique.screenBased &&
                    result.none { it.technique == technique }
                if (allowed) result += StepSuggestion(technique, reason)
            }
        }

        if (slowToSettle(latencies)) offer(SuggestionReason.SLOW_TO_SETTLE, Technique.TOMORROW_LIST, Technique.SLOW_TIDE)
        if (restless(present)) {
            offer(SuggestionReason.RESTLESS_NIGHTS, Technique.MUSCLE_RELEASE, Technique.BODY_SCAN, Technique.COOL_DARK_ROOM)
        }
        if (markedEvenings(Mark.STRESS) >= steps.markMinEvenings) {
            offer(SuggestionReason.STRESSFUL_DAYS, Technique.TOMORROW_LIST, Technique.SLOW_TIDE)
        }
        if (drifting(present)) offer(SuggestionReason.DRIFTING_BEDTIMES, Technique.SCREENS_DOWN, Technique.WARM_SHOWER)
        if (routineHasScreens || markedEvenings(Mark.SCREENS_IN_BED) >= steps.markMinEvenings) {
            offer(SuggestionReason.SCREENS_IN_ROUTINE, Technique.SCREENS_DOWN, Technique.READ_ON_PAPER)
        }
        if (status == DebtStatus.HIGH) offer(SuggestionReason.SHORT_ON_SLEEP, Technique.WARM_SHOWER, Technique.SLOW_TIDE)
        offer(SuggestionReason.STARTER, *STARTERS)
        return result
    }

    private fun slowToSettle(latencies: List<Duration>): Boolean {
        val recent = latencies.take(config.steps.latencyNights)
        if (recent.size < config.steps.latencyMinValues) return false
        return median(recent.map { it.minutesExact() }) > config.steps.slowToSettleLatency.minutesExact()
    }

    private fun restless(present: List<Night>): Boolean {
        val recent = present.take(config.steps.recentNights)
        if (recent.isEmpty()) return false
        return recent.map { it.awakeInMain.minutesExact() }.average() >= config.steps.restlessAwake.minutesExact()
    }

    private fun drifting(present: List<Night>): Boolean {
        val onsets = present.take(config.steps.driftingNights).mapNotNull { it.localFellAsleep() }
        if (onsets.size < config.steps.driftingMinNights) return false
        return circularSdMinutes(onsets.map(::minuteOfDay)) >= config.steps.driftingBedtimeSd.minutesExact()
    }

    private companion object {
        /** GOOD, then MODERATE techniques, each in enum order. */
        val STARTERS: Array<Technique> = (
            Technique.entries.filter { it.evidence == EvidenceLevel.GOOD } +
                Technique.entries.filter { it.evidence == EvidenceLevel.MODERATE }
            ).toTypedArray()
    }
}
