package com.sleepsaver.core.domain.patterns

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.Night
import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** Keel bands: Even keel (≥ 80), Fair (70–79), Choppy (< 70). */
enum class KeelBand { EVEN, FAIR, CHOPPY }

/** One asleep stretch. */
data class SleepSpan(val start: Instant, val end: Instant)

/** One wake date's 24-h window [noon before, noon of] in the night's offset, with its asleep spans. */
data class KeelDay(val wakeDate: LocalDate, val windowStart: Instant, val windowEnd: Instant, val asleep: List<SleepSpan>)

/** Keel as of a date: still calibrating (fewer than minValidDays nights), or a score. */
sealed interface KeelResult {
    data class Calibrating(val validDays: Int, val needed: Int) : KeelResult

    data class Ready(
        val score: Int,
        val band: KeelBand,
        val validDays: Int,
        val onsetSd: Duration?,
        val wakeSd: Duration?,
        val asOf: LocalDate,
    ) : KeelResult
}

/**
 * Keel: a 0–100 regularity score (SRI on 1-min epochs of main sleep, naps excluded, minute pairs 24 elapsed hours
 * apart, over windowDays wake dates; DECISIONS A7). score = clamp(round(200·P − 100), 0, 100) with P the share of
 * matching valid pairs (§2.8).
 */
class KeelCalculator(private val config: SleepConfig = SleepConfig.DEFAULT) {

    fun keel(nights: List<Night>, asOf: LocalDate): KeelResult = TODO("B2: KeelCalculator.keel (§2.8)")

    /** The SRI core over explicit days (also used for the planned keel); null without valid pairs. */
    fun score(days: List<KeelDay>): Int? = TODO("B2: KeelCalculator.score (§2.8)")

    fun band(score: Int): KeelBand = TODO("B2: KeelCalculator.band (§2.8)")
}
