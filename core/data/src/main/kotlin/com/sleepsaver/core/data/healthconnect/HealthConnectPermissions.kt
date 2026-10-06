package com.sleepsaver.core.data.healthconnect

import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.HeartRateVariabilityRmssdRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.RestingHeartRateRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import kotlin.reflect.KClass

/** The Health Connect permissions Sleep Saver reads with. All read-only: nothing is ever written. */
object HealthConnectPermissions {
    val SLEEP: String = HealthPermission.getReadPermission(SleepSessionRecord::class)
    val HEART_RATE: String = HealthPermission.getReadPermission(HeartRateRecord::class)
    val RESTING_HEART_RATE: String = HealthPermission.getReadPermission(RestingHeartRateRecord::class)
    val HRV: String = HealthPermission.getReadPermission(HeartRateVariabilityRmssdRecord::class)
    val STEPS: String = HealthPermission.getReadPermission(StepsRecord::class)
    const val HISTORY: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_HISTORY
    const val BACKGROUND: String = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    val OPTIONAL: Set<String> = setOf(HEART_RATE, RESTING_HEART_RATE, HRV, STEPS)

    /** Record types followed through the Changes API, keyed by the permission that unlocks them. */
    internal val CHANGE_FEED: Map<String, KClass<out Record>> = mapOf(
        SLEEP to SleepSessionRecord::class,
        RESTING_HEART_RATE to RestingHeartRateRecord::class,
        HRV to HeartRateVariabilityRmssdRecord::class,
    )

    /**
     * The change-feed permissions among [granted]. A changes token only covers the types that were granted
     * when it was created, so a different scope means a new token (and a re-import).
     */
    fun changeFeedScope(granted: Set<String>): Set<String> = CHANGE_FEED.keys.filterTo(sortedSetOf()) { it in granted }
}
