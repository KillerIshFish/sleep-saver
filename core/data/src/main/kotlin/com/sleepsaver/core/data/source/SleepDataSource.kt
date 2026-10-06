package com.sleepsaver.core.data.source

import com.sleepsaver.core.domain.model.RawSleepSession
import com.sleepsaver.core.domain.model.VitalSample
import java.time.Instant
import java.time.ZoneOffset

/**
 * Where raw sleep comes from. Health Connect today; a Garmin Health API or manual-entry source could be
 * added later behind the same interface. Implementations never write anywhere.
 */
interface SleepDataSource {
    val id: String

    /** True when reads can succeed right now (installed, permissions granted). Never throws. */
    suspend fun isReadable(): Boolean

    /** Sessions overlapping [from, to), all origins, with stages. */
    suspend fun readSleepSessions(from: Instant, to: Instant): List<RawSleepSession>

    /**
     * Optional vitals for [from, to): resting HR, HRV, daily steps, and average heart rate during each of
     * [sessions]. Missing permissions/data → empty list, never an error.
     */
    suspend fun readVitals(from: Instant, to: Instant, sessions: List<RawSleepSession>): List<RecordedVital>

    /** A fresh changes token, or null when the source has no change feed. */
    suspend fun newChangesToken(): String?

    suspend fun changesSince(token: String): ChangeBatch
}

sealed interface ChangeBatch {
    data class Changes(
        val upserted: List<RawSleepSession>,
        val deletedIds: List<String>,
        val vitals: List<RecordedVital>,
        val deletedVitalIds: List<String>,
        val nextToken: String,
    ) : ChangeBatch

    /** Tokens expire after ~30 days: do a full re-read and get a new token. */
    data object TokenExpired : ChangeBatch
}

/**
 * A vital with the UTC offset it was recorded at, like a session's start/end offsets: the record's own when the
 * source has one, otherwise the device zone's offset at [VitalSample.time] when it was read.
 */
data class RecordedVital(val sample: VitalSample, val offset: ZoneOffset)
