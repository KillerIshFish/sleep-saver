package com.sleepsaver.core.domain.night

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.AddedAwake
import com.sleepsaver.core.domain.model.ManualNap
import com.sleepsaver.core.domain.model.Night
import com.sleepsaver.core.domain.model.NightOverride
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SourceSelection
import java.time.LocalDate

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
 */
class NightAssembler(private val config: SleepConfig = SleepConfig.DEFAULT) {

    /** @return nights sorted by wakeDate ascending; only dates that have some sleep or an override. */
    fun assemble(
        sessions: List<RawSleepSession>,
        source: SourceSelection,
        overrides: List<NightOverride> = emptyList(),
        manualNaps: List<ManualNap> = emptyList(),
        addedAwake: List<AddedAwake> = emptyList(),
    ): List<Night> {
        TODO("M2: night assembly")
    }

    /** True when [session] is a nap under the config rules. */
    fun isNap(session: RawSleepSession): Boolean {
        TODO("M2: nap detection")
    }

    /**
     * Wake date of a non-nap session (local end e, local start s, cutoff 14:00):
     *  - e.time < cutoff → e.date
     *  - s.date == e.date && s.time ≥ cutoff → e.date + 1 (evening sleep that hasn't crossed midnight)
     *  - else → e.date
     */
    fun wakeDateOf(session: RawSleepSession): LocalDate {
        TODO("M2: wake date assignment")
    }
}
