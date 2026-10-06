package com.sleepsaver.feature.review.common

import com.sleepsaver.core.domain.model.SleepBlock

private const val MANUAL_NAP_PREFIX = "manual-nap:"

/** The row id of a nap the user added ("manual-nap:<id>"), or null for a recorded nap. */
internal val SleepBlock.manualNapId: Long?
    get() = sessionIds.firstOrNull { it.startsWith(MANUAL_NAP_PREFIX) }?.removePrefix(MANUAL_NAP_PREFIX)?.toLongOrNull()
