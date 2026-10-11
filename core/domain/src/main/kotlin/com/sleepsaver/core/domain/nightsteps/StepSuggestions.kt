package com.sleepsaver.core.domain.nightsteps

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.journal.Mark
import com.sleepsaver.core.domain.model.DebtStatus
import com.sleepsaver.core.domain.model.Night
import java.time.Duration
import java.time.LocalDate

/** Why a technique is suggested (rule-based, in this order; STARTER when nothing else applies). */
enum class SuggestionReason { SLOW_TO_SETTLE, RESTLESS_NIGHTS, STRESSFUL_DAYS, DRIFTING_BEDTIMES, SCREENS_IN_ROUTINE, SHORT_ON_SLEEP, STARTER }

/** One suggestion: "Try Slow tide: it often takes you a while to settle." */
data class StepSuggestion(val technique: Technique, val reason: SuggestionReason)

/**
 * Rule-based Night steps suggestions (§2.6): no suggestions in a week with AWAY_FROM_HOME or UNWELL; never a technique
 * already in the routine, a LITTLE-evidence or screen-based one, or the same one twice; at most suggestionMax.
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
    ): List<StepSuggestion> = TODO("B2: StepSuggestionEngine.suggest (§2.6)")
}
