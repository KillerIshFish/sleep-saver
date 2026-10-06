package com.sleepsaver.core.domain.night

import com.sleepsaver.core.domain.config.SleepConfig
import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.SourceSelection

/**
 * Applies the user's data-source choice.
 *  - Single(pkg): keep only sessions whose origin == pkg.
 *  - All: keep everything, but when sessions from *different* origins overlap by ≥ `dedupeOverlapRatio`
 *    of the shorter one, keep one per overlapping group by priority:
 *    origin == preferredOrigin > has stages > longer duration > newer lastModified > id (stable tiebreak).
 *    Sessions from the same origin are never de-duplicated against each other (they are fragments).
 */
class SourceFilter(private val config: SleepConfig = SleepConfig.DEFAULT) {
    fun apply(sessions: List<RawSleepSession>, selection: SourceSelection): List<RawSleepSession> {
        TODO("M2: source filter + dedupe")
    }
}
